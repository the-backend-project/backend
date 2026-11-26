package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.Replay.Step;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ReplayTest {

  enum States implements State {Waiting, Answered, Placed}

  static final EventType<Void, Void> Received = BasicEventType.of("Received", UUID.randomUUID());
  static final EventType<Void, Void> Answered = BasicEventType.of("Answered", UUID.randomUUID());
  static final EventType<Void, Void> Placed = BasicEventType.of("Placed", UUID.randomUUID());

  static final EntityModel Order = EntityModel.of("Order", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Placed).to(States.Placed).trigger(Answered).on(ReplayTest.Requests).identifiedBy(entityIdFromSession()).output()
      ),
      States.Placed, List.of()
  ));

  static final EntityModel Request = EntityModel.of("Request", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Received).to(States.Waiting).trigger(Placed).on(Order).identifiedBy(newEntityId()).output()
      ),
      States.Waiting, List.<TransitionModel<?, ?>>of(onEvent(Answered).to(States.Answered).output()),
      States.Answered, List.of()
  ));

  static final EntityModelGroup Requests = EntityModelGroup.of("Requests", UUID.randomUUID(), Request::equals);

  private static final ZonedDateTime time = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC);

  private static EventLog log(EntityModel model, UUID id, ZonedDateTime... timestamps) {
    List<EventType<Void, Void>> types = model == Order ? List.of(Placed) : List.of(Received, Answered);
    List<Event<?>> events = new ArrayList<>();
    for (int i = 0; i < timestamps.length; i++)
      events.add(new Event<>(id, i + 1, types.get(i), timestamps[i]));
    return new EventLog(model, id, List.copyOf(events));
  }

  @Test
  void eventsOfTheSameTransactionAreTriggeredByTheTriggersOfTheirTypeAndModel() {
    EventLog order = log(Order, UUID.randomUUID(), time);
    EventLog request = log(Request, UUID.randomUUID(), time, time);
    Replay replay = Replay.of(order, List.of(request));
    Step received = replay.roots().getFirst();
    assertEquals(1, replay.roots().size());
    assertSame(request, received.log());
    Step placed = received.triggered().getFirst().step().orElseThrow();
    assertSame(order, placed.log());
    assertSame(received, placed.cause().orElseThrow());
    // Triggered on the group of the request
    Step answered = placed.triggered().getFirst().step().orElseThrow();
    assertEquals(Answered, answered.event().type());
  }

  enum RoutedStates implements State {Routed, Accepted}

  static final EventType<Void, Void> Arrived = BasicEventType.of("Arrived", UUID.randomUUID());
  static final EventType<Void, Void> Routed = BasicEventType.of("Routed", UUID.randomUUID());
  static final EventType<Void, Void> Submitted = BasicEventType.of("Submitted", UUID.randomUUID());
  static final EventType<Void, Void> Accepted = BasicEventType.of("Accepted", UUID.randomUUID());

  /// A request stored as accepted when it is submitted, which is triggered with the type starting its choice
  static final EntityModel Submission = EntityModel.of("Submission", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Submitted).to(RoutedStates.Accepted).assembleInput()
              .when(_ -> false).then(onEvent(Routed).to(RoutedStates.Accepted).output())
              .otherwise(onEvent(Accepted).to(RoutedStates.Accepted).output())
      ),
      RoutedStates.Accepted, List.of()
  ));

  /// Routes what arrives to a submission, like the routing of an inbox, and isn't stored
  static final EntityModel Router = EntityModel.notStored("Router", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Arrived).to(RoutedStates.Routed).assembleInput()
              .when(_ -> false).then(onEvent(Accepted).to(RoutedStates.Routed).output())
              .otherwise(onEvent(Routed).to(RoutedStates.Routed)
                  .trigger(Submitted).on(Submission).identifiedBy(newEntityId())
                  .output())
      ),
      RoutedStates.Routed, List.of()
  ));

  @Test
  void theStepOfAnEntityWhichIsNotStoredIsDeducedFromTheOnlyTransitionTriggeringTheEvent() {
    UUID id = UUID.randomUUID();
    EventLog submission = new EventLog(Submission, id, List.of(new Event<>(id, 1, Accepted, time)));
    Replay replay = Replay.of(submission, List.of(), List.of(Router, Submission));
    Step routed = replay.roots().getFirst();
    assertTrue(routed.isDeduced());
    assertEquals(Routed, routed.event().type());
    assertSame(Router, routed.log().entityModel());
    // The trigger of Submitted takes the choice storing Accepted
    assertSame(submission, routed.triggered().getFirst().step().orElseThrow().log());
  }

  @Test
  void nothingIsDeducedWithoutTheEntityModels() {
    UUID id = UUID.randomUUID();
    EventLog submission = new EventLog(Submission, id, List.of(new Event<>(id, 1, Accepted, time)));
    Step root = Replay.of(submission).roots().getFirst();
    assertSame(submission, root.log());
  }

  enum OutboxStates implements State {Waiting, Done}

  static final EventType<Void, Void> Sent = BasicEventType.of("Sent", UUID.randomUUID());
  static final EventType<Void, Void> Replied = BasicEventType.of("Replied", UUID.randomUUID());
  static final EventType<Void, Void> Expired = BasicEventType.of("Expired", UUID.randomUUID());

  static final Action<Void> Forward = new Action<>() {
    @Override public String name() {return "Forward";}
    @Override public Mono<InputEvent<?>> execute(Void data) {return Mono.just(new InputEvent<>(Replied, null));}
  };

  /// Sends a request by an action, whose outcome is Replied, and expires by a delayed trigger, like an outbox request
  static final EntityModel Outbox = EntityModel.of("Outbox", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Sent).to(OutboxStates.Waiting).assembleInput()
              .trigger(Expired).with(d -> d).after(_ -> Duration.ofSeconds(5))
              .trigger(Forward).with(_ -> null)
              .output()
      ),
      OutboxStates.Waiting, List.<TransitionModel<?, ?>>of(
          onEvent(Replied).to(OutboxStates.Done).output(),
          onEvent(Expired).to(OutboxStates.Done).output()
      ),
      OutboxStates.Done, List.of()
  ));

  private static EventLog outbox(EventType<Void, Void> outcome) {
    UUID id = UUID.randomUUID();
    return new EventLog(Outbox, id, List.of(new Event<>(id, 1, Sent, time), new Event<>(id, 2, outcome, time.plusSeconds(1))));
  }

  @Test
  void anEventNoTransitionTriggersInALaterTransactionIsTheOutcomeOfAnAction() {
    Replay replay = Replay.of(outbox(Replied));
    Step sent = replay.steps().getFirst();
    Step replied = replay.steps().getLast();
    assertSame(sent, replied.outcomeOf().orElseThrow());
    assertSame(replied, sent.performed().getFirst().outcome().orElseThrow());
  }

  @Test
  void theOutcomeOfAnActionIsInANewTransactionOfTheSameSession() {
    Replay replay = Replay.of(outbox(Replied));
    assertEquals(List.of(1, 2), replay.steps().stream().map(Step::transaction).toList());
    assertEquals(List.of(1, 1), replay.steps().stream().map(Step::session).toList());
  }

  @Test
  void aDelayedEventStartsASessionOfItsOwn() {
    Replay replay = Replay.of(outbox(Expired));
    assertEquals(List.of(1, 2), replay.steps().stream().map(Step::session).toList());
  }

  @Test
  void aDelayedEventIsNotTheOutcomeOfAnAction() {
    Replay replay = Replay.of(outbox(Expired));
    assertTrue(replay.steps().getLast().outcomeOf().isEmpty());
    assertTrue(replay.steps().getFirst().performed().getFirst().outcome().isEmpty());
  }

  @Test
  void anEventOfAnotherTransactionIsARoot() {
    EventLog order = log(Order, UUID.randomUUID(), time.plusSeconds(1));
    EventLog request = log(Request, UUID.randomUUID(), time);
    Replay replay = Replay.of(order, List.of(request));
    assertEquals(2, replay.roots().size());
    assertTrue(replay.roots().getFirst().triggered().getFirst().step().isEmpty());
  }

}
