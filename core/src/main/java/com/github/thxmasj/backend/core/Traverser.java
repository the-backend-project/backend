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
    var reachedBySessionOnly = stored ? statesOfEntitiesOfTheSessionOnly() : transitions.keySet();
    var reached = stored ? statesOfStoredEntities(reachedBySessionOnly) : Set.<State>of();
    transitions.forEach((from, fromState) -> {
      if (reached.contains(from))
        requireNotStoredToSelf(from, fromState);
      for (var transition : fromState) {
        requireStoredEventOfConditionalTrigger(transition);
        if (mayBeOfAnEntityOfTheSessionOnly(reachedBySessionOnly, from, transition))
          requireNoLaterTrigger(transition);
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

  /// An entity of the session only is not found after its session, so its transitions, and those they may choose, can't
  /// have delayed or conditional triggers, which fire in a session of their own
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

  /// The states an entity which is stored may be in: every state but `Begin`, except those which only entities of the
  /// session only reach, which may be left by events not stored. An entity may be created otherwise than from `Begin`,
  /// so a state isn't left out for not being reached from it.
  private Set<State> statesOfStoredEntities(Set<State> reachedBySessionOnly) {
    var reachedByStored = statesReachedFromBegin(EventType::stored);
    var states = new HashSet<State>();
    for (State state : transitions.keySet())
      if (state != Begin && (reachedByStored.contains(state) || !reachedBySessionOnly.contains(state)))
        states.add(state);
    return states;
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
