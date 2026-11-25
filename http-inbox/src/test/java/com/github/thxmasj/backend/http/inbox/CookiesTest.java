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
    return new HttpRequest<>(Method.GET, URI.create("/"), headers);
  }

  @Test
  public void theCookieIsFoundByItsName() {
    assertEquals("T", Cookies.value(request(Headers.of("Cookie", "a=1; backoffice_session=T")), "backoffice_session"));
    assertNull(Cookies.value(request(Headers.of("Cookie", "a=1")), "backoffice_session"));
    assertNull(Cookies.value(request(Headers.of("Cookie", "backoffice_session=")), "backoffice_session"));
    assertNull(Cookies.value(request(Headers.none()), "backoffice_session"));
  }

  /// The cookies may come in several `Cookie` headers, e.g. split by HTTP/2, which the inbox keeps apart
  @Test
  public void theCookieIsFoundInAnyOfSeveralHeaders() {
    var request = request(Headers.builder().add("Cookie", "a=1; b=2").add("cookie", "backoffice_session=T").build());
    assertEquals("2", Cookies.value(request, "b"));
    assertEquals("T", Cookies.value(request, "backoffice_session"));
    assertNull(Cookies.value(request, "c"));
  }

}
