package com.github.thxmasj.backend.core.database;

public record Parameter<T>(Class<? extends T> type, T value) {

  /// The parameter without its value, which may be a secret, e.g. the data of a delayed trigger, as it is written in the
  /// message of a failed query: its type, and the length of bytes or text, or that it is null
  public String described() {
    if (value == null)
      return type.getSimpleName() + " null";
    if (value instanceof byte[] bytes)
      return "byte[" + bytes.length + "]";
    if (value instanceof CharSequence text)
      return type.getSimpleName() + " of " + text.length() + " characters";
    return type.getSimpleName();
  }

}
