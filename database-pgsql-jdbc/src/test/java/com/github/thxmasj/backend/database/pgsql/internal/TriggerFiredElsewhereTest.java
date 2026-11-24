package com.github.thxmasj.backend.database.pgsql.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// PostgreSQL's store tells the error of a trigger fired by another transaction by the value its failed cast quotes,
/// whatever the language of the server
// Run with the module's database tests, on PostgreSQL, though it needs no database
@Tag("database")
public class TriggerFiredElsewhereTest {

  private final ChangeState changeState = new ChangeState(null, "test", Clock.systemUTC());

  /// The clients wrap the database's error in a message with the SQL, which has the value whatever failed
  private static RuntimeException wrapped(Throwable error) {
    return new RuntimeException("Query ChangeState failed: " + error.getMessage() + "\nSQL:\nSELECT CAST('TriggerFiredElsewhere' || ...)", error);
  }

  @Test
  public void anotherFailureOfTheTransactionIsNotTakenForIt() {
    assertFalse(changeState.isTriggerFiredElsewhere(wrapped(new SQLException("ERROR: deadlock detected", "40P01"))));
    assertFalse(changeState.isTriggerFiredElsewhere(wrapped(new RuntimeException("Connection reset"))));
  }

  @Test
  public void theErrorOfTheDeletionIsTakenForItInAnyLanguage() {
    assertTrue(changeState.isTriggerFiredElsewhere(wrapped(new SQLException("ERROR: invalid input syntax for type uuid: \"TriggerFiredElsewhere0\"", "22P02"))));
    assertTrue(changeState.isTriggerFiredElsewhere(wrapped(new SQLException("FEHLER: ungültige Eingabesyntax für Typ uuid: »TriggerFiredElsewhere0«", "22P02"))));
  }

}
