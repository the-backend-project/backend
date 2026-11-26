package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.EventType;
import java.time.ZonedDateTime;
import java.util.UUID;

public record DelayedEvent<T>(
    EntityType entityType,
    UUID entityId,
    EventType<T, ?> type,
    int eventNumber,
    T data,
    ZonedDateTime after
) {

  public DelayedEvent<T> withEventNumber(int eventNumber) {
    return new DelayedEvent<>(entityType, entityId, type, eventNumber, data, after);
  }

}
