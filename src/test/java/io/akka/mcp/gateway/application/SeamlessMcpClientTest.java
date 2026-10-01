package io.akka.mcp.gateway.application;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public class SeamlessMcpClientTest {

    private static final String MCP_URL = "https://mcp.seamless.ai/mcp";
    private static final String API_KEY = "seamless-api-key-under-test";
    private static final String OKTA_APP_ID = "0oaSeamlessAppIdUnderTest";
    private static final String USER = "user@example.com";

    private static SeamlessMcpClient client() {
        return new SeamlessMcpClient(MCP_URL, API_KEY, OKTA_APP_ID);
    }

    // ---- identity / how-to ----

    @Test
    public void identifiesItselfAsSeamless() {
        assertThat(client().getMcpId()).isEqualTo("seamless");
        assertThat(client().getMcpName()).isEqualTo("Seamless.AI");
    }

    @Test
    public void requiredOktaAppId_isWhateverWasConfigured() {
        assertThat(client().getRequiredOktaAppId()).isEqualTo(OKTA_APP_ID);
    }

    @Test
    public void requiredOktaAppId_blankWhenUnconfigured_soNoUserIsGatedOutByApplicationAssignment() {
        var ungated = new SeamlessMcpClient(MCP_URL, API_KEY, "");
        assertThat(ungated.getRequiredOktaAppId()).isEmpty();
    }

    @Test
    public void canHandle_onlyToolsWithSeamlessPrefix() {
        var client = client();
        assertThat(client.canHandle("Seamless_search_contacts")).isTrue();
        assertThat(client.canHandle("Seamless_get_credits")).isTrue();
        assertThat(client.canHandle("Seamless_read_resource")).isTrue();
        assertThat(client.canHandle("HubSpot_search_contacts")).isFalse();
        assertThat(client.canHandle("search_contacts")).isFalse();
        assertThat(client.canHandle(null)).isFalse();
    }

    @Test
    public void isConnected_whenMcpUrlNotConfigured_isFalse() {
        var unconfigured = new SeamlessMcpClient("", API_KEY, OKTA_APP_ID);
        assertThat(unconfigured.isConnected(USER)).isFalse();
    }

    @Test
    public void isConnected_whenApiKeyNotConfigured_isFalse() {
        var unconfigured = new SeamlessMcpClient(MCP_URL, "", OKTA_APP_ID);
        assertThat(unconfigured.isConnected(USER)).isFalse();
    }

    @Test
    public void isConnected_whenBothConfigured_isTrueWithoutCallingUpstream() {
        assertThat(client().isConnected(USER)).isTrue();
    }

    @Test
    public void howTo_describesUsingSeamlessAndUsesTheDashboardUrl() {
        var howTo = client().howTo("https://gateway.example.com");
        assertThat(howTo.connectToolDescription()).contains("Seamless.AI");
        assertThat(howTo.capabilitiesLine()).isNotBlank();
        assertThat(howTo.markdownBody())
                .contains("Seamless.AI")
                .contains("https://gateway.example.com")
                .contains("Seamless_")
                .contains("no separate")
                .contains("connect step");
    }

    // ---- fake upstream ----

    /** Scripted upstream: each lambda answers one MCP client call; unset calls fail the test. */
    private static class FakeUpstream {
        java.util.function.Supplier<List<ToolSpecification>> listTools = () -> { throw new AssertionError("unexpected listTools"); };
        Function<ToolExecutionRequest, ToolExecutionResult> executeTool = r -> { throw new AssertionError("unexpected executeTool"); };
        Function<String, McpReadResourceResult> readResource = u -> { throw new AssertionError("unexpected readResource"); };
        final AtomicInteger clientsBuilt = new AtomicInteger();
        final List<ToolExecutionRequest> requests = new ArrayList<>();
        final List<String> resourceUris = new ArrayList<>();

        SeamlessMcpClient client() {
            return new SeamlessMcpClient(
                    () -> {
                        clientsBuilt.incrementAndGet();
                        return (McpClient) Proxy.newProxyInstance(
                                McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                    case "listTools" -> listTools.get();
                                    case "executeTool" -> {
                                        var request = (ToolExecutionRequest) args[0];
                                        requests.add(request);
                                        yield executeTool.apply(request);
                                    }
                                    case "readResource" -> {
                                        resourceUris.add((String) args[0]);
                                        yield readResource.apply((String) args[0]);
                                    }
                                    case "close" -> null;
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
                    },
                    MCP_URL, API_KEY, OKTA_APP_ID);
        }
    }

    private static ToolExecutionResult result(String text, boolean isError) {
        return ToolExecutionResult.builder().resultText(text).isError(isError).build();
    }

    // ---- tool listing ----

    @Test
    public void listTools_prefixesUpstreamToolsAndAddsReadResourceTool() throws Exception {
        var upstream = new FakeUpstream();
        upstream.listTools = () -> List.of(ToolSpecification.builder()
                .name("search_contacts").description("Search contacts")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").build())
                .build());

        var entries = upstream.client().listTools(USER);

        var names = entries.stream().map(e -> (String) e.toolSpec().get("name")).toList();
        assertThat(names).containsExactly("Seamless_search_contacts", "Seamless_read_resource");
    }

    @Test
    public void listTools_readResourceToolIsReadOnlyAndRequiresUri() throws Exception {
        var upstream = new FakeUpstream();
        upstream.listTools = () -> List.of(ToolSpecification.builder()
                .name("search_contacts").description("Search contacts")
                .parameters(JsonObjectSchema.builder().addStringProperty("query").build())
                .build());

        var entries = upstream.client().listTools(USER);

        var readResource = entries.stream()
                .filter(e -> "Seamless_read_resource".equals(e.toolSpec().get("name")))
                .findFirst().orElseThrow();
        assertThat(readResource.meta().isWrite()).isFalse();
        assertThat(readResource.toolSpec().get("annotations")).isEqualTo(Map.of("readOnlyHint", true));
        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) readResource.toolSpec().get("inputSchema");
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("uri"));
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties.keySet()).containsExactly("uri");
    }

    /**
     * AkkaMcpGateway only falls back to its cached tool registry when a live {@code listTools}
     * call returns an empty list (a transient upstream hiccup) — otherwise it overwrites the
     * cache with whatever came back. The gateway-provided {@code read_resource} tool must
     * therefore not be appended when Seamless itself returns nothing, or a 0-tool response would
     * never look empty, and the cache would be overwritten with just read_resource — making every
     * other previously-cached Seamless tool look unknown and default to write.
     */
    @Test
    public void listTools_emptyUpstreamResult_returnsEmptyList_soTheGatewayCacheGuardStillWorks() throws Exception {
        var upstream = new FakeUpstream();
        upstream.listTools = List::of;

        var entries = upstream.client().listTools(USER);

        assertThat(entries).isEmpty();
    }

    // ---- read_resource ----

    @Test
    public void readResource_returnsResourceText() throws Exception {
        var upstream = new FakeUpstream();
        upstream.readResource = uri -> new McpReadResourceResult(List.of(
                new McpTextResourceContents(uri, "{\"credits\":5}", "application/json")));

        var result = upstream.client().callTool(USER, "Seamless_read_resource", Map.of("uri", "seamless://credits"));

        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isEqualTo("{\"credits\":5}");
        assertThat(upstream.resourceUris).containsExactly("seamless://credits");
    }

    @Test
    public void readResource_joinsMultipleContents() throws Exception {
        var upstream = new FakeUpstream();
        upstream.readResource = uri -> new McpReadResourceResult(List.of(
                new McpTextResourceContents(uri, "one", "text/plain"),
                new McpTextResourceContents(uri, "two", "text/plain")));

        var result = upstream.client().callTool(USER, "Seamless_read_resource", Map.of("uri", "seamless://templates"));

        assertThat(result.text()).isEqualTo("one\ntwo");
    }

    @Test
    public void readResource_rejectsUriOutsideSeamlessSchemeWithoutCallingUpstream() throws Exception {
        var upstream = new FakeUpstream();

        var result = upstream.client().callTool(USER, "Seamless_read_resource", Map.of("uri", "https://evil.example.com/x"));

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("seamless://");
        assertThat(upstream.clientsBuilt.get()).isZero();
    }

    @Test
    public void readResource_rejectsMissingUriWithoutCallingUpstream() throws Exception {
        var upstream = new FakeUpstream();

        var result = upstream.client().callTool(USER, "Seamless_read_resource", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("uri");
        assertThat(upstream.clientsBuilt.get()).isZero();
    }

    // ---- call forwarding and error reporting ----

    @Test
    public void callTool_stripsPrefixAndForwardsArguments() throws Exception {
        var upstream = new FakeUpstream();
        upstream.executeTool = r -> result("{\"contacts\":[]}", false);

        var result = upstream.client().callTool(USER, "Seamless_search_contacts", Map.of("query", "vp"));

        assertThat(result.text()).isEqualTo("{\"contacts\":[]}");
        assertThat(result.isError()).isFalse();
        assertThat(upstream.requests).hasSize(1);
        assertThat(upstream.requests.get(0).name()).isEqualTo("search_contacts");
        assertThat(upstream.requests.get(0).arguments()).contains("\"query\"").contains("vp");
    }

    @Test
    public void callTool_upstreamErrorResult_reachesUserUnchanged() throws Exception {
        var upstream = new FakeUpstream();
        upstream.executeTool = r -> result("Insufficient credits to research this contact", true);

        var result = upstream.client().callTool(USER, "Seamless_research_contacts", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).isEqualTo("Insufficient credits to research this contact");
    }

    @Test
    public void callTool_upstreamProtocolError_showsUpstreamMessage() throws Exception {
        var upstream = new FakeUpstream();
        upstream.executeTool = r -> { throw new McpException(-32000, "MCP Server access is not enabled for your account"); };

        var result = upstream.client().callTool(USER, "Seamless_search_contacts", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("MCP Server access is not enabled for your account");
        assertThat(result.text()).doesNotContain("temporarily unavailable");
    }

    @Test
    public void callTool_timeout_reportsTemporarilyUnavailable() throws Exception {
        var upstream = new FakeUpstream();
        upstream.executeTool = r -> { throw new RuntimeException(new HttpTimeoutException("request timed out")); };

        var result = upstream.client().callTool(USER, "Seamless_search_contacts", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("Seamless.AI is temporarily unavailable");
    }

    @Test
    public void callTool_connectionFailure_reportsTemporarilyUnavailable() throws Exception {
        var upstream = new FakeUpstream();
        upstream.executeTool = r -> { throw new RuntimeException(new java.net.ConnectException("refused")); };

        var result = upstream.client().callTool(USER, "Seamless_search_contacts", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("Seamless.AI is temporarily unavailable");
    }
}
