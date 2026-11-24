package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.Event.join;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public record EventLog(
    EntityModel entityModel,
    UUID entityId,
    List<Event<?>> events
) {

  public int lastEventNumber() {
    return events.isEmpty() ? 0 : events.getLast().eventNumber();
  }

  public <T> T number(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> EventLog.<T>copy(e))
        .orElseThrow(() -> new NoSuchElementException("number(" + number + ", " + eventType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> Optional<T> numberIfExists(int number, EventType<?, T> eventType) {
    return events()
        .stream()
        .filter(e -> e.eventNumber() == number && eventType.equals(e.type()))
        .findFirst()
        .map(e -> EventLog.<T>copy(e));
  }

  public <T> T one(DataType<T> dataType) {
    return effectiveEvents().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> EventLog.<T>copy(e))
        .filter(e -> e != null)
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("one(" + dataType.name() + "). Have:\n" + effectiveEvents().stream().map(e -> e.toString()).collect(joining("\n"))));
  }

  public <T> T last(DataType<T> dataType) {
    T result = effectiveEvents().reversed().stream()
        .filter(e -> dataType.equals(e.type().outputDataType()))
        .map(e -> EventLog.<T>cached(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + dataType.name() + ")"));
    return result;
  }

  public <T> T one(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> EventLog.<T>copy(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException(String.format("one(%s) on %s [log: %s]",
            eventType.name(),
            entityModel.name(),
            events.stream().map(Event::typeName).collect(joining(","))
        )));
  }

  @SafeVarargs
  public final <T> T one(EventType<?, T>... eventTypes) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventTypes).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> EventLog.<T>cached(e))
        .findFirst()
        .orElseThrow();
  }

  public <T> Optional<T> oneIfExists(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> Stream.of(eventType).map(EventType::id).toList().contains(e.type().id()))
        .map(e -> EventLog.<T>cached(e))
        .findFirst();
  }

  public <T> T last(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> EventLog.<T>cached(e))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("last(" + eventType.name() + ")"));
  }

  public <T> Optional<T> lastIfExists(EventType<?, T> eventType) {
    return effectiveEvents().reversed().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> EventLog.<T>cached(e))
        .findFirst();
  }

  public <T> List<T> all(EventType<?, T> eventType) {
    return effectiveEvents().stream()
        .filter(e -> e.type().id().equals(eventType.id()))
        .map(e -> EventLog.<T>cached(e))
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
        if (event.type() instanceof BasicEventType.Rollback rollbackType) {
          int toNumber = Event.unmarshal(rollbackType, event.data()).toNumber();
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
    return new EventLog(entityModel(), entityId(), events().stream().<Event<?>>map(Event::asStored).toList());
  }

  public EventLog withNewEvent(Event<?> newEvent) {
    return new EventLog(
        entityModel(),
        entityId(),
        join(events(), newEvent)
    );
  }

  public static EventLog empty(UUID id, EntityModel model) {
    return new EventLog(model, id, List.of());
  }


  /// The data of the event unmarshalled for the reader, as the data type unmarshals it, so that one changing it doesn't
  /// change what others read. A masked event of the transaction gives a copy of its whole data, which only the
  /// transaction has, as its stored data is masked.
  @SuppressWarnings("unchecked")
  private static <T> T copy(Event<?> event) {
    Event<T> typed = (Event<T>) event;
    byte[] data = typed.isMasked() ? Event.marshal(typed.type(), typed.getUnmarshalledData()) : typed.data();
    return Event.unmarshal(typed.type(), data);
  }

  /// The data of the event, unmarshalled once and shared by its readers, or null if it has none. A masked event of the
  /// transaction gives its whole data, which only the transaction has.
  @SuppressWarnings("unchecked")
  private static <T> T cached(Event<?> event) {
    return ((Event<T>) event).getUnmarshalledData();
  }

  /// Whether the entity is one of the session only, whose events are not stored: of a model which is not stored, or
  /// created by an event which is not stored
  boolean isSessionOnly() {
    return !entityModel.stored() || (!events.isEmpty() && !events.getFirst().type().stored());
  }

  /// Whether the entity would be one of the session only with the event as its next, see [#isSessionOnly()]
  boolean isSessionOnlyWith(Event<?> next) {
    Event<?> first = events.isEmpty() ? next : events.getFirst();
    return !entityModel.stored() || !first.type().stored();
  }


}
