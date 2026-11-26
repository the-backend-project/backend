package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
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

/// The entity models a state machine runs
public final class EntityModels {

  private EntityModels() {}

  /// The entity models given, and those their transitions trigger events on, and so on, each once. The models given
  /// are those nothing triggers events on, e.g. the entity models of an inbox, whose requests trigger the events of
  /// the application, whose transitions in turn trigger events on e.g. outbox requests, rollback models and indexes.
  /// The models found are in the order they are reached, after the ones given.
  ///
  /// @throws IllegalArgumentException if a model given is an [EntityModelGroup], which has no entities of its own, or
  ///                                  if two models have the same id
  /// @throws NullPointerException if a model given is null
  /// @throws RuntimeException what the transitions of a model fail with, e.g. as its supplier does
  public static List<EntityModel> resolve(List<? extends EntityModel> entityModels) {
    var failures = new LinkedHashMap<EntityModel, RuntimeException>();
    var resolved = resolve(entityModels, failures);
    if (!failures.isEmpty())
      throw failures.values().iterator().next();
    return resolved;
  }

  /// The entity models as [#resolve(List)], but a model whose transitions fail, e.g. as its supplier does, is put in
  /// the failures, with what they fail with, and left out, so that the other models are resolved and checked too, and
  /// the failures reported with their refusals. The models the failing one triggers events on are not found through
  /// it, as its transitions aren't known.
  ///
  /// @param failures where the models whose transitions fail are put, in the order they are reached
  /// @throws IllegalArgumentException as [#resolve(List)]
  /// @throws NullPointerException if a model given is null
  public static List<EntityModel> resolve(List<? extends EntityModel> entityModels, Map<EntityModel, RuntimeException> failures) {
    for (EntityModel model : entityModels)
      if (model == null)
        throw new NullPointerException(
            "One of the entity models given is null. If it is a constant, it is read before it is created, e.g. by " +
                "another constant declared before it"
        );
      else if (model instanceof EntityModelGroup)
        throw new IllegalArgumentException("Entity model group " + model.name() + " has no entities of its own");
    Set<EntityModel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<UUID, EntityModel> byId = new HashMap<>();
    List<EntityModel> resolved = new ArrayList<>();
    Deque<EntityModel> toVisit = new ArrayDeque<>(entityModels);
    while (!toVisit.isEmpty()) {
      EntityModel model = toVisit.removeFirst();
      if (!seen.add(model))
        continue;
      EntityModel other = byId.putIfAbsent(model.id(), model);
      if (other != null)
        throw new IllegalArgumentException(
            "Entity models " + other.name() + " and " + model.name() + " have the same id " + model.id()
        );
      Map<State, List<TransitionModel<?, ?>>> byState;
      try {
        byState = model.transitions();
        if (byState == null)
          throw new NullPointerException("Entity model " + model.name() + " has no transitions");
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

  /// Adds the entity models the transition triggers events on, in its reversal and in the transitions it may choose,
  /// and so on. A group is left out, as its models are those of the group which are run.
  private static void addTargets(TransitionModel<?, ?> transition, Deque<EntityModel> toVisit) {
    addTargets(transition.triggers(), toVisit);
    if (transition.reverseModel() != null)
      addTargets(transition.reverseModel(), toVisit);
    for (var filter : transition.filters())
      addTargets(filter.alternative().model(), toVisit);
  }

  private static void addTargets(List<EventTrigger<?, ?, ?>> triggers, Deque<EntityModel> toVisit) {
    for (var trigger : triggers) {
      EntityModel target = trigger.entityModel();
      if (target != null && !(target instanceof EntityModelGroup))
        toVisit.addLast(target);
    }
  }
}
