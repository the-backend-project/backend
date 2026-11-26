package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityType;
import com.github.thxmasj.backend.core.IdEntityType;
import com.github.thxmasj.backend.core.IndexEntityType;

/// The types of the tables where the state machine stores entities and events, independent of how a database names
/// them. A database module maps the tables of its schema to these, and gives the type of a table in its constraint
/// violations (see [DatabaseClient.DataIntegrityViolation#tableType()]), so the state machine can tell which entity a
/// conflict is on: the id and index entities of an entity have its entity id.
///
/// The key a database reports for a conflict has the values listed for each, separated by commas.
public enum TableType {

  /// The table of the entities which are neither id nor index entities, with a row stored with the first event of
  /// each, keyed by its entity id
  Entity,
  /// The table of the events of the entities which are neither id nor index entities, keyed by entity id and event
  /// number
  Event,
  /// The table of the events of the index entities, keyed by entity id and event number
  IndexEvent,
  /// The table of the events of the id entities, keyed by entity id and event number, and unique by type and data
  IdEvent;

  /// The type of the table where the events of the entity type are stored
  public static TableType of(EntityType entityType) {
    return switch (entityType) {
      case IdEntityType _ -> IdEvent;
      case IndexEntityType _ -> IndexEvent;
      default -> Event;
    };
  }

  /// Whether a table of this type has the events, or the entities, of the entity type
  public boolean stores(EntityType entityType) {
    return of(entityType) == (this == Entity ? Event : this);
  }

}
