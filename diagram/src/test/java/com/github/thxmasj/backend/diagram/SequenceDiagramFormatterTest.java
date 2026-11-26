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
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

class SequenceDiagramFormatterTest {

  enum States implements State {Placed, Accepted, Refused}

  static final EventType<Void, Void> Placed = BasicEventType.of("Placed", UUID.randomUUID());
  static final EventType<Void, Void> Accepted = BasicEventType.of("Accepted", UUID.randomUUID());
  static final EventType<Void, Void> Refused = BasicEventType.of("Refused", UUID.randomUUID());
  static final EventType<Void, Void> Reviewed = BasicEventType.of("Reviewed", UUID.randomUUID());
  static final EventType<Void, Void> Reserved = BasicEventType.of("Reserved", UUID.randomUUID());
  static final EventType<Void, Void> Released = BasicEventType.of("Released", UUID.randomUUID());
  static final EventType<Void, Void> Audited = BasicEventType.of("Audited", UUID.randomUUID());
  static final EventType<Void, Void> Shipped = BasicEventType.of("Shipped", UUID.randomUUID());

  static final EntityModel Stock = EntityModel.of("Stock", UUID.randomUUID(), Map::of);
  static final EntityModel Audit = EntityModel.of("Audit", UUID.randomUUID(), Map::of);

  static final Action<String> Notify = new Action<>() {
    @Override public String name() {return "Notify customer";}
    @Override public Mono<InputEvent<?>> execute(String data) {return Mono.empty();}
  };

  static final EntityModel Order = EntityModel.of("Order", UUID.randomUUID(), SequenceDiagramFormatterTest::transitions);

  private static Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
      Begin, List.<TransitionModel<?, ?>>of(
          onEvent(Placed).to(States.Placed)
              .trigger(Reserved).on(Stock).identifiedBy(newEntityId())
              .reversible(assemble((_, _) -> UUID.randomUUID())
                  .trigger(Released).on(Stock).identifiedBy(EntitySelector::entityId))
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
      States.Accepted, List.<TransitionModel<?, ?>>of(onEvent(Shipped).toSelf().output()),
      States.Refused, List.of()
    );
  }

  private final UUID orderId = UUID.randomUUID();
  private final List<Event<?>> events = new ArrayList<>();

  private <T> void event(EventType<?, T> type, T data) {
    events.add(new Event<>(orderId, events.size() + 1, type, ZonedDateTime.now(), data));
  }

  private void event(EventType<?, Void> type) {
    events.add(new Event<>(orderId, events.size() + 1, type, ZonedDateTime.now()));
  }

  private String diagram() {
    return new SequenceDiagramFormatter(new EventLog(Order, orderId, List.copyOf(events))).format();
  }

  @Test
  void eventsArriveFromOutsideAndTheirTriggersAreMessagesToTheirModels() {
    event(Placed);
    assertThat(diagram()).contains(
        """
        participant "Order" as P1
        participant "Stock" as P2
        """,
        """
        [-> P1: 1. Placed
        activate P1
        P1 -> P2: Reserved
        deactivate P1
        """
    );
  }

  @Test
  void theOutcomeOfAChoiceHasTheTriggersAndActionsOfThePathToIt() {
    event(Placed);
    event(Accepted);
    assertThat(diagram()).contains(
        """
        [-> P1: 2. Accepted
        activate P1
        P1 -> P3: Audited
        P1 -> P1: Shipped
        P1 ->>] : ⚡ Notify customer
        deactivate P1
        """
    );
  }

  @Test
  void theNextEventTriggeredOnTheEntityItselfIsNestedInTheTransition() {
    event(Placed);
    event(Accepted);
    event(Shipped);
    assertThat(diagram()).contains(
        """
        [-> P1: 2. Accepted
        activate P1
        P1 -> P3: Audited
        P1 -> P1: 3. Shipped
        activate P1
        deactivate P1
        P1 ->>] : ⚡ Notify customer
        deactivate P1
        """
    ).doesNotContain("[-> P1: 3.");
  }

  @Test
  void anEventIsNamedInTheColourOfTheSeverityOfItsTransition() {
    event(Placed);
    event(Refused);
    assertThat(diagram()).contains("[-> P1: 2. <color:#7A5200><b>⚠ Refused</b></color>\n");
  }

  @Test
  void aRollbackTakesTheReverseTransitionsAndStrikesThroughTheEventsRolledBack() {
    event(Placed);
    event(BuiltinEventTypes.EventsRolledBack, new Rollback.Data(0, "Cancelled"));
    // From Begin again after the rollback
    event(Placed);
    assertThat(diagram()).contains(
        "[-> P1: 1. <s>Placed</s>\n",
        """
        [-> P1: 2. Events rolled back
        activate P1
        P1 -> P2: <color:#7A0000>Released</color>
        deactivate P1
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
    event(Accepted);
    File file = new SequenceDiagramFormatter(new EventLog(Order, orderId, List.copyOf(events)))
        .formatToImage(directory.resolve("order.svg").toFile());
    String svg = Files.readString(file.toPath());
    assertThat(svg).startsWith("<svg").contains("Notify customer", "color-scheme:light dark");
  }

}
