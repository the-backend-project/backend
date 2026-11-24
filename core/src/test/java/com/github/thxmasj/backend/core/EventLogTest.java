package com.github.thxmasj.backend.core;

import static java.util.UUID.randomUUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class EventLogTest {

  @Test
  public void whenOneEventInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
      new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsInLogEffectiveEventsAreSameAsEvents() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(events, log.effectiveEvents());
  }

  @Test
  public void whenTwoEventsAndRollbackInLogEffectiveEventsAreNone() {
    List<Event<?>> events = List.of(
        new Event<>(randomUUID(), 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 2, new BasicEventType.DataLess("Test2", randomUUID()), Clock.systemUTC()),
        new Event<>(randomUUID(), 3, BuiltinEventTypes.EventsRolledBack, Clock.systemUTC(), new Data(0, "testing"))
        //new Event.Rollback(3, 0, "testing", Clock.systemUTC())
    );
    EventLog log = new EventLog(null, null, events);
    assertEquals(List.of(), log.effectiveEvents());
  }

  @Test
  public void eventLogPreservesEntityModel() {
    EntityModel model = EntityModel.of("TestModel", randomUUID(), Map::of);
    UUID entityId = randomUUID();
    List<Event<?>> events = List.of(
        new Event<>(entityId, 1, new BasicEventType.DataLess("Test1", randomUUID()), Clock.systemUTC())
    );
    EventLog log = new EventLog(model, entityId, events);
    assertEquals(model, log.entityModel());
    assertEquals(entityId, log.entityId());
    assertEquals(events, log.events());
  }

  record Assigned(String number) {}

  private static final EventType<Assigned, Assigned> NumberAssigned = BasicEventType.of("Number assigned", randomUUID(), DataType.json(Assigned.class));

  /// An event stored with no data, e.g. of a transition recording nothing, is read as null, not failing
  @Test
  public void anEventStoredWithoutDataIsReadAsNull() {
    var clock = Clock.systemUTC();
    UUID entityId = randomUUID();
    List<Event<?>> events = List.of(
        new Event<>(entityId, 1, NumberAssigned, LocalDateTime.now(clock), clock, null),
        new Event<>(entityId, 2, NumberAssigned, LocalDateTime.now(clock), clock, DataType.json(Assigned.class).marshal(new Assigned("7")))
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
    List<Event<?>> events = List.of(new Event<>(randomUUID(), 1, NumberAssigned, Clock.systemUTC(), data));
    EventLog log = new EventLog(null, null, events);
    assertSame(data, log.last(NumberAssigned));
    assertSame(data, log.all(NumberAssigned).getFirst());
    assertSame(data, log.oneIfExists(NumberAssigned).orElseThrow());
  }

}
