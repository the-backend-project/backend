package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.internal.Memoized;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/// The entity types created by the factory methods of [EntityType], whose transitions are created when they are first
/// asked for
sealed class MemoizedEntityType implements EntityType {

  private final String name;
  private final UUID id;
  private final Memoized<Map<State, List<TransitionModel<?, ?>>>> transitions;

  MemoizedEntityType(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    this.name = name;
    this.id = id;
    this.transitions = new Memoized<>(transitions, "transitions of entity type " + name);
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
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions.get();
  }

  @Override
  public String toString() {
    return name;
  }

  static final class NotStored extends MemoizedEntityType implements EntityType.NotStored {
    NotStored(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
      super(name, id, transitions);
    }
  }

}
