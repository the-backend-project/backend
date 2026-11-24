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

  @Override
  public Mono<InputEvent<?>> execute(HttpRequest<byte[]> request) {
    // Without the query, which may have a secret
    Debug.log(ZonedDateTime.now() + ": Forwarding " + request.method() + " " + request.uri().getScheme() + "://" + request.uri().getAuthority() + request.uri().getRawPath());
    return forwarder.exchange(request)
        .<InputEvent<?>>map(response -> new InputEvent<>(ResponseReceived, response))
        .onErrorResume(ConnectException.class, _ -> Mono.just(new InputEvent<>(ConnectionFailed, null)));
  }
}
