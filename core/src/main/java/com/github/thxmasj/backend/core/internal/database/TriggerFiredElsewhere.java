package com.github.thxmasj.backend.core.internal.database;

/// The trigger which fired a transaction was no longer pending when the transaction was stored, as another transaction
/// fired it first, e.g. of another instance which picked the same trigger. The transaction is not stored, so the
/// trigger's event is taken once.
public class TriggerFiredElsewhere extends RuntimeException {

  /// The text a store fails with when the deletion of the trigger which fired a transaction deletes nothing, by which
  /// the failure is told from others
  public static final String MARKER = "TriggerFiredElsewhere";

  public TriggerFiredElsewhere(Throwable cause) {
    super("The trigger which fired the transaction was fired by another transaction first", cause);
  }

  /// The error number SQL Server's store throws with
  public static final int SQL_SERVER_ERROR = 50001;

  /// Whether the failure is that of a store whose deletion of the trigger deleted nothing. It is told by the error of
  /// the database itself, the innermost cause, rather than by the messages of the clients around it, which have the
  /// SQL, and so the marker, whatever failed: SQL Server's error of [#SQL_SERVER_ERROR], or PostgreSQL's failure to cast
  /// the marker, whose message quotes it.
  static boolean isCause(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root)
      root = root.getCause();
    if (root instanceof java.sql.SQLException e && e.getErrorCode() == SQL_SERVER_ERROR)
      return true;
    return root.getMessage() != null && root.getMessage().contains("\"" + MARKER);
  }

}
