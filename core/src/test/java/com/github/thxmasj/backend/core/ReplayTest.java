package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.Replay.Step;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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

  @Test
  void anEventOfAnotherTransactionIsARoot() {
    EventLog order = log(Order, UUID.randomUUID(), time.plusSeconds(1));
    EventLog request = log(Request, UUID.randomUUID(), time);
    Replay replay = Replay.of(order, List.of(request));
    assertEquals(2, replay.roots().size());
    assertTrue(replay.roots().getFirst().triggered().getFirst().step().isEmpty());
  }

}
