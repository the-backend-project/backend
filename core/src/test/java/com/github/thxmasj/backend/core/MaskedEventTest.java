package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.EntityType.Begin;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class MaskedEventTest {

  enum States implements State {Open}

  static EventType<String, String> PasswordSet = EventType.of("Password set", UUID.fromString("1f3a5c7e-9b2d-4e46-a8c0-d2e4f6a8b013"), DataType.string());
  static EventType.NotStored<Void, Void> Viewed = EventType.notStored("Viewed", UUID.fromString("5b7d9f1a-3c4e-4a68-b0d2-e4f6a8c0d235"));
  static EventType<Void, Void> Touched = EventType.of("Touched", UUID.fromString("9d1f3b5c-7e8a-4c80-a2b4-c6d8e0f2a457"));
  static EntityType Account = EntityType.of("Account", UUID.fromString("3c5e7a9b-1d2f-4b64-86a8-cadcebf0a679"), () -> Map.of());

  /// The transitions of the session read the log of the entity with the whole data of a masked event, by every accessor
  @Test
  public void theLogOfTheSessionHasTheWholeDataOfAMaskedEvent() {
    var event = Event.masked(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> "***");
    var log = new EventLog(Account, event.entityId(), List.of(event));
    assertEquals("hunter2", log.one(PasswordSet));
    assertEquals("hunter2", log.oneIfExists(PasswordSet).orElseThrow());
    assertEquals("hunter2", log.one(DataType.string()));
    assertEquals("hunter2", log.number(1, PasswordSet));
    assertEquals("***", new String(event.marshalled().storedData()));
  }

  /// The transition creating an account, from `Begin`, from which every entity is created
  private static final TransitionModel<String, String> opening = onEvent(PasswordSet).to(States.Open).assembleInput().output(d -> d);

  @Test
  public void aTransitionOnAnEventNotStoredCantHaveAConditionalTrigger() {
    var conditional = onEvent(Viewed).toSelf()
        .assemble(c -> c.log().entityId())
        .trigger(Touched).when(PasswordSet, id -> id).is(Touched).with(_ -> null).on(Account).identifiedBy(EntitySelector::entityId)
        .output();
    var e = assertThrows(IllegalArgumentException.class, () -> EntityTypeChecksTest.check(Map.of(Begin, List.of(opening), States.Open, List.of(conditional))));
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
    var e = assertThrows(IllegalArgumentException.class, () -> EntityTypeChecksTest.check(Map.of(Begin, List.of(opening), States.Open, List.of(choosing))));
    assertTrue(e.getMessage().contains("but the transition on Viewed, which it may choose, is not stored"), e.getMessage());
  }

  /// An entity of the session only can't have delayed triggers, which fire in a session of their own, while a stored
  /// one can
  @Test
  public void aTransitionOfAnEntityOfTheSessionOnlyCantHaveADelayedTrigger() {
    var delaying = onEvent(Touched).to(States.Open).trigger(Touched).after(_ -> java.time.Duration.ofMinutes(1)).output();
    var e = assertThrows(IllegalArgumentException.class, () -> EntityTypeChecksTest.check(Map.of(Begin, List.of(delaying), States.Open, List.of()), false));
    assertTrue(e.getMessage().contains("can't have a delayed trigger"), e.getMessage());
    assertDoesNotThrow(() -> EntityTypeChecksTest.check(Map.of(Begin, List.of(delaying), States.Open, List.of()), true));
  }

  record Codes(List<String> values) {}

  static EventType<Codes, Codes> CodesSet = EventType.of("Codes set", UUID.fromString("2e4a6c8f-0b1d-4f35-97a9-cbdfe1f3a5c7"), DataType.json(Codes.class));

  /// Every reader of a masked event of the transaction shares its whole data, as the readers of any other event do, by
  /// every accessor
  @Test
  public void everyReaderOfAMaskedEventSharesItsWholeData() {
    var codes = new Codes(List.of("c0de"));
    var event = Event.masked(UUID.randomUUID(), 1, CodesSet, ZonedDateTime.now(), codes, _ -> new Codes(List.of("***")));
    var log = new EventLog(Account, event.entityId(), List.of(event));
    assertSame(codes, log.one(CodesSet));
    assertSame(codes, log.number(1, CodesSet));
    assertSame(codes, log.one(DataType.json(Codes.class)));
    assertSame(codes, log.last(CodesSet));
  }

  @Test
  public void aMaskGivingNullIsRefused() {
    var event = Event.masked(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> null);
    var e = assertThrows(IllegalStateException.class, event::marshalled);
    assertTrue(e.getMessage().contains("The mask of Password set gives null"), e.getMessage());
    assertEquals("hunter2", event.data());
  }

  /// An event of a type with data has data, so a transition on it can't end in output() without a data adapter
  @Test
  public void anEventTypeWithDataCantHaveATransitionWithoutData() {
    var e = assertThrows(IllegalArgumentException.class, () -> EntityTypeChecksTest.check(Map.of(Begin, List.of(onEvent(PasswordSet).to(States.Open).output()), States.Open, List.of())));
    assertTrue(e.getMessage().startsWith("The transition on Password set has no output, but its event type has data"), e.getMessage());
  }

  /// The data is masked when the event is marshalled, which its transition does if it is stored, once
  @Test
  public void aMaskIsAppliedOnceWhenTheEventIsMarshalled() {
    var masked = new AtomicInteger();
    var event = Event.masked(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2", _ -> {
      masked.incrementAndGet();
      return "***";
    });
    assertEquals("hunter2", event.data());
    assertEquals(0, masked.get());
    var marshalled = event.marshalled();
    assertSame(marshalled, marshalled.marshalled());
    assertEquals("***", new String(marshalled.storedData()));
    assertEquals("***", new String(marshalled.storedData()));
    assertEquals("hunter2", marshalled.data());
    assertEquals(1, masked.get());
  }

  /// An event of a transition has no data as stored until it is marshalled, which its transition does if it is stored,
  /// so storing one which isn't is a failure rather than marshalling it later
  @Test
  public void anEventNotMarshalledHasNoDataAsStored() {
    var event = Event.of(UUID.randomUUID(), 1, PasswordSet, ZonedDateTime.now(), "hunter2");
    var e = assertThrows(IllegalStateException.class, event::storedData);
    assertTrue(e.getMessage().contains("The event Password set is not marshalled"), e.getMessage());
    assertEquals("hunter2", new String(event.marshalled().storedData()));
  }

  /// The log a transition is given unmarshals an event read from the event log once, and every accessor shares the
  /// data, while any other log unmarshals it on every read
  @Test
  public void theLogOfATransitionUnmarshalsAnEventReadFromTheEventLogOnce() {
    var stored = Event.stored(UUID.randomUUID(), 1, CodesSet, ZonedDateTime.now(), DataType.json(Codes.class).marshal(new Codes(List.of("c0de"))));
    var log = new EventLog(Account, stored.entityId(), List.of(stored));
    var ofTransition = log.forTransition();
    var codes = ofTransition.one(CodesSet);
    assertEquals(new Codes(List.of("c0de")), codes);
    assertSame(codes, ofTransition.number(1, CodesSet));
    assertSame(codes, ofTransition.last(CodesSet));
    assertSame(codes, ofTransition.all(CodesSet).getFirst());
    assertNotSame(log.one(CodesSet), log.one(CodesSet));
    assertNotSame(codes, log.forTransition().one(CodesSet));
  }

  static IdEntityType AccountIds = IdEntityType.of("Account ids", UUID.fromString("4d6f8a0c-2e1b-4c5e-a7c9-ebdf1f3b5d79"), PasswordSet);

  /// An entity of the session only is never stored, so it can't claim an id or be indexed, which would point to an
  /// entity which doesn't exist, while a stored one can
  @Test
  public void aTransitionOfAnEntityOfTheSessionOnlyCantTriggerAnIdOrIndexEvent() {
    var claiming = onEvent(Touched).to(States.Open)
        .assemble(c -> c.log().entityId())
        .trigger(PasswordSet).with(_ -> "id").on(AccountIds).identifiedBy(EntitySelector::entityId)
        .output();
    var e = assertThrows(IllegalArgumentException.class, () -> EntityTypeChecksTest.check(Map.of(Begin, List.of(claiming), States.Open, List.of()), false));
    assertTrue(e.getMessage().contains("can't trigger Password set on the id entity type Account ids"), e.getMessage());
    assertDoesNotThrow(() -> EntityTypeChecksTest.check(Map.of(Begin, List.of(claiming), States.Open, List.of()), true));
  }
}
