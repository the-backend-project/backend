package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EntityTypeGroup;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// The entity types a state machine runs
public final class EntityTypes {

  private EntityTypes() {}

  /// The entity types given, and those their transitions trigger events on, and so on, each once. The models given
  /// are those nothing triggers events on, e.g. the entity types of an inbox, whose requests trigger the events of
  /// the application, whose transitions in turn trigger events on e.g. outbox requests, rollback models and indexes.
  /// The models found are in the order they are reached, after the ones given.
  ///
  /// @throws IllegalArgumentException if a model given is an [EntityTypeGroup], which has no entities of its own, or
  ///                                  if two models have the same id
  /// @throws NullPointerException if a model given is null
  /// @throws RuntimeException what the transitions of a model fail with, e.g. as its supplier does
  public static List<EntityType> resolve(List<? extends EntityType> entityTypes) {
    var failures = new LinkedHashMap<EntityType, RuntimeException>();
    var resolved = resolve(entityTypes, failures);
    if (!failures.isEmpty())
      throw failures.values().iterator().next();
    return resolved;
  }

  /// The entity types as [#resolve(List)], but a model whose transitions fail, e.g. as its supplier does, is put in
  /// the failures, with what they fail with, and left out, so that the other models are resolved and checked too, and
  /// the failures reported with their refusals. The models the failing one triggers events on are not found through
  /// it, as its transitions aren't known.
  ///
  /// @param failures where the models whose transitions fail are put, in the order they are reached
  /// @throws IllegalArgumentException as [#resolve(List)]
  /// @throws NullPointerException if a model given is null
  public static List<EntityType> resolve(List<? extends EntityType> entityTypes, Map<EntityType, RuntimeException> failures) {
    for (EntityType model : entityTypes)
      if (model == null)
        throw new NullPointerException(
            "One of the entity types given is null. If it is a constant, it is read before it is created, e.g. by " +
                "another constant declared before it"
        );
      else if (model instanceof EntityTypeGroup)
        throw new IllegalArgumentException("Entity type group " + model.name() + " has no entities of its own");
    Set<EntityType> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<UUID, EntityType> byId = new HashMap<>();
    List<EntityType> resolved = new ArrayList<>();
    Deque<EntityType> toVisit = new ArrayDeque<>(entityTypes);
    while (!toVisit.isEmpty()) {
      EntityType model = toVisit.removeFirst();
      if (!seen.add(model))
        continue;
      EntityType other = byId.putIfAbsent(model.id(), model);
      if (other != null)
        throw new IllegalArgumentException(
            "Entity types " + other.name() + " and " + model.name() + " have the same id " + model.id()
        );
      Map<State, List<TransitionModel<?, ?>>> byState;
      try {
        byState = model.transitions();
        if (byState == null)
          throw new NullPointerException("Entity type " + model.name() + " has no transitions");
      } catch (RuntimeException e) {
        failures.put(model, e);
        continue;
      }
      resolved.add(model);
      for (List<TransitionModel<?, ?>> transitions : byState.values())
        for (TransitionModel<?, ?> transition : transitions)
          addTargets(transition, toVisit);
    }
    return List.copyOf(resolved);
  }

  /// Adds the entity types the transition triggers events on, in its reversal and in the transitions it may choose,
  /// and so on. A group is left out, as its models are those of the group which are run.
  private static void addTargets(TransitionModel<?, ?> transition, Deque<EntityType> toVisit) {
    addTargets(transition.triggers(), toVisit);
    if (transition.reverseModel() != null)
      addTargets(transition.reverseModel(), toVisit);
    for (var filter : transition.filters())
      addTargets(filter.alternative().model(), toVisit);
  }

  private static void addTargets(List<EventTrigger<?, ?, ?>> triggers, Deque<EntityType> toVisit) {
    for (var trigger : triggers) {
      EntityType target = trigger.entityType();
      if (target != null && !(target instanceof EntityTypeGroup))
        toVisit.addLast(target);
    }
  }
}
