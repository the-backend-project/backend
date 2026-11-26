package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;

import com.github.thxmasj.backend.core.internal.EntityModels;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/// The checks of the entity models a state machine runs, made when it is created. They fail with the refusals and
/// failures of every model at once, each named, so that the models are fixed in one go rather than one refusal at a
/// time.
final class EntityModelChecks {

  private final EntityModel model;
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  /// Why the transitions are refused, all of them. Each is told once, by the transition it is declared on, also if the
  /// transition is reached by more than one path.
  private final Set<String> refusals = new LinkedHashSet<>();

  private EntityModelChecks(EntityModel model) {
    this.model = model;
    this.transitions = model.transitions();
  }

  /// The entity models given, and those their transitions trigger events on, and so on (see
  /// [EntityModels#resolve(List)]), checked
  ///
  /// @throws IllegalArgumentException with the refusals of the transitions of every model, and the failures of the
  ///                                  models whose transitions can't be created or checked, e.g. as their supplier
  ///                                  fails, each named. The failures are suppressed exceptions of it, with their
  ///                                  stack traces. Also if one of the models given is an [EntityModelGroup], or if
  ///                                  two of the models have the same id.
  /// @throws NullPointerException if one of the entity models given is null, e.g. a constant not created yet
  static List<EntityModel> check(List<? extends EntityModel> entityModels) {
    var failures = new LinkedHashMap<EntityModel, RuntimeException>();
    List<EntityModel> models = EntityModels.resolve(entityModels, failures);
    var refusals = new ArrayList<String>();
    var causes = new ArrayList<RuntimeException>();
    failures.forEach((model, e) -> {
      refusals.add("The transitions of entity model " + model.name() + " can't be created: " + e);
      causes.add(e);
    });
    for (var model : models) {
      try {
        check(model);
      } catch (IllegalArgumentException e) {
        refusals.add("The transitions of entity model " + model.name() + " are refused: " + e.getMessage());
      } catch (RuntimeException e) {
        refusals.add("The transitions of entity model " + model.name() + " can't be checked: " + e);
        causes.add(e);
      }
    }
    if (!refusals.isEmpty()) {
      var e = new IllegalArgumentException(String.join("\n", refusals));
      causes.forEach(e::addSuppressed);
      throw e;
    }
    return models;
  }

  /// Checks the transitions of the entity model
  ///
  /// @param model the entity model whose transitions are checked. Whether its entities are stored (see
  ///              [EntityModel.NotStored]) tells whether an event which is not stored may change their state, and its
  ///              id tells a trigger on the model from one on another.
  /// @throws IllegalArgumentException with the refusals of the transitions, all of them
  static void check(EntityModel model) {
    new EntityModelChecks(model).checkTransitions();
  }

  private void checkTransitions() {
    boolean stored = !(model instanceof EntityModel.NotStored);
    transitions.forEach(this::requireUniquePaths);
    requireReachableFromBegin();
    requireLeavingBegin();
    var reachedBySessionOnly = stored ? statesOfEntitiesOfTheSessionOnly() : transitions.keySet();
    var reached = stored ? statesOfStoredEntities() : Set.<State>of();
    var delayingNotStored = notStoredEventTypesWithADelayedTrigger();
    transitions.forEach((from, fromState) -> {
      if (reached.contains(from))
        requireNotStoredToSelf(from, fromState);
      for (var transition : fromState) {
        requireStoredEventOfConditionalTrigger(transition);
        requireDataOfEventTypesWithData(transition);
        requireAtMostOneDelayedTrigger(transition, 0);
        requireNoDelayedTriggerNumberedAlike(transition, 0, List.of(), delayingNotStored);
        if (mayBeOfAnEntityOfTheSessionOnly(reachedBySessionOnly, from, transition)) {
          requireNoLaterTrigger(transition);
          requireNoIdOrIndex(transition);
        }
      }
    });
    if (refusals.size() == 1)
      throw new IllegalArgumentException(refusals.iterator().next());
    if (!refusals.isEmpty())
      throw new IllegalArgumentException(refusals.size() + " refusals of the transitions:\n- " + String.join("\n- ", refusals));
  }

  private void refuse(String refusal) {
    refusals.add(refusal);
  }

  /// Whether the transition may be taken by an entity of the session only: one from a state such an entity reaches, or
  /// one from `Begin` which may choose an event not stored, which creates one
  private boolean mayBeOfAnEntityOfTheSessionOnly(Set<State> reachedBySessionOnly, State from, TransitionModel<?, ?> transition) {
    if (reachedBySessionOnly.contains(from))
      return true;
    return from == Begin && transition.leaves().stream().anyMatch(leaf -> leaf.eventType() instanceof EventType.NotStored);
  }

  /// A conditional trigger refers to the event of its transition, so the transition, and every transition it may
  /// choose, must be of an event which is stored
  private void requireStoredEventOfConditionalTrigger(TransitionModel<?, ?> transition) {
    if (transition.triggers().stream().anyMatch(trigger -> trigger instanceof EventTrigger.Conditional<?, ?, ?>)) {
      for (var leaf : transition.leaves()) {
        if (leaf.eventType() instanceof EventType.NotStored)
          refuse(String.format(
              "The transition on %s has a conditional trigger, which refers to its event, but the transition on %s, which " +
                  "it may choose, is not stored",
              transition.eventType().name(),
              leaf.eventType().name()
          ));
      }
    }
    for (var filter : transition.filters())
      requireStoredEventOfConditionalTrigger(filter.alternative().model());
  }

  /// An event of a type with data has data: `output()` without a data adapter, which gives an event without data, is
  /// only for an event type without data, of [DataType#none()]. Otherwise the event is stored without data, which its
  /// readers would have to expect.
  private void requireDataOfEventTypesWithData(TransitionModel<?, ?> transition) {
    for (var leaf : transition.leaves())
      if (leaf.withoutData() && !(leaf.eventType().outputDataType() instanceof DataType.NoneDataType))
        refuse(String.format(
            "The transition on %s has no output, but its event type has data, of %s. Give its output with " +
                "output(dataAdapter), or use an event type without data",
            leaf.eventType().name(),
            leaf.eventType().outputDataType().name()
        ));
  }

  /// An entity has at most one pending delayed trigger, which the next event stored on it replaces, so a transition,
  /// with the one it chooses, can't trigger two delayed events, as the second would replace the first. The refusal is
  /// told by the transition on which the second is declared, and not again by the transitions it may choose.
  private void requireAtMostOneDelayedTrigger(TransitionModel<?, ?> transition, int inherited) {
    int delayed = inherited + transition.delayedEventTypes().size();
    if (delayed > 1) {
      refuse(String.format(
          "The transition on %s triggers %d delayed events%s, but an entity has at most one pending delayed trigger, so " +
              "the second would replace the first",
          transition.eventType().name(),
          delayed,
          inherited > 0 ? ", with the transitions choosing it" : ""
      ));
      return;
    }
    for (var filter : transition.filters())
      requireAtMostOneDelayedTrigger(filter.alternative().model(), delayed);
  }

  /// The event types of transitions of the model which may end in an event not stored with a delayed trigger on the
  /// way to it. The event stored, and so the number of the delayed trigger, is that of the transition chosen, not of
  /// the one choosing, so each path from the transition to one it may choose is judged by the one it ends in.
  private Set<EventType<?, ?>> notStoredEventTypesWithADelayedTrigger() {
    var types = new HashSet<EventType<?, ?>>();
    for (var fromState : transitions.values())
      for (var transition : fromState)
        if (endsNotStoredWithADelayedTrigger(transition, false))
          types.add(transition.eventType());
    return types;
  }

  private static boolean endsNotStoredWithADelayedTrigger(TransitionModel<?, ?> transition, boolean delayedBefore) {
    boolean delayed = delayedBefore || !transition.delayedEventTypes().isEmpty();
    if (transition.filters().isEmpty())
      return delayed && transition.eventType() instanceof EventType.NotStored;
    return transition.filters().stream().anyMatch(filter -> endsNotStoredWithADelayedTrigger(filter.alternative().model(), delayed));
  }

  /// A delayed trigger is numbered after the event of its transition if that is stored, and with its number if it
  /// isn't. So a transition with a delayed trigger which triggers, on its own entity, an event not stored whose
  /// transition has a delayed trigger too, would have two delayed triggers on the entity with the same number, of which
  /// only one can be pending. The entity a trigger is on is only known when it is taken, so a trigger on this model of
  /// an event not stored with a delayed trigger is taken to be on the same entity, which is refused, as the transaction
  /// would fail when stored. A trigger on another model is not. The refusal is told by the transition the trigger is
  /// declared on, once, however many of the paths through it have a delayed trigger.
  private void requireNoDelayedTriggerNumberedAlike(
      TransitionModel<?, ?> transition,
      int inheritedDelayed,
      List<DeclaredTrigger> inheritedTriggers,
      Set<EventType<?, ?>> delayingNotStored
  ) {
    int delayed = inheritedDelayed + transition.delayedEventTypes().size();
    var triggers = new ArrayList<>(inheritedTriggers);
    for (var trigger : transition.triggers())
      triggers.add(new DeclaredTrigger(transition, trigger));
    if (transition.filters().isEmpty()) {
      if (delayed == 0)
        return;
      for (var declared : triggers) {
        var trigger = declared.trigger();
        if (!(trigger instanceof EventTrigger.Conditional<?, ?, ?>) && delayingNotStored.contains(trigger.eventType()) && isOnTheModel(trigger))
          refuse(String.format(
              "The transition on %s triggers %s, which isn't stored and has a delayed trigger, and it, or a transition " +
                  "it chooses or is chosen by, has a delayed trigger too. On the same entity, the two would have the " +
                  "same number, but an entity has at most one pending delayed trigger",
              declared.transition().eventType().name(),
              trigger.eventType().name()
          ));
      }
    }
    for (var filter : transition.filters())
      requireNoDelayedTriggerNumberedAlike(filter.alternative().model(), delayed, triggers, delayingNotStored);
  }

  /// A trigger with the transition it is declared on
  private record DeclaredTrigger(TransitionModel<?, ?> transition, EventTrigger<?, ?, ?> trigger) {}

  /// An entity of the session only is never stored, so an id or index event, by which an entity is found later, would
  /// point to an entity which doesn't exist, and an id would be claimed for good. Its transitions, and those they may
  /// choose, therefore can't trigger events on an id or index model.
  private void requireNoIdOrIndex(TransitionModel<?, ?> transition) {
    for (var trigger : transition.triggers())
      if (trigger.entityModel() instanceof IdEntityModel || trigger.entityModel() instanceof IndexEntityModel)
        refuse(String.format(
            "The transition on %s may be of an entity of the session only, which is never stored, so it can't trigger %s " +
                "on the %s model %s, which would point to an entity which doesn't exist",
            transition.eventType().name(),
            trigger.eventType().name(),
            trigger.entityModel() instanceof IdEntityModel ? "id" : "index",
            trigger.entityModel().name()
        ));
    for (var filter : transition.filters())
      requireNoIdOrIndex(filter.alternative().model());
  }

  /// Whether the trigger is on the entity model of the transitions, or may be: on a group which includes it, or without
  /// a model of its own, as a delayed trigger, which is on the entity of its transition
  private boolean isOnTheModel(EventTrigger<?, ?, ?> trigger) {
    if (trigger.entityModel() == null)
      return true;
    return trigger.entityModel().id().equals(model.id())
        || trigger.entityModel() instanceof EntityModelGroup group && group.includes(model);
  }

  /// An entity of the session only is not found after its session, so its transitions, and those they may choose, can't
  /// have delayed or conditional triggers, which fire in a session of their own
  private void requireNoLaterTrigger(TransitionModel<?, ?> transition) {
    boolean conditional = transition.triggers().stream().anyMatch(trigger -> trigger instanceof EventTrigger.Conditional<?, ?, ?>);
    if (conditional || !transition.delayedEventTypes().isEmpty())
      refuse(String.format(
          "The transition on %s may be of an entity of the session only, which is not found after its session, so it " +
              "can't have a %s trigger, which fires in a session of its own",
          transition.eventType().name(),
          conditional ? "conditional" : "delayed"
      ));
    for (var filter : transition.filters())
      requireNoLaterTrigger(filter.alternative().model());
  }

  /// A transition from `Begin` creates the entity, so it must leave `Begin`, as an entity in it couldn't be told from one
  /// which doesn't exist, and its state would be that of an entity without events. An id or index entity has no states,
  /// so all its events are taken from `Begin`.
  private void requireLeavingBegin() {
    if (model instanceof IdEntityModel || model instanceof IndexEntityModel)
      return;
    for (var transition : transitions.getOrDefault(Begin, List.of()))
      for (var leaf : transition.leaves())
        if (leaf.toState() == null || leaf.toState() == Begin)
          refuse(String.format(
              "The transition on %s from Begin stays in Begin, but it creates the entity, so it must leave Begin",
              leaf.eventType().name()
          ));
  }

  /// Every entity is created from `Begin`, as its state is found by replaying its events from there, so a state with
  /// transitions which `Begin` doesn't lead to could never be left, and is refused, as is a model with transitions but
  /// none from `Begin`
  private void requireReachableFromBegin() {
    if (transitions.values().stream().allMatch(List::isEmpty))
      return;
    if (transitions.getOrDefault(Begin, List.of()).isEmpty()) {
      refuse("The model has transitions, but none from Begin, from which every entity is created");
      return;
    }
    var reached = new HashSet<State>();
    var toVisit = new ArrayDeque<State>(List.of(Begin));
    while (!toVisit.isEmpty())
      for (var transition : transitions.getOrDefault(toVisit.removeFirst(), List.of()))
        for (State target : targets(transition))
          if (reached.add(target))
            toVisit.add(target);
    for (var entry : transitions.entrySet())
      if (entry.getKey() != Begin && !entry.getValue().isEmpty() && !reached.contains(entry.getKey()))
        refuse(String.format(
            "State %s is not reached from Begin, from which every entity is created, so its transitions are never taken",
            entry.getKey().name()
        ));
  }

  /// The states the transition may lead to: those of the transitions it may choose, as a transition with choices
  /// always ends in one of them, or its own if it has none
  private List<State> targets(TransitionModel<?, ?> transition) {
    return transition.leaves().stream().map(TransitionModel::toState).filter(Objects::nonNull).toList();
  }

  /// The states an entity which is stored reaches: from `Begin` by the events stored, as one created by an event not
  /// stored is an entity of the session only, and from there by any event. The states only entities of the session
  /// reach may be left by events not stored.
  private Set<State> statesOfStoredEntities() {
    return statesReachedFromBegin(eventType -> !(eventType instanceof EventType.NotStored));
  }

  /// The states an entity of the session only of a stored model reaches: from `Begin` by the events not stored, which
  /// create one, and from there by any event
  private Set<State> statesOfEntitiesOfTheSessionOnly() {
    return statesReachedFromBegin(eventType -> eventType instanceof EventType.NotStored);
  }

  /// The states reached from the leaves from `Begin` whose event types are of the kind, and from there by any event
  private Set<State> statesReachedFromBegin(Predicate<EventType<?, ?>> kind) {
    var reached = new HashSet<State>();
    var toVisit = new ArrayDeque<State>();
    for (var transition : transitions.getOrDefault(Begin, List.of()))
      for (var leaf : transition.leaves())
        if (kind.test(leaf.eventType()) && leaf.toState() != null && reached.add(leaf.toState()))
          toVisit.add(leaf.toState());
    while (!toVisit.isEmpty()) {
      State state = toVisit.removeFirst();
      for (var transition : transitions.getOrDefault(state, List.of()))
        for (var leaf : transition.leaves())
          if (leaf.toState() != null && reached.add(leaf.toState()))
            toVisit.add(leaf.toState());
    }
    return reached;
  }

  /// An event which is not stored is not in the log, so it must not change the state of an entity which is stored. One
  /// creating an entity, from [EntityModel#Begin], makes the entity one of the session only, whose state is not found
  /// from a log.
  private void requireNotStoredToSelf(State from, List<TransitionModel<?, ?>> transitionsFromState) {
    for (var transition : transitionsFromState) {
      for (var leaf : transition.leaves()) {
        if (leaf.eventType() instanceof EventType.NotStored && leaf.toState() != null && !leaf.toState().equals(from))
          refuse(String.format(
              "Event type %s is not stored, but its transition from %s goes to %s. The state of an entity is found " +
                  "from its stored events, so a transition on an event which is not stored must stay in its state",
              leaf.eventType().name(),
              from.name(),
              leaf.toState().name()
          ));
      }
    }
  }

  /// Only the event of a chosen transition is stored, so a logged event is matched with the transitions from a state
  /// with the choices expanded, taking the first match. Of the transition matched, the log only uses its target state,
  /// to find the current state, and its reverse model, to roll the event back. Transitions on the same event type, from
  /// the state or chosen from it, are rejected if those differ, since the log couldn't tell which one was taken.
  private void requireUniquePaths(State from, List<TransitionModel<?, ?>> transitionsFromState) {
    var byEventType = new HashMap<EventType<?, ?>, TransitionModel<?, ?>>();
    for (var transition : transitionsFromState) {
      for (var leaf : transition.leaves()) {
        var other = byEventType.putIfAbsent(leaf.eventType(), leaf);
        if (other != null && isDistinguishable(other, leaf)) {
          refuse(String.format(
              "Event type %s has transitions from %s with different target states or reverse models: %s and %s. " +
                  "The event of a chosen transition is stored, so the transition of a logged event would be ambiguous",
              leaf.eventType().name(),
              from.name(),
              other,
              leaf
          ));
        }
      }
    }
  }

  /// Whether the log would have to tell the transitions apart: by their target states, or by their reverse models, which
  /// are compared by identity since separately built reverse models can't be compared
  private static boolean isDistinguishable(TransitionModel<?, ?> a, TransitionModel<?, ?> b) {
    return !Objects.equals(a.toState(), b.toState()) || a.reverseModel() != b.reverseModel();
  }
}
