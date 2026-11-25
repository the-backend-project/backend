package com.github.thxmasj.backend.http;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public class NettyHttpClient implements HttpClient {

  private final reactor.netty.http.client.HttpClient nettyHttpClient;
  private final Supplier<Map<String, String>> fixedHeaders;
  private final Set<String> fixedHeaderNames;

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = Map::of;
    this.fixedHeaderNames = Set.of();
  }

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient, Supplier<String> accessToken) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = () -> Map.of("Authorization", "Bearer " + accessToken.get());
    this.fixedHeaderNames = Set.of("Authorization");
  }

  public NettyHttpClient(reactor.netty.http.client.HttpClient nettyHttpClient, String clientId, String clientSecret) {
    this.nettyHttpClient = nettyHttpClient;
    this.fixedHeaders = () -> Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes()));
    this.fixedHeaderNames = Set.of("Authorization");
  }

  /// `Authorization`, if the client authenticates with an access token or HTTP Basic, and otherwise none
  @Override
  public Set<String> fixedHeaders() {
    return fixedHeaderNames;
  }

  @Override
  public Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> message) {
    long t0 = System.nanoTime();
    return nettyHttpClient
        .headers(h -> message.headers().forEach(h::add))
        .headers(h -> fixedHeaders.get().forEach(h::add))
        .request(switch (message.method()) {
          case GET -> HttpMethod.GET;
          case POST -> HttpMethod.POST;
          case PUT -> HttpMethod.PUT;
          case DELETE -> HttpMethod.DELETE;
        })
        .uri(message.uri())
        .send(message.body() == null ? Mono.empty() : Mono.just(Unpooled.wrappedBuffer((message.body()))))
        .response((httpClientResponse, buf) -> buf.aggregate().asByteArray()
            .map(body -> new HttpResponse<>(
                    httpClientResponse.status().code(),
                    httpClientResponse.status().reasonPhrase(),
                    Headers.ofResponseEntries(httpClientResponse.responseHeaders().entries()),
                    body
                )
            )
          .defaultIfEmpty(new HttpResponse<>(
              httpClientResponse.status().code(),
              httpClientResponse.status().reasonPhrase(),
              Headers.ofResponseEntries(httpClientResponse.responseHeaders().entries())
          ))
        )
        .single()
        .onErrorMap(
            io.netty.handler.timeout.TimeoutException.class,
            t -> new TimeoutException(Duration.ofNanos(System.nanoTime() - t0), t)
        )
        .onErrorMap(
            io.netty.handler.timeout.ReadTimeoutException.class,
            t -> new TimeoutException(Duration.ofNanos(System.nanoTime() - t0), t)
        );
  }

}
