package com.github.thxmasj.backend.core.internal.database;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

public class TriggerFiredElsewhereTest {

  /// The clients wrap the database's error in a message with the SQL, which has the marker whatever failed
  private static RuntimeException wrapped(Throwable error) {
    return new RuntimeException("Query ChangeState failed: " + error.getMessage() + "\nSQL:\nIF @@ROWCOUNT = 0 THROW 50001, '" + TriggerFiredElsewhere.MARKER + "', 1;", error);
  }

  @Test
  public void anotherFailureOfTheTransactionIsNotTakenForIt() {
    assertFalse(TriggerFiredElsewhere.isCause(wrapped(new SQLException("Transaction was deadlocked", "40001", 1205))));
    assertFalse(TriggerFiredElsewhere.isCause(wrapped(new RuntimeException("Connection reset"))));
  }

  @Test
  public void theErrorOfTheDeletionIsTakenForIt() {
    // SQL Server's THROW
    assertTrue(TriggerFiredElsewhere.isCause(wrapped(new SQLException(TriggerFiredElsewhere.MARKER, "S0001", TriggerFiredElsewhere.SQL_SERVER_ERROR))));
    // PostgreSQL's failure to cast the marker, which quotes it
    assertTrue(TriggerFiredElsewhere.isCause(wrapped(new SQLException("ERROR: invalid input syntax for type uuid: \"" + TriggerFiredElsewhere.MARKER + "0\"", "22P02"))));
  }

}
