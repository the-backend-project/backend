package com.github.thxmasj.backend.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.http.HttpRequest.Method;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

public class HttpMessageTest {

  @Test
  public void marshallingIsReversible() {
    byte[] originalBody = "Hello World!".getBytes();
    HttpRequest<byte[]> original = new HttpRequest<>(
        Method.POST,
        URI.create("/a/b/c"),
        Headers.of("Header1", "value1", "Header2", "value2"),
        originalBody
    );
    assertArrayEquals(originalBody, HttpMessageParser.parseRequest(HttpMessage.toBytes(original)).body());
  }

  /// Raw messages are equal by the content of their bodies, as the bytes of two messages read are different arrays
  @Test
  public void messagesWithEqualBodiesAreEqual() {
    var request = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("A", "1"), "order".getBytes());
    var same = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("A", "1"), "order".getBytes());
    var other = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("A", "1"), "other".getBytes());
    assertEquals(request, same);
    assertEquals(request.hashCode(), same.hashCode());
    assertNotEquals(request, other);
    assertEquals(HttpResponse.create(200, "OK", "done".getBytes()), HttpResponse.create(200, "OK", "done".getBytes()));
  }

  @Test
  public void bodyIsMappedAndTheRestKept() {
    HttpResponse<String> typed = HttpResponse.create(201, "Created", Headers.of("Location", "/orders/1"), "order");
    HttpResponse<byte[]> raw = typed.map(String::getBytes);
    assertEquals(new HttpResponse<>(201, "Created", Headers.of("Location", "/orders/1"), "order".getBytes()), raw);
    assertEquals("/orders/1", typed.headerValue("location"));
  }

  /// A message with a payload is shown with the payload as text, as it has no bytes to send until it is marshalled
  @Test
  public void messageWithAPayloadIsShownWithItsPayload() {
    var typed = HttpRequest.create(Method.POST, URI.create("/orders"), 42);
    assertEquals("POST /orders\n\n42", typed.toString());
    assertEquals("POST /orders\n\n42", new String(HttpMessage.toBytes(typed.map(n -> n.toString().getBytes()))));
  }

  /// Header names are compared without case, as in HTTP, e.g. a stored request with a retry of it as received
  @Test
  public void headerNamesAreComparedWithoutCase() {
    var received = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("content-type", "text/plain"), "order".getBytes());
    var stored = HttpMessageParser.parseRequest(HttpMessage.toBytes(
        new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("Content-Type", "text/plain"), "order".getBytes())
    ));
    assertEquals(received, stored);
    assertEquals(stored, received);
    assertEquals(received.hashCode(), stored.hashCode());
    assertNotEquals(received, new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("content-type", "text/html"), "order".getBytes()));
  }

  /// The URIs of requests are compared as written, as different escapes may be different requests to a server
  @Test
  public void urisAreComparedAsWritten() {
    assertNotEquals(new HttpRequest<>(Method.GET, URI.create("/orders/a%2fb")), new HttpRequest<>(Method.GET, URI.create("/orders/a%2Fb")));
  }

  @Test
  public void messageWithoutHeadersHasNoneRatherThanNull() {
    assertThrows(NullPointerException.class, () -> new HttpResponse<>(204, "No Content", (Headers) null));
    assertThrows(NullPointerException.class, () -> new HttpRequest<>(Method.GET, URI.create("/"), (Headers) null));
  }

  /// A header with several values is written as a line per value, and parsed back with its values apart, in order
  @Test
  public void headerWithSeveralValuesIsStoredAsALinePerValue() {
    var headers = Headers.builder()
        .add("Set-Cookie", "a=1")
        .add("Content-Type", "text/plain")
        .add("set-cookie", "b=2")
        .build();
    var response = new HttpResponse<>(200, "OK", headers, "done".getBytes());
    String stored = new String(HttpMessage.toBytes(response));
    assertEquals("HTTP/1.1 200 OK\nSet-Cookie:a=1\nContent-Type:text/plain\nset-cookie:b=2\n\ndone", stored);
    var parsed = HttpMessageParser.parseResponse(stored.getBytes());
    assertEquals(List.of("a=1", "b=2"), parsed.headerValues("SET-COOKIE"));
    assertEquals("a=1", parsed.headerValue("Set-Cookie"));
    assertEquals(headers.fields(), parsed.headers().fields());
    assertEquals(response, parsed);
  }

  /// Whitespace around the name and the value of a header line is not part of them
  @Test
  public void headerLinesAreReadWithoutTheWhitespaceAroundNameAndValue() {
    var parsed = HttpMessageParser.parseRequest("POST /orders\nContent-Type:text/plain\nX-Correlation-Id : abc \n\norder".getBytes());
    assertEquals(new HttpRequest<>(Method.POST, URI.create("/orders"), Headers.of("content-type", "text/plain", "X-Correlation-Id", "abc"), "order".getBytes()), parsed);
    assertEquals(List.of(), parsed.headerValues("Cookie"));
  }

  /// A line which isn't a header, e.g. a pseudo-header of HTTP/2, which is never stored, fails to parse
  @Test
  public void headerLineWithoutNameFails() {
    assertThrows(IllegalArgumentException.class, () -> HttpMessageParser.parseResponse("HTTP/1.1 200 OK\n:status:200\n\n{}".getBytes()));
  }

  /// The values of a repeated header may be joined, e.g. by a proxy between a request and a retry of it, which is the
  /// same request (RFC 9110 section 5.3)
  @Test
  public void requestWithValuesJoinedEqualsOneWithThemApart() {
    var joined = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("X-Tag", "a, b"), "order".getBytes());
    var apart = new HttpRequest<>(Method.PUT, URI.create("/orders/1"), Headers.of("x-tag", "a", "X-Tag", "b"), "order".getBytes());
    assertEquals(joined, apart);
    assertEquals(joined.hashCode(), apart.hashCode());
  }

  /// The values of a header are compared in order, as the order of e.g. the cookies set matters, but not the order of
  /// headers of different names
  @Test
  public void valuesOfAHeaderAreComparedInOrder() {
    var ab = Headers.builder().add("Cookie", "a=1").add("Accept", "text/plain").add("Cookie", "b=2").build();
    var ba = Headers.builder().add("Cookie", "b=2").add("Accept", "text/plain").add("Cookie", "a=1").build();
    var acceptFirst = Headers.builder().add("accept", "text/plain").add("cookie", "a=1").add("cookie", "b=2").build();
    assertNotEquals(ab, ba);
    assertEquals(ab, acceptFirst);
    assertEquals(ab.hashCode(), acceptFirst.hashCode());
  }

  /// The data of an event is typed as Object, so a message of bytes is read from it with raw(..)
  @Test
  public void messageOfBytesIsReadFromData() {
    Object response = new HttpResponse<>(200, "OK", Headers.of(), "done".getBytes());
    assertArrayEquals("done".getBytes(), HttpResponse.raw(response).body());
    Object request = new HttpRequest<>(Method.GET, URI.create("/orders"));
    assertEquals(URI.create("/orders"), HttpRequest.raw(request).uri());
    assertThrows(IllegalArgumentException.class, () -> HttpResponse.raw(HttpResponse.create(200, "OK", "a payload")));
    assertThrows(IllegalArgumentException.class, () -> HttpRequest.raw("not a request"));
  }
}
