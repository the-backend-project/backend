package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.BasicEventType.Rollback;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/// Event logs replayed through the transitions of their entity models, for drawing them, e.g. as a sequence diagram: the
/// transitions each event took, the events they triggered, and which of those are events of the logs.
///
/// An event doesn't record what caused it, so the replay finds the transition each event took like the state machine
/// does, from the state the events before it took the entity to. A transition with choices stores the event of the
/// alternative it chose, so the transition of an event is the path from the transition of the state to that
/// alternative, whose triggers and actions are all those of the event.
///
/// The events of one transaction have its timestamp, so an event of a log is taken to be triggered by a trigger of the
/// same type on its entity model, or on a group of it, of an event with the same timestamp, e.g. the event an inbox
/// request triggers on the entity it was processed on, and the response the entity triggers on the inbox request. An
/// event no trigger is found for is a root of the replay, e.g. one given to the state machine from outside, or
/// triggered by an entity whose log isn't replayed. A conditional trigger fires in another transaction, so its event
/// is never found.
///
/// A rollback takes the reverse transitions of the events it rolls back, latest first, whose triggers are those of the
/// rollback.
public final class Replay {

  private final List<EventLog> logs;
  private final List<Step> steps;

  private Replay(List<EventLog> logs, List<Step> steps) {
    this.logs = logs;
    this.steps = steps;
  }

  /// An event triggered by a step, and the step of the event if it is in the logs of the replay
  ///
  /// @param reversal whether it is triggered by the reverse transition of an event rolled back
  public record Triggered(EventTrigger<?, ?, ?> trigger, boolean reversal, Optional<Step> step) {}

  /// An event of a log, with the path of transitions it took, the events they triggered, and the step whose
  /// transition triggered it, if it is in the logs of the replay
  public static final class Step {

    private final EventLog log;
    private final Event<?> event;
    private final List<TransitionModel<?, ?>> path;
    private final List<Step> rolledBack;
    private final List<Triggered> triggered = new ArrayList<>();
    private Step cause;
    private boolean isRolledBack;

    private Step(EventLog log, Event<?> event, List<TransitionModel<?, ?>> path, List<Step> rolledBack) {
      this.log = log;
      this.event = event;
      this.path = path;
      this.rolledBack = rolledBack;
    }

    public EventLog log() {
      return log;
    }

    public Event<?> event() {
      return event;
    }

    /// The transitions from the transition of the state to the one of the event, which is the last
    public List<TransitionModel<?, ?>> path() {
      return path;
    }

    /// The transition of the event
    public TransitionModel<?, ?> transition() {
      return path.getLast();
    }

    /// The events triggered by the transitions of the path, in their order, followed for a rollback by those of the
    /// reverse transitions of the events it rolled back
    public List<Triggered> triggered() {
      return unmodifiableList(triggered);
    }

    /// The actions of the transitions of the path
    public List<Action<?>> actions() {
      return path.stream().flatMap(t -> t.actions().stream()).toList();
    }

    /// For a rollback, the steps of the events it rolled back, latest first
    public List<Step> rolledBack() {
      return rolledBack;
    }

    /// Whether the event was rolled back by a later one
    public boolean isRolledBack() {
      return isRolledBack;
    }

    /// The step whose transition triggered the event, if it is in the logs of the replay
    public Optional<Step> cause() {
      return Optional.ofNullable(cause);
    }

    private Instant time() {
      return event.timestamp().toInstant();
    }
  }

  /// Replays the log alone
  public static Replay of(EventLog log) {
    return of(log, List.of());
  }

  /// Replays the log with related logs, e.g. those of its inbox and outbox requests, whose events are matched to the
  /// triggers of each other's
  ///
  /// @throws IllegalStateException if an event of a log has no transition from the state the events before it took
  ///                               the entity to, e.g. one of another version of the application
  public static Replay of(EventLog log, List<EventLog> related) {
    List<EventLog> logs = Stream.concat(Stream.of(log), related.stream()).toList();
    List<Step> steps = new ArrayList<>();
    for (EventLog l : logs)
      steps.addAll(replay(l));
    // In the order of time, and of the logs and their events within a transaction
    steps.sort(Comparator.comparing(Step::time)
        .thenComparing(step -> logs.indexOf(step.log()))
        .thenComparing(step -> step.event().eventNumber()));
    for (Step step : steps) {
      if (step.cause == null)
        match(step, steps);
    }
    return new Replay(logs, unmodifiableList(steps));
  }

  /// The logs replayed, the first one first
  public List<EventLog> logs() {
    return logs;
  }

  /// The steps of all the logs, in the order of time, and of the logs and their events within a transaction
  public List<Step> steps() {
    return steps;
  }

  /// The steps no step of the replay triggered, in order
  public List<Step> roots() {
    return steps.stream().filter(step -> step.cause == null).toList();
  }

  /// Takes the step to be triggered by the first trigger of its type on its entity model of a step in the same
  /// transaction, which isn't taken by another step, isn't a later event of the same entity, and isn't triggered by
  /// the step itself
  private static void match(Step step, List<Step> steps) {
    for (Step candidate : steps) {
      if (candidate == step || !candidate.time().equals(step.time()))
        continue;
      if (candidate.log() == step.log() && candidate.event().eventNumber() >= step.event().eventNumber())
        continue;
      if (isCausedBy(candidate, step))
        continue;
      for (int i = 0; i < candidate.triggered.size(); i++) {
        Triggered triggered = candidate.triggered.get(i);
        EventTrigger<?, ?, ?> trigger = triggered.trigger();
        if (triggered.step().isEmpty()
            && trigger instanceof EventTrigger.Immediate<?, ?, ?>
            && trigger.eventType().equals(step.event().type())
            && trigger.entityModel() != null
            && EntityModelGroup.targets(trigger.entityModel(), step.log().entityModel())) {
          candidate.triggered.set(i, new Triggered(trigger, triggered.reversal(), Optional.of(step)));
          step.cause = candidate;
          return;
        }
      }
    }
  }

  private static boolean isCausedBy(Step step, Step cause) {
    for (Step s = step; s != null; s = s.cause) {
      if (s == cause)
        return true;
    }
    return false;
  }

  private static boolean isOnItself(EventTrigger<?, ?, ?> trigger, EventLog log) {
    return trigger instanceof EventTrigger.Immediate<?, ?, ?>
        && trigger.entityModel() != null
        && EntityModelGroup.targets(trigger.entityModel(), log.entityModel());
  }

  /// The steps of the events in the log, from the effective events: a rollback moves the entity back to its state
  /// after the event rolled back to
  private static List<Step> replay(EventLog log) {
    Map<State, List<TransitionModel<?, ?>>> transitions = log.entityModel().transitions();
    List<Event<?>> events = log.events();
    List<Step> steps = new ArrayList<>();
    // The effective steps so far, with the state after each
    List<Step> effective = new ArrayList<>();
    List<State> statesAfter = new ArrayList<>();
    State state = Begin;
    for (int i = 0; i < events.size(); i++) {
      Event<?> event = events.get(i);
      EventType<?, ?> next = i + 1 < events.size() ? events.get(i + 1).type() : null;
      List<TransitionModel<?, ?>> path = path(log, transitions, state, event.type(), next);
      Step step;
      if (event.type() instanceof Rollback rollbackType) {
        int toNumber = Event.unmarshal(rollbackType, event.data()).toNumber();
        int actualToNumber = toNumber >= 0 ? toNumber : event.eventNumber() - 1 + toNumber;
        int kept = 0;
        while (kept < effective.size() && effective.get(kept).event().eventNumber() <= actualToNumber)
          kept++;
        List<Step> rolledBack = List.copyOf(effective.subList(kept, effective.size()).reversed());
        rolledBack.forEach(s -> s.isRolledBack = true);
        step = new Step(log, event, path, rolledBack);
        effective.subList(kept, effective.size()).clear();
        statesAfter.subList(kept, statesAfter.size()).clear();
        state = statesAfter.isEmpty() ? Begin : statesAfter.getLast();
      } else {
        step = new Step(log, event, path, List.of());
        if (step.transition().toState() != null)
          state = step.transition().toState();
        effective.add(step);
        statesAfter.add(state);
      }
      path.forEach(t -> t.triggers().forEach(trigger -> step.triggered.add(new Triggered(trigger, false, Optional.empty()))));
      for (Step reversed : step.rolledBack()) {
        TransitionModel<?, ?> reverse = reversed.transition().reverseModel();
        if (reverse != null)
          reverse.triggers().forEach(trigger -> step.triggered.add(new Triggered(trigger, true, Optional.empty())));
      }
      steps.add(step);
    }
    return steps;
  }

  /// The path of transitions an event of the type took from the state, to the transition of the type: the one of the
  /// type, or an alternative of a choice of the type. Several paths may lead to the type, e.g. the same rejection in
  /// several choices, and the log doesn't tell which one was taken, as the choices depend on data it doesn't have. The
  /// first one which triggers no event on the entity's own model is taken then, as the next event may as well have
  /// been triggered by another entity, e.g. an application completing an inbox request. If they all do, the first one
  /// triggering the next event of the log is taken, and else the first one. A rollback without a transition of its own
  /// takes the built-in one, which has no triggers or actions.
  private static List<TransitionModel<?, ?>> path(
      EventLog log,
      Map<State, List<TransitionModel<?, ?>>> transitions,
      State state,
      EventType<?, ?> type,
      EventType<?, ?> next
  ) {
    List<List<TransitionModel<?, ?>>> candidates = transitions.getOrDefault(state, List.of()).stream()
        .flatMap(Replay::paths)
        .filter(path -> path.getLast().eventType().equals(type))
        .toList();
    if (candidates.isEmpty() && type instanceof Rollback rollbackType)
      return List.of(TransitionModelBuilder.rollbackOn(rollbackType));
    if (candidates.isEmpty())
      throw new IllegalStateException(String.format(
          "No transition of %s from %s for logged event %s",
          log.entityModel().name(),
          state.name(),
          type.name()
      ));
    return candidates.stream()
        .filter(path -> triggersOnItself(path, log).isEmpty())
        .findFirst()
        .or(() -> candidates.stream().filter(path -> next != null && triggersOnItself(path, log).contains(next)).findFirst())
        .orElse(candidates.getFirst());
  }

  private static List<EventType<?, ?>> triggersOnItself(List<TransitionModel<?, ?>> path, EventLog log) {
    return path.stream()
        .flatMap(t -> t.triggers().stream())
        .filter(trigger -> isOnItself(trigger, log))
        .<EventType<?, ?>>map(EventTrigger::eventType)
        .toList();
  }

  /// The paths from the transition to each of the transitions whose event it may store
  private static Stream<List<TransitionModel<?, ?>>> paths(TransitionModel<?, ?> transition) {
    if (transition.filters().isEmpty())
      return Stream.of(List.of(transition));
    return transition.filters().stream()
        .flatMap(filter -> paths(filter.alternative().model()))
        .map(path -> Stream.concat(Stream.of(transition), path.stream()).toList());
  }

}
