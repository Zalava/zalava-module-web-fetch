package org.zalava.modules.webfetch;

import java.time.Duration;
import java.util.Map;

record FetchLimits(Duration timeout, int maxResponseBytes, int maxRedirects) {

  static final int MAX_RESPONSE_BYTES = 2_000_000;
  static final int MAX_REDIRECTS = 5;
  private static final int DEFAULT_TIMEOUT_SECONDS = 10;
  private static final int DEFAULT_RESPONSE_BYTES = 500_000;
  private static final int DEFAULT_REDIRECTS = 3;

  static FetchLimits from(Map<String, Object> configuration) {
    Map<String, Object> values = configuration == null ? Map.of() : configuration;
    return new FetchLimits(
        Duration.ofSeconds(integer(values, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS, 1, 30)),
        integer(values, "maxResponseBytes", DEFAULT_RESPONSE_BYTES, 1, MAX_RESPONSE_BYTES),
        integer(values, "maxRedirects", DEFAULT_REDIRECTS, 0, MAX_REDIRECTS));
  }

  private static int integer(
      Map<String, Object> values, String name, int fallback, int minimum, int maximum) {
    Object value = values.get(name);
    if (value == null) {
      return fallback;
    }
    int parsed;
    if (value instanceof Number number) {
      parsed = number.intValue();
    } else if (value instanceof String text) {
      try {
        parsed = Integer.parseInt(text);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(name + " must be an integer", exception);
      }
    } else {
      throw new IllegalArgumentException(name + " must be an integer");
    }
    if (parsed < minimum || parsed > maximum) {
      throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
    }
    return parsed;
  }
}
