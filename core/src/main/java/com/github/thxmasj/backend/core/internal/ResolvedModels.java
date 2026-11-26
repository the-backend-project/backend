package com.github.thxmasj.backend.core.internal;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EntityTypeGroup;
import com.github.thxmasj.backend.core.EventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// The entity types a state machine runs, with their event types, worked out once from the entity types given, for
/// the state machine, its storage, the test API and the backoffice
///
/// @param entityTypes            the models given, and those their transitions trigger events on (see
///                                [EntityType#reachable(List)])
/// @param entityTypesById        the models by their ids
/// @param eventTypesByEntityType the event types of each model, including the builtin event types
/// @param eventTypes              the event types of all the models, including the builtin event types
public record ResolvedModels(
    List<EntityType> entityTypes,
    Map<UUID, EntityType> entityTypesById,
    Map<EntityType, List<EventType<?, ?>>> eventTypesByEntityType,
    List<EventType<?, ?>> eventTypes
) {

  public static ResolvedModels of(List<? extends EntityType> entityTypes) {
    return ofResolved(EntityTypes.resolve(entityTypes));
  }

  /// The models already resolved, e.g. by `EntityTypeChecks`, which checks them too
  public static ResolvedModels ofResolved(List<EntityType> resolved) {
    return new ResolvedModels(
        resolved,
        Map.copyOf(resolved.stream().collect(toMap(EntityType::id, identity()))),
        Map.copyOf(EventTypes.eventTypesByEntityType(resolved)),
        EventTypes.eventTypes(resolved)
    );
  }

  /// Whether the entity type is one of those run, the same instance. Another instance with its id isn't, as it may
  /// differ, e.g. be created from other configuration.
  public boolean runs(EntityType entityType) {
    return !(entityType instanceof EntityTypeGroup) && entityTypesById.get(entityType.id()) == entityType;
  }

  /// The failure of using an entity type which isn't run, see [#runs(EntityType)]
  ///
  /// @param runner what runs the models, e.g. "the state machine", for the message
  public IllegalArgumentException notRun(EntityType entityType, String runner) {
    EntityType sameId = entityTypesById.get(entityType.id());
    return new IllegalArgumentException(sameId == null
        ? "Entity type " + entityType.name() + " with id " + entityType.id() + " is not run by " + runner
        : "Entity type " + entityType.name() + " is another instance than the one with its id run by " + runner +
            ", e.g. as it is created again from configuration. Use the one run, e.g. from the entityTypes() of the " +
            "state machine");
  }
}
