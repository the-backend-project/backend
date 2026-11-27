package com.github.thxmasj.backend.core;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.EventType.Rollback.Data;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void whenOneEventInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
      new Event<>(randomUUID(), 1, EventType.of("Test", randomUUID()), ZonedDateTime.now(), null)
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null),
        new Event<>(randomUUID(), 2, EventType.of("Test2", randomUUID()), ZonedDateTime.now(), null)
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null),
        new Event<>(randomUUID(), 2, EventType.of("Test2", randomUUID()), ZonedDateTime.now(), null),
        new Event<>(randomUUID(), 3, BuiltinEventTypes.EventsRolledBack, ZonedDateTime.now(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(List.of(), log.effectiveEvents());
  }

  /// The effective events of a log follow the events added to it, also a rollback, which rolls the events after the
  /// one it rolls back to back
  @Test
  public void theEffectiveEventsFollowTheEventsAdded() {
    var first = new Event<>(randomUUID(), 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null);
    var second = new Event<>(randomUUID(), 2, EventType.of("Test2", randomUUID()), ZonedDateTime.now(), null);
    var log = EventLog.empty(null, null).withNewEvent(first).withNewEvent(second);
    assertEquals(List.of(first, second), log.effectiveEvents());
    var rolledBack = log.withNewEvent(new Event<>(randomUUID(), 3, BuiltinEventTypes.EventsRolledBack, ZonedDateTime.now(), new Data(1, "testing")));
    assertEquals(List.of(first), rolledBack.effectiveEvents());
  }

  /// The log as stored has the stored form of every event of the transaction, so an event without one is refused,
  /// rather than its whole data standing in for the stored form
  @Test
  public void theLogAsStoredRefusesAnEventOfTheTransactionWithoutItsStoredForm() {
    var numbers = EntityType.of("Numbers", randomUUID(), Map::of);
    var event = new Event<>(randomUUID(), 1, NumberAssigned, ZonedDateTime.now(), new Assigned("7"));
    var log = EventLog.empty(event.entityId(), numbers).withNewEvent(event);
    var e = assertThrows(IllegalStateException.class, log::asStored);
    assertEquals("The event Number assigned of Numbers in the log has no stored form", e.getMessage());
    var withForm = EventLog.empty(event.entityId(), numbers).withNewEvent(event, Change.Event.of(numbers, event, null));
    assertEquals(new Assigned("7"), withForm.asStored().one(NumberAssigned));
  }

  @Test
  public void eventLogPreservesEntityType() {
    EntityType model = EntityType.of("TestModel", randomUUID(), Map::of);
    UUID entityId = randomUUID();
    List<Event<?>> events = List.of(
        new Event<>(entityId, 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null)
    );
    EventLog log = new EventLog(model, entityId, events);
    assertEquals(model, log.entityType());
    assertEquals(entityId, log.entityId());
    assertEquals(events, log.events());
  }

  record Assigned(String number) {}

  private static final EventType<Assigned, Assigned> NumberAssigned = EventType.of("Number assigned", randomUUID(), DataType.json(Assigned.class));

  /// An event stored with no data, e.g. of a transition recording nothing, is read as null, not failing
  @Test
  public void anEventStoredWithoutDataIsReadAsNull() {
    var clock = Clock.systemUTC();
    UUID entityId = randomUUID();
    List<EventLog.Entry<?>> events = List.of(
        new Change.Event<>(null, entityId, 1, NumberAssigned, ZonedDateTime.now(clock), null),
        new Change.Event<>(null, entityId, 2, NumberAssigned, ZonedDateTime.now(clock), DataType.json(Assigned.class).marshal(new Assigned("7")))
    );
    EventLog log = new EventLog(null, entityId, events);
    assertEquals(Arrays.asList(null, new Assigned("7")), log.all(NumberAssigned));
    assertEquals(new Assigned("7"), log.last(NumberAssigned));
    assertEquals(2, log.count(NumberAssigned));
  }

  /// The data of an event of the transaction is read as the transaction made it, not marshalled and unmarshalled again,
  /// by every accessor
  @Test
  public void theDataOfAnEventOfTheTransactionIsReadAsItIs() {
    var data = new Assigned("8");
    List<Event<?>> events = List.of(new Event<>(randomUUID(), 1, NumberAssigned, ZonedDateTime.now(), data));
    EventLog log = new EventLog(null, null, events);
    assertSame(data, log.last(NumberAssigned));
    assertSame(data, log.all(NumberAssigned).getFirst());
    assertSame(data, log.oneIfExists(NumberAssigned).orElseThrow());
    assertSame(data, log.one(NumberAssigned));
    assertSame(data, log.number(1, NumberAssigned));
    assertSame(data, log.numberIfExists(1, NumberAssigned).orElseThrow());
    assertSame(data, log.one(DataType.json(Assigned.class)));
  }

}
