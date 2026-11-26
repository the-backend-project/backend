package com.github.thxmasj.backend.core;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
      Event.of(randomUUID(), 1, EventType.of("Test", randomUUID()), ZonedDateTime.now(), null)
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        Event.of(randomUUID(), 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null),
        Event.of(randomUUID(), 2, EventType.of("Test2", randomUUID()), ZonedDateTime.now(), null)
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        Event.of(randomUUID(), 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null),
        Event.of(randomUUID(), 2, EventType.of("Test2", randomUUID()), ZonedDateTime.now(), null),
        Event.of(randomUUID(), 3, BuiltinEventTypes.EventsRolledBack, ZonedDateTime.now(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(List.of(), log.effectiveEvents());
  }

  @Test
  public void eventLogPreservesEntityType() {
    EntityType model = EntityType.of("TestModel", randomUUID(), Map::of);
    UUID entityId = randomUUID();
    List<Event<?>> events = List.of(
        Event.of(entityId, 1, EventType.of("Test1", randomUUID()), ZonedDateTime.now(), null)
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
    List<Event<?>> events = List.of(
        Event.stored(entityId, 1, NumberAssigned, ZonedDateTime.now(clock), null),
        Event.stored(entityId, 2, NumberAssigned, ZonedDateTime.now(clock), DataType.json(Assigned.class).marshal(new Assigned("7")))
    );
    EventLog log = new EventLog(null, entityId, events);
    assertEquals(Arrays.asList(null, new Assigned("7")), log.all(NumberAssigned));
    assertEquals(new Assigned("7"), log.last(NumberAssigned));
    assertEquals(2, log.count(NumberAssigned));
  }

  /// The data of an event of the transaction is read as the transaction made it, not marshalled and unmarshalled again
  @Test
  public void theDataOfAnEventOfTheTransactionIsReadAsItIs() {
    var data = new Assigned("8");
    List<Event<?>> events = List.of(Event.of(randomUUID(), 1, NumberAssigned, ZonedDateTime.now(), data));
    EventLog log = new EventLog(null, null, events);
    assertSame(data, log.last(NumberAssigned));
    assertSame(data, log.all(NumberAssigned).getFirst());
    assertSame(data, log.oneIfExists(NumberAssigned).orElseThrow());
  }

}
