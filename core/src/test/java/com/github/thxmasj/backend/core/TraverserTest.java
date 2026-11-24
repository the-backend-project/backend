package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class TraverserTest {

  enum States implements State {Requested, Accepted, Rejected}

  static EventType<Void, Void>
      Requested = BasicEventType.of("Requested", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a01")),
      Retried = BasicEventType.of("Retried", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a02")),
      Accepted = BasicEventType.of("Accepted", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a03")),
      Rejected = BasicEventType.of("Rejected", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a04"));

  /// Shared by the choices, as the same transition reached by more than one path
  private static final TransitionModel<Void, Void> acceptance = onEvent(Accepted).to(States.Accepted).assembleInput().output();

  private static TransitionModel<Void, Void> reject(State to) {
    return onEvent(Rejected).to(to).assembleInput().output();
  }

  private static TransitionModel<Void, Void> choosing(EventType<Void, Void> type, TransitionModel<Void, Void> rejection) {
    return onEvent(type).to(States.Requested)
        .assembleInput()
        .when(_ -> false).then(rejection)
        .otherwise(acceptance);
  }

  @Test
  public void uniquePathsAreAccepted() {
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected))),
        States.Accepted, List.of(reject(States.Rejected))
    )));
  }

  @Test
  public void sameTransitionByTwoPathsIsAccepted() {
    var rejection = reject(States.Rejected);
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, rejection), choosing(Retried, rejection))
    )));
  }

  @Test
  public void alikeTransitionsBuiltSeparatelyAreAccepted() {
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reject(States.Rejected)))
    )));
  }

  @Test
  public void twoChoicesOfTheSameEventTypeAreRejected() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reject(States.Requested)))
    )));
    assertTrue(e.getMessage().startsWith("Event type Rejected has transitions from Begin "), e.getMessage());
  }

  @Test
  public void alikeTransitionsWithDifferentReverseModelsAreRejected() {
    var reversibleRejection = onEvent(Rejected).to(States.Rejected)
        .assembleInput()
        .reversible(assemble((_, _) -> null))
        .output();
    assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reversibleRejection))
    )));
  }

  @Test
  public void choiceOfAnEventTypeTransitionedOnDirectlyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), reject(States.Requested))
    )));
  }

  static EventType<Void, Void> Viewed = BasicEventType.notStored("Viewed", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a05"));

  @Test
  public void anEventNotStoredStayingInItsStateIsAccepted() {
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(onEvent(Viewed).toSelf().output())
    )));
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(onEvent(Viewed).to(States.Accepted).output())
    )));
  }

  @Test
  public void anEventNotStoredChangingTheStateOfAStoredEntityIsRejected() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(onEvent(Retried).toSelf().assembleInput().when(_ -> true).then(onEvent(Viewed).to(States.Rejected).output()).otherwise(reject(States.Rejected)))
    )));
    assertTrue(e.getMessage().startsWith("Event type Viewed is not stored, but its transition from Accepted goes to Rejected"), e.getMessage());
  }

  /// An entity has at most one pending delayed trigger, so a transition can't trigger two
  @Test
  public void aTransitionWithTwoDelayedTriggersIsRejected() {
    var twice = onEvent(Retried).toSelf().trigger(Accepted).after(_ -> Duration.ofMinutes(1)).trigger(Rejected).after(_ -> Duration.ofMinutes(2)).output();
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(acceptance), States.Accepted, List.of(twice))));
    assertTrue(e.getMessage().startsWith("The transition on Retried triggers 2 delayed events"), e.getMessage());
  }

  static EventType<Void, Void> QuietlyRetried = BasicEventType.notStored("Quietly retried", UUID.fromString("6f8a0c2e-4b1d-4e7a-b9cb-dfe1f3a5c7e9"));

  /// A transition with a delayed trigger which triggers an event not stored of the model with a delayed trigger too is
  /// refused, as on the same entity the two would have the same number
  @Test
  public void aDelayedTriggerWithAnotherOneNumberedAlikeIsRejected() {
    var delayedThenQuiet = onEvent(Retried).toSelf()
        .trigger(Accepted).after(_ -> Duration.ofMinutes(1))
        .trigger(QuietlyRetried).on(EntityModel.of("Other", UUID.randomUUID(), Map::of)).identifiedBy(_ -> null)
        .output();
    var quiet = onEvent(QuietlyRetried).toSelf().trigger(Rejected).after(_ -> Duration.ofMinutes(2)).output();
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(acceptance), States.Accepted, List.of(delayedThenQuiet, quiet))));
    assertTrue(e.getMessage().startsWith("The transition on Retried has a delayed trigger, and triggers Quietly retried"), e.getMessage());
    var quietWithoutDelay = onEvent(QuietlyRetried).toSelf().output();
    assertDoesNotThrow(() -> new Traverser(Map.of(Begin, List.of(acceptance), States.Accepted, List.of(delayedThenQuiet, quietWithoutDelay))));
    // On another model, the two delayed triggers are on different entities
    assertDoesNotThrow(() -> new Traverser(Map.of(Begin, List.of(acceptance), States.Accepted, List.of(delayedThenQuiet, quiet)), true, EntityModel.of("This", UUID.randomUUID(), Map::of)));
    // On a group which includes the model, it may be the same entity
    var self = EntityModel.of("Other", UUID.randomUUID(), Map::of);
    var onGroup = onEvent(Retried).toSelf()
        .trigger(Accepted).after(_ -> Duration.ofMinutes(1))
        .trigger(QuietlyRetried).on(EntityModelGroup.of("Group", UUID.randomUUID(), m -> m == self)).identifiedBy(_ -> null)
        .output();
    assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(acceptance), States.Accepted, List.of(onGroup, quiet)), true, self));
  }

  /// All the refusals of a model are reported together, so it is fixed in one go
  @Test
  public void allRefusalsAreReportedTogether() {
    var twice = onEvent(Retried).toSelf().trigger(Accepted).after(_ -> Duration.ofMinutes(1)).trigger(Rejected).after(_ -> Duration.ofMinutes(2)).output();
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(twice),
        States.Rejected, List.of(onEvent(Retried).to(States.Requested).output())
    )));
    assertTrue(e.getMessage().startsWith("2 refusals of the transitions:"), e.getMessage());
    assertTrue(e.getMessage().contains("- The transition on Retried triggers 2 delayed events"), e.getMessage());
    assertTrue(e.getMessage().contains("- State Rejected is not reached from Begin"), e.getMessage());
  }

  /// A transition from `Begin` creates the entity, so it can't stay in `Begin`
  @Test
  public void aTransitionFromBeginStayingInBeginIsRejected() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(Begin, List.of(onEvent(Accepted).toSelf().output()))));
    assertTrue(e.getMessage().startsWith("The transition on Accepted from Begin stays in Begin"), e.getMessage());
  }

  /// Every entity is created from `Begin`, so a model without transitions from it, or with a state it doesn't lead to,
  /// is refused
  @Test
  public void aStateNotReachedFromBeginIsRejected() {
    var noBegin = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        States.Accepted, List.of(onEvent(Viewed).to(States.Rejected).output())
    )));
    assertTrue(noBegin.getMessage().startsWith("The model has transitions, but none from Begin"), noBegin.getMessage());
    var unreached = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(),
        States.Rejected, List.of(onEvent(Viewed).to(States.Requested).output())
    )));
    assertTrue(unreached.getMessage().startsWith("State Rejected is not reached from Begin"), unreached.getMessage());
  }

  @Test
  public void anEventNotStoredCreatingAnEntityOrOnAnEntityNotStoredMayChangeItsState() {
    assertDoesNotThrow(() -> new Traverser(Map.of(Begin, List.of(onEvent(Viewed).to(States.Accepted).output()))));
    // The entities of a stored model created by an event not stored are of the session only, so the states only they
    // reach may be left by events not stored
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(onEvent(Viewed).to(States.Requested).output()),
        States.Requested, List.of(onEvent(Viewed).to(States.Accepted).output())
    )));
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(acceptance),
        States.Accepted, List.of(onEvent(Viewed).to(States.Rejected).output())
    ), false));
  }

}
