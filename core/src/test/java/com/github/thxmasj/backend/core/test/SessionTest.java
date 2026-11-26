package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// A session is the transactions an event leads to through actions. A transaction processing the outcome of an
/// action finds the entities of the session in the transactions before it, and emits its events to the one waiting
/// for them. See docs/sessions.md.
@Tag("database")
public class SessionTest {

  enum RequestStates implements State {Waiting, Answered}

  enum WorkerStates implements State {Once, Twice1, Twice2, ToGroup1, ToGroup2, ToNote1, ToNote2, Done}

  enum NoteStates implements State {Noted, Recalled}

  static EntityModel Request = EntityModel.of("Request", id(1), SessionTest::requestTransitions);
  static EntityModel Worker = EntityModel.of("Worker", id(2), SessionTest::workerTransitions);
  /// An entity of the session only, never stored
  static EntityModel Note = EntityModel.notStored("Note", id(3), SessionTest::noteTransitions);
  /// Stands for the request without naming its model, as `HttpInbox.Request` does for the inbox requests
  static EntityModelGroup Requests = EntityModelGroup.of("Requests", id(4), Request::equals);

  static EventType<Void, Void> StartedOnce = event("Started once", 10);
  static EventType<Void, Void> StartedTwice = event("Started twice", 11);
  static EventType<Void, Void> StartedTwiceToGroup = event("Started twice to group", 12);
  static EventType<Void, Void> StartedTwiceToNote = event("Started twice to note", 13);
  static EventType<Void, Void> Answered = event("Answered", 14);
  static EventType<Void, Void> HopOnce = event("Hop once", 15);
  static EventType<Void, Void> HopTwice = event("Hop twice", 16);
  static EventType<Void, Void> HopTwiceToGroup = event("Hop twice to group", 17);
  static EventType<Void, Void> HopTwiceToNote = event("Hop twice to note", 18);
  static EventType<Void, Void> NextHop = event("Next hop", 19);
  static EventType<Void, Void> HopsDone = event("Hops done", 20);
  static EventType<Void, Void> Noted = event("Noted", 21);
  static EventType<Void, Void> Recalled = event("Recalled", 22);

  static Action<Void> next = action("Next", NextHop);
  static Action<Void> done = action("Done", HopsDone);

  private static final StateMachine machine = Init.stateMachine(UUID.randomUUID().toString(), List.of(Request, Worker, Note));

  private static UUID id(int n) {
    return UUID.fromString("5e55e0a1-1a2b-4c3d-8e4f-%012d".formatted(n));
  }

  private static EventType<Void, Void> event(String name, int n) {
    return EventType.of(name, id(n));
  }

  /// An action whose outcome is the event, as of an outbox request getting its response
  private static Action<Void> action(String name, EventType<Void, Void> outcome) {
    return new Action<>() {
      @Override public String name() {return name;}
      @Override public Mono<InputEvent<?>> execute(Void data) {return Mono.just(new InputEvent<>(outcome, null));}
    };
  }

  private static Map<State, List<TransitionModel<?, ?>>> requestTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            start(StartedOnce, HopOnce),
            start(StartedTwice, HopTwice),
            start(StartedTwiceToGroup, HopTwiceToGroup),
            onEvent(StartedTwiceToNote).to(RequestStates.Waiting)
                .trigger(Noted).with(_ -> null).on(Note).identifiedBy(newEntityId())
                .trigger(HopTwiceToNote).with(_ -> null).on(Worker).identifiedBy(newEntityId())
                .output()
        ),
        RequestStates.Waiting, List.of(onEvent(Answered).to(RequestStates.Answered).output()),
        RequestStates.Answered, List.of()
    );
  }

  private static TransitionModel<?, ?> start(EventType<Void, Void> started, EventType<Void, Void> hop) {
    return onEvent(started).to(RequestStates.Waiting)
        .trigger(hop).with(_ -> null).on(Worker).identifiedBy(newEntityId())
        .output();
  }

  private static Map<State, List<TransitionModel<?, ?>>> workerTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(HopOnce).to(WorkerStates.Once).assemble(c -> c.input()).trigger(done).with(_ -> null).output(),
            onEvent(HopTwice).to(WorkerStates.Twice1).assemble(c -> c.input()).trigger(next).with(_ -> null).output(),
            onEvent(HopTwiceToGroup).to(WorkerStates.ToGroup1).assemble(c -> c.input()).trigger(next).with(_ -> null).output(),
            onEvent(HopTwiceToNote).to(WorkerStates.ToNote1).assemble(c -> c.input()).trigger(next).with(_ -> null).output()
        ),
        WorkerStates.Twice1, List.of(secondHop(WorkerStates.Twice2)),
        WorkerStates.ToGroup1, List.of(secondHop(WorkerStates.ToGroup2)),
        WorkerStates.ToNote1, List.of(secondHop(WorkerStates.ToNote2)),
        WorkerStates.Once, List.of(answer(Request)),
        WorkerStates.Twice2, List.of(answer(Request)),
        WorkerStates.ToGroup2, List.of(answer(Requests)),
        WorkerStates.ToNote2, List.of(
            onEvent(HopsDone).to(WorkerStates.Done)
                .trigger(Recalled).with(_ -> null).on(Note).identifiedBy(entityIdFromSession())
                .output()
        ),
        WorkerStates.Done, List.of()
    );
  }

  private static TransitionModel<?, ?> secondHop(WorkerStates to) {
    return onEvent(NextHop).to(to).assemble(c -> c.input()).trigger(done).with(_ -> null).output();
  }

  /// Answers the request of the session, not knowing which one it is
  private static TransitionModel<?, ?> answer(EntityModel request) {
    return onEvent(HopsDone).to(WorkerStates.Done)
        .trigger(Answered).with(_ -> null).on(request).identifiedBy(entityIdFromSession())
        .output();
  }

  /// A note taken in the first transaction of the session, recalled in the last, which answers the request
  private static Map<State, List<TransitionModel<?, ?>>> noteTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(onEvent(Noted).to(NoteStates.Noted).output()),
        NoteStates.Noted, List.of(
            onEvent(Recalled).to(NoteStates.Recalled)
                .trigger(Answered).with(_ -> null).on(Request).identifiedBy(entityIdFromSession())
                .output()
        ),
        NoteStates.Recalled, List.of()
    );
  }

  private static List<String> eventsOfRequest(EventType<Void, Void> started) {
    return machine.onEvent(trigger(started, Request))
        .take(2)
        .take(Duration.ofSeconds(10))
        .map(Event::typeName)
        .collectList()
        .block();
  }

  @Test
  public void entityOfSessionIsFoundOneActionBack() {
    assertEquals(List.of(StartedOnce.name(), Answered.name()), eventsOfRequest(StartedOnce));
  }

  @Test
  public void entityOfSessionIsFoundTwoActionsBack() {
    assertEquals(List.of(StartedTwice.name(), Answered.name()), eventsOfRequest(StartedTwice));
  }

  @Test
  public void entityOfGroupIsFoundTwoActionsBack() {
    assertEquals(List.of(StartedTwiceToGroup.name(), Answered.name()), eventsOfRequest(StartedTwiceToGroup));
  }

  @Test
  public void entityOfSessionOnlyIsFoundTwoActionsBack() {
    assertEquals(List.of(StartedTwiceToNote.name(), Answered.name()), eventsOfRequest(StartedTwiceToNote));
  }

}
