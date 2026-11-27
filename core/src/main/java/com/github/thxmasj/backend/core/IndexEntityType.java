package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static java.util.stream.Collectors.toList;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IndexEntityType extends EntityType {

  static IndexEntityType of(String name, UUID id, EventType<?, ?>... eventTypes) {
    List<TransitionModel<?, ?>> transitions = Arrays.stream(eventTypes)
        .map(IndexEntityType::transition)
        .collect(toList());
    return new IndexEntityType() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public Map<State, List<TransitionModel<?, ?>>> transitions() {return Map.of(Begin, transitions);}
    };
  }

  @SuppressWarnings("unchecked")
  private static TransitionModel<?, ?> transition(EventType<?, ?> eventType) {
    return onEvent((EventType<Object, Object>) eventType).toSelf().assembleInput().output(d -> d);
  }

  static EventType<UUID, UUID> referenceEventType(String name, UUID id) {
    return EventType.of(name, id, DataType.uuid());
  }

}
