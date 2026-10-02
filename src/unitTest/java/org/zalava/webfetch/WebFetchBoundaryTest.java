package org.zalava.webfetch;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.zalava.InvocationContext;
import tools.jackson.databind.json.JsonMapper;

class WebFetchBoundaryTest {
  private static final JsonMapper JSON = new JsonMapper();

  @Test
  void validatesLimitsAndUsesDefaults() {
    assertThat(FetchLimits.from(null).maxRedirects()).isEqualTo(3);
    assertThat(FetchLimits.from(Map.of("timeoutSeconds", "2")).timeout())
        .isEqualTo(Duration.ofSeconds(2));
    for (Object value : List.of("bad", true, 0, 31))
      assertThatThrownBy(() -> FetchLimits.from(Map.of("timeoutSeconds", value)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FetchLimits.from(Map.of("maxResponseBytes", 2000001)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FetchLimits.from(Map.of("maxRedirects", -1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new WebFetchProviderFactory().createProviders(null)).hasSize(1);
  }

  @Test
  void rejectsMalformedAndUnresolvableUrlsBeforeNetwork() throws Exception {
    HttpClient client = mock(HttpClient.class);
    WebFetchSeaProvider provider =
        provider(
            client,
            host -> {
              if (host.equals("unknown.test")) throw new UnknownHostException();
            });
    for (String url :
        List.of(
            "",
            "file:///tmp/file",
            "https://user@example.org/",
            "http:/missing",
            "http://[",
            "http://unknown.test")) assertCode(provider, url, "INVALID_URL");
    assertThat(
            provider
                .callTool("unknown", JSON.createObjectNode(), InvocationContext.system())
                .success())
        .isFalse();
    assertThat(
            provider
                .callTool("webFetch", JSON.createObjectNode(), InvocationContext.system())
                .success())
        .isFalse();
    verifyNoInteractions(client);
    for (String host :
        List.of(
            "127.0.0.1",
            "0.0.0.0",
            "10.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.0.1",
            "224.0.0.1",
            "100.64.0.1",
            "::1",
            "fc00::1"))
      assertThatThrownBy(() -> PublicAddressResolver.system().requirePublic(host))
          .isInstanceOf(IllegalArgumentException.class);
    PublicAddressResolver.system().requirePublic("8.8.8.8");
    PublicAddressResolver.system().requirePublic("100.63.0.1");
    PublicAddressResolver.system().requirePublic("100.128.0.1");
    PublicAddressResolver.system().requirePublic("172.15.0.1");
    PublicAddressResolver.system().requirePublic("172.32.0.1");
    PublicAddressResolver.system().requirePublic("2001:4860:4860::8888");
  }

  @Test
  void translatesTransportFailuresAndPreservesInterrupt() throws Exception {
    for (Exception failure :
        List.of(
            new HttpTimeoutException("timeout"),
            new IOException("broken"),
            new InterruptedException("cancelled"))) {
      HttpClient client = mock(HttpClient.class);
      when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
          .thenThrow(failure);
      try {
        assertCode(
            provider(client, host -> {}),
            "https://example.org",
            failure instanceof HttpTimeoutException
                ? "TIMEOUT"
                : failure instanceof InterruptedException ? "INTERRUPTED" : "FETCH_FAILED");
      } finally {
        if (failure instanceof InterruptedException) assertThat(Thread.interrupted()).isTrue();
      }
    }
  }

  @Test
  void handlesRedirectStatusContentAndSizeBoundaries() throws Exception {
    HttpClient client = mock(HttpClient.class);
    WebFetchSeaProvider provider = provider(client, host -> {});
    doReturn(response(302, Map.of(), new ByteArrayInputStream(new byte[0])))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "INVALID_REDIRECT");
    doReturn(response(302, Map.of("Location", List.of(" ")), InputStream.nullInputStream()))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "INVALID_REDIRECT");
    doReturn(response(302, Map.of("Location", List.of("/next")), InputStream.nullInputStream()))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "REDIRECT_LIMIT");
    for (int status : List.of(199, 404)) {
      doReturn(response(status, Map.of(), InputStream.nullInputStream()))
          .when(client)
          .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
      assertCode(provider, "https://example.org", "HTTP_STATUS");
    }
    doReturn(response(200, Map.of(), InputStream.nullInputStream()))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "UNSUPPORTED_CONTENT_TYPE");
    for (String type :
        List.of(
            "text/plain",
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/javascript",
            "application/x-javascript",
            "TEXT/HTML; charset=utf-8")) {
      doReturn(
              response(
                  200,
                  Map.of("Content-Type", List.of(type)),
                  new ByteArrayInputStream("<p>Hello</p>".getBytes())))
          .when(client)
          .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
      assertThat(
              provider
                  .callTool(
                      "webFetch",
                      JSON.createObjectNode().put("url", "https://example.org"),
                      InvocationContext.system())
                  .success())
          .isTrue();
    }
    doReturn(
            response(
                200,
                Map.of("Content-Type", List.of("text/plain")),
                new ByteArrayInputStream(new byte[101])))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "RESPONSE_TOO_LARGE");
    InputStream broken = mock(InputStream.class);
    doThrow(new IOException("close")).when(broken).close();
    doReturn(response(404, Map.of(), broken))
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    assertCode(provider, "https://example.org", "HTTP_STATUS");
  }

  private WebFetchSeaProvider provider(HttpClient client, PublicAddressResolver resolver) {
    return new WebFetchSeaProvider(
        new FetchLimits(Duration.ofSeconds(1), 100, 1), client, resolver);
  }

  private void assertCode(WebFetchSeaProvider provider, String url, String code) {
    var result =
        provider.callTool(
            "webFetch", JSON.createObjectNode().put("url", url), InvocationContext.system());
    assertThat(result.success()).isFalse();
    assertThat(((Map<?, ?>) result.content()).get("code")).isEqualTo(code);
  }

  private HttpResponse<InputStream> response(
      int status, Map<String, List<String>> headers, InputStream stream) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
    when(response.body()).thenReturn(stream);
    return response;
  }
}
