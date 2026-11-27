package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityType.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EntityTypeGroup;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityRelationshipDiagramFormatterTest {

  enum States implements State {Moving, Stopped}

  static final EventType<Void, Void> CarStarted = EventType.of("Car started", UUID.randomUUID());
  static final EventType<Void, Void> CarStopped = EventType.of("Car stopped", UUID.randomUUID());
  static final EventType<Void, Void> SpeedIncreased = EventType.of("Speed increased", UUID.randomUUID());
  static final EventType<Void, Void> SpeedDecreased = EventType.of("Speed decreased", UUID.randomUUID());
  static final EventType<String, String> NameClaimed = EventType.of("Name claimed", UUID.randomUUID(), DataType.string());

  static final EntityType Car = EntityType.of("Car", UUID.randomUUID(), EntityRelationshipDiagramFormatterTest::carTransitions);
  static final EntityType Speed = EntityType.of("Speed", UUID.randomUUID(), EntityRelationshipDiagramFormatterTest::speedTransitions);
  static final EntityType Engine = EntityType.of("Engine", UUID.randomUUID(), Map::of);
  static final IdEntityType Ids = IdEntityType.of("Ids", UUID.randomUUID(), NameClaimed);

  static List<EntityType> model() {
    return List.of(Car, Speed, Ids);
  }

  private static Map<State, List<TransitionModel<?, ?>>> carTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(CarStarted).to(States.Moving)
                .trigger(SpeedIncreased).on(Speed).identifiedBy(newEntityId())
                .reversible(
                    assemble((_, _) -> UUID.randomUUID())
                        .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                )
                .output()
        ),
        States.Moving, List.of(
            onEvent(CarStopped).to(States.Stopped)
                .trigger(SpeedDecreased).on(Speed).identifiedBy(newEntityId())
                .trigger(CarStopped).on(Engine).identifiedBy(newEntityId())
                .output()
        ),
        States.Stopped, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> speedTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(SpeedIncreased).toSelf().output(),
            onEvent(SpeedDecreased).toSelf().output()
        )
    );
  }

  @Test
  void entitiesWithTheirEventTypes() {
    String diagram = new EntityRelationshipDiagramFormatter("Test", model()).format();
    assertThat(diagram).contains(
        """
        entity "Car" as E1 {
          {field} Car started
          {field} Car stopped
        }
        """,
        """
        entity "Speed" as E3 {
          {field} Speed increased
          {field} Speed decreased
        }
        """
    );
  }

  @Test
  void entitiesOutsideTheModelAreIncludedWithTheEventTypesTriggeredOnThem() {
    assertThat(new EntityRelationshipDiagramFormatter("Test", model()).format()).contains(
        """
        entity "Engine" as E2 {
          {field} Car stopped
        }
        """
    );
  }

  @Test
  void triggersRelateEntities() {
    String diagram = new EntityRelationshipDiagramFormatter("Test", model()).format();
    assertThat(diagram).contains(
        "E1 --> E3 : Car started ↪ Speed increased\\n<color:#7A0000>Car started ↪ Speed decreased</color>\\nCar stopped ↪ Speed decreased\n",
        "E1 --> E2 : Car stopped ↪ Car stopped\n"
    );
    assertThat(diagram).doesNotContain("E2 -->", "E3 -->");
  }

  @Test
  void idAndIndexEntitiesAreLeftOut() {
    assertThat(new EntityRelationshipDiagramFormatter("Test", model()).format()).doesNotContain("Ids", "Name claimed", "E4");
  }

  /// Like the domain entities of a bounded context, without its inbox and outbox requests
  @Test
  void onlyTheEntitiesGivenAndTheRelationsBetweenThemAreShown() {
    String diagram = new EntityRelationshipDiagramFormatter("Test", model()).only(Car, Speed).format();
    assertThat(diagram).contains("entity \"Car\"", "entity \"Speed\"", "Car started ↪ Speed increased")
        .doesNotContain("Engine", "Car stopped ↪ Car stopped");
  }

  @Test
  void compactDiagramOmitsTheEventTypesOfTheEntities() {
    String diagram = new EntityRelationshipDiagramFormatter("Test", model()).compact().format();
    assertThat(diagram).contains(
        "entity \"Car\" as E1 {\n}\n",
        "entity \"Engine\" as E2 {\n}\n",
        "entity \"Speed\" as E3 {\n}\n",
        "E1 --> E2 : Car stopped ↪ Car stopped\n"
    );
    assertThat(diagram).doesNotContain("{field}");
  }

  @Test
  void compactDiagramRendersAsAnERDiagram(@TempDir Path directory) throws IOException {
    File file = new EntityRelationshipDiagramFormatter("Test", model()).compact().formatToImage(directory.toString());
    // The relations are links between entities, not messages between the participants of a sequence diagram
    assertThat(Files.readString(file.toPath())).contains("class=\"link\"").doesNotContain("class=\"message\"");
  }

  @Test
  void rendersToSvg(@TempDir Path directory) throws IOException {
    File file = new EntityRelationshipDiagramFormatter("Test", model()).formatToImage(directory.toString());
    assertThat(file.getName()).isEqualTo("Test.er.svg");
    assertThat(Files.readString(file.toPath())).startsWith("<svg").contains("Car", "Speed", "Engine")
        // Drawn in the colour scheme of the page, so dark mode doesn't put it on an opaque background
        .contains("svg{color-scheme:light dark}");
  }

  static final EntityType Garage = EntityType.of("Garage", UUID.randomUUID(), EntityRelationshipDiagramFormatterTest::garageTransitions);
  static final EntityType Workshop = EntityType.of("Workshop", UUID.randomUUID(), EntityRelationshipDiagramFormatterTest::workshopTransitions);
  static final EntityTypeGroup Places = EntityTypeGroup.of("Places", UUID.randomUUID(), m -> m == Garage || m == Workshop);
  static final EventType<Void, Void> CarParked = EventType.of("Car parked", UUID.randomUUID());
  /// A car which parks in a place
  static final EntityType ParkedCar = EntityType.of("Car", UUID.randomUUID(), EntityRelationshipDiagramFormatterTest::parkedCarTransitions);

  /// A model where the car triggers an event on whichever place of the session it is in
  static List<EntityType> modelWithGroup() {
    return List.of(ParkedCar, Garage, Workshop);
  }

  private static Map<State, List<TransitionModel<?, ?>>> parkedCarTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(CarStopped).to(States.Stopped)
                .trigger(CarParked).on(Places).identifiedBy(EntitySelector.entityIdFromSession())
                .output()
        ),
        States.Stopped, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> garageTransitions() {
    return Map.of(Begin, List.of(onEvent(CarParked).toSelf().output()));
  }

  private static Map<State, List<TransitionModel<?, ?>>> workshopTransitions() {
    return Map.of(Begin, List.of(onEvent(CarParked).toSelf().output()));
  }

  @Test
  void triggerOnGroupRelatesToEachEntityOfTheGroup() {
    String diagram = new EntityRelationshipDiagramFormatter("Test", modelWithGroup()).format();
    assertThat(diagram)
        .contains("E1 --> E2 : Car stopped ↪ Car parked\n", "E1 --> E3 : Car stopped ↪ Car parked\n")
        .contains("entity \"Garage\" as E2", "entity \"Workshop\" as E3")
        .doesNotContain("Places");
  }

  @Test
  void triggerOnGroupIsDrawnInStateDiagram() {
    // The details of the arrow rather than the SVG, which only shows them where Graphviz does the layout
    var labels = new StateDiagramFormatter(ParkedCar).diagram().labelsBySourceLine().values();
    assertThat(labels.stream().flatMap(List::stream).flatMap(label -> label.details().stream()))
        .contains("<&share>Car parked@Places");
  }

}
