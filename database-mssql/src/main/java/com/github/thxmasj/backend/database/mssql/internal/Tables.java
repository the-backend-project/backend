package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.database.Stored;
import java.util.Arrays;

/// The tables of the schema where the state machine stores entities and events
public final class Tables {

  private Tables() {}

  /// The name of the table where it is stored
  public static String table(Stored stored) {
    return switch (stored) {
      case Entity -> "Entity";
      case Event -> "Event";
      case IndexEvent -> "IndexEvent";
      case IdEvent -> "IdEvent";
    };
  }

  /// What is stored in the table of the name, or null if it has no entities or events
  public static Stored stored(String table) {
    return Arrays.stream(Stored.values()).filter(stored -> table(stored).equals(table)).findFirst().orElse(null);
  }

}
