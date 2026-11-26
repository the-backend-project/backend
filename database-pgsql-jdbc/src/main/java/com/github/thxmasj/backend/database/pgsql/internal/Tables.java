package com.github.thxmasj.backend.database.pgsql.internal;

import com.github.thxmasj.backend.core.database.Stored;
import java.util.Arrays;

/// The tables of the schema where the state machine stores entities and events
final class Tables {

  private Tables() {}

  /// The name of the table where it is stored
  static String table(Stored stored) {
    return switch (stored) {
      case Entity -> "Entity";
      case Event -> "Event";
      case IndexEvent -> "IndexEvent";
      case IdEvent -> "IdEvent";
    };
  }

  /// What is stored in the table of the name, or null if it has no entities or events
  static Stored stored(String table) {
    return Arrays.stream(Stored.values()).filter(stored -> table(stored).equals(table)).findFirst().orElse(null);
  }

}
