package com.github.thxmasj.backend.http.inbox;

/// The cookies of a `Cookie` header, e.g. of the session of a user or the secret of a sign-in
public final class Cookies {

  private Cookies() {}

  /// The value of the cookie with the name, or null if the header has none, or one with an empty value
  ///
  /// @param cookies the value of the `Cookie` header, or null if the request has none. The values of a request with
  ///                several `Cookie` headers, e.g. split by HTTP/2, are joined with `, ` by the inbox, so the cookies
  ///                are separated by commas as well as semicolons, which a cookie value can't have (RFC 6265 section
  ///                4.1.1).
  public static String value(String cookies, String name) {
    if (cookies == null)
      return null;
    for (String cookie : cookies.split("[;,]")) {
      int i = cookie.indexOf('=');
      if (i > 0 && cookie.substring(0, i).trim().equals(name)) {
        String value = cookie.substring(i + 1).trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

}
