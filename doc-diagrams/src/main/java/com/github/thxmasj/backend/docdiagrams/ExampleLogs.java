package com.github.thxmasj.backend.docdiagrams;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/// Event logs of the examples, for their sequence diagrams. The event types of the inbox and outbox models are created
/// with the models, so a log is given by the names of its events, each one of an event the entity can store in the
/// state the events before it took it to. The events have no data, which the diagram doesn't show.
final class ExampleLogs {

  private ExampleLogs() {}

  private static final ZonedDateTime time = ZonedDateTime.of(2026, 11, 26, 12, 0, 0, 0, ZoneOffset.UTC);

  static EventLog log(EntityModel model, String... eventNames) {
    UUID entityId = UUID.nameUUIDFromBytes(model.name().getBytes());
    List<Event<?>> events = new ArrayList<>();
    State state = Begin;
    for (String name : eventNames) {
      State from = state;
      TransitionModel<?, ?> transition = model.transitions().getOrDefault(from, List.of()).stream()
          .flatMap(ExampleLogs::leaves)
          .filter(leaf -> leaf.eventType().name().equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(String.format(
              "%s stores no %s from %s, but one of: %s",
              model.name(),
              name,
              from.name(),
              model.transitions().getOrDefault(from, List.of()).stream()
                  .flatMap(ExampleLogs::leaves)
                  .map(leaf -> leaf.eventType().name())
                  .distinct()
                  .collect(joining(", "))
          )));
      events.add(event(entityId, events.size() + 1, transition.eventType()));
      if (transition.toState() != null)
        state = transition.toState();
    }
    return new EventLog(model, entityId, List.copyOf(events));
  }

  private static <T> Event<T> event(UUID entityId, int number, EventType<?, T> type) {
    return new Event<>(entityId, number, type, time.plusSeconds(number));
  }

  private static Stream<TransitionModel<?, ?>> leaves(TransitionModel<?, ?> transition) {
    if (transition.filters().isEmpty())
      return Stream.of(transition);
    return transition.filters().stream().flatMap(filter -> leaves(filter.alternative().model()));
  }

}
