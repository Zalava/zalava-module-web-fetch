package org.zalava.modules.webfetch;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.zalava.api.ModuleConfigurationDescriptor;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ZalavaModule;

/** SEA module entry point for bounded, read-only web fetching. */
public final class WebFetchSeaModule implements ZalavaModule {

  public static final String MODULE_ID = "zalava-module-web-fetch";
  static final String VERSION = moduleVersion();

  @Override
  public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE_ID,
        VERSION,
        "SEA Web Fetch",
        "Bounded HTTP(S) fetch and deterministic text extraction.");
  }

  @Override
  public List<ProviderFactory> providerFactories() {
    return List.of(new WebFetchProviderFactory());
  }

  @Override
  public ModuleConfigurationDescriptor configuration() {
    return new ModuleConfigurationDescriptor(
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                WebFetchProviderFactory.FACTORY_ID,
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of(
                        "timeoutSeconds", integerSchema(1, 30),
                        "maxResponseBytes", integerSchema(1, FetchLimits.MAX_RESPONSE_BYTES),
                        "maxRedirects", integerSchema(0, FetchLimits.MAX_REDIRECTS)),
                    "additionalProperties",
                    false)),
            "additionalProperties",
            false));
  }

  private static Map<String, Object> integerSchema(int minimum, int maximum) {
    return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
  }

  private static String moduleVersion() {
    try (var stream = WebFetchSeaModule.class.getResourceAsStream("/module.properties")) {
      if (stream == null) throw new IllegalStateException("Missing module.properties");
      Properties properties = new Properties();
      properties.load(stream);
      String version = properties.getProperty("module.version");
      if (version == null || version.isBlank())
        throw new IllegalStateException("Missing module.version");
      return version;
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to read module.properties", exception);
    }
  }
}
