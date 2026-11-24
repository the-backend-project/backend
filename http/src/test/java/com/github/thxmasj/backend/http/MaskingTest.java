package com.github.thxmasj.backend.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.http.HttpRequest.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MaskingTest {

  private static final Map<String, String> form = Map.of("Content-Type", "application/x-www-form-urlencoded");

  @Test
  public void credentialHeadersAreMaskedWhateverTheirCase() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/"), Map.of("authorization", "Bearer x", "COOKIE", "s=1", "Accept", "text/html"), null);
    var masked = Masking.CREDENTIALS.mask(request);
    assertEquals(Headers.of(Map.of("authorization", "***", "COOKIE", "***", "Accept", "text/html")), masked.headers());
    var response = new HttpResponse<byte[]>(302, "Found", Map.of("Set-Cookie", "s=1; HttpOnly", "Location", "/"), null);
    assertEquals(Headers.of(Map.of("Set-Cookie", "***", "Location", "/")), Masking.CREDENTIALS.mask(response).headers());
  }

  /// Every value of a header is masked, e.g. each cookie a response sets, and each Cookie header of a request
  @Test
  public void everyValueOfAHeaderIsMasked() {
    var response = new HttpResponse<byte[]>(302, "Found", Headers.builder().add("Set-Cookie", "a=1").add("Location", "/").add("set-cookie", "b=2").build(), null);
    assertEquals(
        Headers.builder().add("Set-Cookie", "***").add("Location", "/").add("set-cookie", "***").build().fields(),
        Masking.CREDENTIALS.mask(response).headers().fields()
    );
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/"), Headers.builder().add("Cookie", "a=1").add("Cookie", "b=2").build(), null);
    assertEquals(List.of("***", "***"), Masking.CREDENTIALS.mask(request).headerValues("Cookie"));
  }

  @Test
  public void queryParametersAreMaskedAsWrittenKeepingTheOthersAndTheFragment() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("https://rp.example.com/cb?state=a%20b&code=s3cr%2Bt#top"), Map.of(), null);
    var masked = Masking.queryParameters("code").mask(request);
    assertEquals("https://rp.example.com/cb?state=a%20b&code=***#top", masked.uri().toString());
  }

  @Test
  public void aRequestWithNothingToMaskIsTheSame() {
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/cb?state=x"), Map.of("Accept", "*/*"), null);
    assertEquals(request, Masking.queryParameters("code").mask(request));
  }

  @Test
  public void formFieldsAreMaskedOnlyInFormBodies() {
    var masking = Masking.formFields("client_secret", "code");
    var request = new HttpRequest<>(Method.POST, URI.create("/token"), form, "grant_type=authorization_code&code=abc&client_secret=xyz".getBytes(UTF_8));
    assertEquals("grant_type=authorization_code&code=***&client_secret=***", new String(masking.mask(request).body(), UTF_8));
    // A body which isn't declared a form may have the fields all the same, so it is masked as a whole
    var json = new HttpRequest<>(Method.POST, URI.create("/token"), Map.of("Content-Type", "application/json"), "{\"code\":\"abc\"}".getBytes(UTF_8));
    assertEquals("***", new String(masking.mask(json).body(), UTF_8));
  }

  /// What is masked of requests doesn't mask responses: their form fields and headers, e.g. a JSON body with a field of
  /// the same name, or a header of the same name
  @Test
  public void whatIsMaskedOfRequestsLeavesResponsesAsTheyAre() {
    var masking = Masking.CREDENTIALS.andFormFields("code").andRequestHeaders("X-Api-Key");
    var json = new HttpResponse<>(200, "OK", Map.of("Content-Type", "application/json", "Set-Cookie", "s=1", "X-Api-Key", "k"), "{\"code\":\"abc\"}".getBytes(UTF_8));
    var stored = masking.mask(json);
    assertEquals("{\"code\":\"abc\"}", new String(stored.body(), UTF_8));
    assertEquals("***", stored.headerValue("Set-Cookie"));
    assertEquals("k", stored.headerValue("X-Api-Key"));
    assertEquals(false, masking.appliesTo(new HttpResponse<>(200, "OK", form, "code=abc".getBytes(UTF_8))));
  }

  /// What is masked of responses doesn't mask requests, nor counts as masking more of them, so it doesn't stand in the
  /// way of deduplication
  @Test
  public void whatIsMaskedOfResponsesLeavesRequestsAsTheyAre() {
    var masking = Masking.CREDENTIALS.andResponseHeaders("Location");
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/cb"), Map.of("Location", "/x"), null);
    assertEquals(request, masking.mask(request));
    assertEquals("***", masking.mask(new HttpResponse<byte[]>(302, "Found", Map.of("Location", "/x"), null)).headerValue("Location"));
    assertEquals(false, masking.masksMoreOfRequestsThan(Masking.CREDENTIALS));
    assertEquals(true, Masking.CREDENTIALS.andRequestHeaders("X-Api-Key").masksMoreOfRequestsThan(Masking.CREDENTIALS));
  }

  /// An empty body has no fields to mask, so it is stored empty, not as a body the client never sent
  @Test
  public void anEmptyBodyIsStoredEmpty() {
    var empty = new HttpRequest<>(Method.POST, URI.create("/token"), Map.of(), new byte[0]);
    assertEquals(0, Masking.formFields("code").mask(empty).body().length);
  }

  @Test
  public void aParameterWhoseNameCantBeDecodedIsMaskedAsAWhole() {
    var request = new HttpRequest<>(Method.POST, URI.create("/token"), form, "a=1&%zz=2".getBytes(UTF_8));
    assertEquals("a=1&***", new String(Masking.formFields("code").mask(request).body(), UTF_8));
  }

  @Test
  public void theBodyOfAResponseIsMaskedAsAWhole() {
    var response = new HttpResponse<>(200, "OK", Map.of("Content-Type", "application/json"), "{\"access_token\":\"x\"}".getBytes(UTF_8));
    assertEquals("***", new String(Masking.responseBody().mask(response).body(), UTF_8));
    assertEquals(null, Masking.responseBody().mask(new HttpResponse<byte[]>(204, "No Content", Map.of(), null)).body());
  }

  @Test
  public void maskingsAreCombined() {
    var masking = Masking.CREDENTIALS.and(Masking.queryParameters("code").andRequestHeaders("X-Api-Key"));
    var request = new HttpRequest<byte[]>(Method.GET, URI.create("/cb?code=1"), Map.of("X-Api-Key", "k", "Cookie", "c"), null);
    var masked = masking.mask(request);
    assertEquals(Headers.of(Map.of("X-Api-Key", "***", "Cookie", "***")), masked.headers());
    assertEquals("/cb?code=***", masked.uri().toString());
  }

  /// A form field is masked also when its name is written encoded
  @Test
  public void aFormFieldWithAnEncodedNameIsMasked() {
    var request = new HttpRequest<>(Method.POST, URI.create("/token"), form, "client%5Fsecret=s3cret&x=1".getBytes(UTF_8));
    assertEquals("client%5Fsecret=***&x=1", new String(Masking.formFields("client_secret").mask(request).body(), UTF_8));
  }

  @Test
  public void maskingAppliesOnlyToMessagesWithSomethingToMask() {
    var plain = new HttpRequest<byte[]>(Method.POST, URI.create("/x"), Map.of("Content-Type", "application/json"), "{}".getBytes(UTF_8));
    assertEquals(false, Masking.CREDENTIALS.appliesTo(plain));
    assertEquals(true, Masking.formFields("code").appliesTo(plain));
    assertEquals(true, Masking.CREDENTIALS.appliesTo(new HttpRequest<byte[]>(Method.GET, URI.create("/x"), Map.of("Cookie", "c"), null)));
    assertEquals(true, Masking.queryParameters("code").appliesTo(new HttpRequest<byte[]>(Method.GET, URI.create("/x?code=1"), Map.of(), null)));
    assertEquals(false, Masking.responseBody().appliesTo(new HttpResponse<byte[]>(204, "No Content", Map.of(), null)));
    assertEquals(true, Masking.CREDENTIALS.appliesTo(new HttpResponse<byte[]>(200, "OK", Map.of("Set-Cookie", "s=1"), null)));
    assertEquals(false, Masking.CREDENTIALS.appliesTo(new HttpResponse<>(201, "Created", Map.of("Content-Type", "application/json"), "{}".getBytes(UTF_8))));
  }

}
