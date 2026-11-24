package com.github.thxmasj.backend.core;

/// An event to take, of its type and with its data
public record InputEvent<T>(
    EventType<T, ?> eventType,
    T data
) {

  /// The event without its data, which may be a secret, as it is written e.g. in debug output
  @Override
  public String toString() {
    return "InputEvent[" + eventType.name() + "]";
  }

}
