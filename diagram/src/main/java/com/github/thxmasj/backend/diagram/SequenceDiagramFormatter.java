package com.github.thxmasj.backend.diagram;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.Replay;
import com.github.thxmasj.backend.core.Replay.Step;
import com.github.thxmasj.backend.core.Replay.Triggered;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

/// A sequence diagram of the event log of an entity, with related logs, e.g. those of its inbox and outbox requests,
/// drawn with PlantUML from their [Replay].
///
/// Each entity whose log is drawn has a lifeline. An event no step of the logs triggered arrives at its entity from
/// outside the diagram. The events a transition triggers are synchronous messages, as they are stored in the same
/// transaction: to the entity of the event, nested in the transition, if it is in the logs, and else to a lifeline of
/// the entity model triggered on. A conditional trigger, which is stored and fires later, is an asynchronous message.
/// The actions leave the diagram, as they are run after the transaction is committed. The triggers of the reverse
/// transitions of a rollback are drawn in the colour of a reversal, and the events rolled back are struck through.
public class SequenceDiagramFormatter {

  private final Replay replay;

  /// A sequence diagram of the log alone
  public SequenceDiagramFormatter(EventLog log) {
    this(log, List.of());
  }

  /// A sequence diagram of the log and the related logs
  public SequenceDiagramFormatter(EventLog log, List<EventLog> related) {
    this.replay = Replay.of(log, related);
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

  /// The PlantUML source of the diagram
  public String format() {
    Participants participants = new Participants(replay.logs());
    StringBuilder messages = new StringBuilder();
    for (Step root : replay.roots())
      step(root, "[->", participants, messages);
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

  /// Draws the step, arriving by the arrow, with the steps it triggered nested in it
  private static void step(Step step, String arrival, Participants participants, StringBuilder messages) {
    String entity = participants.of(step.log());
    String name = PlantUMLFormatter.eventName(step.event().type(), severity(step.transition()));
    if (step.isRolledBack())
      name = "<s>" + name + "</s>";
    messages.append(String.format("%s %s: %d. %s\n", arrival, entity, step.event().eventNumber(), name));
    messages.append("activate ").append(entity).append('\n');
    for (Triggered triggered : step.triggered()) {
      if (triggered.step().isPresent()) {
        step(triggered.step().get(), entity + " ->", participants, messages);
        continue;
      }
      EventTrigger<?, ?, ?> trigger = triggered.trigger();
      String event = trigger.eventType().name();
      if (triggered.reversal())
        event = "<color:" + DiagramColours.REVERSAL.light() + ">" + event + "</color>";
      messages.append(String.format(
          "%s %s %s: %s\n",
          entity,
          trigger instanceof EventTrigger.Conditional<?, ?, ?> ? "->>" : "->",
          participants.of(trigger.entityModel()),
          event
      ));
    }
    step.actions().forEach(action -> messages.append(String.format("%s ->>] : ⚡ %s\n", entity, action.name())));
    messages.append("deactivate ").append(entity).append('\n');
  }

  /// The severity of the transition, or Info for the built-in transitions, which have none
  private static Severity severity(TransitionModel<?, ?> transition) {
    return transition.severity() != null ? transition.severity() : Severity.Info;
  }

  /// The lifelines of the diagram: those of the entities of the logs, and those of the other entity models events are
  /// triggered on. They are declared in the order they are first drawn, so that the diagram reads from left to right,
  /// e.g. from an inbox request to the entity it was processed on.
  private static final class Participants {

    /// The names of the lifelines by their aliases
    private final Map<String, String> names = new LinkedHashMap<>();
    /// The aliases of the lifelines drawn, in the order they were first drawn
    private final Set<String> drawn = new LinkedHashSet<>();
    private final Map<EventLog, String> logs = new LinkedHashMap<>();
    private final Map<String, String> models = new LinkedHashMap<>();

    Participants(List<EventLog> logs) {
      for (EventLog log : logs) {
        String alias = "P" + (names.size() + 1);
        long sameModel = logs.stream().filter(l -> l.entityModel().equals(log.entityModel())).count();
        // Entities of the same model are told apart by the start of their entity ids
        names.put(alias, sameModel > 1
            ? log.entityModel().name() + " " + log.entityId().toString().substring(0, 8)
            : log.entityModel().name());
        this.logs.put(log, alias);
      }
    }

    String of(EventLog log) {
      return drawn(logs.get(log));
    }

    private String drawn(String alias) {
      drawn.add(alias);
      return alias;
    }

    /// The lifeline of an entity model triggered on: of the entity of the logs if there is one entity of it, or a
    /// group of it, and else one of the model, which is unknown for a trigger on an entity model selected when the
    /// trigger fires
    String of(EntityModel model) {
      if (model != null) {
        List<String> entities = logs.entrySet().stream()
            .filter(e -> EntityModelGroup.targets(model, e.getKey().entityModel()))
            .map(Map.Entry::getValue)
            .toList();
        if (entities.size() == 1)
          return drawn(entities.getFirst());
      }
      String name = model != null ? model.name() : "?";
      return drawn(models.computeIfAbsent(name, _ -> {
        String alias = "P" + (names.size() + 1);
        names.put(alias, name);
        return alias;
      }));
    }

    /// The declarations of the lifelines drawn, in the order they were first drawn, and then of the entities of the
    /// logs with no events
    String declarations() {
      return Stream.concat(drawn.stream(), names.keySet().stream())
          .distinct()
          .map(alias -> String.format("participant \"%s\" as %s\n", names.get(alias), alias))
          .collect(joining());
    }
  }

}
