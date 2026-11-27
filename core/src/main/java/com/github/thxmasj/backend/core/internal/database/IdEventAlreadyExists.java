package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.StoredEvent;

/// An id event can't be stored, as an id event of the same type with the same data exists. Not an
/// EventAlreadyExists, as storing the same changes again would fail again.
public class IdEventAlreadyExists extends RuntimeException {

  private final StoredEvent<?> idEvent;

  public IdEventAlreadyExists(StoredEvent<?> idEvent, Throwable cause) {
    super("Id event " + idEvent.typeName() + " of entity " + idEvent.entityId() + " has an id another entity has", cause);
    this.idEvent = idEvent;
  }

  /// The id event that was to be stored
  public StoredEvent<?> idEvent() {
    return idEvent;
  }

}
