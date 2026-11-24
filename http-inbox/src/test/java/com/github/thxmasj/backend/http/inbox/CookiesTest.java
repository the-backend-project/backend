package com.github.thxmasj.backend.http.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class CookiesTest {

  @Test
  public void theCookieIsFoundByItsName() {
    assertEquals("T", Cookies.value("a=1; backoffice_session=T", "backoffice_session"));
    assertNull(Cookies.value("a=1", "backoffice_session"));
    assertNull(Cookies.value("backoffice_session=", "backoffice_session"));
    assertNull(Cookies.value(null, "backoffice_session"));
  }

  /// The values of several `Cookie` headers, e.g. split by HTTP/2, are joined with commas by the inbox
  @Test
  public void theCookieIsFoundInTheJoinedValuesOfSeveralHeaders() {
    assertEquals("T", Cookies.value("a=1, backoffice_session=T", "backoffice_session"));
    assertEquals("1", Cookies.value("a=1, backoffice_session=T", "a"));
  }

}
