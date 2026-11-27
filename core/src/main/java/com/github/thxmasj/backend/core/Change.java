package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/// What the state machine stores of an entity, or reads back: an event as stored, a delayed or a conditional trigger,
/// or the deletion of a stored trigger which fired an event not stored. A transaction stores its changes together. A
/// change is a value, which doesn't change.
public sealed interface Change permits Change.Event, Change.DelayedTrigger, Change.ConditionalTrigger, Change.FiredTrigger {

  /// The entity type of the entity the change is of
  EntityType entityType();

  /// The entity the change is of
  UUID entityId();

  /// An event as the event log stores it: with its data marshalled, as its type's output data type marshals it, and
  /// masked if its transition masks it, or null if it has none. It is made by the transition of an event which is
  /// stored, and read from the event log. The data is unmarshalled by the [EventLog] it is in. Stored events are equal if
  /// their entity types, entity ids, numbers, types, timestamps and data are.
  ///
  /// @param data the data as stored, which must not be changed
  record Event<T>(
      EntityType entityType,
      UUID entityId,
      int eventNumber,
      EventType<?, T> type,
      ZonedDateTime timestamp,
      byte[] data
  ) implements Change, EventLog.Entry<T> {

    public Event {
      Objects.requireNonNull(type, "type is null");
    }

    /// The event as it is stored, with its data masked by the mask, if it isn't null, and marshalled
    ///
    /// @throws IllegalStateException if the mask gives null, which an event can't be stored with
    public static <T> Event<T> of(EntityType entityType, com.github.thxmasj.backend.core.Event<T> event, UnaryOperator<T> mask) {
      T data = event.data();
      if (data != null && mask != null) {
        data = mask.apply(data);
        if (data == null)
          throw new IllegalStateException("The mask of " + event.type().name() + " gives null, which an event can't be stored with");
      }
      return new Event<>(
          entityType,
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
      return o instanceof Event<?> that
          && eventNumber == that.eventNumber
          && Objects.equals(entityType, that.entityType)
          && Objects.equals(entityId, that.entityId)
          && type.equals(that.type)
          && Objects.equals(timestamp, that.timestamp)
          && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entityType, entityId, eventNumber, type, timestamp, Arrays.hashCode(data));
    }

    /// The event without its data, which may be a secret
    @Override
    public String toString() {
      return (entityType != null ? entityType.name() : null) + ":" + entityId + ":ev:[" + type.name() + "]:" + eventNumber +
          " (data#=" + (data != null ? data.length : 0) + ")";
    }

  }

  /// A delayed trigger: the event the entity is to take after a deadline, unless another event is stored on it first.
  /// It has the number of the event it would get, as an event stored on the entity before it makes it stale.
  ///
  /// @param deadline      when it fires: after the delay, when its transition makes it, and its next attempt, when it
  ///                      is read back to fire
  /// @param correlationId the correlation id of the transaction which stored it, when it is read back, or null when its
  ///                      transition makes it, as the transaction storing it gives it
  record DelayedTrigger<T>(
      EntityType entityType,
      UUID entityId,
      EventType<T, ?> type,
      int eventNumber,
      T data,
      ZonedDateTime deadline,
      String correlationId
  ) implements Change {

    public DelayedTrigger<T> withEventNumber(int eventNumber) {
      return new DelayedTrigger<>(entityType, entityId, type, eventNumber, data, deadline, correlationId);
    }

    /// The trigger without its data, which may be a secret, as it is written e.g. in debug output
    @Override
    public String toString() {
      return "DelayedTrigger[" + entityType.name() + ":" + entityId + ":" + eventNumber + ":" + type.name() + ", deadline " + deadline + "]";
    }

  }

  /// A conditional trigger: the event the entity of the trigger event is to take when the entity of the index key
  /// has, as its last event, one of the last types
  record ConditionalTrigger<T>(
      UUID id,
      EventReference triggerEvent,
      EntityType entityType,
      UUID entityId,
      EventType<T, ?> type,
      T data,
      ZonedDateTime timestamp,
      EventType<?, ?> referenceType,
      List<EventType<?, ?>> lastTypes,
      UUID indexKey,
      String correlationId
  ) implements Change {

    /// The trigger without its data, which may be a secret, as it is written e.g. in debug output
    @Override
    public String toString() {
      return "ConditionalTrigger[" + id + ": " + type.name() + " on " + entityType.name() + ":" + entityId + "]";
    }

  }

  /// The deletion of a stored trigger which fired an event not stored, as no event stored deletes it
  ///
  /// @param id          the id of a conditional trigger, or null for a delayed one
  /// @param eventNumber the number of the event it fired, which a delayed trigger has
  /// @param eventTypeId the type of the event it fired, by which a delayed trigger is told from one which replaced it
  ///                    with the same number
  record FiredTrigger(EntityType entityType, UUID entityId, TriggerType type, UUID id, int eventNumber, UUID eventTypeId) implements Change {

    @Override
    public String toString() {
      return entityType.name() + ":" + entityId + ":fired(" + type + "):" + eventNumber;
    }

  }

}
