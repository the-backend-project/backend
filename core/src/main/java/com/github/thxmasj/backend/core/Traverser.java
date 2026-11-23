package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.statusOn;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.internal.Debug;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

class Traverser {

  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  /// Whether entities of the session only are of the model: all of them if it is not stored, and those an event not
  /// stored creates otherwise
  private final boolean hasEntitiesOfTheSessionOnly;

  public Traverser(Map<State, List<TransitionModel<?, ?>>> transitions) {
    this(transitions, true);
  }

  /// @param stored whether the entities are stored (see [EntityModel#stored()]), so that an event which is not
  ///               stored must not change their state
  public Traverser(Map<State, List<TransitionModel<?, ?>>> transitions, boolean stored) {
    this.transitions = transitions;
    this.hasEntitiesOfTheSessionOnly = !stored || transitions.getOrDefault(Begin, List.of()).stream()
        .flatMap(transition -> leaves(transition).stream())
        .anyMatch(leaf -> !leaf.eventType().stored());
    transitions.forEach(this::requireUniquePaths);
    requireReachableFromBegin();
    var reachedBySessionOnly = stored ? statesOfEntitiesOfTheSessionOnly() : transitions.keySet();
    var reached = stored ? statesOfStoredEntities() : Set.<State>of();
    transitions.forEach((from, fromState) -> {
      if (reached.contains(from))
        requireNotStoredToSelf(from, fromState);
      for (var transition : fromState) {
        requireStoredEventOfConditionalTrigger(transition);
        requireAtMostOneDelayedTrigger(transition, 0);
        if (mayBeOfAnEntityOfTheSessionOnly(reachedBySessionOnly, from, transition)) {
          requireNoLaterTrigger(transition);
          requireNoIdOrIndex(transition);
        }
      }
    });
  }

  /// Whether the transition may be taken by an entity of the session only: one from a state such an entity reaches, or
  /// one from `Begin` which may choose an event not stored, which creates one
  private boolean mayBeOfAnEntityOfTheSessionOnly(Set<State> reachedBySessionOnly, State from, TransitionModel<?, ?> transition) {
    if (reachedBySessionOnly.contains(from))
      return true;
    return from == Begin && leaves(transition).stream().anyMatch(leaf -> !leaf.eventType().stored());
  }

  /// A conditional trigger refers to the event of its transition, so the transition, and every transition it may
  /// choose, must be of an event which is stored
  private void requireStoredEventOfConditionalTrigger(TransitionModel<?, ?> transition) {
    if (transition.triggers().stream().anyMatch(trigger -> trigger.condition() != null)) {
      for (var leaf : leaves(transition)) {
        if (!leaf.eventType().stored())
          throw new IllegalArgumentException(String.format(
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

  /// An entity has at most one pending delayed trigger, which the next event stored on it replaces, so a transition,
  /// with the one it chooses, can't trigger two delayed events, as the second would replace the first
  private void requireAtMostOneDelayedTrigger(TransitionModel<?, ?> transition, int inherited) {
    int delayed = inherited + transition.delayedEventTypes().size();
    if (delayed > 1)
      throw new IllegalArgumentException(String.format(
          "The transition on %s triggers %d delayed events, but an entity has at most one pending delayed trigger, so " +
              "the second would replace the first",
          transition.eventType().name(),
          delayed
      ));
    for (var filter : transition.filters())
      requireAtMostOneDelayedTrigger(filter.alternative().model(), delayed);
  }

  /// An entity of the session only is not found after its session, so its transitions, and those they may choose, can't
  /// have delayed or conditional triggers, which fire in a session of their own
  /// An entity of the session only is never stored, so an id or index event, by which an entity is found later, would
  /// point to an entity which doesn't exist, and an id would be claimed for good. Its transitions, and those they may
  /// choose, therefore can't trigger events on an id or index model.
  private void requireNoIdOrIndex(TransitionModel<?, ?> transition) {
    for (var trigger : transition.triggers())
      if (trigger.entityModel() instanceof IdEntityModel || trigger.entityModel() instanceof IndexEntityModel)
        throw new IllegalArgumentException(String.format(
            "The transition on %s may be of an entity of the session only, which is never stored, so it can't trigger %s " +
                "on the %s model %s, which would point to an entity which doesn't exist",
            transition.eventType().name(),
            trigger.eventSpec().eventType().name(),
            trigger.entityModel() instanceof IdEntityModel ? "id" : "index",
            trigger.entityModel().name()
        ));
    for (var filter : transition.filters())
      requireNoIdOrIndex(filter.alternative().model());
  }

  private void requireNoLaterTrigger(TransitionModel<?, ?> transition) {
    boolean conditional = transition.triggers().stream().anyMatch(trigger -> trigger.condition() != null);
    if (conditional || !transition.delayedEventTypes().isEmpty())
      throw new IllegalArgumentException(String.format(
          "The transition on %s may be of an entity of the session only, which is not found after its session, so it " +
              "can't have a %s trigger, which fires in a session of its own",
          transition.eventType().name(),
          conditional ? "conditional" : "delayed"
      ));
    for (var filter : transition.filters())
      requireNoLaterTrigger(filter.alternative().model());
  }

  /// Every entity is created from `Begin`, as its state is found by replaying its events from there, so a state with
  /// transitions which `Begin` doesn't lead to could never be left, and is refused, as is a model with transitions but
  /// none from `Begin`
  private void requireReachableFromBegin() {
    if (transitions.values().stream().allMatch(List::isEmpty))
      return;
    if (transitions.getOrDefault(Begin, List.of()).isEmpty())
      throw new IllegalArgumentException("The model has transitions, but none from Begin, from which every entity is created");
    var reached = new HashSet<State>();
    var toVisit = new ArrayDeque<State>(List.of(Begin));
    while (!toVisit.isEmpty())
      for (var transition : transitions.getOrDefault(toVisit.removeFirst(), List.of()))
        for (State target : targets(transition))
          if (reached.add(target))
            toVisit.add(target);
    for (var entry : transitions.entrySet())
      if (entry.getKey() != Begin && !entry.getValue().isEmpty() && !reached.contains(entry.getKey()))
        throw new IllegalArgumentException(String.format(
            "State %s is not reached from Begin, from which every entity is created, so its transitions are never taken",
            entry.getKey().name()
        ));
  }

  /// The states the transition may lead to: those of the transitions it may choose, and its own, as a choice without
  /// `otherwise` falls through to it (see #315)
  private static List<State> targets(TransitionModel<?, ?> transition) {
    var targets = new ArrayList<State>();
    if (transition.toState() != null)
      targets.add(transition.toState());
    for (var filter : transition.filters())
      targets.addAll(targets(filter.alternative().model()));
    return targets;
  }

  /// The states an entity which is stored reaches: from `Begin` by the events stored, as one created by an event not
  /// stored is an entity of the session only, and from there by any event. The states only entities of the session
  /// reach may be left by events not stored.
  private Set<State> statesOfStoredEntities() {
    return statesReachedFromBegin(EventType::stored);
  }

  /// The states an entity of the session only of a stored model reaches: from `Begin` by the events not stored, which
  /// create one, and from there by any event
  private Set<State> statesOfEntitiesOfTheSessionOnly() {
    return statesReachedFromBegin(eventType -> !eventType.stored());
  }

  /// The states reached from the leaves from `Begin` whose event types are of the kind, and from there by any event
  private Set<State> statesReachedFromBegin(Predicate<EventType<?, ?>> kind) {
    var reached = new HashSet<State>();
    var toVisit = new ArrayDeque<State>();
    for (var transition : transitions.getOrDefault(Begin, List.of()))
      for (var leaf : leaves(transition))
        if (kind.test(leaf.eventType()) && leaf.toState() != null && reached.add(leaf.toState()))
          toVisit.add(leaf.toState());
    while (!toVisit.isEmpty()) {
      State state = toVisit.removeFirst();
      for (var transition : transitions.getOrDefault(state, List.of()))
        for (var leaf : leaves(transition))
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
      for (var leaf : leaves(transition)) {
        if (!leaf.eventType().stored() && leaf.toState() != null && !leaf.toState().equals(from))
          throw new IllegalArgumentException(String.format(
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
      for (var leaf : leaves(transition)) {
        var other = byEventType.putIfAbsent(leaf.eventType(), leaf);
        if (other != null && isDistinguishable(other, leaf)) {
          throw new IllegalArgumentException(String.format(
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

  /// Whether entities of the session only are of the model, which the later transactions of a session find among the
  /// events of the earlier ones
  boolean hasEntitiesOfTheSessionOnly() {
    return hasEntitiesOfTheSessionOnly;
  }

  public State currentState(EventLog eventLog) {
    var effectiveEvents = eventLog.effectiveEvents();
    State currentState = Begin;
    if (effectiveEvents.isEmpty()) {
      return currentState;
    }
    TransitionModel<?, ?> lastTransition;
    for (var loggedEvent : effectiveEvents.stream().map(Event::type).toList()) {
      lastTransition = findTransitionForLoggedEvent(loggedEvent, currentState);
      currentState = targetState(currentState, lastTransition);
    }
//    System.out.printf(
//        """
//        Traverser.currentState for %s: %s
//        Effective events: %s
//        Actual events:    %s
//        """,
//        eventLog.entityModel().name(),
//        currentState.name(),
//        effectiveEvents.stream().map(e -> e.type().name() + "/" + e.type().id()).collect(joining(", ")),
//        eventLog.events().stream().map(Event::typeName).collect(joining(", "))
//    );
    return currentState;
  }

  private State targetState(State currentState, TransitionModel<?, ?> transition) {
    return transition.toState() != null ? transition.toState() : currentState; // toSelf
  }

//  public State targetState(State currentState, EventType<?, ?> eventType) {
//    var model = accept(currentState, eventType);
//    if (model == null) throw new IllegalStateException("No transition found for event " + eventType);
//    return targetState(currentState, model);
//  }

  public Tuple2<State, TransitionModel<?, ?>> accept(EventLog eventLog, EventType<?, ?> eventType) {
    var currentState = currentState(eventLog);
    var availableTransitions = transitions.get(currentState);
    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState.name() + " on " + eventLog.entityModel().name());
    var t = findTransitionForTriggeredEvent(eventType, availableTransitions);
    if (t != null && eventType == BuiltinEventTypes.EventsRolledBack)
      Debug.log("Using custom rollback transition for entity " + eventLog.entityModel().name() + ": " + t + "\nAll transitions:\n" + transitions.values().stream().flatMap(List::stream).map(Object::toString).collect(joining("\n")));
    if (t == null && eventType == BuiltinEventTypes.EventsRolledBack && eventType instanceof BasicEventType.Rollback rollback)
      t = rollbackOn(rollback);
    else if (t == null && eventType == BuiltinEventTypes.StatusLookedUp)
      t = statusOn();
    return tuple(currentState, t);
  }

  public State targetState(EventType<?, ?>... eventTypes) {
    var currentState = Begin;
    for (var eventType : eventTypes) {
      var t = findTransitionForLoggedEvent(eventType, currentState);
      currentState = t.toState();
    }
    return currentState;
  }

//  public TransitionModel<?, ?> accept(State currentState, EventType<?, ?> eventType) {
//    var availableTransitions = transitions.get(currentState);
//    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState);
//    var t = findTransition(eventType, availableTransitions);
//    if (t == null && eventType == BuiltinEventTypes.Rollback && eventType instanceof BasicEventType.Rollback rollback)
//      t = onEvent(rollback).toSelf().assembleInput().output(d -> d);
//    return t;
//  }

  public TransitionModel<?, ?> transitionForEventNumber(EventLog eventLog, int eventNumber) {
    var events = eventLog.events();
    if (events.isEmpty()) {
      throw new IllegalStateException("Event log is empty");
    }
    State state = Begin;
    for (var event : events) {
      if (event.eventNumber() == eventNumber) {
        return findTransitionForLoggedEvent(event.type(), state);
      }
      state = targetStateForLoggedEvent(state, event.type());
    }
    throw new IllegalStateException("Event number " + eventNumber + " not found in event log");
  }

  private TransitionModel<?, ?> modelForLoggedEvent(State currentState, EventType<?, ?> eventType) {
    return findTransitionForLoggedEvent(eventType, currentState);
  }

  private State targetStateForLoggedEvent(State currentState, EventType<?, ?> eventType) {
    return targetState(currentState, modelForLoggedEvent(currentState, eventType));
  }


  private TransitionModel<?, ?> findTransitionForTriggeredEvent(EventType<?, ?> eventType, List<TransitionModel<?, ?>> transitions) {
    for (var transition : transitions) {
      if (transition.eventType().equals(eventType)) {
        return transition;
      }
    }
    return null;
  }

  private TransitionModel<?, ?> findTransitionForLoggedEvent(EventType<?, ?> eventType, State fromState) {
    for (var transition : transitions.get(fromState)) {
      for (var leafTransition : leaves(transition)) {
        if (leafTransition.eventType().equals(eventType)) {
          return leafTransition;
        }
      }
    }
    // TODO: Avoid dynamic creation
    if (eventType == BuiltinEventTypes.EventsRolledBack && eventType instanceof BasicEventType.Rollback rollback)
      return rollbackOn(rollback);
    throw new IllegalStateException(
        "No transition found for logged event " + eventType.name() + " from " + fromState.name() + " (available: " + transitions.get(fromState).stream()
            .flatMap(t -> leaves(t).stream())
            .map(t -> t.eventType().name())
            .collect(joining(", ")) + ")");
  }

  private List<TransitionModel<?, ?>> leaves(TransitionModel<?, ?> transition) {
    ArrayList<TransitionModel<?, ?>> leaves = new ArrayList<>();
    if (transition.filters().isEmpty()) {
      // transition is a leaf
      leaves.add(transition);
    } else {
      transition.filters().stream().map(f -> f.alternative().model()).forEach(alternativeModel -> leaves.addAll(leaves(alternativeModel)));
    }
    return unmodifiableList(leaves);
  }



}
