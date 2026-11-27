package com.github.thxmasj.backend.diagram;

import static java.util.Comparator.comparing;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EntityTypeGroup;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityType;
import com.github.thxmasj.backend.core.IndexEntityType;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

/// An entity relationship diagram of a bounded context, i.e. a state machine's complete model: the transitions of all
/// its entity types, as given to the state machine. Each entity type is an entity listing the event types it accepts,
/// and the entity types are related through the triggers of their transitions: an arrow from one entity to another
/// tells that an event on the first triggers an event on the other. A trigger on an [EntityTypeGroup] relates to each
/// entity type of the group in the model. Triggers of reversals are red. Id and index entities are left out, with the
/// triggers of events on them, as they take much space and tell little about how the entities relate.
public class EntityRelationshipDiagramFormatter {

  private final String name;
  private final List<EntityType> entityTypes;
  private final boolean compact;
  /// The entity types shown, or null for all but id and index entities
  private final Set<EntityType> only;

  /// @param name the name of the model, used for the names of the files the diagram is written to
  /// @param entityTypes the entity types given to the state machine, which with those their transitions trigger events
  ///                     on are the entities of the diagram
  public EntityRelationshipDiagramFormatter(String name, List<? extends EntityType> entityTypes) {
    this(name, EntityType.reachable(entityTypes), false, null);
  }


  private EntityRelationshipDiagramFormatter(
      String name,
      List<EntityType> entityTypes,
      boolean compact,
      Set<EntityType> only
  ) {
    this.name = name;
    this.entityTypes = entityTypes;
    this.compact = compact;
    this.only = only;
  }

  /// This formatter without the event types of the entities, for models with many entities or event types. The event
  /// types involved in the triggers are still shown on the relations.
  public EntityRelationshipDiagramFormatter compact() {
    return new EntityRelationshipDiagramFormatter(name, entityTypes, true, only);
  }

  /// This formatter with only the entity types, and the relations between them, e.g. those of the domain, without the
  /// inbox and outbox requests, which relate to most of them
  public EntityRelationshipDiagramFormatter only(EntityType... entityTypes) {
    return new EntityRelationshipDiagramFormatter(name, this.entityTypes, compact, Set.of(entityTypes));
  }

  public File formatToFile(String directory) throws IOException {
    File file = file(directory, "puml");
    Files.writeString(file.toPath(), format());
    return file;
  }

  public File formatToImage(String directory) throws IOException {
    File file = file(directory, "svg");
    var svg = new ByteArrayOutputStream();
    new SourceStringReader(format()).outputImage(svg, new FileFormatOption(FileFormat.SVG));
    Files.writeString(file.toPath(), DiagramColours.addStyle(svg.toString(UTF_8)));
    return file;
  }

  private File file(String directory, String suffix) {
    var file = new File(String.format("%s/%s.er.%s", directory, name, suffix));
    //noinspection ResultOfMethodCallIgnored
    file.getParentFile().mkdirs();
    return file;
  }

  private record Relation(EntityType from, EntityType to, String label) {}

  public String format() {
    // Entities triggered by the model without being part of it are included too, with the event types triggered on them
    Map<EntityType, Set<EventType<?, ?>>> eventTypes = new LinkedHashMap<>();
    Set<Relation> relations = new LinkedHashSet<>();
    entityTypes.stream()
        .filter(this::shown)
        .sorted(comparing(EntityType::name))
        .forEach(model -> {
          eventTypes.computeIfAbsent(model, _ -> new LinkedHashSet<>());
          model.transitions().entrySet().stream()
              .sorted(comparing(e -> e.getKey().name()))
              .flatMap(e -> e.getValue().stream())
              .forEach(transition -> collect(model, transition, eventTypes, relations));
        });
    List<EntityType> entities = new ArrayList<>(eventTypes.keySet());
    entities.sort(comparing(EntityType::name));
    Map<EntityType, String> aliases = new LinkedHashMap<>();
    entities.forEach(entity -> aliases.put(entity, "E" + (aliases.size() + 1)));
    return String.format(
        """
        @startuml
        skinparam backgroundColor transparent
        skinparam class {
          BackgroundColor #9bb08a
          BorderColor #4b5320
          ArrowColor #4b5320
        }
        hide circle
        hide empty members

        %s
        %s
        @enduml
        """,
        entities.stream()
            .map(entity -> entity(entity, aliases.get(entity), eventTypes.get(entity)))
            .collect(joining()),
        mergeParallelRelations(relations).stream()
            .map(r -> String.format("%s --> %s : %s\n", aliases.get(r.from()), aliases.get(r.to()), r.label()))
            .collect(joining())
    );
  }

  private void collect(
      EntityType entity,
      TransitionModel<?, ?> transition,
      Map<EntityType, Set<EventType<?, ?>>> eventTypes,
      Set<Relation> relations
  ) {
    eventTypes.computeIfAbsent(entity, _ -> new LinkedHashSet<>()).add(transition.eventType());
    collectTriggers(entity, transition.eventType(), transition.triggers(), false, eventTypes, relations);
    if (transition.reverseModel() != null)
      collectTriggers(entity, transition.eventType(), transition.reverseModel().triggers(), true, eventTypes, relations);
    for (var filter : transition.filters())
      collect(entity, filter.alternative().model(), eventTypes, relations);
  }

  private void collectTriggers(
      EntityType entity,
      EventType<?, ?> eventType,
      List<EventTrigger<?, ?, ?>> triggers,
      boolean reversal,
      Map<EntityType, Set<EventType<?, ?>>> eventTypes,
      Set<Relation> relations
  ) {
    for (EventTrigger<?, ?, ?> trigger : triggers) {
      EventType<?, ?> triggered = trigger.eventType();
      String label = eventType.name() + " ↪ " + triggered.name();
      for (EntityType target : targets(trigger.entityType() != null ? trigger.entityType() : entity)) {
        if (!shown(target)) continue;
        eventTypes.computeIfAbsent(target, _ -> new LinkedHashSet<>()).add(triggered);
        relations.add(new Relation(entity, target, reversal ? "<color:" + DiagramColours.REVERSAL.light() + ">" + label + "</color>" : label));
      }
    }
  }

  /// The entities a trigger on the target relates to: the entity types of the model in the group, if the target is a
  /// group with any of them, otherwise the target itself
  private List<EntityType> targets(EntityType target) {
    if (!(target instanceof EntityTypeGroup group))
      return List.of(target);
    List<EntityType> members = entityTypes.stream()
        .filter(group::includes)
        .sorted(comparing(EntityType::name))
        .toList();
    return members.isEmpty() ? List.of(target) : members;
  }

  // Combines the relations between the same pair of entities into one arrow with the labels stacked, as parallel
  // arrows make the diagram hard to read.
  private static List<Relation> mergeParallelRelations(Set<Relation> relations) {
    Map<List<EntityType>, List<String>> labels = new LinkedHashMap<>();
    for (Relation relation : relations)
      labels.computeIfAbsent(List.of(relation.from(), relation.to()), _ -> new ArrayList<>()).add(relation.label());
    return labels.entrySet().stream()
        .map(e -> new Relation(e.getKey().get(0), e.getKey().get(1), String.join("\\n", e.getValue())))
        .toList();
  }

  private boolean shown(EntityType entity) {
    if (only != null)
      return only.contains(entity);
    return !(entity instanceof IdEntityType) && !(entity instanceof IndexEntityType);
  }

  private String entity(EntityType entity, String alias, Set<EventType<?, ?>> eventTypes) {
    String declaration = String.format("entity \"%s\" as %s", entity.name(), alias);
    // An entity without a body would make PlantUML take it as a participant of a sequence diagram
    if (compact) return declaration + " {\n}\n";
    return String.format(
        "%s {\n%s}\n",
        declaration,
        eventTypes.stream()
            .map(eventType -> "  {field} " + eventType.name() + dataTypes(eventType) + "\n")
            .collect(joining())
    );
  }

  private static String dataTypes(EventType<?, ?> eventType) {
    if (eventType.inputDataType().equals(DataType.none()) && eventType.outputDataType().equals(DataType.none()))
      return "";
    return " : " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name();
  }

}
