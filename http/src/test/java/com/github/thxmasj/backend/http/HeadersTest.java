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

  /// A line break would end the header in the stored message, and a colon in a name would split it elsewhere
  @Test
  public void fieldsWhichCantBeWrittenAsAHeaderLineAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> Headers.of("X-A", "1\r\nX-B: 2"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of("X-A:", "1"));
    assertThrows(IllegalArgumentException.class, () -> Headers.of("", "1"));
    assertThrows(NullPointerException.class, () -> Headers.of("X-A", null));
  }
}
