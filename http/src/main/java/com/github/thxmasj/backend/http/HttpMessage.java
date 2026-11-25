package com.github.thxmasj.backend.http;

import static java.util.stream.Collectors.joining;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// An HTTP request or response, with a body of type `T`: the bytes of the body as sent, `byte[]`, or the payload they
/// are parsed into, or marshalled from. A message without a body has a null body.
public sealed interface HttpMessage<T> permits HttpRequest, HttpResponse {

  Headers headers();

  T body();

  /// The start line, i.e. the request line of a request and the status line of a response
  String startLine();

  /// The first value of the header, whose name is compared without case, or null if the message has none
  default String headerValue(String name) {
    return headers().firstValue(name).orElse(null);
  }

  /// The value of a header a message may have only once, e.g. `Authorization`, whose name is compared without case, or
  /// null if the message has none, or several, which a recipient can't choose between: a proxy in front may have
  /// checked another one than the first
  default String singleHeaderValue(String name) {
    List<String> values = headers().allValues(name);
    return values.size() == 1 ? values.getFirst() : null;
  }

  /// The values of the header, whose name is compared without case, in order, or none if the message has none
  default List<String> headerValues(String name) {
    return headers().allValues(name);
  }

  /// The message as sent: its start line, headers and body, which [HttpMessageParser] parses. Only a message whose body
  /// is bytes, or which has none, is sent, so a payload is marshalled into bytes first, e.g. with
  /// [HttpRequest#map(java.util.function.Function)].
  static byte[] toBytes(HttpMessage<byte[]> message) {
    byte[] head = head(message).getBytes(StandardCharsets.UTF_8);
    byte[] body = message.body();
    if (body == null)
      return head;
    return ByteBuffer.allocate(head.length + 2 + body.length)
        .put(head)
        .put("\n\n".getBytes(StandardCharsets.UTF_8))
        .put(body)
        .array();
  }

  /// The message as text: as sent, if the body is bytes or absent, and otherwise with the payload as its string
  static String toString(HttpMessage<?> message) {
    Object body = message.body();
    if (body == null)
      return head(message);
    return head(message) + "\n\n" + (body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body);
  }

  /// Whether the bodies are equal, by content if they are bytes
  static boolean bodiesEqual(Object body, Object other) {
    return body instanceof byte[] bytes && other instanceof byte[] otherBytes
        ? Arrays.equals(bytes, otherBytes)
        : Objects.equals(body, other);
  }

  /// The hash code of the body, consistent with [#bodiesEqual(Object, Object)]
  static int bodyHashCode(Object body) {
    return body instanceof byte[] bytes ? Arrays.hashCode(bytes) : Objects.hashCode(body);
  }

  /// The start line and the headers, with a line for each value of a header
  private static String head(HttpMessage<?> message) {
    if (message.headers().isEmpty())
      return message.startLine();
    return message.startLine() + "\n" + message.headers().stream()
        .map(field -> field.name() + ":" + field.value())
        .collect(joining("\n"));
  }
}
