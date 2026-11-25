package com.github.thxmasj.backend.http;

import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Set;

public interface HttpClient {

  Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> message);

  /// The names of the headers the client adds to every request itself, e.g. `Authorization` with its access token,
  /// which a request message must not have, as it would be sent with both. An outbox fails the transition dispatching
  /// a request message with one of them, before the request is stored or sent.
  default Set<String> fixedHeaders() {
    return Set.of();
  }

  class TimeoutException extends RuntimeException {

    public TimeoutException(Duration elapsedTime, Throwable cause) {
      super(String.format("Client timeout after %s", elapsedTime), cause);
    }

  }
}
