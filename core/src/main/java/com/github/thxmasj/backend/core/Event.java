package com.github.thxmasj.backend.core;

import static java.util.Objects.requireNonNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class Event<T> {

  private final UUID entityId;
  private final int eventNumber;
  private final EventType<?, T> type;
  private final ZonedDateTime timestamp;
  /// Gives the data as it is stored, e.g. masked, from the data, or null to store it as it is
  private final UnaryOperator<T> storedForm;
  // The data, as stored and unmarshalled, of which one is made from the other when it is first asked for. They are
  // guarded by the event, as an event may be read by other threads than the one which created it.
  private byte[] data;
  private T unmarshalledData;
  /// Whether the stored form has been marshalled, which may give no data
  private boolean marshalled;

  private Event(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, byte[] data, T unmarshalledData, UnaryOperator<T> storedForm) {
    this.entityId = entityId;
    this.eventNumber = eventNumber;
    this.type = requireNonNull(type, "type is null");
    this.timestamp = timestamp;
    this.data = data;
    this.unmarshalledData = unmarshalledData;
    this.storedForm = storedForm;
  }

  /// An event with the data, or none if it is null, e.g. of a transition. The data is marshalled when it is first asked
  /// for, e.g. when the event is stored.
  public static <T> Event<T> of(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, T data) {
    return new Event<>(entityId, eventNumber, type, timestamp, null, data, null);
  }

  /// An event whose data in the session differs from the data stored, e.g. masked. [#data()] gives the data, while
  /// [#storedData()], which is what is stored, has the stored form of the data, which is made when it is first asked
  /// for, as an event which is not stored never is.
  ///
  /// @param storedForm gives the data as it is stored, which must not be null
  public static <T> Event<T> masked(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, T data, UnaryOperator<T> storedForm) {
    return new Event<>(entityId, eventNumber, type, timestamp, null, data, requireNonNull(storedForm, "storedForm is null"));
  }

  /// An event as it is stored, with its data marshalled, or null if it has none, e.g. read from the event log. The
  /// data is unmarshalled when it is first asked for.
  public static <T> Event<T> stored(UUID entityId, int eventNumber, EventType<?, T> type, ZonedDateTime timestamp, byte[] data) {
    return new Event<>(entityId, eventNumber, type, timestamp, data, null, null);
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

  /// The event as it is stored, e.g. masked, as a later transaction reads it from the event log
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
        ", data#=" + dataLength() +
        '}';
  }

  private synchronized int dataLength() {
    return data != null ? data.length : 0;
  }

  /// The data of the event, unmarshalled when it is first asked for, or null if it has none. A masked event of the
  /// transaction gives its whole data, which only the transaction has.
  public synchronized T data() {
    if (unmarshalledData == null && data != null) {
      unmarshalledData = unmarshal(type, data);
    }
    return unmarshalledData;
  }

  /// The data as it is stored, e.g. masked, marshalled when it is first asked for, or null if the event has none
  public synchronized byte[] storedData() {
    if (data == null && unmarshalledData != null && !marshalled) {
      T stored = storedForm != null ? storedForm.apply(unmarshalledData) : unmarshalledData;
      if (stored == null)
        throw new IllegalStateException("The mask of " + type.name() + " gives null, which an event can't be stored with");
      data = type.outputDataType().marshal(stored);
      marshalled = true;
    }
    return data;
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

  static <T> byte[] marshal(EventType<?, T> eventType, T data) {
    return eventType.outputDataType().marshal(data);
  }

  static <T> T unmarshal(EventType<?, T> eventType, byte[] data) {
    return eventType.outputDataType().unmarshal(data);
  }

}
