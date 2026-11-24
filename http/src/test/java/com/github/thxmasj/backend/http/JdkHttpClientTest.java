package com.github.thxmasj.backend.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdkHttpClientTest {

  /// The client of the JDK gives the pseudo-headers of a response over HTTP/2, e.g. of a provider negotiating it, which
  /// can't be headers of the response, and every value of a header
  @Test
  void responseHeadersAreWithoutPseudoHeadersAndWithEveryValue() {
    var received = HttpHeaders.of(
        Map.of(":status", List.of("200"), "set-cookie", List.of("a=1", "b=2"), "content-type", List.of("application/json")),
        (_, _) -> true
    );
    var headers = JdkHttpClient.headers(received);
    assertThat(headers.names()).containsExactlyInAnyOrder("set-cookie", "content-type");
    assertThat(headers.allValues("Set-Cookie")).containsExactly("a=1", "b=2");
  }
}
