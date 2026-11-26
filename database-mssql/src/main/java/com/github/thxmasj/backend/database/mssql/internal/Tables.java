package com.github.thxmasj.backend.database.mssql.internal;

import com.github.thxmasj.backend.core.database.TableType;
import java.util.Arrays;

/// The names of the tables of the schema, by their type
public final class Tables {

  private Tables() {}

  /// The name of the table of the type
  public static String table(TableType tableType) {
    return switch (tableType) {
      case Entity -> "Entity";
      case Event -> "Event";
      case IndexEvent -> "IndexEvent";
      case IdEvent -> "IdEvent";
    };
  }

  /// The type of the table of the name, or null if it has no entities or events
  public static TableType tableType(String table) {
    return Arrays.stream(TableType.values()).filter(tableType -> table(tableType).equals(table)).findFirst().orElse(null);
  }

}
