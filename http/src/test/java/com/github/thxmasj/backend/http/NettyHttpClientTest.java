package com.github.thxmasj.backend.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class NettyHttpClientTest {

  /// The access token of the client replaces an Authorization header of the message, as a request with several is
  /// refused
  @Test
  public void accessTokenReplacesTheAuthorizationOfTheMessage() throws Exception {
    var received = new AtomicReference<List<String>>();
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      received.set(exchange.getRequestHeaders().get("Authorization"));
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.start();
    try {
      var client = new NettyHttpClient(new NettyHttpClientBuilder().build(), () -> "token");
      URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/");
      var response = client.exchange(new HttpRequest<byte[]>(Method.GET, uri, Headers.of("Authorization", "Bearer other"), null))
          .block(Duration.ofSeconds(5));
      assertEquals(204, response.statusCode());
      assertEquals(List.of("Bearer token"), received.get());
    } finally {
      server.stop(0);
    }
  }
}
