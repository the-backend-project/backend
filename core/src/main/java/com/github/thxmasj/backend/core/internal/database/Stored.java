package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;

/// What the state machine stores of an entity, independent of how a database stores it. A database module maps the
/// tables of its schema to these, so the state machine can tell from a conflict reported on a table which entity it is
/// on: the id and index entities of an entity have its entity id.
///
/// The key a database reports for a conflict has the values listed for each, separated by commas.
public enum Stored {

  /// An entity which is neither an id nor an index entity, stored with its first event, keyed by its entity id
  Entity,
  /// An event of an entity which is neither an id nor an index entity, keyed by its entity id and event number
  Event,
  /// An event of an index entity, keyed by its entity id and event number
  IndexEvent,
  /// An event of an id entity, keyed by its entity id and event number, and unique by its type and data
  IdEvent;

  /// Where the events of the entity model are stored
  public static Stored of(EntityModel entityModel) {
    return switch (entityModel) {
      case IdEntityModel _ -> IdEvent;
      case IndexEntityModel _ -> IndexEvent;
      default -> Event;
    };
  }

  /// Whether this has the events, or the entities, of the entity model
  public boolean stores(EntityModel entityModel) {
    return of(entityModel) == (this == Entity ? Event : this);
  }

}
