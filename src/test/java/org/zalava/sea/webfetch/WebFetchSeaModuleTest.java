package org.zalava.webfetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.zalava.InvocationContext;
import org.zalava.SeaOperationResult;
import org.zalava.SeaProvider;
import org.zalava.SeaToolDescriptor;
import org.zalava.testing.ConfigFixture;
import org.zalava.testing.ModuleContractKit;
import org.zalava.testing.ProviderFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the released
 * contract kit. A loopback {@code HttpServer} stands in for the public internet and the provider's
 * address policy is replaced with a test double so no external request is made. Host-owned
 * resolution, validation, permissions and persistence stay covered by SEA.
 */
class WebFetchSeaModuleTest {

    private static final String MODULE_ID = "zalava-module-web-fetch";
    private static final String FACTORY_ID = "web-fetch";
    private static final String PROVIDER_ID = "web-fetch";
    private static final String TOOL_NAME = "webFetch";
    private static final String FETCH_LIMITS_TYPE = "org.zalava.webfetch.FetchLimits";
    private static final String PUBLIC_ADDRESS_RESOLVER_TYPE = "org.zalava.webfetch.PublicAddressResolver";
    private static final String PROVIDER_TYPE = "org.zalava.webfetch.WebFetchSeaProvider";

    private ModuleContractKit kit;
    private HttpServer server;

    @BeforeEach
    void loadTheBuiltArtifactAndStartTheFixture() throws IOException {
        kit = ModuleContractKit.load(
                Path.of(System.getProperty("module.artifact")),
                List.of(),
                MODULE_ID,
                System.getProperty("module.version"));

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/text", exchange -> respond(exchange, 200, "text/plain; charset=utf-8", "plain response"));
        server.createContext("/html", exchange ->
                respond(exchange, 200, "text/html", "<html><body><h1>Hello</h1><p>world</p></body></html>"));
        server.createContext("/json", exchange -> respond(exchange, 200, "application/json", "{\"ok\":true}"));
        server.createContext("/image", exchange -> respond(exchange, 200, "image/png", "not-an-image"));
        server.createContext("/large", exchange -> respond(exchange, 200, "text/plain", "x".repeat(64)));
        server.createContext("/redirect", exchange -> redirect(exchange, "/text"));
        server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "text/plain", "too late");
        });
        server.start();
    }

    @AfterEach
    void closeTheArtifact() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (kit != null) {
            kit.close();
        }
    }

    @Test
    void loadsTheModuleFromTheBuiltArtifact() {
        assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
        assertThat(kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
                .endsWith(".jar");
    }

    @Test
    void exposesTheModuleOwnedDescriptorAndConfigurationContract() {
        assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
        assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));
        assertThat(kit.module().descriptor().displayName()).isEqualTo("SEA Web Fetch");

        Map<String, Object> schema = kit.module().configuration().jsonSchema();
        assertThat(schema).containsEntry("type", "object").containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey(FACTORY_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> factory = (Map<String, Object>) properties.get(FACTORY_ID);
        assertThat(factory).containsEntry("type", "object").containsEntry("additionalProperties", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> factoryProperties = (Map<String, Object>) factory.get("properties");
        assertThat(factoryProperties)
                .containsKeys("timeoutSeconds", "maxResponseBytes", "maxRedirects");
    }

    @Test
    void createsTheConfiguredProviderAndDeclaresItsReadOnlyTool() {
        try (ProviderFixture providers = kit.providers(ConfigFixture.empty())) {
            SeaProvider provider = providers.requireProvider(PROVIDER_ID);
            assertThat(provider.descriptor().moduleId()).isEqualTo(MODULE_ID);
            assertThat(provider.descriptor().providerType()).isEqualTo("web-fetch");
            assertThat(provider.descriptor().policyTags()).contains("read-only");
            assertThat(provider.listTools().stream().map(SeaToolDescriptor::name)).containsExactly(TOOL_NAME);

            SeaToolDescriptor tool = providers.requireTool(PROVIDER_ID, TOOL_NAME);
            assertThat(tool.sideEffecting()).isFalse();
            assertThat(tool.inputSchema())
                    .containsEntry("type", "object")
                    .containsEntry("required", List.of("url"))
                    .containsEntry("additionalProperties", false);
        }
    }

    @Test
    void appliesScopedLimitsAndRejectsInvalidConfiguration() {
        try (ProviderFixture providers = kit.providers(configuration(Map.of(
                "timeoutSeconds", 5, "maxResponseBytes", 1_024, "maxRedirects", 0)))) {
            assertThat(providers.requireProvider(PROVIDER_ID)).isNotNull();
        }

        assertThatThrownBy(() -> kit.providers(configuration(Map.of("maxRedirects", 6))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxRedirects must be between 0 and 5");
        assertThatThrownBy(() -> kit.providers(configuration(Map.of("timeoutSeconds", 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeoutSeconds must be between 1 and 30");
    }

    @Test
    void fetchesPlainTextAndReportsBoundedMetadata() throws Exception {
        SeaOperationResult result = fetch(provider(Map.of()), "/text");

        assertThat(result.success()).isTrue();
        assertThat(content(result))
                .containsEntry("url", url("/text"))
                .containsEntry("contentType", "text/plain")
                .containsEntry("text", "plain response");
        assertThat(result.metadata()).containsEntry("status", 200).containsEntry("providerId", PROVIDER_ID);
    }

    @Test
    void extractsDeterministicTextFromHtmlAndKeepsJsonVerbatim() throws Exception {
        assertThat(content(fetch(provider(Map.of()), "/html")).get("text")).isEqualTo("Hello world");
        assertThat(content(fetch(provider(Map.of()), "/json")))
                .containsEntry("contentType", "application/json")
                .containsEntry("text", "{\"ok\":true}");
    }

    @Test
    void followsOnlyTheConfiguredNumberOfValidatedRedirects() throws Exception {
        assertThat(fetch(provider(Map.of()), "/redirect").success()).isTrue();

        SeaOperationResult loop = fetch(provider(Map.of("maxRedirects", 1)), "/loop");
        assertThat(loop.success()).isFalse();
        assertThat(content(loop)).containsEntry("code", "REDIRECT_LIMIT");
    }

    @Test
    void rejectsUnsupportedContentTypeOversizedAndSlowResponses() throws Exception {
        assertThat(content(fetch(provider(Map.of()), "/image"))).containsEntry("code", "UNSUPPORTED_CONTENT_TYPE");
        assertThat(content(fetch(provider(Map.of("maxResponseBytes", 16)), "/large")))
                .containsEntry("code", "RESPONSE_TOO_LARGE");
        assertThat(content(fetch(provider(Map.of("timeoutSeconds", 1)), "/slow"))).containsEntry("code", "TIMEOUT");
    }

    @Test
    void returnsStableFailuresForInvalidUrlAndUnknownTool() throws Exception {
        SeaProvider provider = provider(Map.of());

        assertThat(content(provider.callTool(
                TOOL_NAME, arguments().put("url", "file:///etc/passwd"), InvocationContext.system())))
                .containsEntry("code", "INVALID_URL");
        assertThat(content(provider.callTool(
                "notWebFetch", arguments().put("url", url("/text")), InvocationContext.system())))
                .containsEntry("code", "UNKNOWN_TOOL");
    }

    /**
     * Builds the real provider from the built JAR and replaces only its address-policy collaborator
     * with a test double, so the loopback fixture is reachable without encoding that policy here.
     */
    private SeaProvider provider(Map<String, Object> limits) throws ReflectiveOperationException {
        ClassLoader loader = kit.module().getClass().getClassLoader();
        Class<?> limitsType = Class.forName(FETCH_LIMITS_TYPE, true, loader);
        Method from = limitsType.getDeclaredMethod("from", Map.class);
        from.setAccessible(true);
        Object fetchLimits = from.invoke(null, limits);

        Class<?> resolverType = Class.forName(PUBLIC_ADDRESS_RESOLVER_TYPE, true, loader);
        Object resolver = Proxy.newProxyInstance(
                loader,
                new Class<?>[] {resolverType},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "requirePublic":
                            return null;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "test-double-address-resolver";
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });

        Class<?> providerType = Class.forName(PROVIDER_TYPE, true, loader);
        Constructor<?> constructor =
                providerType.getDeclaredConstructor(limitsType, HttpClient.class, resolverType);
        constructor.setAccessible(true);
        return (SeaProvider) constructor.newInstance(fetchLimits, httpClient(), resolver);
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    private SeaOperationResult fetch(SeaProvider provider, String path) {
        return provider.callTool(TOOL_NAME, arguments().put("url", url(path)), InvocationContext.system());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static ConfigFixture configuration(Map<String, Object> values) {
        return ConfigFixture.empty().factoryConfiguration(MODULE_ID, FACTORY_ID, values);
    }

    private static ObjectNode arguments() {
        return JsonNodeFactory.instance.objectNode();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(SeaOperationResult result) {
        return (Map<String, Object>) result.content();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}