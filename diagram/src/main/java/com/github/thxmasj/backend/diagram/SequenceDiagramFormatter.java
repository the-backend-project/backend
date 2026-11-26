package com.github.thxmasj.backend.diagram;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.Replay;
import com.github.thxmasj.backend.core.Replay.Step;
import com.github.thxmasj.backend.core.Replay.Triggered;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
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
/// Each entity whose log is drawn has a lifeline, and so does each model of the deduced steps of entities which aren't
/// stored. An event no step of the replay triggered arrives at its entity from outside the diagram. The events a
/// transition triggers are synchronous messages, as they are stored in the same transaction: to the entity of the
/// event, nested in the transition, if it is in the replay, and else to a lifeline of the entity model triggered on.
/// A conditional trigger, which is stored and fires later, is an asynchronous message. The actions leave the diagram,
/// as they are run after the transaction is committed. The triggers of the reverse transitions of a rollback are drawn
/// in the colour of a reversal, and the events rolled back are struck through.
///
/// Events triggered on [id][IdEntityModel] and [index][IndexEntityModel] entities are left out, as in the ER diagram,
/// as they take much space and tell little about how the entities relate.
public class SequenceDiagramFormatter {

  private final Replay replay;

  /// A sequence diagram of the log alone
  public SequenceDiagramFormatter(EventLog log) {
    this(log, List.of());
  }

  /// A sequence diagram of the log and the related logs
  public SequenceDiagramFormatter(EventLog log, List<EventLog> related) {
    this(log, related, List.of());
  }

  /// A sequence diagram of the log and the related logs, with the steps of entities which aren't stored deduced from
  /// the transitions of the entity models, e.g. those given to the state machine (see [Replay])
  public SequenceDiagramFormatter(EventLog log, List<EventLog> related, List<? extends EntityModel> entityModels) {
    this.replay = Replay.of(log, related, entityModels);
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
      step(root, null, participants, messages);
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

  /// Draws the step, triggered by the lifeline with the alias, or arriving from outside the diagram if it is null, with
  /// the steps it triggered nested in it
  private static void step(Step step, String from, Participants participants, StringBuilder messages) {
    String entity = participants.of(step.log());
    String name = PlantUMLFormatter.eventName(step.event().type(), severity(step.transition()));
    if (step.isRolledBack())
      name = "<s>" + name + "</s>";
    String arrival = from == null ? "[->" : participants.message(from, entity) + " ->";
    // A deduced step is never stored, so it has no event number
    messages.append(step.isDeduced()
        ? String.format("%s %s: <i>%s</i>\n", arrival, entity, name)
        : String.format("%s %s: %d. %s\n", arrival, entity, step.event().eventNumber(), name));
    messages.append("activate ").append(entity).append('\n');
    for (Triggered triggered : step.triggered()) {
      if (triggered.step().isPresent()) {
        step(triggered.step().get(), entity, participants, messages);
        continue;
      }
      EventTrigger<?, ?, ?> trigger = triggered.trigger();
      if (trigger.entityModel() instanceof IdEntityModel || trigger.entityModel() instanceof IndexEntityModel)
        continue;
      String event = trigger.eventType().name();
      if (triggered.reversal())
        event = "<color:" + DiagramColours.REVERSAL.light() + ">" + event + "</color>";
      messages.append(String.format(
          "%s %s %s: %s\n",
          participants.message(entity, participants.of(trigger.entityModel())),
          trigger instanceof EventTrigger.Conditional<?, ?, ?> ? "->>" : "->",
          participants.of(trigger.entityModel()),
          event
      ));
    }
    step.actions().forEach(action -> messages.append(String.format("%s ->>] : \u26a1 %s\n", entity, action.name())));
    messages.append("deactivate ").append(entity).append('\n');
  }

  /// The severity of the transition, or Info for the built-in transitions, which have none
  private static Severity severity(TransitionModel<?, ?> transition) {
    return transition.severity() != null ? transition.severity() : Severity.Info;
  }

  /// The lifelines of the diagram: those of the entities of the logs, of the models of the deduced steps, and of the
  /// other entity models events are triggered on. They are declared in the order of the first message between two
  /// lifelines they take part in, so that the diagram reads from left to right, e.g. from the routing of a request to
  /// its inbox request and on to the entity it was processed on. A message from outside the diagram doesn't count, so
  /// that an entity which only receives such messages early on, e.g. one created by an entity whose log isn't given,
  /// doesn't come first. Lifelines with no messages between them come last.
  private static final class Participants {

    /// The names of the lifelines by their aliases
    private final Map<String, String> names = new LinkedHashMap<>();
    /// The aliases of the lifelines in the order of the first message between two lifelines they take part in
    private final Set<String> ordered = new LinkedHashSet<>();
    private final Map<EventLog, String> logs = new IdentityHashMap<>();
    private final Map<String, String> models = new LinkedHashMap<>();

    /// The logs of the deduced steps have no entity ids, and the entities of a model of them have one lifeline, of
    /// the model, as nothing tells them apart
    Participants(List<EventLog> logs) {
      for (EventLog log : logs) {
        if (log.entityId() == null) {
          this.logs.put(log, model(log.entityModel().name()));
          continue;
        }
        String alias = "P" + (names.size() + 1);
        long sameModel = logs.stream().filter(l -> l.entityId() != null && l.entityModel().equals(log.entityModel())).count();
        // Entities of the same model are told apart by the start of their entity ids
        names.put(alias, sameModel > 1
            ? log.entityModel().name() + " " + log.entityId().toString().substring(0, 8)
            : log.entityModel().name());
        this.logs.put(log, alias);
      }
    }

    private String model(String name) {
      return models.computeIfAbsent(name, _ -> {
        String alias = "P" + (names.size() + 1);
        names.put(alias, name);
        return alias;
      });
    }

    String of(EventLog log) {
      return logs.get(log);
    }

    /// The lifeline of an entity model triggered on: of the entity of the logs if there is one entity of it, or a
    /// group of it, and else one of the model, which is unknown for a trigger on an entity model selected when the
    /// trigger fires
    String of(EntityModel model) {
      if (model != null) {
        List<String> entities = logs.entrySet().stream()
            .filter(e -> EntityModelGroup.targets(model, e.getKey().entityModel()))
            .map(Map.Entry::getValue)
            .distinct()
            .toList();
        if (entities.size() == 1)
          return entities.getFirst();
      }
      return model(model != null ? model.name() : "?");
    }

    /// Records a message from one lifeline to another, and gives the alias of the first
    String message(String from, String to) {
      ordered.add(from);
      ordered.add(to);
      return from;
    }

    /// The declarations of the lifelines, in the order of their first messages between lifelines, and then those
    /// with none
    String declarations() {
      return Stream.concat(ordered.stream(), names.keySet().stream())
          .distinct()
          .map(alias -> String.format("participant \"%s\" as %s\n", names.get(alias), alias))
          .collect(joining());
    }
  }

}
