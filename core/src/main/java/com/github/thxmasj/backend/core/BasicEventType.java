package com.github.thxmasj.backend.core;

import java.util.Objects;
import java.util.UUID;

/// The event types created by the factory methods of [EventType], equal if their ids are, whatever their kind
sealed class BasicEventType<I, O> implements EventType<I, O> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<O> outputDataType;

  BasicEventType(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    this.name = Objects.requireNonNull(name, "name is null");
    this.id = Objects.requireNonNull(id, "id is null");
    this.inputDataType = Objects.requireNonNull(inputDataType, "inputDataType is null");
    this.outputDataType = Objects.requireNonNull(outputDataType, "outputDataType is null");
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public DataType<I> inputDataType() {
    return inputDataType;
  }

  @Override
  public DataType<O> outputDataType() {
    return outputDataType;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof BasicEventType<?, ?> that && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  static final class NotStored<I, O> extends BasicEventType<I, O> implements EventType.NotStored<I, O> {
    NotStored(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
      super(name, id, inputDataType, outputDataType);
    }
  }

  static final class Request<I, O> extends BasicEventType<I, O> implements EventType.Request<I, O> {
    Request(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
      super(name, id, inputDataType, outputDataType);
    }
  }

  static final class Rollback extends BasicEventType<Rollback.Data, Rollback.Data> implements EventType.Rollback {
    Rollback(String name, UUID id) {
      super(name, id, DataType.json(Data.class), DataType.json(Data.class));
    }
  }

}
