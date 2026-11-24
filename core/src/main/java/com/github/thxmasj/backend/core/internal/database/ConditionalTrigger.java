package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventType;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public record ConditionalTrigger<T>(
    UUID id,
    EventReference triggerEvent,
    EntityModel entityModel,
    UUID entityId,
    EventType<T, ?> type,
    T data,
    ZonedDateTime timestamp,
    EventType<?, ?> referenceType,
    List<EventType<?, ?>> lastTypes,
    UUID indexKey,
    String correlationId
) {

  /// The trigger without its data, which may be a secret, as it is written e.g. in debug output
  @Override
  public String toString() {
    return "ConditionalTrigger[" + id + ": " + type.name() + " on " + entityModel.name() + ":" + entityId + "]";
  }

}
