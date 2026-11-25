package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.internal.database.ChangeState.Change;
import java.util.List;
import java.util.UUID;

/// An event with the number of one being stored exists, as another transaction stored it first
public class EventAlreadyExists extends RuntimeException {

  /// The entity and number of the event which exists, or nulls and 0 if the database's report of it couldn't be read.
  /// The entity model tells the entities sharing an entity id apart: an entity, its id entity and its index entity.
  private final EntityModel entityModel;
  private final UUID entityId;
  private final int eventNumber;

  /// An event exists, but the database's report doesn't tell which
  public EventAlreadyExists(Throwable cause) {
    super("An event being stored already exists", cause);
    this.entityModel = null;
    this.entityId = null;
    this.eventNumber = 0;
  }

  /// The event of the change exists
  public EventAlreadyExists(Change change, List<Change> changes, Throwable cause) {
    super(
        "Event " + change.newEvent().eventNumber() + " on entity " + change.entityModel().name() + ":" +
            change.entityId() + " already exists. Changes:\n" + changes,
        cause
    );
    this.entityModel = change.entityModel();
    this.entityId = change.entityId();
    this.eventNumber = change.newEvent().eventNumber();
  }

  /// Whether the event which exists is the one of the number on the entity
  public boolean isOf(EntityModel entityModel, UUID entityId, int eventNumber) {
    return isOn(entityModel, entityId) && eventNumber == this.eventNumber;
  }

  /// Whether the event which exists is one of the entity, and not of another one with the same entity id, such as its
  /// id or index entity
  public boolean isOn(EntityModel entityModel, UUID entityId) {
    return this.entityModel != null && entityModel.id().equals(this.entityModel.id()) && entityId.equals(this.entityId);
  }

}
