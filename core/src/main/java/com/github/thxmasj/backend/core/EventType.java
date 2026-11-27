package com.github.thxmasj.backend.core;

import java.util.UUID;

/// A type of events: their name, their id and the types of their data. The input is what a transition on the event
/// type is given, and the output is what its event has, which is what is stored. An event type is created with the
/// factory methods of this interface, e.g. [#of(String, UUID, DataType)]. The ids of the event types of a state machine
/// are unique. The event types the factory methods create are equal if their ids are, whatever their kind, while
/// another implementation of the interface decides its own equality, e.g. by identity.
///
/// The sub-types of an event type tell how the state machine treats its events: [NotStored], [Request] and [Rollback].
/// An event type of none of them is an ordinary one, whose events are stored.
public interface EventType<I, O> {

  String name();

  UUID id();

  DataType<I> inputDataType();

  DataType<O> outputDataType();

  /// An event type whose events are not stored. An event which is not stored takes part in its transition like any
  /// other: it is calculated, may trigger events on other entities, which are stored if they are of stored types, is
  /// given to the one who triggered it and is told to the [Listener]. But it is not written to the event log, so the
  /// state of its entity must not depend on it: on an entity which is stored, its transitions stay in their state
  /// (`toSelf()`), which the state machine checks when it is created. An event which is not stored creating an entity,
  /// i.e. from [EntityType#Begin], makes the entity one of the session only: none of its events are stored, and only
  /// the transactions of the session find it (see [EntityType.NotStored]).
  ///
  /// A typical one reads an entity for another entity to assemble from, named `<subject> looked up`.
  interface NotStored<I, O> extends EventType<I, O> {}

  /// An event type of requests, e.g. the validated request of a payment. A rollback goes back over one request at
  /// most: it is rejected if it would roll back more than one event of a request type.
  interface Request<I, O> extends EventType<I, O> {}

  /// An event type rolling back the events of its entity, to the event number in its [Data]
  interface Rollback extends EventType<Rollback.Data, Rollback.Data> {

    /// The event number the events are rolled back to, which is the number of the last event kept, or relative to
    /// the rollback if it is negative, and why
    record Data(
        int toNumber,
        String reason
    ) {}
  }

  /// An event type whose events have no data
  static EventType<Void, Void> of(String name, UUID id) {
    return of(name, id, DataType.none(), DataType.none());
  }

  /// An event type whose input and output have the same data type
  static <T> EventType<T, T> of(String name, UUID id, DataType<T> dataType) {
    return of(name, id, dataType, dataType);
  }

  static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    return new BasicEventType<>(name, id, inputDataType, outputDataType);
  }

  /// An event type whose events are not stored and have no data, see [NotStored]
  static NotStored<Void, Void> notStored(String name, UUID id) {
    return notStored(name, id, DataType.none(), DataType.none());
  }

  /// An event type whose events are not stored, see [NotStored]
  static <T> NotStored<T, T> notStored(String name, UUID id, DataType<T> dataType) {
    return notStored(name, id, dataType, dataType);
  }

  /// An event type whose events are not stored, see [NotStored]
  static <I, O> NotStored<I, O> notStored(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    return new BasicEventType.NotStored<>(name, id, inputDataType, outputDataType);
  }

  /// An event type of requests, see [Request]
  static <T> Request<T, T> request(String name, UUID id, DataType<T> dataType) {
    return request(name, id, dataType, dataType);
  }

  /// An event type of requests, see [Request]
  static <I, O> Request<I, O> request(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    return new BasicEventType.Request<>(name, id, inputDataType, outputDataType);
  }

  /// An event type rolling back the events of its entity, see [Rollback]
  static Rollback rollback(String name, UUID id) {
    return new BasicEventType.Rollback(name, id);
  }

}
