package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import java.util.List;
import java.util.UUID;

/// An event with the number of one being stored exists, as another transaction stored it first
public class EventAlreadyExists extends RuntimeException {

  /// The table, entity and number of the event which exists, or nulls and 0 if the database's report of it couldn't be
  /// read. The table tells the entities sharing an entity id apart: an entity, its id entity and its index entity.
  private final String table;
  private final UUID entityId;
  private final int eventNumber;

  public EventAlreadyExists(String duplicateKey) {
    super("Event with raw id " + duplicateKey + " already exists");
    this.table = null;
    this.entityId = null;
    this.eventNumber = 0;
  }

  /// @param table the table the event is stored in, "Entity" for the first event of an entity which is not an id or
  ///     index entity
  public EventAlreadyExists(String table, UUID entityId, int eventNumber, List<?> changes) {
    super("Event " + eventNumber + " on entity " + entityId + " in " + table + " already exists. Changes:\n" + changes);
    this.table = table.equals("Entity") ? "Event" : table;
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
    return entityId.equals(this.entityId) && table(entityModel).equals(table);
  }

  /// The table the events of the entity model are stored in
  private static String table(EntityModel entityModel) {
    return switch (entityModel) {
      case IdEntityModel _ -> "IdEvent";
      case IndexEntityModel _ -> "IndexEvent";
      default -> "Event";
    };
  }

}
