package com.github.thxmasj.backend.core;

import static java.util.Objects.requireNonNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/// An event of an entity, as a transition makes it: its type, its number in the entity's event log, when it happened
/// and its data, the output of its transition, or null if it has none. It is what the transition's triggers, the one who
/// triggered it and the transitions of the same transaction see. The event log stores it as a [StoredEvent], which its
/// transition makes if it is stored, masked if the transition masks it.
///
/// Nothing may change the data of an event, neither the transitions reading it, nor the one who triggered it, nor a
/// listener: they share it. Event data should therefore be immutable, e.g. records with immutable collections, made
/// with `List.copyOf` in the compact constructor.
public record Event<T>(
    UUID entityId,
    int eventNumber,
    EventType<?, T> type,
    ZonedDateTime timestamp,
    T data
) implements EventLog.Entry<T> {

  public Event {
    requireNonNull(type, "type is null");
  }

  /// The event without its data, which may be a secret
  @Override
  public String toString() {
    return "Event[entityId=" + entityId + ", eventNumber=" + eventNumber + ", type=" + type + ", timestamp=" + timestamp + "]";
  }

  static <EVENT> List<EVENT> join(List<EVENT> events, EVENT tail) {
    return Stream.concat(events.stream(), Stream.of(tail)).toList();
  }

  static <EVENT> List<EVENT> join(EVENT head, List<EVENT> events) {
    return Stream.concat(Stream.of(head), events.stream()).toList();
  }

}
