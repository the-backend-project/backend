package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityType.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.statusOn;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.internal.Debug;
import java.util.List;
import java.util.Map;

/// The transitions of an entity type, taken by an entity: its current state, found from its event log, and the
/// transition an event takes from it. The transitions are checked before, by [EntityTypeChecks].
class Traverser {

  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  /// Whether entities of the session only are of the model: all of them if it is not stored, and those an event not
  /// stored creates otherwise
  private final boolean hasEntitiesOfTheSessionOnly;

  Traverser(EntityType model) {
    this.transitions = model.transitions();
    this.hasEntitiesOfTheSessionOnly = model instanceof EntityType.NotStored || transitions.getOrDefault(Begin, List.of()).stream()
        .flatMap(transition -> transition.leaves().stream())
        .anyMatch(leaf -> leaf.eventType() instanceof EventType.NotStored);
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
    for (var loggedEvent : effectiveEvents.stream().map(EventLog.Entry::type).toList()) {
      lastTransition = findTransitionForLoggedEvent(loggedEvent, currentState);
      currentState = targetState(currentState, lastTransition);
    }
//    System.out.printf(
//        """
//        Traverser.currentState for %s: %s
//        Effective events: %s
//        Actual events:    %s
//        """,
//        eventLog.entityType().name(),
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
    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState.name() + " on " + eventLog.entityType().name());
    var t = findTransitionForTriggeredEvent(eventType, availableTransitions);
    if (t != null && eventType == BuiltinEventTypes.EventsRolledBack)
      Debug.log("Using custom rollback transition for entity " + eventLog.entityType().name() + ": " + t + "\nAll transitions:\n" + transitions.values().stream().flatMap(List::stream).map(Object::toString).collect(joining("\n")));
    if (t == null && eventType == BuiltinEventTypes.EventsRolledBack)
      t = rollbackOn(BuiltinEventTypes.EventsRolledBack);
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
//    if (t == null && eventType == BuiltinEventTypes.Rollback && eventType instanceof EventType.Rollback rollback)
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
      for (var leafTransition : transition.leaves()) {
        if (leafTransition.eventType().equals(eventType)) {
          return leafTransition;
        }
      }
    }
    // TODO: Avoid dynamic creation
    if (eventType == BuiltinEventTypes.EventsRolledBack)
      return rollbackOn(BuiltinEventTypes.EventsRolledBack);
    throw new IllegalStateException(
        "No transition found for logged event " + eventType.name() + " from " + fromState.name() + " (available: " + transitions.get(fromState).stream()
            .flatMap(t -> t.leaves().stream())
            .map(t -> t.eventType().name())
            .collect(joining(", ")) + ")");
  }
}
