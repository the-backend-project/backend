package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.internal.database.EventsByEntityId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
public class ConditionalTriggerTest {

  enum OrderStates implements State {Created, Shipped, Cancelled}

  enum BatchStates implements State {Open, Waiting, Closed}

  enum CheckStates implements State {Done}

  static EntityModel Order = EntityModel.of("Order", UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b01"), ConditionalTriggerTest::orderTransitions);
  static EntityModel Batch = EntityModel.of("Batch", UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b02"), ConditionalTriggerTest::batchTransitions);
  /// A check of a batch, which is not stored, so it is an entity of the session only. Its conditional trigger is on the
  /// batch, which is stored, and must be deleted as it fires.
  static EntityModel Check = EntityModel.notStored("Check", UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b10"), ConditionalTriggerTest::checkTransitions);
  static EventType<UUID, UUID> orderReferenced = IndexEntityModel.referenceEventType(
      "Order referenced",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b03")
  );
  static IndexEntityModel Index = IndexEntityModel.of(
      "Index",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b04"),
      orderReferenced
  );

  static EventType<UUID, UUID> OrderCreated = EventType.of(
      "Order created",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b05"),
      DataType.uuid()
  );
  static EventType<Void, Void> OrderShipped = EventType.of(
      "Order shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b06")
  );
  static EventType<Void, Void> OrderCancelled = EventType.of(
      "Order cancelled",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0b")
  );
  static EventType<UUID, UUID> BatchCloseRequestedWhenShippedOrCancelled = EventType.of(
      "Batch close requested when shipped or cancelled",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0c"),
      DataType.uuid()
  );
  static EventType<Void, Void> BatchOpened = EventType.of(
      "Batch opened",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b07")
  );
  static EventType<UUID, UUID> BatchCloseRequestedWhenShipped = EventType.of(
      "Batch close requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b08"),
      DataType.uuid()
  );
  static EventType<UUID, UUID> SessionBatchCloseRequestedWhenShipped = EventType.of(
      "Session batch close requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0a"),
      DataType.uuid()
  );
  static EventType<Void, Void> BatchClosed = EventType.of(
      "Batch closed",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b09")
  );

  static EventType<UUID, UUID> BatchCheckRequestedWhenShipped = EventType.of(
      "Batch check requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0d"),
      DataType.uuid()
  );
  static EventType<Void, Void> BatchNoted = EventType.of(
      "Batch noted",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0f")
  );
  /// Not stored, so its conditional trigger is deleted by a change of its own when it fires, also as its transition
  /// stores another event on the batch
  static EventType<Void, Void> BatchChecked = EventType.notStored(
      "Batch checked",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0e")
  );

  static EventType<UUID, UUID> CheckRequestedWhenShipped = EventType.of(
      "Check requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b11"),
      DataType.uuid()
  );
  /// Its data is the id of the batch checked
  static EventType<UUID, UUID> CheckRun = EventType.of(
      "Check run",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b12"),
      DataType.uuid()
  );

  private String schema;
  private EventsByEntityId eventsByEntityId;

  private StateMachine stateMachine(String schema) {
    this.schema = schema;
    eventsByEntityId = Init.storage(schema, entityModels()).eventsByEntityId();
    return Init.stateMachine(schema, entityModels());
  }

  private static List<EntityModel> entityModels() {
    return List.of(Order, Batch, Index, Check);
  }

  private static Map<State, List<TransitionModel<?, ?>>> orderTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(OrderCreated).to(OrderStates.Created)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(orderReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2()))
                .output(d -> d.t1().t1())
        ),
        OrderStates.Created, List.of(
            onEvent(OrderShipped).to(OrderStates.Shipped).output(),
            onEvent(OrderCancelled).to(OrderStates.Cancelled).output()
        ),
        OrderStates.Shipped, List.of(),
        OrderStates.Cancelled, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> batchTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(BatchOpened).to(BatchStates.Open).output()),
        BatchStates.Open, List.of(
            onEvent(BatchCloseRequestedWhenShipped).to(BatchStates.Waiting)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(BatchClosed)
                .when(orderReferenced, t -> t.t1())
                .is(OrderShipped)
                .with(_ -> null)
                .on(Batch)
                .identifiedBy(t -> entityId((UUID) t.t2()))
                .output(d -> d.t1()),
            onEvent(BatchCheckRequestedWhenShipped).toSelf()
                .assemble(c -> c.input())
                .trigger(BatchChecked)
                .when(orderReferenced, t -> t)
                .is(OrderShipped)
                .with(_ -> null)
                .on(Batch)
                .identifiedBy(entityIdFromSession())
                .output(d -> d),
            onEvent(BatchChecked).toSelf()
                .trigger(BatchNoted).on(Batch).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(BatchNoted).toSelf().output(),
            onEvent(CheckRequestedWhenShipped).toSelf()
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(CheckRun)
                .when(orderReferenced, t -> t.t1())
                .is(OrderShipped)
                .with(t -> t.t2())
                .on(Check)
                .identifiedBy(EntitySelector.newEntityId())
                .output(d -> d.t1()),
            onEvent(SessionBatchCloseRequestedWhenShipped).to(BatchStates.Waiting)
                .assemble(c -> c.input())
                .trigger(BatchClosed)
                .when(orderReferenced, t -> t)
                .is(OrderShipped)
                .with(_ -> null)
                .on(Batch)
                .identifiedBy(entityIdFromSession())
                .output(d -> d),
            onEvent(BatchCloseRequestedWhenShippedOrCancelled).to(BatchStates.Waiting)
                .assemble(c -> c.input())
                .trigger(BatchClosed)
                .when(orderReferenced, t -> t)
                .is(OrderShipped, OrderCancelled)
                .with(_ -> null)
                .on(Batch)
                .identifiedBy(entityIdFromSession())
                .output(d -> d)
        ),
        BatchStates.Waiting, List.of(onEvent(BatchClosed).to(BatchStates.Closed).output()),
        BatchStates.Closed, List.of()
    );
  }


  private static Map<State, List<TransitionModel<?, ?>>> checkTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(CheckRun).to(CheckStates.Done)
                .assemble(c -> c.input())
                .trigger(BatchNoted).with(_ -> null).on(Batch).identifiedBy(batchId -> entityId(batchId))
                .output(d -> d.t1())
        ),
        CheckStates.Done, List.of()
    );
  }

  private EventLog batchLog(UUID batchId) {
    return eventsByEntityId.execute(
        Batch,
        List.of(BatchOpened, CheckRequestedWhenShipped, BatchCloseRequestedWhenShipped, SessionBatchCloseRequestedWhenShipped, BatchCloseRequestedWhenShippedOrCancelled, BatchCheckRequestedWhenShipped, BatchNoted, BatchClosed),
        batchId
    ).block();
  }

  private int batchEventCount(StateMachine machine, UUID batchId) {
    return batchLog(batchId).events().size();
  }

  private void awaitBatchEvents(StateMachine machine, UUID batchId, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (System.currentTimeMillis() < deadline && batchEventCount(machine, batchId) < expected) Thread.sleep(200);
  }

  private int conditionalTriggerCount() {
    return Init.client().sql("SELECT CAST(COUNT(*) AS INT) AS N FROM \"" + schema + "\".\"ConditionalTrigger\"")
        .name("Test.ConditionalTriggers")
        .map(row -> row.get("N", Integer.class))
        .one()
        .block();
  }

  /// A conditional trigger which fires an event not stored is deleted as it fires, rather than firing again
  @Test
  public void conditionalTriggerFiringAnEventNotStoredIsDeleted() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    machine.onEvent(trigger(BatchCheckRequestedWhenShipped, Batch, batch.entityId()), key).blockFirst();
    assertEquals(1, conditionalTriggerCount());

    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (System.currentTimeMillis() < deadline && conditionalTriggerCount() > 0) Thread.sleep(200);
    assertEquals(0, conditionalTriggerCount());
    // Batch checked is not stored, but Batch noted, which it triggers, is
    awaitBatchEvents(machine, batch.entityId(), 3);
    assertEquals(3, batchEventCount(machine, batch.entityId()));
    assertEquals(0, conditionalTriggerCount());
  }

  /// A conditional trigger which another transaction fires first, e.g. of another instance which picked it too, is
  /// taken once: the transaction finding it gone when stored fails, rather than storing the events it triggers again
  @Test
  public void conditionalTriggerFiredByAnotherTransactionFirstIsTakenOnce() throws Exception {
    this.schema = UUID.randomUUID().toString();
    eventsByEntityId = Init.storage(schema, entityModels()).eventsByEntityId();
    // Deletes the trigger just before the transaction it fired is stored, as the other transaction would
    var machine = Init.stateMachine(schema, entityModels(), java.time.Clock.systemUTC(), new com.github.thxmasj.backend.core.JsonLinesListener(_ -> {}, java.time.Clock.systemUTC()), keys ->
        keys.stream().anyMatch(key -> key.endsWith(BatchChecked.id().toString())) ?
            Init.client().sql("DELETE FROM \"" + schema + "\".\"ConditionalTrigger\"").name("Test.DeleteConditionalTriggers").update().then() :
            reactor.core.publisher.Mono.empty()
    );
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    machine.onEvent(trigger(BatchCheckRequestedWhenShipped, Batch, batch.entityId()), key).blockFirst();
    assertEquals(1, conditionalTriggerCount());

    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (System.currentTimeMillis() < deadline && conditionalTriggerCount() > 0) Thread.sleep(200);
    Thread.sleep(2500);
    assertEquals(0, conditionalTriggerCount());
    // Batch noted, which the fired Batch checked triggers, is not stored, as the transaction failed
    assertEquals(2, batchEventCount(machine, batch.entityId()));
  }

  /// A conditional trigger on an entity of the session only, as its model is not stored, is deleted as it fires, rather
  /// than firing again
  @Test
  public void conditionalTriggerOnAnEntityOfTheSessionOnlyIsDeleted() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    machine.onEvent(trigger(CheckRequestedWhenShipped, Batch, batch.entityId()), key).blockFirst();
    assertEquals(1, conditionalTriggerCount());

    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    // The check notes the batch once, which is stored
    awaitBatchEvents(machine, batch.entityId(), 3);
    assertEquals(0, conditionalTriggerCount());
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batch.entityId()));
  }

  @Test
  public void waitingEventFiresWhenAllReferencedEntitiesAreInState() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order1 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: no order is shipped, so the close event must wait
    machine.onEvent(trigger(BatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition still not met: only one of two orders is shipped
    machine.onEvent(trigger(OrderShipped, Order, order1.entityId())).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the waiting event fires within about a second
    machine.onEvent(trigger(OrderShipped, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());

    // The row was deleted with the fired event's change, so it is not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void sessionIdentifiedTargetWaitsAndFiresWhenConditionIsMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: the order is not shipped, so the close event waits
    machine.onEvent(trigger(SessionBatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the event fires on the session's own entity
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());

    // Not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void waitingEventFiresWhenAllReferencedEntitiesAreInOneOfTheStates() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order1 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: one order is cancelled, the other is neither shipped nor cancelled
    machine.onEvent(trigger(OrderCancelled, Order, order1.entityId())).blockFirst();
    machine.onEvent(trigger(BatchCloseRequestedWhenShippedOrCancelled, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: one order is cancelled and the other is shipped
    machine.onEvent(trigger(OrderShipped, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());
  }

  @Test
  public void sessionIdentifiedTargetFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(SessionBatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

  @Test
  public void eventFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(BatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

}
