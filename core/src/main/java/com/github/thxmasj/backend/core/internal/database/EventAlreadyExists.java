package com.github.thxmasj.backend.core.internal.database;

import java.util.List;
import java.util.UUID;

/// An event with the number of one being stored exists, as another transaction stored it first
public class EventAlreadyExists extends RuntimeException {

  /// The entity and number of the event which exists, or null and 0 if the database's report of it couldn't be read
  private final UUID entityId;
  private final int eventNumber;

  public EventAlreadyExists(String duplicateKey) {
    super("Event with raw id " + duplicateKey + " already exists");
    this.entityId = null;
    this.eventNumber = 0;
  }

  public EventAlreadyExists(UUID entityId, int eventNumber, List<?> changes) {
    super("Event " + eventNumber + " on entity " + entityId + " already exists. Changes:\n" + changes);
    this.entityId = entityId;
    this.eventNumber = eventNumber;
  }

  /// Whether the event which exists is the one of the number on the entity
  public boolean isOf(UUID entityId, int eventNumber) {
    return entityId.equals(this.entityId) && eventNumber == this.eventNumber;
  }

}
