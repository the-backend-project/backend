package com.github.thxmasj.backend.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.http.Headers.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class HeadersTest {

  private final Headers headers = Headers.builder()
      .add("Set-Cookie", "a=1")
      .add("Content-Type", "text/plain")
      .add("set-cookie", "b=2")
      .build();

  @Test
  public void namesAreLookedUpWithoutCase() {
    assertEquals(Optional.of("a=1"), headers.firstValue("SET-COOKIE"));
    assertEquals(List.of("a=1", "b=2"), headers.allValues("set-cookie"));
    assertEquals(List.of("text/plain"), headers.allValues("content-type"));
    assertTrue(headers.contains("CONTENT-TYPE"));
    assertFalse(headers.contains("Cookie"));
    assertEquals(Optional.empty(), headers.firstValue("Cookie"));
    assertEquals(List.of(), headers.allValues("Cookie"));
  }

  @Test
  public void namesAreAsFirstWrittenInTheOrderTheyFirstAppear() {
    assertEquals(List.of("Set-Cookie", "Content-Type"), List.copyOf(headers.names()));
  }

  @Test
  public void withReplacesTheValuesOfTheNameWhereTheFirstWas() {
    assertEquals(
        List.of(new Field("set-cookie", "c=3"), new Field("Content-Type", "text/plain")),
        headers.with("set-cookie", "c=3").fields()
    );
    assertEquals(new Field("Location", "/"), headers.with("Location", "/").fields().getLast());
  }

  @Test
  public void plusAddsAValueAndWithoutRemovesAllOfTheName() {
    assertEquals(List.of("a=1", "b=2", "c=3"), headers.plus("SET-COOKIE", "c=3").allValues("Set-Cookie"));
    assertEquals(List.of(new Field("Content-Type", "text/plain")), headers.without("SET-COOKIE").fields());
    assertEquals(3, headers.fields().size(), "unchanged");
  }

  @Test
  public void mapGivesOneValuePerNameInItsOrder() {
    var map = new LinkedHashMap<String, String>();
    map.put("B", "2");
    map.put("A", "1");
    assertEquals(List.of(new Field("B", "2"), new Field("A", "1")), Headers.of(map).fields());
    assertTrue(Headers.of(Map.of()).isEmpty());
    assertEquals(Headers.none(), Headers.of(Map.of()));
  }

  /// A line break would end the header in the stored message, a colon in a name would split the line elsewhere, as for
  /// the pseudo-headers of HTTP/2, and whitespace around a name would be lost when it is read back
  @Test
  public void fieldsWhichCantBeStoredAsALineAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> Headers.of("X-A", "1\r\nX-B: 2"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of("X-A:", "1"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of(":status", "200"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of("", "1"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of(" X-A", "1"));
    assertThrows(NullPointerException.class, () -> Headers.of("X-A", null));
  }

  /// A name which isn't a token of RFC 9110, which an application may have made and an earlier version stored, can be
  /// read back, and is compared and hashed consistently, also when it isn't ASCII
  @Test
  public void namesWhichArentTokensAreKeptAndComparedConsistently() {
    assertEquals("1", Headers.of("X Trace", "1").firstValue("x trace").orElseThrow());
    var dotted = Headers.of("\u0130", "1");
    var plain = Headers.of("i", "1");
    assertEquals(dotted, plain);
    assertEquals(dotted.hashCode(), plain.hashCode());
  }

  /// A CR, LF or NUL in a value received is replaced by a space, as RFC 9110 section 5.5 lets a recipient do, rather
  /// than failing the request or response
  @Test
  public void lineBreaksInValuesReceivedAreReplacedBySpaces() {
    var received = Headers.ofEntries(List.of(Map.entry("X-A", "a\rb\nc\0d"), Map.entry("X-B", "e")));
    assertEquals(List.of(new Field("X-A", "a b c d"), new Field("X-B", "e")), received.fields());
  }
}
