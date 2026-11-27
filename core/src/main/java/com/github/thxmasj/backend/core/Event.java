package com.github.thxmasj.backend.core;

import static java.util.Objects.requireNonNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/// An event of an entity: its type, its number in the entity's event log, when it happened and its data. An event is
/// immutable. An event of a transition has its data, and is marshalled by its transition if it is stored, see
/// [#marshalled()]. An event read from the event log has its data as stored, which is unmarshalled when it is read.
///
/// Nothing may change the data of an event, neither the transitions reading it, nor the one who triggered it, nor a
/// listener: they share it. Event data should therefore be immutable, e.g. records with immutable collections, made
/// with `List.copyOf` in the compact constructor.
public final class Event<T> {

  private final UUID entityId;
  private final int eventNumber;
  private final EventType<?, T> type;
  private final ZonedDateTime timestamp;
  /// Whether the event has its data unmarshalled, as an event of a transition has, rather than only as stored
  private final boolean hasData;
  private final T data;
  /// Whether the event has its data as stored, which may be none, as an event read from the event log or marshalled
  /// has
  private final boolean marshalled;
  private final byte[] storedData;
  /// Gives the data as it is stored, e.g. masked, from the data, or null to store it as it is
  private final UnaryOperator<T> storedForm;

  private Event(
      UUID entityId,
      int eventNumber,
      EventType<?, T> type,
      ZonedDateTime timestamp,
      boolean hasData,
      T data,
      boolean marshalled,
      byte[] storedData,
      UnaryOperator<T> storedForm
  ) {
    this.entityId = entityId;
    this.eventNumber = eventNumber;
    this.type = requireNonNull(type, "type is null");
    this.timestamp = timestamp;
    this.hasData = hasData;
    this.data = data;
    this.marshalled = marshalled;
    this.storedData = storedData;
    this.storedForm = storedForm;
  }

  /// An event with the data, or none if it is null, e.g. of a transition, which is marshalled by [#marshalled()]
  public static <T> Event<T> of(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, T data) {
    return new Event<>(entityId, eventNumber, type, timestamp, true, data, false, null, null);
  }

  /// An event whose data in the session differs from the data stored, e.g. masked. [#data()] gives the data, while
  /// [#storedData()], which is what is stored, has the stored form of the data, which [#marshalled()] makes, as an
  /// event which is not stored never is.
  ///
  /// @param storedForm gives the data as it is stored, which must not be null
  public static <T> Event<T> masked(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, T data, UnaryOperator<T> storedForm) {
    return new Event<>(entityId, eventNumber, type, timestamp, true, data, false, null, requireNonNull(storedForm, "storedForm is null"));
  }

  /// An event as it is stored, with its data marshalled, or null if it has none, e.g. read from the event log
  public static <T> Event<T> stored(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, byte[] data) {
    return new Event<>(entityId, eventNumber, type, timestamp, false, null, true, data, null);
  }

  public UUID entityId() {
    return entityId;
  }

  public int eventNumber() {
    return eventNumber;
  }

  public ZonedDateTime timestamp() {
    return timestamp;
  }

  public EventType<?, T> type() {
    return type;
  }

  public String typeName() {
    return type.name();
  }

  /// Whether the event is stored masked, so that its data in the transaction differs from the data stored
  public boolean isMasked() {
    return storedForm != null;
  }

  /// Whether the event has its data as stored, see [#storedData()]
  public boolean isMarshalled() {
    return marshalled;
  }

  /// The event with its data as it is stored, e.g. masked, or the event itself if it has it already. The transition of
  /// an event marshals it if it is stored, so that a failure of masking or marshalling it is one of the transition.
  ///
  /// @throws IllegalStateException if the mask gives null, which an event can't be stored with
  public Event<T> marshalled() {
    if (marshalled)
      return this;
    if (data == null)
      return new Event<>(entityId, eventNumber, type, timestamp, hasData, null, true, null, storedForm);
    T stored = storedForm != null ? storedForm.apply(data) : data;
    if (stored == null)
      throw new IllegalStateException("The mask of " + type.name() + " gives null, which an event can't be stored with");
    byte[] bytes = type.outputDataType().marshal(stored);
    return new Event<>(entityId, eventNumber, type, timestamp, hasData, data, true, bytes, storedForm);
  }

  /// The event as it is stored, e.g. masked, as a later transaction reads it from the event log
  ///
  /// @throws IllegalStateException if the event is masked and not marshalled
  public Event<T> asStored() {
    if (storedForm == null)
      return this;
    return stored(entityId, eventNumber, type, timestamp, storedData());
  }

  @Override
  public String toString() {
    return "Event{" +
        "entityId=" + entityId +
        ", eventNumber=" + eventNumber +
        ", type=" + type +
        ", typeInput=" + type.inputDataType().name() +
        ", typeOutput=" + type.outputDataType().name() +
        ", timestamp=" + timestamp +
        ", data#=" + (storedData != null ? storedData.length : 0) +
        '}';
  }

  /// The data of the event, or null if it has none. A masked event of the transaction gives its whole data, which only
  /// the transaction has. An event read from the event log unmarshals its data on every call: a transition reads it
  /// through the log it is given, which unmarshals it once (see [EventLog]). The data must not be changed, see [Event].
  public T data() {
    return hasData ? data : unmarshal(type, storedData);
  }

  /// Whether the event has its data unmarshalled, so that [#data()] doesn't unmarshal it
  boolean hasData() {
    return hasData;
  }

  /// The data as it is stored, e.g. masked, or null if the event has none
  ///
  /// @throws IllegalStateException if the event is not marshalled, see [#marshalled()]
  public byte[] storedData() {
    if (!marshalled)
      throw new IllegalStateException("The event " + type.name() + " is not marshalled, as its transition marshals an event which is stored");
    return storedData;
  }

  static <EVENT> List<EVENT> join(List<EVENT> events, EVENT tail) {
    return Stream.concat(events.stream(), Stream.of(tail)).toList();
  }

  static <EVENT> List<EVENT> join(EVENT head, List<EVENT> events) {
    return Stream.concat(Stream.of(head), events.stream()).toList();
  }

  static List<Event<?>> join(List<Event<?>> list1, List<Event<?>> list2) {
    return Stream.concat(list1.stream(), list2.stream()).toList();
  }

  static <T> T unmarshal(EventType<?, T> eventType, byte[] data) {
    return data == null ? null : eventType.outputDataType().unmarshal(data);
  }

}
