package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.Listener;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// An event which fails on the event number, as another instance appended an event to the entity first, is calculated
/// again from the new event log. The outcome of an action is the next event after the one which triggered the action, so
/// it is dropped if another event was stored on its entity first, and calculated again only if the other event was on
/// another entity.
@Tag("database")
public class RetryTest {

  enum States implements State { Counting, Tallying }

  static EventType<String, String> CountingStarted = BasicEventType.of("Counting started", UUID.fromString("5d0c8a3e-7b21-4f6a-9e4d-1c3b5a7f9e02"), DataType.string());
  static EventType<String, String> Counted = BasicEventType.of("Counted", UUID.fromString("a2e4c6f8-0b1d-4e3f-8a5c-7d9e1f3b5c14"), DataType.string());
  /// Runs the action reporting the count to the tally of the id
  static EventType<UUID, UUID> CountRequested = BasicEventType.of("Count requested", UUID.fromString("c6a8e0b2-4d1f-4a7c-9b3e-5f7a9c1e3d38"), DataType.uuid());
  /// The outcome of the action, which triggers an event on the tally
  static EventType<UUID, UUID> CountReported = BasicEventType.of("Count reported", UUID.fromString("e8c0a2d4-6f3b-4c9e-8d5a-7b9c1e3f5a4a"), DataType.uuid());
  /// Runs the action indexing the counter again
  static EventType<UUID, UUID> IndexRequested = BasicEventType.of("Index requested", UUID.fromString("4e6a8c0e-2f9b-4c5e-a3d1-3b5c7e9a1d70"), DataType.uuid());
  /// The outcome of the action, which triggers an event on the counter's index entity, which has the counter's id
  static EventType<UUID, UUID> CountIndexed = BasicEventType.of("Count indexed", UUID.fromString("6a8c0e2a-4b1d-4e7a-b5f3-5d7e9b1c3f92"), DataType.uuid());
  static EventType<UUID, UUID> CounterReferenced = IndexEntityModel.referenceEventType("Counter referenced", UUID.fromString("8c0e2a4c-6d3f-4a9c-87b5-7f9b1d3e5ab4"));
  static IndexEntityModel CounterIndex = IndexEntityModel.of("Counter index", UUID.fromString("a0e2c4e6-8f5b-4c1e-99d7-9b1d3f5a7cd6"), CounterReferenced);
  static EventType<Void, Void> Tallied = BasicEventType.of("Tallied", UUID.fromString("0a2c4e6a-8b5d-4e1a-9f7c-9d1e3a5b7c5c"));

  static Action<UUID> reporting = new Action<>() {
    @Override public String name() {return "Reporting";}
    @Override public Mono<InputEvent<?>> execute(UUID tallyId) {
      return Mono.just(new InputEvent<>(CountReported, tallyId));
    }
  };

  static Action<UUID> indexing = new Action<>() {
    @Override public String name() {return "Indexing";}
    @Override public Mono<InputEvent<?>> execute(UUID counterId) {
      return Mono.just(new InputEvent<>(CountIndexed, counterId));
    }
  };

  static EntityModel Counter = EntityModel.of("Counter", UUID.fromString("3f7b9d1e-5a2c-4c8e-b0f4-6e8a0c2d4f26"), RetryTest::counterTransitions);
  static EntityModel Tally = EntityModel.of("Tally", UUID.fromString("2c4e6a8c-0d7f-4a3c-b1e9-1f3a5c7d9e6e"), () -> Map.<State, List<TransitionModel<?, ?>>>of(
      Begin, List.of(onEvent(Tallied).to(States.Tallying).output()),
      States.Tallying, List.of(onEvent(Tallied).to(States.Tallying).output())
  ));

  private static Map<State, List<TransitionModel<?, ?>>> counterTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(CountingStarted).to(States.Counting)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(CounterReferenced).with(t -> t.t2().value()).on(CounterIndex).identifiedBy(t -> newEntityId(t.t2()))
                .output(d -> d.t1().t1())
        ),
        States.Counting, List.of(
            onEvent(Counted).to(States.Counting).assembleInput().output(d -> d),
            onEvent(CountRequested).to(States.Counting).assembleInput().trigger(reporting).with(d -> d).output(d -> d),
            onEvent(CountReported).to(States.Counting)
                .assembleInput()
                .trigger(Tallied).on(Tally).identifiedBy(tallyId -> entityId(tallyId))
                .output(d -> d.t1()),
            onEvent(IndexRequested).to(States.Counting).assembleInput().trigger(indexing).with(d -> d).output(d -> d),
            onEvent(CountIndexed).to(States.Counting)
                .assembleInput()
                .trigger(CounterReferenced).with(counterId -> counterId).on(CounterIndex).identifiedBy(counterId -> entityId(counterId))
                .output(d -> d.t1())
        )
    );
  }

  @Test
  public void eventIsCalculatedAgainFromTheNewEventLogWhenAnotherInstanceAppendedFirst() {
    String schema = UUID.randomUUID().toString();
    StateMachine other = Init.stateMachine(schema, List.of(Counter));
    UUID counterId = other.onEvent(trigger(CountingStarted, Counter), "start").blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    // The other instance appends an event to the counter after this one read its event log, just before this one
    // stores its event, once
    AtomicBoolean appended = new AtomicBoolean();
    StateMachine machine = Init.stateMachine(schema, List.of(Counter), Clock.systemUTC(), new Logger("Test"), keys ->
        keys.contains(correlationId + "-" + Counted.id()) && appended.compareAndSet(false, true) ?
            other.onEvent(trigger(Counted, Counter, counterId), "other").next().then() :
            Mono.empty()
    );

    Event<?> event = machine.onEvent(trigger(Counted, Counter, counterId).withCorrelationId(correlationId), "this")
        .blockFirst(Duration.ofSeconds(20));

    assertTrue(appended.get());
    assertEquals("this", event.getUnmarshalledData());
    // After the counter's first event and the one the other instance appended
    assertEquals(3, event.eventNumber().intValue());
  }

  /// The numbers of the events of the type stored by the transactions of the correlation id
  record Stored(String correlationId, String eventType, CompletableFuture<List<Integer>> numbers) implements Listener {
    Stored(String correlationId, String eventType) {
      this(correlationId, eventType, new CompletableFuture<>());
    }
    @Override public void changeAccepted(String id, List<Change> changes) {
      List<Integer> found = changes.stream()
          .filter(change -> change.event() != null && change.event().type().equals(eventType))
          .map(change -> change.event().number())
          .toList();
      if (id.equals(correlationId) && !found.isEmpty())
        numbers.complete(found);
    }
  }

  private static List<String> events(String schema, EntityModel model, List<EventType<?, ?>> types, UUID entityId) {
    EventLog log = Init.storage(schema, List.of(Counter)).eventsByEntityId().execute(model, types, entityId).block();
    return log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList();
  }

  @Test
  public void outcomeOfAnActionIsDroppedWhenAnotherInstanceAppendedToItsEntityFirst() throws Exception {
    String schema = UUID.randomUUID().toString();
    StateMachine other = Init.stateMachine(schema, List.of(Counter));
    UUID counterId = other.onEvent(trigger(CountingStarted, Counter), "start").blockFirst().entityId();
    UUID tallyId = other.onEvent(trigger(Tallied, Tally)).blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    // The other instance appends an event to the counter just before this one stores the action's outcome, the first
    // time. A second time would be a retry.
    AtomicInteger attempts = new AtomicInteger();
    CompletableFuture<Void> retried = new CompletableFuture<>();
    StateMachine machine = Init.stateMachine(schema, List.of(Counter), Clock.systemUTC(), new Logger("Test"), keys -> {
      if (!keys.contains(correlationId + "-" + CountReported.id()))
        return Mono.empty();
      if (attempts.incrementAndGet() > 1) {
        retried.complete(null);
        return Mono.empty();
      }
      return other.onEvent(trigger(Counted, Counter, counterId), "other").next().then();
    });

    machine.onEvent(trigger(CountRequested, Counter, counterId).withCorrelationId(correlationId), tallyId).blockFirst(Duration.ofSeconds(20));

    // A retry would come within a second (a delay of 500 ms with full jitter)
    assertThrows(TimeoutException.class, () -> retried.get(3, TimeUnit.SECONDS));
    assertEquals(1, attempts.get());
    assertEquals(
        List.of("1:Counting started", "2:Count requested", "3:Counted"),
        events(schema, Counter, List.of(CountingStarted, Counted, CountRequested, CountReported), counterId)
    );
    assertEquals(List.of("1:Tallied"), events(schema, Tally, List.of(Tallied), tallyId));
  }

  @Test
  public void outcomeOfAnActionIsCalculatedAgainWhenAnotherInstanceAppendedToAnotherEntityFirst() throws Exception {
    String schema = UUID.randomUUID().toString();
    StateMachine other = Init.stateMachine(schema, List.of(Counter));
    UUID counterId = other.onEvent(trigger(CountingStarted, Counter), "start").blockFirst().entityId();
    UUID tallyId = other.onEvent(trigger(Tallied, Tally)).blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    Stored reported = new Stored(correlationId, CountReported.name());
    // The other instance appends an event to the tally just before this one stores the action's outcome, once
    AtomicBoolean appended = new AtomicBoolean();
    StateMachine machine = Init.stateMachine(schema, List.of(Counter), Clock.systemUTC(), reported, keys ->
        keys.contains(correlationId + "-" + CountReported.id()) && appended.compareAndSet(false, true) ?
            other.onEvent(trigger(Tallied, Tally, tallyId)).next().then() :
            Mono.empty()
    );

    machine.onEvent(trigger(CountRequested, Counter, counterId).withCorrelationId(correlationId), tallyId).blockFirst(Duration.ofSeconds(20));

    // The next event after the request on the counter
    assertEquals(List.of(3), reported.numbers().get(20, TimeUnit.SECONDS));
    assertTrue(appended.get());
    // After the tally's first event and the one the other instance appended
    assertEquals(List.of("1:Tallied", "2:Tallied", "3:Tallied"), events(schema, Tally, List.of(Tallied), tallyId));
  }

  /// The index entity of the counter has the counter's id, but is another entity, so a transaction failing on its event
  /// number is calculated again
  @Test
  public void outcomeOfAnActionIsCalculatedAgainWhenAnotherInstanceAppendedToTheIndexEntityOfItsEntityFirst() throws Exception {
    String schema = UUID.randomUUID().toString();
    StateMachine other = Init.stateMachine(schema, List.of(Counter));
    UUID counterId = other.onEvent(trigger(CountingStarted, Counter), "start").blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    Stored indexed = new Stored(correlationId, CountIndexed.name());
    // The other instance appends an event to the counter's index entity just before this one stores the action's
    // outcome, once
    AtomicBoolean appended = new AtomicBoolean();
    StateMachine machine = Init.stateMachine(schema, List.of(Counter), Clock.systemUTC(), indexed, keys ->
        keys.contains(correlationId + "-" + CountIndexed.id()) && appended.compareAndSet(false, true) ?
            other.onEvent(trigger(CounterReferenced, CounterIndex, counterId), counterId).next().then() :
            Mono.empty()
    );

    machine.onEvent(trigger(IndexRequested, Counter, counterId).withCorrelationId(correlationId), counterId).blockFirst(Duration.ofSeconds(20));

    // The next event after the request on the counter
    assertEquals(List.of(3), indexed.numbers().get(20, TimeUnit.SECONDS));
    assertTrue(appended.get());
    // After the index entity's first event and the one the other instance appended
    assertEquals(
        List.of("1:Counter referenced", "2:Counter referenced", "3:Counter referenced"),
        events(schema, CounterIndex, List.of(CounterReferenced), counterId)
    );
  }

}
