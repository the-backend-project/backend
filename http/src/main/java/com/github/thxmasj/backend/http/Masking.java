package com.github.thxmasj.backend.http;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// What of the requests and responses of an inbox request, or of an at-most-once outbox request, is masked in the event
/// log. Requests and responses are masked apart: of a request, the values of headers, query parameters and the fields of
/// a form body, and of a response, the values of headers and the whole body. The transitions see the whole request and
/// response, while the event log has [#MASK] in place of what is masked, e.g. of a session cookie, an authorization
/// code or a token.
///
/// ```java
/// .masking(Masking.queryParameters("code").andFormFields("client_secret").andResponseHeaders("Location").andResponseBody())
/// ```
///
/// Every inbox request and at-most-once outbox request masks [#CREDENTIALS], the headers which carry credentials, and
/// what its `masking` adds.
///
/// @param requestHeaders  the names of the headers of a request whose values are masked, compared without case
/// @param queryParameters the names of the query parameters of a request whose values are masked
/// @param formFields      the names of the fields of an `application/x-www-form-urlencoded` body of a request whose
///                        values are masked. A body which isn't declared a form is masked as a whole, as the client may
///                        have sent the fields all the same.
/// @param responseHeaders the names of the headers of a response whose values are masked, compared without case
/// @param wholeResponseBody whether the body of a response is masked as a whole, e.g. one with a token
public record Masking(
    Set<String> requestHeaders,
    Set<String> queryParameters,
    Set<String> formFields,
    Set<String> responseHeaders,
    boolean wholeResponseBody
) {

  /// The value of what is masked in the event log
  public static final String MASK = "***";

  /// Masks nothing
  public static final Masking NONE = new Masking(Set.of(), Set.of(), Set.of(), Set.of(), false);

  /// The headers which carry credentials, masked in the event log of every inbox request and at-most-once outbox
  /// request: `Authorization`, `Proxy-Authorization` and `Cookie` of requests, and `Set-Cookie` of responses
  public static final Masking CREDENTIALS = requestHeaders("Authorization", "Proxy-Authorization", "Cookie").andResponseHeaders("Set-Cookie");

  public Masking {
    requestHeaders = lowerCase(requestHeaders);
    queryParameters = Set.copyOf(queryParameters);
    formFields = Set.copyOf(formFields);
    responseHeaders = lowerCase(responseHeaders);
  }

  private static Set<String> lowerCase(Set<String> names) {
    return names.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
  }

  /// Masks the values of the headers of a request
  public static Masking requestHeaders(String... names) {
    return NONE.andRequestHeaders(names);
  }

  /// Masks the values of the query parameters of a request
  public static Masking queryParameters(String... names) {
    return NONE.andQueryParameters(names);
  }

  /// Masks the values of the fields of a form body of a request
  public static Masking formFields(String... names) {
    return NONE.andFormFields(names);
  }

  /// Masks the values of the headers of a response
  public static Masking responseHeaders(String... names) {
    return NONE.andResponseHeaders(names);
  }

  /// Masks the body of a response
  public static Masking responseBody() {
    return NONE.andResponseBody();
  }

  /// This masking and the values of the headers of a request
  public Masking andRequestHeaders(String... names) {
    return new Masking(union(requestHeaders, names), queryParameters, formFields, responseHeaders, wholeResponseBody);
  }

  /// This masking and the values of the query parameters of a request
  public Masking andQueryParameters(String... names) {
    return new Masking(requestHeaders, union(queryParameters, names), formFields, responseHeaders, wholeResponseBody);
  }

  /// This masking and the values of the fields of a form body of a request
  public Masking andFormFields(String... names) {
    return new Masking(requestHeaders, queryParameters, union(formFields, names), responseHeaders, wholeResponseBody);
  }

  /// This masking and the values of the headers of a response
  public Masking andResponseHeaders(String... names) {
    return new Masking(requestHeaders, queryParameters, formFields, union(responseHeaders, names), wholeResponseBody);
  }

  /// This masking and the body of a response
  public Masking andResponseBody() {
    return new Masking(requestHeaders, queryParameters, formFields, responseHeaders, true);
  }

  /// What this masking or the other masks
  public Masking and(Masking other) {
    return new Masking(
        union(requestHeaders, other.requestHeaders.toArray(String[]::new)),
        union(queryParameters, other.queryParameters.toArray(String[]::new)),
        union(formFields, other.formFields.toArray(String[]::new)),
        union(responseHeaders, other.responseHeaders.toArray(String[]::new)),
        wholeResponseBody || other.wholeResponseBody
    );
  }

  private static Set<String> union(Set<String> set, String... names) {
    var union = new TreeSet<>(set);
    union.addAll(List.of(names));
    return union;
  }

  /// The request as it is stored, with what is masked replaced by [#MASK]
  public HttpRequest<byte[]> mask(HttpRequest<byte[]> request) {
    if (request == null || !appliesTo(request))
      return request;
    return new HttpRequest<>(request.method(), maskQuery(request.uri()), maskHeaders(request.headers(), requestHeaders), maskForm(request));
  }

  /// The response as it is stored, with what is masked replaced by [#MASK]
  public HttpResponse<byte[]> mask(HttpResponse<byte[]> response) {
    if (response == null || !appliesTo(response))
      return response;
    byte[] body = wholeResponseBody && response.body() != null ? MASK.getBytes(UTF_8) : response.body();
    return new HttpResponse<>(response.statusCode(), response.reasonPhrase(), maskHeaders(response.headers(), responseHeaders), body);
  }

  /// Whether masking the request may change it, which is told without its body: it has a header this masking masks, a
  /// query with parameters to mask, or a body with form fields to mask
  public boolean appliesTo(HttpRequest<?> request) {
    return hasMaskedHeader(request.headers(), requestHeaders)
        || (!queryParameters.isEmpty() && request.uri().getRawQuery() != null)
        || (!formFields.isEmpty() && request.body() != null);
  }

  /// Whether masking the response may change it, which is told without its body: it has a header this masking masks, or
  /// a body which is masked
  public boolean appliesTo(HttpResponse<?> response) {
    return hasMaskedHeader(response.headers(), responseHeaders) || (wholeResponseBody && response.body() != null);
  }

  /// Whether this masking masks more of requests than the other one does: other headers, query parameters or form
  /// fields of requests, which a request may differ in from another one stored, unseen. What it masks of responses
  /// doesn't count.
  public boolean masksMoreOfRequestsThan(Masking other) {
    return !other.requestHeaders.containsAll(requestHeaders)
        || !other.queryParameters.containsAll(queryParameters)
        || !other.formFields.containsAll(formFields);
  }

  private static boolean hasMaskedHeader(Map<String, String> original, Set<String> names) {
    return !names.isEmpty() && original.keySet().stream().anyMatch(name -> names.contains(name.toLowerCase(Locale.ROOT)));
  }

  private static Map<String, String> maskHeaders(Map<String, String> original, Set<String> names) {
    if (!hasMaskedHeader(original, names))
      return original;
    var masked = new LinkedHashMap<String, String>();
    original.forEach((name, value) -> masked.put(name, names.contains(name.toLowerCase(Locale.ROOT)) ? MASK : value));
    return masked;
  }

  /// The URI with the query masked. The query starts at the first `?`, which a path can't have, and ends at a
  /// fragment, if any.
  private URI maskQuery(URI uri) {
    if (queryParameters.isEmpty() || uri.getRawQuery() == null)
      return uri;
    String s = uri.toString();
    int query = s.indexOf('?');
    int fragment = s.indexOf('#', query);
    return URI.create(
        s.substring(0, query + 1) + maskParameters(uri.getRawQuery(), queryParameters) + (fragment >= 0 ? s.substring(fragment) : "")
    );
  }

  /// The body of the request with the form fields masked. A body which isn't declared a form is masked as a whole, as
  /// it may have the fields all the same, e.g. sent with another content type by mistake.
  private byte[] maskForm(HttpRequest<byte[]> request) {
    byte[] body = request.body();
    if (formFields.isEmpty() || body == null)
      return body;
    if (!isForm(request))
      return MASK.getBytes(UTF_8);
    return maskParameters(new String(body, UTF_8), formFields).getBytes(UTF_8);
  }

  /// Whether the message is declared a form, i.e. its content type is `application/x-www-form-urlencoded`
  public static boolean isForm(HttpMessage<?> message) {
    String contentType = message.headerValue("Content-Type");
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
  }

  /// The parameters, as written, with the values of those with the names masked. A parameter whose name can't be
  /// decoded is masked as a whole, as it may be one of them.
  private static String maskParameters(String parameters, Set<String> names) {
    return Stream.of(parameters.split("&", -1))
        .map(pair -> {
          int i = pair.indexOf('=');
          String rawName = i < 0 ? pair : pair.substring(0, i);
          String name;
          try {
            name = URLDecoder.decode(rawName, UTF_8);
          } catch (IllegalArgumentException e) {
            return MASK;
          }
          return names.contains(name) ? rawName + "=" + MASK : pair;
        })
        .collect(Collectors.joining("&"));
  }

}
