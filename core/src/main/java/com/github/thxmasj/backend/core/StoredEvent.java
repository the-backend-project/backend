package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/// An event as the event log stores it: with its data marshalled, as its type's output data type marshals it, and
/// masked if its transition masks it, or null if it has none. It is made by the transition of an event which is stored,
/// and read from the event log. The data is unmarshalled by the [EventLog] it is in. Stored events are equal if their
/// entity ids, numbers, types, timestamps and data are.
///
/// @param data the data as stored, which must not be changed
public record StoredEvent<T>(
    UUID entityId,
    int eventNumber,
    EventType<?, T> type,
    ZonedDateTime timestamp,
    byte[] data
) implements EventLog.Entry<T> {

  public StoredEvent {
    Objects.requireNonNull(type, "type is null");
  }

  /// The event as it is stored, with its data masked by the mask, if it isn't null, and marshalled
  ///
  /// @throws IllegalStateException if the mask gives null, which an event can't be stored with
  public static <T> StoredEvent<T> of(Event<T> event, UnaryOperator<T> mask) {
    T data = event.data();
    if (data != null && mask != null) {
      data = mask.apply(data);
      if (data == null)
        throw new IllegalStateException("The mask of " + event.type().name() + " gives null, which an event can't be stored with");
    }
    return new StoredEvent<>(
        event.entityId(),
        event.eventNumber(),
        event.type(),
        event.timestamp(),
        data != null ? event.type().outputDataType().marshal(data) : null
    );
  }

  /// The data unmarshalled, or null if the event has none, as a new instance on every call
  T unmarshalled() {
    return data == null ? null : type.outputDataType().unmarshal(data);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof StoredEvent<?> that
        && eventNumber == that.eventNumber
        && Objects.equals(entityId, that.entityId)
        && type.equals(that.type)
        && Objects.equals(timestamp, that.timestamp)
        && Arrays.equals(data, that.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entityId, eventNumber, type, timestamp, Arrays.hashCode(data));
  }

  /// The event without its data, which may be a secret
  @Override
  public String toString() {
    return "StoredEvent[entityId=" + entityId + ", eventNumber=" + eventNumber + ", type=" + type + ", timestamp=" +
        timestamp + ", data#=" + (data != null ? data.length : 0) + "]";
  }

}
