package com.github.thxmasj.backend.database.mssql.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/// SQL Server's store tells the error of a trigger fired by another transaction by SQL Server's error number
public class TriggerFiredElsewhereTest {

  private final ChangeState changeState = new ChangeState(null, "test", Clock.systemUTC());

  /// The clients wrap the database's error in a message with the SQL, whatever failed
  private static RuntimeException wrapped(Throwable error) {
    return new RuntimeException("Query ChangeState failed: " + error.getMessage() + "\nSQL:\nIF @@ROWCOUNT = 0 THROW 50001, 'The trigger was fired by another transaction', 1;", error);
  }

  @Test
  public void anotherFailureOfTheTransactionIsNotTakenForIt() {
    assertFalse(changeState.isTriggerFiredElsewhere(wrapped(new SQLException("Transaction was deadlocked", "40001", 1205))));
    assertFalse(changeState.isTriggerFiredElsewhere(wrapped(new RuntimeException("Connection reset"))));
  }

  @Test
  public void theErrorOfTheDeletionIsTakenForIt() {
    assertTrue(changeState.isTriggerFiredElsewhere(wrapped(new SQLException("The trigger was fired by another transaction", "S0001", 50001))));
  }

}
