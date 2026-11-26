package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.BasicEventType.Rollback;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

/// A sequence diagram of the event log of an entity, drawn with PlantUML.
///
/// An event doesn't record what caused it, so the diagram replays the log through the transitions of the entity's
/// model, like the state machine does, to find the transition each event took. A transition with choices stores the
/// event of the alternative it chose, so the transition of an event is the path from the transition of the state to
/// that alternative, and the events triggered and actions taken along the path are those of the event.
///
/// Each event arrives at the entity's lifeline from outside the diagram, as the log doesn't tell which entity, if any,
/// triggered it. The events it triggers are synchronous messages to the lifelines of the entity models they are
/// triggered on, in the same transaction. An event it triggers on the entity's own model which is the next one in the
/// log is drawn as a message to itself, nested in the transition, with the events that one triggers. A conditional
/// trigger, which is stored and fires later, is an asynchronous message. The actions leave the diagram, as they are
/// run after the transaction is committed.
///
/// A rollback takes the reverse transitions of the events it rolls back, latest first, whose triggers are drawn in the
/// colour of a reversal. The events rolled back are struck through.
public class SequenceDiagramFormatter {

  private final EventLog log;

  public SequenceDiagramFormatter(EventLog log) {
    this.log = log;
  }

  /// Writes the diagram as SVG to the file
  public File formatToImage(File file) throws IOException {
    //noinspection ResultOfMethodCallIgnored
    file.getAbsoluteFile().getParentFile().mkdirs();
    var svg = new ByteArrayOutputStream();
    new SourceStringReader(format()).outputImage(svg, new FileFormatOption(FileFormat.SVG));
    try (var out = new FileOutputStream(file)) {
      out.write(DiagramColours.addSequenceStyle(svg.toString(UTF_8)).getBytes(UTF_8));
    }
    return file;
  }

  /// An event of the log, with the path of transitions it took, and for a rollback the steps of the events it rolled
  /// back, latest first
  private record Step(Event<?> event, List<TransitionModel<?, ?>> path, List<Step> rolledBack) {

    TransitionModel<?, ?> transition() {
      return path.getLast();
    }

    List<EventTrigger<?, ?, ?>> triggers() {
      return path.stream().flatMap(t -> t.triggers().stream()).toList();
    }

    List<Action<?>> actions() {
      return path.stream().flatMap(t -> t.actions().stream()).toList();
    }
  }

  /// The PlantUML source of the diagram
  public String format() {
    List<Step> steps = replay();
    Set<Event<?>> rolledBack = new HashSet<>();
    steps.forEach(step -> step.rolledBack().forEach(r -> rolledBack.add(r.event())));
    Participants participants = new Participants(log.entityModel());
    StringBuilder messages = new StringBuilder();
    for (int i = 0; i < steps.size(); )
      i = step(steps, i, "[->", rolledBack, participants, messages);
    return String.format(
        """
        @startuml
        skinparam backgroundColor transparent
        skinparam participant {
          BackgroundColor #9bb08a
          BorderColor #4b5320
        }
        skinparam sequence {
          LifeLineBorderColor #4b5320
          LifeLineBackgroundColor #9bb08a
        }
        hide footbox

        %s
        %s@enduml
        """,
        participants.declarations(),
        messages
    );
  }

  /// Draws the step at the index, arriving by the arrow, and the steps of the events it triggers on the entity itself
  /// nested in it. Returns the index of the step after them.
  private int step(
      List<Step> steps,
      int index,
      String arrival,
      Set<Event<?>> rolledBack,
      Participants participants,
      StringBuilder messages
  ) {
    Step step = steps.get(index);
    String entity = participants.id(log.entityModel());
    String name = PlantUMLFormatter.eventName(step.event().type(), severity(step.transition()));
    if (rolledBack.contains(step.event()))
      name = "<s>" + name + "</s>";
    messages.append(String.format("%s %s: %d. %s\n", arrival, entity, step.event().eventNumber(), name));
    messages.append("activate ").append(entity).append('\n');
    int next = index + 1;
    for (EventTrigger<?, ?, ?> trigger : step.triggers()) {
      if (next < steps.size() && isOnItself(trigger) && trigger.eventType().equals(steps.get(next).event().type()))
        next = step(steps, next, entity + " ->", rolledBack, participants, messages);
      else
        trigger(messages, participants, entity, trigger, false);
    }
    for (Step reversed : step.rolledBack()) {
      TransitionModel<?, ?> reverse = reversed.transition().reverseModel();
      if (reverse != null)
        reverse.triggers().forEach(trigger -> trigger(messages, participants, entity, trigger, true));
    }
    step.actions().forEach(action -> messages.append(String.format("%s ->>] : ⚡ %s\n", entity, action.name())));
    messages.append("deactivate ").append(entity).append('\n');
    return next;
  }

  /// Whether the trigger is one in the transaction on the entity's own model, which is taken to be on the entity
  /// itself when its event is the next one in the log
  private boolean isOnItself(EventTrigger<?, ?, ?> trigger) {
    return trigger instanceof EventTrigger.Immediate<?, ?, ?> && log.entityModel().equals(trigger.entityModel());
  }

  private static void trigger(
      StringBuilder messages,
      Participants participants,
      String entity,
      EventTrigger<?, ?, ?> trigger,
      boolean reversal
  ) {
    String name = trigger.eventType().name();
    if (reversal)
      name = "<color:" + DiagramColours.REVERSAL.light() + ">" + name + "</color>";
    messages.append(String.format(
        "%s %s %s: %s\n",
        entity,
        trigger instanceof EventTrigger.Conditional<?, ?, ?> ? "->>" : "->",
        participants.id(trigger.entityModel()),
        name
    ));
  }

  /// The severity of the transition, or Info for the built-in transitions, which have none
  private static Severity severity(TransitionModel<?, ?> transition) {
    return transition.severity() != null ? transition.severity() : Severity.Info;
  }

  /// The lifelines of the diagram: the entity's, first, and those of the entity models the events are triggered on,
  /// in the order they are first triggered on
  private static final class Participants {

    private final Map<String, String> ids = new LinkedHashMap<>();

    Participants(EntityModel entityModel) {
      id(entityModel);
    }

    /// The alias of the lifeline of the entity model, which is unknown for a trigger on an entity model selected when
    /// the trigger fires
    String id(EntityModel entityModel) {
      String name = entityModel != null ? entityModel.name() : "?";
      return ids.computeIfAbsent(name, _ -> "P" + (ids.size() + 1));
    }

    String declarations() {
      return ids.entrySet().stream()
          .map(e -> String.format("participant \"%s\" as %s\n", e.getKey(), e.getValue()))
          .collect(joining());
    }
  }

  /// The steps of the events in the log, from the effective events: a rollback moves the entity back to its state
  /// after the event rolled back to.
  private List<Step> replay() {
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
      List<TransitionModel<?, ?>> path = path(transitions, state, event.type(), next);
      if (event.type() instanceof Rollback rollbackType) {
        int toNumber = Event.unmarshal(rollbackType, event.data()).toNumber();
        int actualToNumber = toNumber >= 0 ? toNumber : event.eventNumber() - 1 + toNumber;
        int kept = 0;
        while (kept < effective.size() && effective.get(kept).event().eventNumber() <= actualToNumber)
          kept++;
        steps.add(new Step(event, path, List.copyOf(effective.subList(kept, effective.size()).reversed())));
        effective.subList(kept, effective.size()).clear();
        statesAfter.subList(kept, statesAfter.size()).clear();
        state = statesAfter.isEmpty() ? Begin : statesAfter.getLast();
      } else {
        Step step = new Step(event, path, List.of());
        steps.add(step);
        if (step.transition().toState() != null)
          state = step.transition().toState();
        effective.add(step);
        statesAfter.add(state);
      }
    }
    return steps;
  }

  /// The path of transitions an event of the type took from the state, to the transition of the type: the one of the
  /// type, or an alternative of a choice of the type. Several paths may lead to the type, e.g. the same rejection in
  /// several choices, and the log doesn't tell which one was taken, as the choices depend on data it doesn't have. The
  /// first one which triggers no event on the entity itself is taken then, as the next event may as well have been
  /// triggered by another entity, e.g. an application completing an inbox request. If they all do, the first one
  /// triggering the next event of the log is taken, and else the first one. A rollback without a transition of its own
  /// takes the built-in one, which has no triggers or actions.
  private List<TransitionModel<?, ?>> path(
      Map<State, List<TransitionModel<?, ?>>> transitions,
      State state,
      EventType<?, ?> type,
      EventType<?, ?> next
  ) {
    List<List<TransitionModel<?, ?>>> candidates = transitions.getOrDefault(state, List.of()).stream()
        .flatMap(SequenceDiagramFormatter::paths)
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
        .filter(path -> triggersOnItself(path).isEmpty())
        .findFirst()
        .or(() -> candidates.stream().filter(path -> next != null && triggersOnItself(path).contains(next)).findFirst())
        .orElse(candidates.getFirst());
  }

  private List<EventType<?, ?>> triggersOnItself(List<TransitionModel<?, ?>> path) {
    return path.stream()
        .flatMap(t -> t.triggers().stream())
        .filter(this::isOnItself)
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
