package org.zalava.modules.webfetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.zalava.api.InvocationContext;
import org.zalava.api.ProviderCapabilities;
import org.zalava.api.ProviderDescriptor;
import org.zalava.api.ZalavaOperationResult;
import org.zalava.api.ZalavaProvider;
import org.zalava.api.ZalavaToolDescriptor;
import org.zalava.api.ZalavaToolInputSchemas;

/** A read-only provider that fetches public textual HTTP(S) documents. */
public final class WebFetchZalavaProvider implements ZalavaProvider {

  static final String TOOL_NAME = "webFetch";
  private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
  private static final ZalavaToolDescriptor WEB_FETCH =
      new ZalavaToolDescriptor(
          TOOL_NAME,
          "Fetch a public HTTP(S) document with strict redirect, timeout, size, and content-type limits.",
          false,
          List.of("zalava_backed", "web-fetch", "network", "read-only"),
          ZalavaToolInputSchemas.object(Map.of("url", ZalavaToolInputSchemas.string()), "url"));

  private final FetchLimits limits;
  private final HttpClient client;
  private final PublicAddressResolver addressResolver;
  private final ProviderDescriptor descriptor;

  public WebFetchZalavaProvider(FetchLimits limits) {
    this(
        limits,
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        PublicAddressResolver.system());
  }

  WebFetchZalavaProvider(
      FetchLimits limits, HttpClient client, PublicAddressResolver addressResolver) {
    this.limits = limits;
    this.client = client;
    this.addressResolver = addressResolver;
    this.descriptor =
        new ProviderDescriptor(
            "web-fetch",
            WebFetchZalavaModule.MODULE_ID,
            "web-fetch",
            "Web Fetch",
            "Bounded public HTTP(S) fetch and deterministic text extraction.",
            WebFetchZalavaModule.VERSION,
            ProviderCapabilities.toolsOnly(),
            List.of("zalava_backed", "web-fetch", "network", "read-only"),
            Map.of("network", "public-http(s)"));
  }

  @Override
  public ProviderDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ProviderCapabilities capabilities() {
    return descriptor.capabilities();
  }

  @Override
  public List<ZalavaToolDescriptor> listTools() {
    return List.of(WEB_FETCH);
  }

  @Override
  public ZalavaOperationResult callTool(
      String toolName, java.util.Map<String, Object> argumentValues, InvocationContext context) {
    tools.jackson.databind.JsonNode arguments =
        new tools.jackson.databind.json.JsonMapper().valueToTree(argumentValues);
    if (!TOOL_NAME.equals(toolName)) {
      return failure("UNKNOWN_TOOL", "Unknown web fetch tool");
    }
    String rawUrl = arguments == null ? null : arguments.path("url").asString(null);
    if (rawUrl == null || rawUrl.isBlank()) {
      return failure("INVALID_URL", "A non-blank url is required");
    }
    try {
      return fetch(parseAndValidate(rawUrl));
    } catch (IllegalArgumentException exception) {
      return failure("INVALID_URL", exception.getMessage());
    } catch (java.net.http.HttpTimeoutException exception) {
      return failure("TIMEOUT", "The fetch timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return failure("INTERRUPTED", "The fetch was interrupted");
    } catch (IOException exception) {
      return failure("FETCH_FAILED", "The fetch could not be completed");
    }
  }

  private ZalavaOperationResult fetch(URI initial) throws IOException, InterruptedException {
    URI current = initial;
    for (int redirects = 0; ; redirects++) {
      HttpRequest request =
          HttpRequest.newBuilder(current)
              .timeout(limits.timeout())
              .header(
                  "Accept",
                  "text/html, text/plain, application/json, application/xml, text/xml;q=0.9, */*;q=0.1")
              .header("User-Agent", "zalava-module-web-fetch/" + WebFetchZalavaModule.VERSION)
              .GET()
              .build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      int status = response.statusCode();
      if (REDIRECT_STATUSES.contains(status)) {
        close(response.body());
        if (redirects >= limits.maxRedirects()) {
          return failure("REDIRECT_LIMIT", "The fetch exceeded the redirect limit");
        }
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
          return failure("INVALID_REDIRECT", "The redirect response did not include a location");
        }
        current = parseAndValidate(current.resolve(location).toString());
        continue;
      }
      if (status < 200 || status >= 300) {
        close(response.body());
        return failure("HTTP_STATUS", "The remote server returned HTTP " + status);
      }
      String contentType = response.headers().firstValue("Content-Type").orElse("");
      if (!isTextual(contentType)) {
        close(response.body());
        return failure("UNSUPPORTED_CONTENT_TYPE", "The response content type is not supported");
      }
      byte[] body;
      try (InputStream stream = response.body()) {
        body = readBounded(stream);
      } catch (ResponseTooLargeException exception) {
        return failure("RESPONSE_TOO_LARGE", "The response exceeded the configured byte limit");
      }
      String text = extract(contentType, body);
      return new ZalavaOperationResult(
          true,
          Map.of(
              "url", current.toString(),
              "contentType", normalizedContentType(contentType),
              "text", text),
          Map.of("providerId", descriptor.providerId(), "status", status, "bytes", body.length));
    }
  }

  private URI parseAndValidate(String raw) {
    try {
      URI uri = new URI(raw).normalize();
      String scheme = uri.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        throw new IllegalArgumentException("URL scheme must be http or https");
      }
      if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
        throw new IllegalArgumentException("URL must include a host and no user info");
      }
      addressResolver.requirePublic(uri.getHost());
      return uri;
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("URL is malformed");
    } catch (java.net.UnknownHostException exception) {
      throw new IllegalArgumentException("URL host could not be resolved");
    }
  }

  private byte[] readBounded(InputStream stream) throws IOException, ResponseTooLargeException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8_192];
      for (int read; (read = stream.read(buffer)) >= 0; ) {
        if (output.size() + read > limits.maxResponseBytes()) {
          throw new ResponseTooLargeException();
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static String extract(String contentType, byte[] body) {
    String document = new String(body, StandardCharsets.UTF_8);
    return normalizedContentType(contentType).equals("text/html")
        ? Jsoup.parse(document).text()
        : document;
  }

  private static boolean isTextual(String contentType) {
    String normalized = normalizedContentType(contentType);
    return normalized.startsWith("text/")
        || normalized.equals("application/json")
        || normalized.equals("application/xml")
        || normalized.equals("application/xhtml+xml")
        || normalized.equals("application/javascript")
        || normalized.equals("application/x-javascript");
  }

  private static String normalizedContentType(String contentType) {
    int separator = contentType.indexOf(';');
    return (separator >= 0 ? contentType.substring(0, separator) : contentType)
        .trim()
        .toLowerCase(Locale.ROOT);
  }

  private static ZalavaOperationResult failure(String code, String message) {
    return new ZalavaOperationResult(
        false, Map.of("code", code, "message", message), Map.of("providerId", "web-fetch"));
  }

  private static void close(InputStream stream) {
    try (stream) {
      // Deliberately discard bounded error/redirect bodies.
    } catch (IOException ignored) {
      // The provider must preserve the original stable result.
    }
  }

  private static final class ResponseTooLargeException extends Exception {}
}
