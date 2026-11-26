package com.github.thxmasj.backend.database.pgsql.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.internal.database.Stored;
import java.util.Arrays;

/// The tables of the schema with entities and events, named as in the schema, with what the state machine stores in
/// each
enum EventTable {

  Entity(Stored.Entity),
  Event(Stored.Event),
  IndexEvent(Stored.IndexEvent),
  IdEvent(Stored.IdEvent);

  private final Stored stored;

  EventTable(Stored stored) {
    this.stored = stored;
  }

  /// The table with the events of the entity model
  static EventTable of(EntityModel entityModel) {
    Stored stored = Stored.of(entityModel);
    return Arrays.stream(values()).filter(table -> table.stored == stored).findFirst().orElseThrow();
  }

  /// What is stored in the table of the name, or null if it has no entities or events
  static Stored stored(String name) {
    return Arrays.stream(values()).filter(table -> table.name().equals(name)).findFirst().map(table -> table.stored).orElse(null);
  }

}
