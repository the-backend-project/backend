package com.github.thxmasj.backend.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/// Stands for any entity type of a group, as the target of a trigger, e.g. any HTTP inbox request. An event is
/// triggered on a group with the entity identified by the session ([EntitySelector#entityIdFromSession()]): it is
/// triggered on the entity of the session whose model is in the group, such as the inbox request an application
/// responds to, without the triggering transition knowing which model that is. A group has no entities or transitions
/// of its own, so it is not given to the state machine, and its entities can't be identified in any other way.
/// See [sessions](https://the-backend-project.github.io/backend/sessions/).
public interface EntityTypeGroup extends EntityType {

  /// None, as the transitions are those of the entity types in the group
  @Override
  default Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of();
  }

  /// Whether the entity type is in the group
  boolean includes(EntityType entityType);

  static EntityTypeGroup of(String name, UUID id, Predicate<EntityType> includes) {
    return new EntityTypeGroup() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public boolean includes(EntityType entityType) {return includes.test(entityType);}
      @Override public String toString() {return name;}
    };
  }

  /// Whether a trigger on the target, an entity type or a group, can be taken by an entity of the model
  static boolean targets(EntityType target, EntityType entityType) {
    return target.equals(entityType) || target instanceof EntityTypeGroup group && group.includes(entityType);
  }

}
