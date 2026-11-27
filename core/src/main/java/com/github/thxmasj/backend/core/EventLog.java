package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.Event.join;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/// The event log of an entity: its events, oldest first. An event read from the event log is a [StoredEvent], with its
/// data as stored, and an event of the transaction an [Event], with its data as the transition made it. The accessors
/// give the data of the events, which every reader shares, so nothing may change it (see [Event]).
///
/// The log a transition is given unmarshals each stored event at most once, and shares the data within the
/// transition. It is confined to the transition, whose steps are taken one after another, so it needs no locking. Any
/// other log unmarshals the data of a stored event on every read.
public final class EventLog {

  /// An event in an event log, as stored or as its transition made it
  public sealed interface Entry<T> permits Event, Change.Event {

    UUID entityId();

    int eventNumber();

    EventType<?, T> type();

    ZonedDateTime timestamp();

    default String typeName() {
      return type().name();
    }
  }

  private final EntityType entityType;
  private final UUID entityId;
  private final List<Entry<?>> events;
  /// The stored forms of the events of the transaction which are stored, by their numbers, which [#asStored()] gives
  private final Map<Integer, Change.Event<?>> storedForms;
  /// The data of the stored events which have been read, by their numbers, if the log is a transition's, or else null
  private final Map<Integer, Object> unmarshalled;

  public EventLog(EntityType entityType, UUID entityId, List<? extends Entry<?>> events) {
    this(entityType, entityId, List.copyOf(events), Map.of(), null);
  }

  private EventLog(
      EntityType entityType,
      UUID entityId,
      List<Entry<?>> events,
      Map<Integer, Change.Event<?>> storedForms,
      Map<Integer, Object> unmarshalled
  ) {
    this.entityType = entityType;
    this.entityId = entityId;
    this.events = events;
    this.storedForms = storedForms;
    this.unmarshalled = unmarshalled;
  }

  public EntityType entityType() {
    return entityType;
  }

  public UUID entityId() {
    return entityId;
  }

  public List<Entry<?>> events() {
    return events;
  }

  /// The log as a transition is given it, which unmarshals the data of each stored event at most once, see
  /// [EventLog]. It must only be read by the transition, e.g. by its steps and the transitions it chooses.
  EventLog forTransition() {
    return new EventLog(entityType, entityId, events, storedForms, new HashMap<>());
  }

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  public <T> T number(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> this.<T>dataOf(e))
        .orElseThrow(() -> new NoSuchElementException("number(" + number + ", " + eventType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> Optional<T> numberIfExists(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> this.<T>dataOf(e));
  }

  public <T> T one(DataType<T> dataType) {
    return effectiveEvents().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> this.<T>dataOf(e))
        .filter(e -> e != null)
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("one(" + dataType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> T last(DataType<T> dataType) {
    T result = effectiveEvents().reversed().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> this.<T>dataOf(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + dataType.name() + ")"));
    return result;
  }

  public <T> T one(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>dataOf(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(String.format("one(%s) on %s [log: %s]",
            eventType.name(),
            entityType.name(),
            events.stream().map(Entry::typeName).collect(joining(","))
        )));
  }

  @SafeVarargs
  public final <T> T one(EventType<?, T>... eventTypes) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventTypes).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> this.<T>dataOf(e))
        .findFirst()
        .orElseThrow();
  }

  public <T> Optional<T> oneIfExists(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventType).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> this.<T>dataOf(e))
        .findFirst();
  }

  public <T> T last(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>dataOf(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + eventType.name() + ")"));
  }

  public <T> Optional<T> lastIfExists(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>dataOf(e))
        .findFirst();
  }

  public <T> List<T> all(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> this.<T>dataOf(e))
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

  public List<Entry<?>> subLog(int fromEventNumber) {
    return events.stream().filter(e -> e.eventNumber() >= fromEventNumber).toList();
  }

  /**
   * Effective events, ignoring events that were rolled back.
   */
  public List<Entry<?>> effectiveEvents() {
    // Traverse the event log backwards and skip events between a rollback and its target exclusively
    List<Entry<?>> effectiveEventsReversed = new ArrayList<>(events.size());
    int skipTo = Integer.MAX_VALUE;
    for (Entry<?> event : events.reversed()) {
      if (skipTo >= event.eventNumber()) {
        if (event.type() instanceof EventType.Rollback) {
          int toNumber = this.<EventType.Rollback.Data>dataOf(event).toNumber();
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
    return new EventLog(
        entityType,
        entityId,
        events.stream().<Entry<?>>map(e -> e instanceof Event<?> && storedForms.containsKey(e.eventNumber()) ? storedForms.get(e.eventNumber()) : e).toList(),
        Map.of(),
        null
    );
  }

  /// The log with the event after its events, e.g. one read from the event log
  public EventLog withNewEvent(Entry<?> newEvent) {
    return withNewEvent(newEvent, null);
  }

  /// The log with the event of a transition after its events, and its stored form, if it is stored, which
  /// [#asStored()] gives
  EventLog withNewEvent(Entry<?> newEvent, Change.Event<?> storedForm) {
    Map<Integer, Change.Event<?>> forms = storedForms;
    if (storedForm != null) {
      var added = new HashMap<>(storedForms);
      added.put(newEvent.eventNumber(), storedForm);
      forms = Map.copyOf(added);
    }
    return new EventLog(entityType, entityId, join(events, newEvent), forms, null);
  }

  public static EventLog empty(UUID id, EntityType entityType) {
    return new EventLog(entityType, id, List.of());
  }


  /// The data of the event of the log, or null if it has none, shared by its readers. A masked event of the
  /// transaction gives its whole data, which only the transaction has. The data of a stored event is unmarshalled once
  /// in the log of a transition, and on every read in any other log.
  public <T> T data(Entry<T> entry) {
    return this.<T>dataOf(entry);
  }

  @SuppressWarnings("unchecked")
  private <T> T dataOf(Entry<?> entry) {
    return switch ((Entry<T>) entry) {
      case Event<T> event -> event.data();
      case Change.Event<T> stored when unmarshalled == null -> stored.unmarshalled();
      case Change.Event<T> stored -> {
        if (unmarshalled.containsKey(stored.eventNumber()))
          yield (T) unmarshalled.get(stored.eventNumber());
        T data = stored.unmarshalled();
        unmarshalled.put(stored.eventNumber(), data);
        yield data;
      }
    };
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
  boolean isSessionOnlyWith(Entry<?> next) {
    Entry<?> first = events.isEmpty() ? next : events.getFirst();
    return entityType instanceof EntityType.NotStored || first.type() instanceof EventType.NotStored;
  }


}
