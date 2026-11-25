package com.github.thxmasj.backend.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class NettyHttpClientTest {

  /// A client with an access token or HTTP Basic credentials adds Authorization itself, which an outbox refuses in a
  /// request message
  @Test
  public void clientWithCredentialsAddsAuthorization() {
    var netty = new NettyHttpClientBuilder().build();
    assertEquals(Set.of("Authorization"), new NettyHttpClient(netty, () -> "token").fixedHeaders());
    assertEquals(Set.of("Authorization"), new NettyHttpClient(netty, "client", "secret").fixedHeaders());
    assertEquals(Set.of(), new NettyHttpClient(netty).fixedHeaders());
  }
}
