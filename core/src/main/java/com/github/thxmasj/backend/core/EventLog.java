package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.Event.join;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/// The event log of an entity: its events, oldest first. Its accessors give the data of the events, which every reader
/// shares, so nothing may change it (see [Event]).
///
/// The log a transition is given unmarshals each event read from the event log at most once, and shares the data
/// within the transition. It is confined to the transition, whose steps are taken one after another, so it needs no
/// locking. Any other log unmarshals the data of such an event on every read. An event of the transaction has its data,
/// which is never unmarshalled.
public final class EventLog {

  private final EntityType entityType;
  private final UUID entityId;
  private final List<Event<?>> events;
  /// The data of the events read from the event log which have been read, if the log is a transition's, or else null
  private final Map<Event<?>, Object> unmarshalled;

  public EventLog(EntityType entityType, UUID entityId, List<Event<?>> events) {
    this(entityType, entityId, events, null);
  }

  private EventLog(EntityType entityType, UUID entityId, List<Event<?>> events, Map<Event<?>, Object> unmarshalled) {
    this.entityType = entityType;
    this.entityId = entityId;
    this.events = events;
    this.unmarshalled = unmarshalled;
  }

  public EntityType entityType() {
    return entityType;
  }

  public UUID entityId() {
    return entityId;
  }

  public List<Event<?>> events() {
    return events;
  }

  /// The log as a transition is given it, which unmarshals the data of each event at most once, see [EventLog]. It
  /// must only be read by the transition, e.g. by its steps and the transitions it chooses.
  EventLog forTransition() {
    return new EventLog(entityType, entityId, events, new IdentityHashMap<>());
  }

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  public <T> T number(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> this.<T>data(e))
        .orElseThrow(() -> new NoSuchElementException("number(" + number + ", " + eventType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> Optional<T> numberIfExists(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> this.<T>data(e));
  }

  public <T> T one(DataType<T> dataType) {
    return effectiveEvents().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> this.<T>data(e))
        .filter(e -> e != null)
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("one(" + dataType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> T last(DataType<T> dataType) {
    T result = effectiveEvents().reversed().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> this.<T>data(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + dataType.name() + ")"));
    return result;
  }

  public <T> T one(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>data(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(String.format("one(%s) on %s [log: %s]",
            eventType.name(),
            entityType.name(),
            events.stream().map(Event::typeName).collect(joining(","))
        )));
  }

  @SafeVarargs
  public final <T> T one(EventType<?, T>... eventTypes) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventTypes).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> this.<T>data(e))
        .findFirst()
        .orElseThrow();
  }

  public <T> Optional<T> oneIfExists(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventType).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> this.<T>data(e))
        .findFirst();
  }

  public <T> T last(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>data(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + eventType.name() + ")"));
  }

  public <T> Optional<T> lastIfExists(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>data(e))
        .findFirst();
  }

  public <T> List<T> all(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>data(e))
        .toList();
  }

  public ZonedDateTime created() {
    // TODO: Assumes empty log will never exist
    return events().getFirst().timestamp();
  }

  public long sum(EventType<?, Long> eventType) {
    return all(eventType).stream().mapToLong(l -> l).sum();
  }

  public long count(EventType<?, ?> eventType) {
    return effectiveEvents().stream().filter(e -> e.type().id().equals(eventType.id())).count();
  }

  public boolean exists(EventType<?, ?> eventType) {
    return events().stream().anyMatch(e -> e.type().id().equals(eventType.id()));
  }

  public List<Event<?>> subLog(int fromEventNumber) {
    return events.stream().filter(e -> e.eventNumber() >= fromEventNumber).toList();
  }

  /**
   * Effective events, ignoring events that were rolled back.
   */
  public List<Event<?>> effectiveEvents() {
    // Traverse the event log backwards and skip events between a rollback and its target exclusively
    List<Event<?>> effectiveEventsReversed = new ArrayList<>(events.size());
    int skipTo = Integer.MAX_VALUE;
    for (Event<?> event : events.reversed()) {
      if (skipTo >= event.eventNumber()) {
        if (event.type() instanceof EventType.Rollback) {
          int toNumber = this.<EventType.Rollback.Data>data(event).toNumber();
          skipTo = toNumber >= 0 ? toNumber : event.eventNumber() - 1 + toNumber ;
        } else {
          effectiveEventsReversed.add(event);
        }
      }
    }
    return unmodifiableList(effectiveEventsReversed.reversed());
  }

  /// The log with its events as they are stored, e.g. masked, as a later transaction reads it
  EventLog asStored() {
    return new EventLog(entityType(), entityId(), events().stream().<Event<?>>map(Event::asStored).toList());
  }

  public EventLog withNewEvent(Event<?> newEvent) {
    return new EventLog(
        entityType(),
        entityId(),
        join(events(), newEvent)
    );
  }

  public static EventLog empty(UUID id, EntityType entityType) {
    return new EventLog(entityType, id, List.of());
  }


  /// The data of the event, or null if it has none, shared by its readers. A masked event of the transaction gives its
  /// whole data, which only the transaction has. The data of an event read from the event log is unmarshalled once in
  /// the log of a transition, and on every read in any other log.
  @SuppressWarnings("unchecked")
  private <T> T data(Event<?> event) {
    Event<T> typed = (Event<T>) event;
    if (unmarshalled == null || typed.hasData())
      return typed.data();
    if (unmarshalled.containsKey(typed))
      return (T) unmarshalled.get(typed);
    T data = typed.data();
    unmarshalled.put(typed, data);
    return data;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof EventLog that
        && Objects.equals(entityType, that.entityType)
        && Objects.equals(entityId, that.entityId)
        && Objects.equals(events, that.events);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entityType, entityId, events);
  }

  @Override
  public String toString() {
    return "EventLog[entityType=" + entityType + ", entityId=" + entityId + ", events=" + events + "]";
  }

  /// Whether the entity is one of the session only, whose events are not stored: of a model which is not stored, or
  /// created by an event which is not stored
  boolean isSessionOnly() {
    return entityType instanceof EntityType.NotStored || (!events.isEmpty() && events.getFirst().type() instanceof EventType.NotStored);
  }

  /// Whether the entity would be one of the session only with the event as its next, see [#isSessionOnly()]
  boolean isSessionOnlyWith(Event<?> next) {
    Event<?> first = events.isEmpty() ? next : events.getFirst();
    return entityType instanceof EntityType.NotStored || first.type() instanceof EventType.NotStored;
  }


}
