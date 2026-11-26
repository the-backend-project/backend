package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/// The checks of the entity models when a state machine is created, which fail before the database is used
public class StateMachineModelsTest {

  enum States implements State {Created}

  static EventType<Void, Void>
      Created = BasicEventType.of("Created", UUID.fromString("7d3a1b2c-5e4f-4a6b-8c9d-0e1f2a3b4c01")),
      Reminded = BasicEventType.of("Reminded", UUID.fromString("7d3a1b2c-5e4f-4a6b-8c9d-0e1f2a3b4c02")),
      Expired = BasicEventType.of("Expired", UUID.fromString("7d3a1b2c-5e4f-4a6b-8c9d-0e1f2a3b4c03"));

  private static StateMachine stateMachine(List<EntityModel> models) {
    return new StateMachine(null, models, null, null, null, null, null, null, null, false);
  }

  /// A model whose transitions supplier fails doesn't hide the refusals of another model, so both are fixed in one go
  @Test
  public void aFailingSupplierAndARefusedModelAreBothReported() {
    var failure = new IllegalStateException("Not configured");
    var failing = EntityModel.of("Failing", UUID.randomUUID(), () -> {throw failure;});
    var refused = EntityModel.of("Refused", UUID.randomUUID(), () -> Map.of(
        Begin, List.of(onEvent(Created).to(States.Created)
            .trigger(Reminded).after(_ -> Duration.ofMinutes(1))
            .trigger(Expired).after(_ -> Duration.ofMinutes(2))
            .output())
    ));
    var e = assertThrows(IllegalArgumentException.class, () -> stateMachine(List.of(failing, refused)));
    assertEquals(
        "The transitions of entity model Failing can't be created: java.lang.IllegalStateException: Not configured\n" +
            "The transitions of entity model Refused are refused: The transition on Created triggers 2 delayed events, " +
            "but an entity has at most one pending delayed trigger, so the second would replace the first",
        e.getMessage()
    );
    assertSame(failure, e.getSuppressed()[0]);
  }

  /// Two instances of a model with the same id are refused, as they may differ
  @Test
  public void twoModelsWithTheSameIdAreRefused() {
    var id = UUID.randomUUID();
    var e = assertThrows(IllegalArgumentException.class, () -> stateMachine(List.of(
        EntityModel.of("First", id, Map::of),
        EntityModel.of("Second", id, Map::of)
    )));
    assertTrue(e.getMessage().startsWith("Entity models First and Second have the same id"), e.getMessage());
  }
}
