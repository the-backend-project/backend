package com.github.thxmasj.backend.core;

/// The kind of stored trigger that an event originates from, which the transaction of the event deletes
public enum TriggerType {
  /// The event was fired by a delayed trigger (a row in the DelayedTrigger table), which is identified by its entity and
  /// event number
  DELAYED,
  /// The event was fired by a conditional trigger (a row in the ConditionalTrigger table), whose id identifies the row
  CONDITIONAL
}
