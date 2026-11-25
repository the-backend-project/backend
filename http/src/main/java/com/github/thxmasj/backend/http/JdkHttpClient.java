package com.github.thxmasj.backend.http;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Mono;

/// An HTTP client of the JDK, e.g. for the token requests an at-most-once outbox sends to an OpenID Connect provider,
/// which the backoffice and the identity provider send with it as they call the provider with the HTTP client of the
/// JDK otherwise. The [NettyHttpClient] can't be built into a native image with its TLS context, which holds a
/// `SecureRandom` created when the image is built.
///
/// A connection which fails is told the outbox as a [ConnectException], as the outbox treats it as a request never
/// sent.
public final class JdkHttpClient implements HttpClient {

  private static final Duration timeout = Duration.ofSeconds(10);
  /// The headers the HTTP client of the JDK sets itself, from the URI and the body of the request, which are left out of
  /// the message rather than refused, as e.g. a request forwarded from an inbox has them. The client has no
  /// [#fixedHeaders()] of its own.
  private static final Set<String> restrictedHeaders = Set.of("connection", "content-length", "expect", "host", "upgrade");

  private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build();

  @Override
  public Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> message) {
    var request = java.net.http.HttpRequest.newBuilder(message.uri()).timeout(timeout);
    message.headers().forEach((name, value) -> {
      if (!restrictedHeaders.contains(name.toLowerCase()))
        request.header(name, value);
    });
    byte[] body = message.body();
    request.method(message.method().name(), body != null ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());
    return Mono.fromFuture(() -> client.sendAsync(request.build(), BodyHandlers.ofByteArray()))
        .map(response -> {
          byte[] responseBody = response.body();
          return new HttpResponse<>(response.statusCode(), "", headers(response.headers()), responseBody != null && responseBody.length > 0 ? responseBody : null);
        })
        .onErrorMap(CompletionException.class, e -> e.getCause() != null ? e.getCause() : e)
        .onErrorMap(HttpConnectTimeoutException.class, e -> (ConnectException) new ConnectException(e.getMessage()).initCause(e));
  }

  /// The headers of a response, with every value of a name, cleaned up as [Headers#ofResponseEntries(List)] does.
  /// Over HTTP/2 the client gives the pseudo-headers too, e.g. `:status`, which are not headers. The client gives the
  /// headers by name, sorted, so unlike those of the [NettyHttpClient] they are not in the order received, but the
  /// values of a name are.
  static Headers headers(java.net.http.HttpHeaders received) {
    var headers = Headers.builder();
    received.map().forEach((name, values) -> {
      if (!name.startsWith(":"))
        values.forEach(value -> headers.add(name, Headers.received(value)));
    });
    return headers.build();
  }

}
