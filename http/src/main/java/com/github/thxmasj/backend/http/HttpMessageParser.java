package com.github.thxmasj.backend.http;

import com.github.thxmasj.backend.http.HttpRequest.Method;
import java.net.URI;

public class HttpMessageParser {

  public static HttpRequest<byte[]> parseRequest(byte[] rawMessage) {
    String message = new String(rawMessage);
    String requestLine = message.lines().findFirst().orElseThrow();
    Method method = Method.valueOf(requestLine.substring(0, requestLine.indexOf(" ")));
    URI uri = uri(message);
    Headers headers = HttpMessageParser.headers(message);
    byte[] body = HttpMessageParser.body(message);
    return new HttpRequest<>(method, uri, headers, body);
  }

  public static HttpResponse<byte[]> parseResponse(byte[] rawMessage) {
    String message = new String(rawMessage);
    String statusLine = message.lines().findFirst().orElseThrow();
    var s = statusLine.substring(statusLine.indexOf(" ") + 1);
    int statusCode = Integer.parseInt(s.substring(0, s.indexOf(" ")));
    String reasonPhrase = statusLine.substring(statusLine.lastIndexOf(" ") + 1);
    Headers headers = HttpMessageParser.headers(message);
    byte[] body = HttpMessageParser.body(message);
    return new HttpResponse<>(statusCode, reasonPhrase, headers, body);
  }

  private static URI uri(String message) {
    String startLine = message.lines().findFirst().orElseThrow();
    return URI.create(startLine.substring(message.indexOf(" ") + 1));
  }

  /// The headers of the message, with a value for each header line, in order
  public static Headers headers(String message) {
    return headersOfHead(head(message));
  }

  public static byte[] body(String message) {
    int indexOfBody = indexOfBody(message);
    if (indexOfBody == -1)
      return null;
    String substring = message.substring(indexOfBody);
    return substring.isBlank() ? null : substring.getBytes();
  }

  private static Headers headersOfHead(String head) {
    if (head.isBlank())
      return Headers.none();
    String[] lines = head.split("\n");
    if (lines.length < 2)
      return Headers.none();
    var headers = Headers.builder();
    for (int i = 1; i < lines.length; i++) {
      int separatorIndex = lines[i].indexOf(":");
      if (separatorIndex == -1)
        throw new IllegalStateException("Invalid header -- no separator: <" + lines[i] + ">");
      String name = lines[i].substring(0, separatorIndex).trim();
      // A pseudo-header of HTTP/2, e.g. :status, which responses were stored with before headers were validated, is
      // a line starting with its colon, and not a header
      if (name.isEmpty())
        continue;
      headers.add(name, lines[i].substring(separatorIndex + 1).trim());
    }
    return headers.build();
  }

  private static String head(String message) {
    int indexOfBody = indexOfBody(message);
    return message.substring(0, indexOfBody > -1 ? indexOfBody : message.length());
  }

  private static int indexOfBody(String message) {
    int i = message.indexOf("\n\n");
    return (i > -1) ? i + 2 : i;
  }


}
