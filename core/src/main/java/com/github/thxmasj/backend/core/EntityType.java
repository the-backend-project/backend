package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.internal.EntityTypes;
import com.github.thxmasj.backend.core.internal.Memoized;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/// A type of entities: the transitions an entity takes, by the state it is in. The state machine is given the entity
/// types it runs, and takes the transitions of each from the entity type itself.
public interface EntityType extends Identifiable {

  State Begin = () -> "Begin";

  /// The transitions of the entities of the type, by the state they are taken from
  Map<State, List<TransitionModel<?, ?>>> transitions();

  /// An entity type whose entities are not stored. The entities of a type which is not stored are entities of the
  /// session only: their transitions are taken, and the events they trigger on other entities are stored if those
  /// are, but none of their own events are stored. They are found by the transactions of the session, the later ones
  /// among the events of the earlier ones, but not by another session. Their transitions may therefore go to any
  /// state, but they can't have delayed or conditional triggers, which fire in a session of their own.
  interface NotStored extends EntityType {}

  /// An entity type whose transitions are created when they are first asked for, so that they can refer to the type
  /// itself and to other types created the same way, e.g. as constants which refer to each other.
  static EntityType of(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    var created = new Memoized<>(transitions, "transitions of entity type " + name);
    return new EntityType() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return created.get();}
      @Override public String toString() {return name;}
    };
  }

  /// An entity type whose entities are not stored, but of the session only (see [NotStored]), with its transitions
  /// created when they are first asked for, as [#of(String, UUID, Supplier)]
  static NotStored notStored(String name, UUID id, Supplier<Map<State, List<TransitionModel<?, ?>>>> transitions) {
    var created = new Memoized<>(transitions, "transitions of entity type " + name);
    return new NotStored() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return created.get();}
      @Override public String toString() {return name;}
    };
  }

  /// The entity types, and those their transitions trigger events on, directly, in reversals and in the transitions they
  /// may choose, and so on, each once: the types a state machine given the entity types runs. A group triggered on
  /// adds no types, as its types are those of the group which are run. The types found follow the ones given.
  ///
  /// @throws IllegalArgumentException if one of the entity types given is an [EntityTypeGroup], or if two of the
  ///                                  types have the same id
  /// @throws NullPointerException if one of the entity types given is null, e.g. a constant not created yet
  static List<EntityType> reachable(List<? extends EntityType> entityTypes) {
    return EntityTypes.resolve(entityTypes);
  }
}
