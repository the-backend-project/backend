package com.github.thxmasj.backend.http.outbox;

import static com.github.thxmasj.backend.http.outbox.EventTypes.*;

import com.github.thxmasj.backend.core.*;
import com.github.thxmasj.backend.http.*;
import com.github.thxmasj.backend.core.internal.Debug;
import java.net.*;
import java.time.ZonedDateTime;
import reactor.core.publisher.*;

class ForwardAction implements Action<HttpRequest<byte[]>> {

  private final HttpClient forwarder;

  public ForwardAction(HttpClient forwarder) {
    this.forwarder = forwarder;
  }

  @Override
  public String name() {return "Forward";}

  /// The request message, made in the transition dispatching it, so that one which can't be sent fails the transition,
  /// before it is stored or sent
  ///
  /// @throws IllegalArgumentException if the message has a header the forwarder adds itself, e.g. `Authorization` of a
  ///                                  client with an access token, as it would be sent with both
  HttpRequest<byte[]> message(HttpRequest<byte[]> request) {
    for (String name : forwarder.fixedHeaders())
      if (request.headers().contains(name))
        throw new IllegalArgumentException(
            "The request message has the header " + name + ", which the HTTP client of the outbox adds itself: " + request.requestLine()
        );
    return request;
  }

  @Override
  public Mono<InputEvent<?>> execute(HttpRequest<byte[]> request) {
    // Without the query and the user info, which may have secrets
    var uri = request.uri();
    Debug.log(ZonedDateTime.now() + ": Forwarding " + request.method() + " " + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "") + uri.getRawPath());
    return forwarder.exchange(request)
        .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
        .onErrorResume(ConnectException.class, _ -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
  }
}
