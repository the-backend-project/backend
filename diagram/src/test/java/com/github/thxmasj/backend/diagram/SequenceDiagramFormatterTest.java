package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.Rollback;
import com.github.thxmasj.backend.core.BuiltinEventTypes;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class SequenceDiagramFormatterTest {

  enum States implements State {Placed, Accepted, Refused, Waiting, Answered}

  static final EventType<Void, Void> Placed = BasicEventType.of("Placed", UUID.randomUUID());
  static final EventType<Void, Void> Accepted = BasicEventType.of("Accepted", UUID.randomUUID());
  static final EventType<Void, Void> Refused = BasicEventType.of("Refused", UUID.randomUUID());
  static final EventType<Void, Void> Reviewed = BasicEventType.of("Reviewed", UUID.randomUUID());
  static final EventType<Void, Void> Reserved = BasicEventType.of("Reserved", UUID.randomUUID());
  static final EventType<Void, Void> Released = BasicEventType.of("Released", UUID.randomUUID());
  static final EventType<Void, Void> Audited = BasicEventType.of("Audited", UUID.randomUUID());
  static final EventType<Void, Void> Shipped = BasicEventType.of("Shipped", UUID.randomUUID());
  static final EventType<Void, Void> Received = BasicEventType.of("Received", UUID.randomUUID());
  static final EventType<Void, Void> Answered = BasicEventType.of("Answered", UUID.randomUUID());
  static final EventType<Void, Void> Arrived = BasicEventType.of("Arrived", UUID.randomUUID());
  static final EventType<Void, Void> Responded = BasicEventType.of("Responded", UUID.randomUUID());
  static final EventType<Void, Void> Notified = BasicEventType.of("Notified", UUID.randomUUID());
  static final EventType<UUID, UUID> Indexed = BasicEventType.of("Indexed", UUID.randomUUID(), DataType.uuid());

  static final EntityModel Stock = EntityModel.of("Stock", UUID.randomUUID(), Map::of);
  static final EntityModel Audit = EntityModel.of("Audit", UUID.randomUUID(), Map::of);

  static final Action<String> Notify = new Action<>() {
    @Override public String name() {return "Notify customer";}
    @Override public Mono<InputEvent<?>> execute(String data) {return Mono.empty();}
  };

  static final EntityModel Order = EntityModel.of("Order", UUID.randomUUID(), SequenceDiagramFormatterTest::orderTransitions);

  /// A request placing an order, which the order answers through the group of requests, like an inbox request
  static final EntityModel Request = EntityModel.of("Request", UUID.randomUUID(), SequenceDiagramFormatterTest::requestTransitions);

  static final EntityModelGroup Requests = EntityModelGroup.of("Requests", UUID.randomUUID(), Request::equals);

  /// Routes what arrives to a new request and gets its response, like the routing of an inbox, and isn't stored
  static final EntityModel Router = EntityModel.notStored("Router", UUID.randomUUID(), () -> Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Arrived).to(States.Waiting).trigger(Received).on(Request).identifiedBy(newEntityId()).output()
      ),
      States.Waiting, List.<TransitionModel<?, ?>>of(onEvent(Responded).to(States.Answered).output()),
      States.Answered, List.of()
  ));

  static final IndexEntityModel Index = IndexEntityModel.of("Index", UUID.randomUUID(), Indexed);

  private static Map<State, List<TransitionModel<?, ?>>> orderTransitions() {
    return Map.of(
        Begin, List.<TransitionModel<?, ?>>of(
            onEvent(Placed).to(States.Placed)
                .trigger(Reserved).on(Stock).identifiedBy(newEntityId())
                .reversible(assemble((_, _) -> UUID.randomUUID())
                    .trigger(Released).on(Stock).identifiedBy(EntitySelector::entityId))
                .trigger(Answered).on(Requests).identifiedBy(entityIdFromSession())
                .output()
        ),
        States.Placed, List.<TransitionModel<?, ?>>of(
            onEvent(Reviewed).to(States.Placed).assembleInput()
                .trigger(Audited).on(Audit).identifiedBy(newEntityId())
                .when(_ -> true).then(onEvent(Refused).to(States.Refused).severity(Severity.Warning).output(), _ -> null)
                .otherwise(onEvent(Accepted).to(States.Accepted)
                    .assemble(_ -> "")
                    .trigger(Notify).with(d -> d)
                    .trigger(Shipped).on(Order).identifiedBy(entityIdFromSession())
                    .output(), _ -> null)
        ),
        // Notified is the outcome of the action notifying the customer, which no transition triggers
        States.Accepted, List.<TransitionModel<?, ?>>of(onEvent(Shipped).toSelf().output(), onEvent(Notified).toSelf().output()),
        States.Refused, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> requestTransitions() {
    return Map.of(
        Begin, List.<TransitionModel<?, ?>>of(
            onEvent(Received).to(States.Waiting)
                .trigger(Placed).on(Order).identifiedBy(newEntityId())
                .output()
        ),
        States.Waiting, List.<TransitionModel<?, ?>>of(
            onEvent(Answered).to(States.Answered)
                .assemble(c -> c.log().entityId())
                .trigger(Indexed).with(id -> id).on(Index).identifiedBy(newEntityId())
                .trigger(Responded).on(Router).identifiedBy(entityIdFromSession())
                .output()
        ),
        States.Answered, List.of()
    );
  }

  private final UUID orderId = UUID.randomUUID();
  private final List<Event<?>> events = new ArrayList<>();
  /// The timestamp of the current transaction, which all its events have
  private ZonedDateTime transaction = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC);

  private void nextTransaction() {
    transaction = transaction.plusSeconds(1);
  }

  private <T> void event(EventType<?, T> type, T data) {
    events.add(new Event<>(orderId, events.size() + 1, type, transaction, data));
  }

  private void event(EventType<?, Void> type) {
    events.add(new Event<>(orderId, events.size() + 1, type, transaction));
  }

  private EventLog log() {
    return new EventLog(Order, orderId, List.copyOf(events));
  }

  private String diagram() {
    return new SequenceDiagramFormatter(log()).format();
  }

  @Test
  void eventsArriveFromOutsideAndTheirTriggersAreMessagesToTheirModels() {
    event(Placed);
    assertThat(diagram()).contains(
        """
        participant "Order" as P1
        participant "Stock" as P2
        participant "Requests" as P3
        """,
        """
        [-> P1: 1. Placed
        activate P1
        P1 -> P2: Reserved
        P1 -> P3: Answered
        deactivate P1
        """
    );
  }

  @Test
  void theOutcomeOfAChoiceHasTheTriggersAndActionsOfThePathToIt() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    assertThat(diagram()).contains(
        """
        [-> P1: 2. Accepted
        activate P1
        P1 -> P4: Audited
        P1 -> P1: Shipped
        deactivate P1
        end
        P1 ->> A1: ⚡ Notify customer
        """
    );
  }

  @Test
  void anEventTriggeredOnTheEntityItselfInTheTransactionIsNestedInIt() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    event(Shipped);
    assertThat(diagram()).contains(
        """
        [-> P1: 2. Accepted
        activate P1
        P1 -> P4: Audited
        P1 -> P1: 3. Shipped
        activate P1
        deactivate P1
        deactivate P1
        end
        P1 ->> A1: ⚡ Notify customer
        """
    ).doesNotContain("[-> P1: 3.");
  }

  @Test
  void anEventOfALaterTransactionIsNotTriggeredByTheTransitionsOfAnEarlierOne() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    nextTransaction();
    event(Shipped);
    assertThat(diagram()).contains("P1 -> P1: Shipped\n", "[-> P1: 3. Shipped\n");
  }

  @Test
  void theEventsOfARelatedLogAreMatchedToTheTriggersOfTheSameTransaction() {
    UUID requestId = UUID.randomUUID();
    event(Placed);
    var request = new EventLog(Request, requestId, List.of(
        new Event<>(requestId, 1, Received, transaction),
        new Event<>(requestId, 2, Answered, transaction)
    ));
    assertThat(new SequenceDiagramFormatter(log(), List.of(request)).format()).contains(
        // In the order they are first drawn, from the request to the order
        """
        participant "Request" as P2
        participant "Order" as P1
        participant "Stock" as P3
        """,
        """
        [-> P2: 1. Received
        activate P2
        P2 -> P1: 1. Placed
        activate P1
        P1 -> P3: Reserved
        P1 -> P2: 2. Answered
        activate P2
        P2 -> P4: Responded
        deactivate P2
        deactivate P1
        deactivate P2
        """
    ).doesNotContain("[-> P1", "Requests");
  }

  /// Like the routing of an inbox, which isn't stored, so that an inbox request is accepted from and responds to the
  /// same lifeline
  @Test
  void theStepOfAnEntityWhichIsNotStoredIsDeducedFromTheEntityModels() {
    UUID requestId = UUID.randomUUID();
    event(Placed);
    var request = new EventLog(Request, requestId, List.of(
        new Event<>(requestId, 1, Received, transaction),
        new Event<>(requestId, 2, Answered, transaction)
    ));
    String diagram = new SequenceDiagramFormatter(log(), List.of(request), List.of(Router, Request, Order)).format();
    assertThat(diagram).contains(
        """
        participant "Router" as P3
        participant "Request" as P2
        participant "Order" as P1
        """,
        """
        [-> P3: <i>Arrived</i>
        activate P3
        P3 -> P2: 1. Received
        """,
        """
        P2 -> P3: Responded
        """
    ).doesNotContain("[-> P2");
  }

  /// As in the ER diagram
  @Test
  void eventsTriggeredOnIdAndIndexEntitiesAreLeftOut() {
    UUID requestId = UUID.randomUUID();
    var request = new EventLog(Request, requestId, List.of(
        new Event<>(requestId, 1, Received, transaction),
        new Event<>(requestId, 2, Answered, transaction)
    ));
    assertThat(new SequenceDiagramFormatter(request).format()).doesNotContain("Indexed", "\"Index\"");
  }

  @Test
  void theOutcomeOfAnActionComesBackFromTheLifelineOfTheAction() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    nextTransaction();
    event(Notified);
    assertThat(diagram()).contains(
        """
        end
        P1 ->> A1: ⚡ Notify customer
        group Transaction 3, 2026-11-26T12:00:02Z
        A1 -> P1: 3. Notified
        """,
        "participant \"Notify customer\" as A1\n"
    ).doesNotContain("[-> P1: 3.");
  }

  /// The events of a transaction have its timestamp
  @Test
  void theStepsOfEachTransactionAreDrawnInAGroupOfTheirOwn() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    event(Shipped);
    String diagram = diagram();
    assertThat(diagram).contains(
        """
        group Transaction 1, 2026-11-26T12:00:00Z
        [-> P1: 1. Placed
        """,
        """
        end
        group Transaction 2, 2026-11-26T12:00:01Z
        [-> P1: 2. Accepted
        """
    );
    assertThat(diagram.split("group Transaction")).hasSize(3);
  }

  /// The outcome of the action continues the session of the transaction which ran it
  @Test
  void theTransactionsOfASessionOfSeveralAreDrawnInAGroupOfTheSession() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    nextTransaction();
    event(Notified);
    String diagram = diagram();
    assertThat(diagram).contains(
        """
        group Session 2
        group Transaction 2, 2026-11-26T12:00:01Z
        """,
        """
        A1 -> P1: 3. Notified
        activate P1
        deactivate P1
        end
        end
        """
    ).doesNotContain("Session 1", ", session ");
  }

  /// Groups must nest, so a session with a transaction of another between its own is told by the names of its
  /// transactions
  @Test
  void theTransactionsOfAnInterleavedSessionAreNamedWithTheSession() {
    event(Placed);
    nextTransaction();
    event(Accepted);
    nextTransaction();
    UUID otherId = UUID.randomUUID();
    var other = new EventLog(Order, otherId, List.of(new Event<>(otherId, 1, Placed, transaction)));
    nextTransaction();
    event(Notified);
    String diagram = new SequenceDiagramFormatter(log(), List.of(other)).format();
    assertThat(diagram).contains(
        "group Transaction 2, session 2, 2026-11-26T12:00:01Z\n",
        "group Transaction 3, 2026-11-26T12:00:02Z\n",
        "group Transaction 4, session 2, 2026-11-26T12:00:03Z\n"
    ).doesNotContain("group Session");
  }

  @Test
  void anEventIsNamedInTheColourOfTheSeverityOfItsTransition() {
    event(Placed);
    nextTransaction();
    event(Refused);
    assertThat(diagram()).contains("[-> P1: 2. <color:#7A5200><b>⚠ Refused</b></color>\n");
  }

  @Test
  void aRollbackTakesTheReverseTransitionsAndStrikesThroughTheEventsRolledBack() {
    event(Placed);
    nextTransaction();
    event(BuiltinEventTypes.EventsRolledBack, new Rollback.Data(0, "Cancelled"));
    nextTransaction();
    // From Begin again after the rollback
    event(Placed);
    assertThat(diagram()).contains(
        "[-> P1: 1. <s>Placed</s>\n",
        """
        [-> P1: 2. Events rolled back
        activate P1
        P1 -> P2: <color:#7A0000>Released</color>
        deactivate P1
        end
        group Transaction 3, 2026-11-26T12:00:02Z
        [-> P1: 3. Placed
        """
    );
  }

  @Test
  void anEventWithoutATransitionFromTheStateIsAnError() {
    event(Accepted);
    assertThatThrownBy(this::diagram)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No transition of Order from Begin for logged event Accepted");
  }

  @Test
  void theDiagramIsWrittenAsSvg(@TempDir Path directory) throws IOException {
    event(Placed);
    nextTransaction();
    event(Accepted);
    File file = new SequenceDiagramFormatter(log()).formatToImage(directory.resolve("order.svg").toFile());
    String svg = Files.readString(file.toPath());
    assertThat(svg).startsWith("<svg").contains("Notify customer", "color-scheme:light dark");
  }

}
