package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.BasicEventType.Rollback;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/// Event logs replayed through the transitions of their entity models, for drawing them, e.g. as a sequence diagram: the
/// transitions each event took, the events they triggered, and which of those are events of the logs.
///
/// An event doesn't record what caused it, so the replay finds the transition each event took like the state machine
/// does, from the state the events before it took the entity to. A transition with choices stores the event of the
/// alternative it chose, so the transition of an event is the path from the transition of the state to that
/// alternative, whose triggers and actions are all those of the event. A trigger starts the first transition of the
/// path, so the event stored may be of another type than the one triggered, e.g. `Request accepted` of an inbox
/// request triggered with `Request received`.
///
/// The events of one transaction have its timestamp, so an event of a log is taken to be triggered by a trigger of the
/// type starting its path on its entity model, or on a group of it, of an event with the same timestamp, e.g. the
/// event an inbox request triggers on the entity it was processed on, and the response the entity triggers on the
/// inbox request. A conditional trigger fires in another transaction, so its event is never found.
///
/// An entity of a model which is not stored, e.g. the `RequestRouting` of an inbox, has no log. Given the entity models
/// of the state machine, the replay deduces its step from their transitions: an event no trigger of the logs is found
/// for is taken to be triggered by a transition of a model which is not stored, if that is the only transition of such
/// a model triggering it. Its step has no event number, as it is never stored. Events of stored models are only taken
/// from their logs, which can be given. An event with neither is a root of the replay, e.g. one given to the state
/// machine from outside, or triggered by an entity whose log isn't given.
///
/// The outcome of an action, such as the response to an outbox request forwarded, is given back to the entity which ran
/// it, in a later transaction. Which event it is isn't declared, but an event which no transition of the models
/// triggers, neither at once, conditionally nor delayed, can only be the outcome of an action or be given from outside.
/// Such an event is taken to be the outcome of the first action of the latest earlier transaction of its entity whose
/// outcome isn't found yet. The models are those of the logs, those given, and those their transitions trigger events
/// on.
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

  /// An event triggered by a step, and the step of the event if it is in the replay
  ///
  /// @param reversal whether it is triggered by the reverse transition of an event rolled back
  public record Triggered(EventTrigger<?, ?, ?> trigger, boolean reversal, Optional<Step> step) {}

  /// An action run after the transaction of a step, and the step of its outcome if it is in the replay
  public record Performed(Action<?> action, Optional<Step> outcome) {}

  /// An event of a log, or a deduced one of an entity which isn't stored, with the path of transitions it took, the
  /// events they triggered, and the step whose transition triggered it, if it is in the replay
  public static final class Step {

    private final EventLog log;
    private final Event<?> event;
    private final List<TransitionModel<?, ?>> path;
    private final List<Step> rolledBack;
    private final boolean deduced;
    private final List<Triggered> triggered = new ArrayList<>();
    private final List<Performed> performed = new ArrayList<>();
    private Step cause;
    private Step outcomeOf;
    private boolean isRolledBack;

    private Step(EventLog log, Event<?> event, List<TransitionModel<?, ?>> path, List<Step> rolledBack, boolean deduced) {
      this.log = log;
      this.event = event;
      this.path = path;
      this.rolledBack = rolledBack;
      this.deduced = deduced;
      path.forEach(t -> t.triggers().forEach(trigger -> triggered.add(new Triggered(trigger, false, Optional.empty()))));
      path.forEach(t -> t.actions().forEach(action -> performed.add(new Performed(action, Optional.empty()))));
      for (Step reversed : rolledBack) {
        TransitionModel<?, ?> reverse = reversed.transition().reverseModel();
        if (reverse != null)
          reverse.triggers().forEach(trigger -> triggered.add(new Triggered(trigger, true, Optional.empty())));
      }
    }

    /// The log of the entity, which for a deduced step is of its model, with no entity id, and the deduced event only
    public EventLog log() {
      return log;
    }

    /// The event, which for a deduced step has no entity id and event number 0
    public Event<?> event() {
      return event;
    }

    /// Whether the step is deduced from the transitions of an entity model which is not stored, rather than read from
    /// a log
    public boolean isDeduced() {
      return deduced;
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

    /// The actions of the transitions of the path, run after the transaction, with the steps of their outcomes
    public List<Performed> performed() {
      return unmodifiableList(performed);
    }

    /// For a rollback, the steps of the events it rolled back, latest first
    public List<Step> rolledBack() {
      return rolledBack;
    }

    /// Whether the event was rolled back by a later one
    public boolean isRolledBack() {
      return isRolledBack;
    }

    /// The step whose transition triggered the event, if it is in the replay
    public Optional<Step> cause() {
      return Optional.ofNullable(cause);
    }

    /// The step whose action the event is the outcome of, if it is in the replay
    public Optional<Step> outcomeOf() {
      return Optional.ofNullable(outcomeOf);
    }

    /// The type of the event triggered to take the path
    private EventType<?, ?> triggeredType() {
      return path.getFirst().eventType();
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
    return of(log, related, List.of());
  }

  /// Replays the log with related logs, and deduces the steps of the entities which aren't stored from the transitions
  /// of the entity models, e.g. those given to the state machine, and those they trigger events on
  ///
  /// @throws IllegalStateException if an event of a log has no transition from the state the events before it took
  ///                               the entity to, e.g. one of another version of the application
  public static Replay of(EventLog log, List<EventLog> related, List<? extends EntityModel> entityModels) {
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
    List<EntityModel> notStored = entityModels.isEmpty() ? List.of() : EntityModel.reachable(entityModels).stream()
        .filter(model -> !model.stored())
        .toList();
    List<Step> all = new ArrayList<>();
    List<EventLog> allLogs = new ArrayList<>(logs);
    for (Step step : steps) {
      if (step.cause == null) {
        Step deduced = deduce(step, notStored);
        if (deduced != null) {
          all.add(deduced);
          allLogs.add(deduced.log());
        }
      }
      all.add(step);
    }
    Set<EventType<?, ?>> triggeredTypes = triggeredTypes(
        Stream.concat(allLogs.stream().map(EventLog::entityModel), entityModels.stream()).distinct().toList()
    );
    for (int i = 0; i < all.size(); i++) {
      Step step = all.get(i);
      if (step.cause == null && !step.deduced && !triggeredTypes.contains(step.triggeredType()))
        matchOutcome(step, all.subList(0, i));
    }
    return new Replay(List.copyOf(allLogs), unmodifiableList(all));
  }

  /// The logs replayed, the first one first, followed by the related ones and those of the deduced steps
  public List<EventLog> logs() {
    return logs;
  }

  /// The steps of all the logs, in the order of time, and of the logs and their events within a transaction, with the
  /// deduced steps before the steps they triggered
  public List<Step> steps() {
    return steps;
  }

  /// The steps no step of the replay triggered, in order, including the outcomes of actions, which come in
  /// transactions of their own
  public List<Step> roots() {
    return steps.stream().filter(step -> step.cause == null).toList();
  }

  /// Takes the step to be triggered by the first trigger of the type starting its path on its entity model of a step
  /// in the same transaction, which isn't taken by another step, isn't a later event of the same entity, and isn't
  /// triggered by the step itself
  private static void match(Step step, List<Step> steps) {
    for (Step candidate : steps) {
      if (candidate == step || !candidate.time().equals(step.time()))
        continue;
      if (candidate.log() == step.log() && candidate.event().eventNumber() >= step.event().eventNumber())
        continue;
      if (isCausedBy(candidate, step))
        continue;
      if (link(candidate, step))
        return;
    }
  }

  /// Links the step to the first trigger of the cause it may have been triggered by, which isn't taken by another
  /// step. Returns whether there was one.
  private static boolean link(Step cause, Step step) {
    for (int i = 0; i < cause.triggered.size(); i++) {
      Triggered triggered = cause.triggered.get(i);
      if (triggered.step().isEmpty() && triggers(triggered.trigger(), step.triggeredType(), step.log().entityModel())) {
        cause.triggered.set(i, new Triggered(triggered.trigger(), triggered.reversal(), Optional.of(step)));
        step.cause = cause;
        return true;
      }
    }
    return false;
  }

  /// Takes the step to be the outcome of the first action of the latest step before it, of the same entity in an earlier
  /// transaction, with an action whose outcome isn't found yet
  private static void matchOutcome(Step step, List<Step> before) {
    for (Step candidate : before.reversed()) {
      if (candidate.log() != step.log() || !candidate.time().isBefore(step.time()))
        continue;
      for (int i = 0; i < candidate.performed.size(); i++) {
        Performed performed = candidate.performed.get(i);
        if (performed.outcome().isEmpty()) {
          candidate.performed.set(i, new Performed(performed.action(), Optional.of(step)));
          step.outcomeOf = candidate;
          return;
        }
      }
    }
  }

  /// The types of the events the transitions of the models, and of those they trigger events on, trigger at once,
  /// conditionally, delayed or when reversed
  private static Set<EventType<?, ?>> triggeredTypes(List<EntityModel> models) {
    Set<EventType<?, ?>> types = new HashSet<>();
    for (EntityModel model : EntityModel.reachable(models)) {
      model.transitions().values().stream().flatMap(List::stream).flatMap(Replay::paths).flatMap(List::stream).forEach(t -> {
        t.triggers().forEach(trigger -> types.add(trigger.eventType()));
        types.addAll(t.delayedEventTypes());
        if (t.reverseModel() != null)
          t.reverseModel().triggers().forEach(trigger -> types.add(trigger.eventType()));
      });
    }
    return types;
  }

  /// The step of an entity of a model which isn't stored that triggered the step, if exactly one transition of such a
  /// model triggers it, or null
  private static Step deduce(Step step, List<EntityModel> notStored) {
    EntityModel found = null;
    List<TransitionModel<?, ?>> foundPath = null;
    for (EntityModel model : notStored) {
      for (List<TransitionModel<?, ?>> path : model.transitions().values().stream().flatMap(List::stream).flatMap(Replay::paths).toList()) {
        boolean triggersStep = path.stream()
            .flatMap(t -> t.triggers().stream())
            .anyMatch(trigger -> triggers(trigger, step.triggeredType(), step.log().entityModel()));
        if (triggersStep) {
          // The same transition may be one of several states
          if (found == model && foundPath.equals(path))
            continue;
          if (found != null)
            return null;
          found = model;
          foundPath = path;
        }
      }
    }
    if (found == null)
      return null;
    Event<?> event = new Event<>(null, 0, foundPath.getLast().eventType(), step.event().timestamp());
    Step deduced = new Step(new EventLog(found, null, List.of(event)), event, foundPath, List.of(), true);
    link(deduced, step);
    return deduced;
  }

  /// Whether the trigger, in the transaction, triggers an event of the type on an entity of the model
  private static boolean triggers(EventTrigger<?, ?, ?> trigger, EventType<?, ?> type, EntityModel model) {
    return trigger instanceof EventTrigger.Immediate<?, ?, ?>
        && trigger.eventType().equals(type)
        && trigger.entityModel() != null
        && EntityModelGroup.targets(trigger.entityModel(), model);
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
        step = new Step(log, event, path, rolledBack, false);
        effective.subList(kept, effective.size()).clear();
        statesAfter.subList(kept, statesAfter.size()).clear();
        state = statesAfter.isEmpty() ? Begin : statesAfter.getLast();
      } else {
        step = new Step(log, event, path, List.of(), false);
        if (step.transition().toState() != null)
          state = step.transition().toState();
        effective.add(step);
        statesAfter.add(state);
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
  /// which may have triggered the next event of the log is taken, and else the first one. A rollback without a
  /// transition of its own takes the built-in one, which has no triggers or actions.
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
        .or(() -> candidates.stream()
            .filter(path -> next != null && triggersOnItself(path, log).stream().anyMatch(t -> mayStore(log.entityModel(), t, next)))
            .findFirst())
        .orElse(candidates.getFirst());
  }

  private static List<EventType<?, ?>> triggersOnItself(List<TransitionModel<?, ?>> path, EventLog log) {
    return path.stream()
        .flatMap(t -> t.triggers().stream())
        .filter(trigger -> isOnItself(trigger, log))
        .<EventType<?, ?>>map(EventTrigger::eventType)
        .toList();
  }

  /// Whether an event of the type triggered on an entity of the model may store an event of the stored type
  private static boolean mayStore(EntityModel model, EventType<?, ?> triggered, EventType<?, ?> stored) {
    return model.transitions().values().stream()
        .flatMap(List::stream)
        .filter(t -> t.eventType().equals(triggered))
        .flatMap(Replay::paths)
        .anyMatch(path -> path.getLast().eventType().equals(stored));
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
