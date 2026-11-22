package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class MaskedEventTest {

  enum States implements State {Open}

  static EventType<String, String> PasswordSet = BasicEventType.of("Password set", UUID.fromString("1f3a5c7e-9b2d-4e46-a8c0-d2e4f6a8b013"), DataType.string());
  static EventType<Void, Void> Viewed = BasicEventType.notStored("Viewed", UUID.fromString("5b7d9f1a-3c4e-4a68-b0d2-e4f6a8c0d235"));
  static EventType<Void, Void> Touched = BasicEventType.of("Touched", UUID.fromString("9d1f3b5c-7e8a-4c80-a2b4-c6d8e0f2a457"));
  static EntityModel Account = EntityModel.of("Account", UUID.fromString("3c5e7a9b-1d2f-4b64-86a8-cadcebf0a679"), () -> Map.of());

  /// The transitions of the session read the log of the entity with the whole data of a masked event, by every accessor
  @Test
  public void theLogOfTheSessionHasTheWholeDataOfAMaskedEvent() {
    var event = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> "***");
    var log = new EventLog(Account, event.entityId(), List.of(event));
    assertEquals("hunter2", log.one(PasswordSet));
    assertEquals("hunter2", log.oneIfExists(PasswordSet).orElseThrow());
    assertEquals("hunter2", log.one(DataType.string()));
    assertEquals("hunter2", log.number(1, PasswordSet));
    assertEquals("***", new String(event.getMarshalledData()));
  }

  @Test
  public void aTransitionOnAnEventNotStoredCantHaveAConditionalTrigger() {
    var conditional = onEvent(Viewed).toSelf()
        .assemble(c -> c.log().entityId())
        .trigger(Touched).when(PasswordSet, id -> id).is(Touched).with(_ -> null).on(Account).identifiedBy(EntitySelector::entityId)
        .output();
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(), States.Open, List.of(conditional))));
    assertTrue(e.getMessage().startsWith("The transition on Viewed has a conditional trigger, which refers to its event"), e.getMessage());
  }

  /// A transition on a stored event which chooses one which isn't can't have a conditional trigger either
  @Test
  public void aTransitionChoosingOneNotStoredCantHaveAConditionalTrigger() {
    var choosing = onEvent(Touched).toSelf()
        .assemble(c -> c.log().entityId())
        .trigger(Touched).when(PasswordSet, id -> id).is(Touched).with(_ -> null).on(Account).identifiedBy(EntitySelector::entityId)
        .when(_ -> true).then(onEvent(Viewed).toSelf().output(), _ -> null)
        .otherwise(onEvent(Touched).toSelf().output(), _ -> null);
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(), States.Open, List.of(choosing))));
    assertTrue(e.getMessage().contains("but the transition on Viewed, which it may choose, is not stored"), e.getMessage());
  }

  /// An entity of the session only can't have delayed triggers, which fire in a session of their own, while a stored
  /// one can
  @Test
  public void aTransitionOfAnEntityOfTheSessionOnlyCantHaveADelayedTrigger() {
    var delaying = onEvent(Touched).to(States.Open).trigger(Touched).after(_ -> java.time.Duration.ofMinutes(1)).output();
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(delaying), States.Open, List.of()), false));
    assertTrue(e.getMessage().contains("can't have a delayed trigger"), e.getMessage());
    assertDoesNotThrow(() -> new Traverser(Map.of(Begin, List.of(delaying), States.Open, List.of()), true));
  }

  @Test
  public void aMaskGivingNullStoresNoData() {
    var event = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> null);
    assertEquals(null, event.getMarshalledData());
    assertEquals("hunter2", event.getUnmarshalledData());
    var built = onEvent(PasswordSet).toSelf().masked(_ -> null).assembleInput().output(d -> d);
    assertEquals(PasswordSet, built.eventType());
  }

  /// The data is masked when it is first marshalled, which only storing it asks for
  @Test
  public void aMaskIsAppliedOnlyWhenTheDataStoredIsAskedFor() {
    var masked = new java.util.concurrent.atomic.AtomicInteger();
    var event = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> {
      masked.incrementAndGet();
      return "***";
    });
    assertEquals("hunter2", event.getUnmarshalledData());
    assertEquals(0, masked.get());
    assertEquals("***", new String(event.getMarshalledData()));
    assertEquals("***", new String(event.data()));
    assertEquals(1, masked.get());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static EventType<ArrayList<String>, ArrayList<String>> Scanned = BasicEventType.of("Scanned", UUID.fromString("2b4d6f8a-0c1e-4a3c-95e7-a9cbedf10b46"), (DataType) DataType.json(ArrayList.class));

  /// Each reader of the log gets the data of its own, which it may change, except of a masked event of the
  /// transaction, whose whole data is only the one the transaction has
  @Test
  public void eachReaderOfTheLogGetsTheDataOfItsOwn() {
    var event = new Event<>(UUID.randomUUID(), 1, Scanned, ZonedDateTime.now(), new ArrayList<>(List.of("a")));
    var log = new EventLog(Account, event.entityId(), List.of(event));
    log.one(Scanned).add("b");
    assertEquals(List.of("a"), log.one(Scanned));
    var masked = new Event<>(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> "***");
    assertEquals("hunter2", new EventLog(Account, masked.entityId(), List.of(masked)).one(PasswordSet));
  }
}
