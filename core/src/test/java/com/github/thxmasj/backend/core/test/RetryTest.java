package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// An event which fails on the event number, as another instance appended an event to the entity first, is calculated
/// again from the new event log
@Tag("database")
public class RetryTest {

  enum States implements State { Counting }

  static EventType<String, String> CountingStarted = BasicEventType.of("Counting started", UUID.fromString("5d0c8a3e-7b21-4f6a-9e4d-1c3b5a7f9e02"), DataType.string());
  static EventType<String, String> Counted = BasicEventType.of("Counted", UUID.fromString("a2e4c6f8-0b1d-4e3f-8a5c-7d9e1f3b5c14"), DataType.string());

  static EntityModel Counter = EntityModel.of("Counter", UUID.fromString("3f7b9d1e-5a2c-4c8e-b0f4-6e8a0c2d4f26"), RetryTest::counterTransitions);

  private static Map<State, List<TransitionModel<?, ?>>> counterTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(CountingStarted).to(States.Counting).assembleInput().output(d -> d)),
        States.Counting, List.of(onEvent(Counted).to(States.Counting).assembleInput().output(d -> d))
    );
  }

  @Test
  public void eventIsCalculatedAgainFromTheNewEventLogWhenAnotherInstanceAppendedFirst() {
    String schema = UUID.randomUUID().toString();
    StateMachine other = Init.stateMachine(schema, List.of(Counter));
    UUID counterId = other.onEvent(trigger(CountingStarted, Counter), "start").blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    // The other instance appends an event to the counter after this one read its event log, just before this one
    // stores its event, once
    AtomicBoolean appended = new AtomicBoolean();
    StateMachine machine = Init.stateMachine(schema, List.of(Counter), Clock.systemUTC(), new Logger("Test"), keys ->
        keys.contains(correlationId + "-" + Counted.id()) && appended.compareAndSet(false, true) ?
            other.onEvent(trigger(Counted, Counter, counterId), "other").next().then() :
            Mono.empty()
    );

    Event<?> event = machine.onEvent(correlationId, trigger(Counted, Counter, counterId), "this")
        .blockFirst(Duration.ofSeconds(20));

    assertTrue(appended.get());
    assertEquals("this", event.getUnmarshalledData());
    // After the counter's first event and the one the other instance appended
    assertEquals(3, event.eventNumber().intValue());
  }

}
