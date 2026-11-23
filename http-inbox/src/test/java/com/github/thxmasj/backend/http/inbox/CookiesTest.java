package com.github.thxmasj.backend.http.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.thxmasj.backend.http.Headers;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class CookiesTest {

  private static HttpRequest<Void> request(Headers headers) {
    return HttpRequest.create(Method.GET, URI.create("/"), headers);
  }

  @Test
  public void cookieIsFoundInAnyCookieHeader() {
    var request = request(Headers.builder().add("Cookie", "a=1; b=2").add("cookie", "c=3").build());
    assertEquals("2", Cookies.value(request, "b"));
    assertEquals("3", Cookies.value(request, "c"));
    assertNull(Cookies.value(request, "d"));
  }

  @Test
  public void cookieWithEmptyValueOrNoCookieHeaderIsNone() {
    assertNull(Cookies.value(request(Headers.of("Cookie", "a=")), "a"));
    assertNull(Cookies.value(request(Headers.none()), "a"));
  }
}
