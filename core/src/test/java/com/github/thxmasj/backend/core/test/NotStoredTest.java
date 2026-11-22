package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// Events and entities which are not stored, and events stored masked
@Tag("database")
public class NotStoredTest {

  enum States implements State { Opened, Viewed, Signed }

  static EventType<String, String> AccountOpened = BasicEventType.of("Account opened", UUID.fromString("5e0a3f8c-2b7d-4c19-9a64-d1f8b3e7a025"), DataType.string());
  static EventType<Void, Void> AccountTouched = BasicEventType.of("Account touched", UUID.fromString("c47d1e9b-0a3f-4b28-8e65-f2a9c1d7b304"));
  /// Not stored, and triggers a stored event on another entity
  static EventType<Void, String> PageViewed = BasicEventType.notStored("Page viewed", UUID.fromString("9b2c6e0f-4d1a-4e73-b8a5-3f7d9c2e1b46"), DataType.none(), DataType.string());
  static EventType<UUID, UUID> ViewRecorded = BasicEventType.of("View recorded", UUID.fromString("2f8a4d6c-9e1b-4a35-87c0-b5d3e9f1a627"), DataType.uuid());
  /// Not stored, creating an entity of the session only
  static EventType<UUID, UUID> SignInStarted = BasicEventType.notStored("Sign-in started", UUID.fromString("e1d5b9a3-7c2f-4086-9d4e-a6b8c0f2e315"), DataType.uuid());
  static EventType<UUID, String> SignedIn = BasicEventType.of("Signed in", UUID.fromString("7a3e9c1d-5b0f-4d62-a8e4-c9f1b3d5e708"), DataType.uuid(), DataType.string());
  static EventType<String, String> SecretIssued = BasicEventType.of("Secret issued", UUID.fromString("4c8f2a6e-1d9b-4357-b0e3-d7a5c9f1e842"), DataType.string());

  /// Not stored, and fired by a delayed trigger, whose transition records a view
  static EventType<Void, Void> LookupDue = BasicEventType.notStored("Lookup due", UUID.fromString("1b3d5f7a-9c0e-4a24-86b8-dafc1e3f5a91"));
  static EventType<Void, Void> LookupScheduled = BasicEventType.of("Lookup scheduled", UUID.fromString("3d5f7b9c-1e2a-4c46-a8da-fc1e3f5a7bb3"));
  /// Stored, but chooses a transition which is not stored, after scheduling a stored event
  static EventType<Void, Void> TouchScheduled = BasicEventType.of("Touch scheduled", UUID.fromString("5f7b9d1e-3a4c-4e68-8afc-1e3f5a7b9dd5"));
  static EventType<Void, Void> TouchScheduledQuietly = BasicEventType.notStored("Touch scheduled quietly", UUID.fromString("7b9d1f3a-5c6e-4a8a-ac1e-3f5a7b9dbff7"));

  /// Sets a timeout on its entity, which a watcher of the entity cancels at once, in the same transaction
  static EventType<Void, Void> TimeoutSet = BasicEventType.of("Timeout set", UUID.fromString("9d1f3b5c-7e8a-4c0e-b2d4-f6a8c0e2a419"));
  static EventType<Void, Void> AccountTimedOut = BasicEventType.of("Account timed out", UUID.fromString("b1f3d5e7-9a0c-4e2a-84f6-a8c0e2a4c63b"));
  static EventType<UUID, UUID> WatchStarted = BasicEventType.of("Watch started", UUID.fromString("d3b5f7a9-1c2e-4a4c-a6a8-c0e2a4c6e85d"), DataType.uuid());
  static EntityModel Watcher = EntityModel.of("Watcher", UUID.fromString("f5d7b9c1-3e4a-4c6e-88ca-e2a4c6e8a07f"), () -> Map.<State, List<TransitionModel<?, ?>>>of(
      Begin, List.of(
          onEvent(WatchStarted).to(VisitStates.Open)
              .assembleInput()
              .trigger(AccountTouched).on(NotStoredTest.Account).identifiedBy(account -> entityId(account))
              .output(d -> d.t1())
      ),
      VisitStates.Open, List.of()
  ));

  /// Not stored, and schedules a stored event on its entity
  static EventType<Void, Void> ReminderScheduled = BasicEventType.notStored("Reminder scheduled", UUID.fromString("8e2c4a6f-0b1d-4f37-95e9-a3c5e7b9d148"));
  static EventType<String, String> PasswordSet = BasicEventType.of("Password set", UUID.fromString("6a1f3c5e-8b2d-4e90-a7c4-d9e1b3f5a768"), DataType.string());

  static EntityModel Account = EntityModel.of("Account", UUID.fromString("8d4b0f2a-6e3c-4a91-b7d5-e9c1f3a5b067"), NotStoredTest::accountTransitions);
  static EntityModel Views = EntityModel.of("Views", UUID.fromString("b6e2a8d4-0c5f-4e17-9a3b-f1d7c9e5a283"), NotStoredTest::viewTransitions);
  static EntityModel SignIn = EntityModel.of("SignIn", UUID.fromString("f3a7c1e5-9d2b-4f68-8c0a-b4e6d8f0a159"), NotStoredTest::signInTransitions);
  /// A model which is not stored
  static EntityModel Session = EntityModel.notStored("Session", UUID.fromString("0d6b2f8e-4a1c-4b73-95e7-c3a9f5d1b724"), NotStoredTest::sessionTransitions);

  enum VisitStates implements State { Open, Closed }
  enum DeliveryStates implements State { Dispatched, Delivered }

  static EventType<Void, Void> VisitStarted = BasicEventType.of("Visit started", UUID.fromString("2a4c6e8f-0b1d-4f35-97a9-cbedf1a3b582"));
  static EventType<Void, Void> VisitClosed = BasicEventType.of("Visit closed", UUID.fromString("4c6e8a0b-2d3f-4a57-b9cb-edf1a3c5d7a4"));
  static EventType<Void, Void> DeliveryRequested = BasicEventType.of("Delivery requested", UUID.fromString("6e8a0c2d-4f5b-4c79-9bed-f1a3c5e7f9c6"));
  static EventType<Void, Void> DeliveryArrived = BasicEventType.of("Delivery arrived", UUID.fromString("8a0c2e4f-6b7d-4e9b-bdf1-a3c5e7a9b1e8"));

  /// Gives the delivery its arrival in a later transaction of the session
  static Action<String> courier = new Action<>() {
    @Override public String name() {return "Courier";}
    @Override public Mono<InputEvent<?>> execute(String data) {
      return Mono.delay(Duration.ofMillis(100)).<InputEvent<?>>map(_ -> new InputEvent<>(DeliveryArrived, null));
    }
  };

  /// A visit, which is not stored, waits for a delivery, which is
  static EntityModel Visit = EntityModel.notStored("Visit", UUID.fromString("a0c2e4a6-8d9f-4ab1-9f13-c5e7a9cbd30a"), () -> Map.<State, List<TransitionModel<?, ?>>>of(
      Begin, List.of(
          onEvent(VisitStarted).to(VisitStates.Open)
              .trigger(DeliveryRequested).on(NotStoredTest.Delivery).identifiedBy(newEntityId())
              .output()
      ),
      VisitStates.Open, List.of(onEvent(VisitClosed).to(VisitStates.Closed).output()),
      VisitStates.Closed, List.of()
  ));
  static EntityModel Delivery = EntityModel.of("Delivery", UUID.fromString("c2e4a6c8-0f1b-4cd3-b135-e7a9cbedf52c"), () -> Map.<State, List<TransitionModel<?, ?>>>of(
      Begin, List.of(onEvent(DeliveryRequested).to(DeliveryStates.Dispatched).assemble(_ -> "").trigger(courier).with(d -> d).output()),
      DeliveryStates.Dispatched, List.of(
          // In a later transaction of the session, which finds the visit among the events of the first one
          onEvent(DeliveryArrived).to(DeliveryStates.Delivered)
              .trigger(VisitClosed).on(Visit).identifiedBy(entityIdFromSession())
              .output()
      ),
      DeliveryStates.Delivered, List.of()
  ));

  private static final List<String> lines = new CopyOnWriteArrayList<>();
  private static final String schema = UUID.randomUUID().toString();
  private static final StateMachine machine = Init.stateMachine(
      schema,
      entityModels(),
      Clock.systemUTC(),
      new JsonLinesListener(lines::add, Clock.systemUTC())
  );

  private static List<EntityModel> entityModels() {
    return List.of(Account, Views, SignIn, Session, Visit, Watcher);
  }

  private static Map<State, List<TransitionModel<?, ?>>> accountTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(AccountOpened).to(States.Opened).assembleInput().output(d -> d)),
        States.Opened, List.of(
            onEvent(AccountTouched).toSelf().output(),
            onEvent(ReminderScheduled).toSelf().assembleInput().trigger(AccountTouched).after(_ -> Duration.ofMillis(100)).output(),
            onEvent(LookupScheduled).toSelf().assembleInput().trigger(LookupDue).after(_ -> Duration.ofMillis(100)).output(),
            onEvent(LookupDue).toSelf()
                .assemble(c -> c.log().entityId())
                .trigger(ViewRecorded).with(id -> id).on(Views).identifiedBy(newEntityId())
                .output(),
            onEvent(TimeoutSet).toSelf()
                .assemble(c -> c.log().entityId())
                .trigger(AccountTimedOut).after(_ -> Duration.ofMillis(100))
                .trigger(WatchStarted).with(account -> account).on(Watcher).identifiedBy(newEntityId())
                .output(),
            onEvent(AccountTimedOut).toSelf().output(),
            onEvent(TouchScheduled).toSelf()
                .assembleInput()
                .trigger(AccountTouched).after(_ -> Duration.ofMillis(100))
                .when(_ -> true).then(onEvent(TouchScheduledQuietly).toSelf().output(), d -> null)
                .otherwise(onEvent(AccountTouched).toSelf().output(), d -> null),
            onEvent(PasswordSet).toSelf().masked(_ -> "***").assembleInput().output(d -> d),
            onEvent(PageViewed).toSelf()
                .assemble(c -> c.log().entityId())
                .trigger(ViewRecorded).with(id -> id).on(Views).identifiedBy(newEntityId())
                .output(d -> "<html>" + d.t1() + "</html>")
        )
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> viewTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(ViewRecorded).to(States.Viewed).assembleInput().output(d -> d)),
        States.Viewed, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> signInTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(SignInStarted).to(States.Opened)
                .assembleInput()
                .trigger(ViewRecorded).with(id -> id).on(Views).identifiedBy(newEntityId())
                // The view's entity id
                .output(d -> d.t2().accepted().event().entityId())
        ),
        States.Opened, List.of(onEvent(SignedIn).to(States.Signed).assembleInput().output(d -> "signed in " + d)),
        States.Signed, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> sessionTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(SecretIssued).to(States.Opened)
                .assembleInput()
                .trigger(AccountOpened).with(secret -> "account of " + secret).on(Account).identifiedBy(newEntityId())
                .output(d -> d.t1())
        ),
        States.Opened, List.of()
    );
  }

  private static EventLog log(EntityModel model, List<EventType<?, ?>> types, UUID entityId) {
    return Init.storage(schema, entityModels()).eventsByEntityId().execute(model, types, entityId).block();
  }

  private static List<String> events(EventLog log) {
    return log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList();
  }

  @Test
  public void anEventNotStoredTriggersStoredEventsAndTheNextEventTakesItsNumber() {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();

    Event<?> viewed = machine.onEvent(trigger(PageViewed, Account, account), null)
        .filter(e -> e.type().equals(PageViewed))
        .blockFirst(Duration.ofSeconds(10));
    assertEquals("<html>" + account + "</html>", viewed.getUnmarshalledData());
    machine.onEvent(trigger(AccountTouched, Account, account), null).blockFirst();

    assertEquals(List.of("1:Account opened", "2:Account touched"), events(log(Account, List.of(AccountOpened, AccountTouched, PageViewed), account)));
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Page viewed\"") && l.contains("\"stored\":false") && !l.contains("eventNumber")), lines.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"View recorded\"") && l.contains("\"eventNumber\":1")), lines.toString());
  }

  @Test
  public void anEntityCreatedByAnEventNotStoredIsOneOfTheSessionOnly() {
    UUID view = UUID.randomUUID();
    Event<?> started = machine.onEvent(trigger(SignInStarted, SignIn), view).blockFirst(Duration.ofSeconds(10));
    assertEquals(List.of("1:View recorded"), events(log(Views, List.of(ViewRecorded), (UUID) started.getUnmarshalledData())));
    var e = assertThrows(Exception.class, () -> log(SignIn, List.of(SignInStarted, SignedIn), started.entityId()));
    assertTrue(e instanceof UnknownEntity || e.getCause() instanceof UnknownEntity, e.toString());
    // After the session, the entity is not found
    assertThrows(Exception.class, () -> machine.onEvent(trigger(SignedIn, SignIn, started.entityId()), view).blockFirst(Duration.ofSeconds(10)));
  }

  @Test
  public void theEventsOfAModelNotStoredAreNotStoredButThoseTheyTriggerAre() {
    Event<?> issued = machine.onEvent(trigger(SecretIssued, Session), "s3cret").blockFirst(Duration.ofSeconds(10));
    assertEquals("s3cret", issued.getUnmarshalledData());

    var e = assertThrows(Exception.class, () -> log(Session, List.of(SecretIssued), issued.entityId()));
    assertTrue(e instanceof UnknownEntity || e.getCause() instanceof UnknownEntity, e.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Secret issued\"") && l.contains("\"stored\":false")), lines.toString());
  }

  @Test
  public void aMaskedEventIsStoredMaskedAndHasItsWholeDataInTheSession() {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();

    Event<?> set = machine.onEvent(trigger(PasswordSet, Account, account), "hunter2").blockFirst(Duration.ofSeconds(10));
    assertEquals("hunter2", set.getUnmarshalledData());

    EventLog log = log(Account, List.of(AccountOpened, PasswordSet), account);
    assertEquals(List.of("1:Account opened", "2:Password set"), events(log));
    assertEquals("***", log.events().getLast().getUnmarshalledData());
  }

  /// The delayed event fires unless another event is stored first, so it has the number of the event not stored,
  /// which the next event stored takes, and replaces the trigger pending with that number
  @Test
  public void aDelayedEventOfAnEventNotStoredFires() throws InterruptedException {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();
    machine.onEvent(trigger(ReminderScheduled, Account, account), null).blockFirst(Duration.ofSeconds(10));
    // Scheduled again, which replaces the pending trigger, as it has the same number
    machine.onEvent(trigger(ReminderScheduled, Account, account), null).blockFirst(Duration.ofSeconds(10));

    List<String> events = List.of();
    for (int i = 0; i < 100 && events.size() < 2; i++) {
      Thread.sleep(200);
      events = events(log(Account, List.of(AccountOpened, AccountTouched), account));
    }
    assertEquals(List.of("1:Account opened", "2:Account touched"), events);
  }

  /// An entity of the session only is found by the later transactions of the session, which an action starts
  @Test
  public void anEntityOfTheSessionOnlyIsFoundByTheLaterTransactionsOfTheSession() {
    var events = machine.onEvent(trigger(VisitStarted, Visit), null).take(2).collectList().block(Duration.ofSeconds(10));
    assertEquals(List.of("Visit started", "Visit closed"), events.stream().map(Event::typeName).toList());

    var e = assertThrows(Exception.class, () -> log(Visit, List.of(VisitStarted, VisitClosed), events.getFirst().entityId()));
    assertTrue(e instanceof UnknownEntity || e.getCause() instanceof UnknownEntity, e.toString());
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Delivery arrived\"") && l.contains("\"eventNumber\":2")), lines.toString());
  }

  private int delayedTriggerCount(UUID entityId) {
    return Init.client().sql("SELECT CAST(COUNT(*) AS INT) AS N FROM \"" + schema + "\".\"DelayedTrigger\" WHERE EntityId=:entityId")
        .name("Test.DelayedTriggers")
        .bind("entityId", entityId)
        .map(row -> row.get("N", Integer.class))
        .one()
        .block();
  }

  /// A delayed trigger which fires an event not stored is deleted as it fires, rather than firing again
  @Test
  public void aDelayedTriggerFiringAnEventNotStoredIsDeleted() throws InterruptedException {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();
    machine.onEvent(trigger(LookupScheduled, Account, account), null).blockFirst(Duration.ofSeconds(10));
    assertEquals(1, delayedTriggerCount(account));

    for (int i = 0; i < 100 && delayedTriggerCount(account) > 0; i++)
      Thread.sleep(200);
    assertEquals(0, delayedTriggerCount(account));
    assertEquals(List.of("1:Account opened", "2:Lookup scheduled"), events(log(Account, List.of(AccountOpened, LookupScheduled), account)));
    assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"Lookup due\"") && l.contains(account.toString())), lines.toString());
  }

  /// A transition whose event is stored, but which chooses one which isn't, numbers its delayed event after the
  /// events actually stored
  @Test
  public void aDelayedEventOfATransitionChoosingOneNotStoredFires() throws InterruptedException {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();
    machine.onEvent(trigger(TouchScheduled, Account, account), null).blockFirst(Duration.ofSeconds(10));

    List<String> events = List.of();
    for (int i = 0; i < 100 && events.size() < 2; i++) {
      Thread.sleep(200);
      events = events(log(Account, List.of(AccountOpened, AccountTouched), account));
    }
    assertEquals(List.of("1:Account opened", "2:Account touched"), events);
  }

  /// An event stored on the entity after the transition setting a timeout, in the same transaction, cancels it
  @Test
  public void aDelayedEventIsCancelledByAnEventStoredLaterInTheSameTransaction() throws InterruptedException {
    UUID account = machine.onEvent(trigger(AccountOpened, Account), "a").blockFirst().entityId();
    machine.onEvent(trigger(TimeoutSet, Account, account), null).blockFirst(Duration.ofSeconds(10));

    for (int i = 0; i < 50 && delayedTriggerCount(account) > 0; i++)
      Thread.sleep(200);
    assertEquals(0, delayedTriggerCount(account));
    assertEquals(List.of("1:Account opened", "2:Timeout set", "3:Account touched"), events(log(Account, List.of(AccountOpened, TimeoutSet, AccountTouched, AccountTimedOut), account)));
  }
}
