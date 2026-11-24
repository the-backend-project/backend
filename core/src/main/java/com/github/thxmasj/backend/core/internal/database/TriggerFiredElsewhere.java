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

  /// Whether the failure, or one it was caused by, is that of a store whose deletion of the trigger deleted nothing
  static boolean isCause(Throwable failure) {
    for (var t = failure; t != null; t = t.getCause())
      if (t.getMessage() != null && t.getMessage().contains(MARKER))
        return true;
    return false;
  }

}
