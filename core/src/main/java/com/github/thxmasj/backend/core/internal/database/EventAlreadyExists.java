package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import java.util.List;
import java.util.UUID;

/// An event with the number of one being stored exists, as another transaction stored it first
public class EventAlreadyExists extends RuntimeException {

  /// The entity and number of the event which exists, or nulls and 0 if the database's report of it couldn't be read.
  /// The entity model tells the entities sharing an entity id apart: an entity, its id entity and its index entity.
  private final EntityModel entityModel;
  private final UUID entityId;
  private final int eventNumber;

  public EventAlreadyExists(String duplicateKey) {
    super("Event with raw id " + duplicateKey + " already exists");
    this.entityModel = null;
    this.entityId = null;
    this.eventNumber = 0;
  }

  public EventAlreadyExists(EntityModel entityModel, UUID entityId, int eventNumber, List<?> changes) {
    super("Event " + eventNumber + " on entity " + entityModel.name() + ":" + entityId + " already exists. Changes:\n" + changes);
    this.entityModel = entityModel;
    this.entityId = entityId;
    this.eventNumber = eventNumber;
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
