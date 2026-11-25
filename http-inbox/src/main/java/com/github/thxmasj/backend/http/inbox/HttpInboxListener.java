package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.http.Headers;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpResponse;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/// Receives HTTP requests with reactor-netty and feeds them to the HTTP inbox of a state machine. The response is taken
/// from the response event produced by the inbox' request routing.
///
/// The correlation id of a request is decided by the inbox' [HttpInbox#trigger()], e.g. from a header set with
/// [HttpInbox#correlationIdHeader(String)].
///
/// Filters can be added to e.g. authenticate, log, rewrite or short-circuit requests before they reach the inbox.
public class HttpInboxListener {

  /// Intercepts a request on its way to the inbox. A filter can pass the request, possibly modified, on to `next`, or
  /// respond by itself without calling `next`.
  @FunctionalInterface
  public interface Filter {

    Mono<HttpResponse<byte[]>> filter(HttpRequest<byte[]> request, Function<HttpRequest<byte[]>, Mono<HttpResponse<byte[]>>> next);

  }

  private final StateMachine stateMachine;
  private final HttpInbox inbox;
  private final Duration responseTimeout;
  private final List<Filter> filters;

  /// @param stateMachine the state machine created with the entity models of the inbox, see [HttpInbox#entityModels()]
  /// @throws IllegalArgumentException if the state machine doesn't run the request routing of the inbox
  public HttpInboxListener(StateMachine stateMachine, HttpInbox inbox) {
    this(stateMachine, inbox, Duration.ofSeconds(30));
  }

  public HttpInboxListener(StateMachine stateMachine, HttpInbox inbox, Duration responseTimeout) {
    this(stateMachine, inbox, responseTimeout, List.of());
  }

  private HttpInboxListener(
      StateMachine stateMachine,
      HttpInbox inbox,
      Duration responseTimeout,
      List<Filter> filters
  ) {
    if (!stateMachine.runs(inbox.requestRouting()))
      throw new IllegalArgumentException(
          "The state machine doesn't run the request routing of the inbox. Create it with the entity models of the same " +
          "inbox the listener is given."
      );
    this.stateMachine = stateMachine;
    this.inbox = inbox;
    this.responseTimeout = responseTimeout;
    this.filters = List.copyOf(filters);
  }

  /// Returns a listener which also applies the given filter. Filters are applied in the order they are added, so the
  /// first filter added sees the request first.
  public HttpInboxListener filter(Filter filter) {
    List<Filter> filters = new ArrayList<>(this.filters);
    filters.add(filter);
    return new HttpInboxListener(stateMachine, inbox, responseTimeout, filters);
  }

  /// Starts listening on the given port. Use port 0 to bind to an ephemeral port.
  public DisposableServer listen(int port) {
    return listen(HttpServer.create().port(port));
  }

  /// Starts listening with the given server, which can be preconfigured with e.g. host, port, TLS and protocols.
  public DisposableServer listen(HttpServer server) {
    Function<HttpRequest<byte[]>, Mono<HttpResponse<byte[]>>> chain = this::exchange;
    for (Filter filter : filters.reversed()) {
      Function<HttpRequest<byte[]>, Mono<HttpResponse<byte[]>>> next = chain;
      chain = request -> filter.filter(request, next);
    }
    Function<HttpRequest<byte[]>, Mono<HttpResponse<byte[]>>> filteredExchange = chain;
    return server.handle((request, response) -> handle(request, response, filteredExchange)).bindNow();
  }

  private Mono<Void> handle(
      HttpServerRequest request,
      HttpServerResponse response,
      Function<HttpRequest<byte[]>, Mono<HttpResponse<byte[]>>> exchange
  ) {
    Method method;
    try {
      method = Method.valueOf(request.method().name());
    } catch (IllegalArgumentException e) {
      return response.status(HttpResponseStatus.METHOD_NOT_ALLOWED).send();
    }
    // A request with an invalid header or URI is rejected rather than repaired (RFC 9110 section 5.5, RFC 9113 section
    // 8.2.1)
    Headers headers;
    URI uri;
    try {
      headers = Headers.ofRequestEntries(request.requestHeaders().entries());
      uri = URI.create(request.uri());
    } catch (IllegalArgumentException e) {
      return response.status(HttpResponseStatus.BAD_REQUEST).send();
    }
    return request.receive().aggregate().asByteArray()
        .filter(body -> body.length > 0)
        .map(body -> new HttpRequest<>(method, uri, headers, body))
        .switchIfEmpty(Mono.fromSupplier(() -> new HttpRequest<>(method, uri, headers, null)))
        .flatMap(exchange)
        .timeout(responseTimeout)
        .flatMap(message -> send(response, message))
        .onErrorResume(TimeoutException.class, _ -> response.status(HttpResponseStatus.GATEWAY_TIMEOUT).send())
        .onErrorResume(_ -> response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send());
  }

  private Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> requestMessage) {
    return stateMachine.onEvent(inbox.trigger(), requestMessage)
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        // The response as given, which the event log may have masked
        .map(event -> HttpResponse.raw(event.getUnmarshalledData()))
        .switchIfEmpty(Mono.error(() -> new IllegalStateException("No response event for " + requestMessage.requestLine())));
  }

  private static Mono<Void> send(HttpServerResponse response, HttpResponse<byte[]> message) {
    response.status(HttpResponseStatus.valueOf(message.statusCode(), message.reasonPhrase()));
    message.headers().forEach(response::addHeader);
    return message.body() == null
        ? response.send()
        : response.send(Mono.just(Unpooled.wrappedBuffer(message.body()))).then();
  }

}
