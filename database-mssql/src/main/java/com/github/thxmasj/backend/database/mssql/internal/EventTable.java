package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import java.util.Arrays;

/// The tables of the schema an event is stored in, named as in the schema. The id and index entities of an entity have
/// its entity id, so the table of an event tells which of them it is on.
enum EventTable {

  /// The entities with their entity models, which has a row for the first event of an entity that is neither an id nor
  /// an index entity, keyed by the entity id alone
  Entity,
  /// The events of the entities which are neither id nor index entities
  Event,
  /// The events of the index entities
  IndexEvent,
  /// The events of the id entities, with a unique index on their type and data
  IdEvent;

  /// The table with the events of the entity model
  static EventTable of(EntityModel entityModel) {
    return switch (entityModel) {
      case IdEntityModel _ -> IdEvent;
      case IndexEntityModel _ -> IndexEvent;
      default -> Event;
    };
  }

  /// The table of the name, or null if no event is stored in it
  static EventTable named(String name) {
    return Arrays.stream(values()).filter(table -> table.name().equals(name)).findFirst().orElse(null);
  }

  /// Whether events of the entity model are stored in the table
  boolean stores(EntityModel entityModel) {
    return of(entityModel) == (this == Entity ? Event : this);
  }

}
