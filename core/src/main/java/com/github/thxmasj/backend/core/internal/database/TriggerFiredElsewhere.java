package com.github.thxmasj.backend.core.internal.database;

/// The trigger which fired a transaction was no longer pending when the transaction was stored, as another transaction
/// fired it first, e.g. of another instance which picked the same trigger. The transaction is not stored, so the
/// trigger's event is taken once. Each store tells its database's error of this from others, see
/// [ChangeState#isTriggerFiredElsewhere(Throwable)].
public class TriggerFiredElsewhere extends RuntimeException {

  public TriggerFiredElsewhere(Throwable cause) {
    super("The trigger which fired the transaction was fired by another transaction first", cause);
  }

}
