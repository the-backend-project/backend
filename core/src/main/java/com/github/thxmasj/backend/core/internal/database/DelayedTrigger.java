package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.time.ZonedDateTime;
import java.util.UUID;

public record DelayedTrigger<T>(
    UUID entityId,
    EntityModel entityModel,
    int eventNumber,
    EventType<T, ?> eventType,
    T data,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {

  /// The trigger without its data, which may be a secret, as it is written e.g. in debug output
  @Override
  public String toString() {
    return "DelayedTrigger[" + entityModel.name() + ":" + entityId + ":" + eventNumber + ":" + eventType.name() + ", next attempt at " + nextAttemptAt + "]";
  }

}
