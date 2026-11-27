package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.Change;
import com.github.thxmasj.backend.core.EntityType;
import java.util.List;
import java.util.UUID;

/// An event with the number of one being stored exists, as another transaction stored it first
public class EventAlreadyExists extends RuntimeException {

  /// The entity and number of the event which exists, or nulls and 0 if the database's report of it couldn't be read.
  /// The entity type tells the entities sharing an entity id apart: an entity, its id entity and its index entity.
  private final EntityType entityType;
  private final UUID entityId;
  private final int eventNumber;

  /// An event exists, but the database's report doesn't tell which
  public EventAlreadyExists(Throwable cause) {
    super("An event being stored already exists", cause);
    this.entityType = null;
    this.entityId = null;
    this.eventNumber = 0;
  }

  /// The event of a change exists
  public EventAlreadyExists(Change.Event<?> event, List<Change> changes, Throwable cause) {
    super(
        "Event " + event.eventNumber() + " on entity " + event.entityType().name() + ":" +
            event.entityId() + " already exists. Changes:\n" + changes,
        cause
    );
    this.entityType = event.entityType();
    this.entityId = event.entityId();
    this.eventNumber = event.eventNumber();
  }

  /// Whether the event which exists is the one of the number on the entity
  public boolean isOf(EntityType entityType, UUID entityId, int eventNumber) {
    return isOn(entityType, entityId) && eventNumber == this.eventNumber;
  }

  /// Whether the event which exists is one of the entity, and not of another one with the same entity id, such as its
  /// id or index entity
  public boolean isOn(EntityType entityType, UUID entityId) {
    return this.entityType != null && entityType.id().equals(this.entityType.id()) && entityId.equals(this.entityId);
  }

}
