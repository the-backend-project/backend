package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.http.HttpRequest;

/// The cookies of a request, e.g. of the session of a user or the secret of a sign-in
public final class Cookies {

  private Cookies() {}

  /// The value of the cookie with the name, or null if the request has none, or one with an empty value. The cookies
  /// are read from every `Cookie` header, as a request may have several, e.g. over HTTP/2 (RFC 9113 section 8.2.3).
  public static String value(HttpRequest<?> request, String name) {
    for (String cookies : request.headerValues("Cookie")) {
      for (String cookie : cookies.split(";")) {
        int i = cookie.indexOf('=');
        if (i > 0 && cookie.substring(0, i).trim().equals(name)) {
          String value = cookie.substring(i + 1).trim();
          return value.isEmpty() ? null : value;
        }
      }
    }
    return null;
  }

}
