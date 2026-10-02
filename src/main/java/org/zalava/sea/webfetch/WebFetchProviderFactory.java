package org.zalava.webfetch;

import java.util.List;
import java.util.Map;
import org.zalava.ProviderFactory;
import org.zalava.ProviderFactoryContext;
import org.zalava.ProviderFactoryDescriptor;
import org.zalava.ZalavaProvider;

/** Creates the single configured bounded web-fetch provider. */
public final class WebFetchProviderFactory implements ProviderFactory {

  public static final String FACTORY_ID = "web-fetch";

  @Override
  public ProviderFactoryDescriptor descriptor() {
    return new ProviderFactoryDescriptor(
        FACTORY_ID,
        WebFetchSeaModule.MODULE_ID,
        "web-fetch",
        "Web Fetch",
        "Fetches public HTTP(S) documents within fixed response bounds.");
  }

  @Override
  public List<ZalavaProvider> createProviders(ProviderFactoryContext context) {
    Map<String, Object> configuration = context == null ? Map.of() : context.configuration();
    return List.of(new WebFetchSeaProvider(FetchLimits.from(configuration)));
  }
}
