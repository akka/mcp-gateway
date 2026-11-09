package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Forwards each user's own Customer Hub token as {@code Authorization: Bearer}. The hub validates the token on
 * every call and decides what that person may see, on top of the gateway's own Okta application, role and write
 * checks. The gateway keeps no arguments or results of these calls (see {@link #recordsToolContent()}).
 */
public class CustomerHubMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "customer-hub";
    public static final String MCP_NAME = "Customer Hub";

    private static final String TOOL_PREFIX = "CustomerHub_";
    private static final String NOT_CONNECTED_MESSAGE = "Customer Hub is not connected. Connect it on the gateway dashboard.";
    private static final String REJECTED_MESSAGE = "The Customer Hub did not accept your token. "
            + "Create a new one in the hub and connect again on the gateway dashboard.";
    private static final String UNAVAILABLE_MESSAGE = "The Customer Hub is temporarily unavailable. Please try again shortly.";
    private static final String FAILED_MESSAGE = "The Customer Hub request failed. If this keeps happening, contact your administrator.";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String CONNECTION_EVENT_TOOL = "token";
    private static final Duration TRANSPORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TOOL_EXECUTION_TIMEOUT = TRANSPORT_TIMEOUT.plusSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(CustomerHubMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComponentClient componentClient;
    private final HttpClientProvider httpClientProvider;
    private final String mcpUrl;
    private final String oktaAppId;

    public CustomerHubMcpClient(ComponentClient componentClient, HttpClientProvider httpClientProvider,
                                String mcpUrl, String oktaAppId) {
        this.componentClient = componentClient;
        this.httpClientProvider = httpClientProvider;
        this.mcpUrl = mcpUrl;
        this.oktaAppId = oktaAppId;
    }

    @Override
    public String getMcpId() { return MCP_ID; }

    @Override
    public String getMcpName() { return MCP_NAME; }

    @Override
    public String getRequiredOktaAppId() { return oktaAppId; }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        return new HowToContent(
                "Step-by-step instructions for connecting the Customer Hub to the MCP Gateway.",
                "Customer requests and priorities, within your own Customer Hub access",
                """
                # How to Connect the Customer Hub

                The Customer Hub checks every call against your own access. You connect with a token the hub
                issues to you, not through a sign-in redirect.

                ## Prerequisites
                - Access to the Customer Hub
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Create a token in the Customer Hub**
                   Sign in to the Customer Hub and create an MCP token. Copy it when the hub shows it.

                2. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                3. **Paste the token**
                   Find the Customer Hub card, paste the token and click **Connect**. The gateway checks it with
                   the hub before keeping it.

                4. **Verify in your MCP client**
                   Run `tools/list`. Customer Hub tools are prefixed `CustomerHub_`. If they are missing, call
                   `howto_refresh_tools`.

                ## What the gateway keeps
                The gateway stores your token to forward it to the hub, and keeps no arguments or results of these calls.
                Its history shows that you called a tool, when, and whether it succeeded, plus your connects and
                disconnects.

                ## Troubleshooting
                - "did not accept your token": the token expired or was revoked. Create a new one and connect again.
                - "temporarily unavailable": the hub could not be reached. Try again shortly.
                """.formatted(dashboardUrl));
    }

    @Override
    public boolean recordsToolContent() { return false; }

    @Override
    public boolean toolListDependsOnCaller() { return true; }

    /** Usable only with an Okta application to gate it and a URL that does not send tokens in the clear. */
    @Override
    public boolean isAvailable() {
        return !oktaAppId.isBlank() && isSecureEndpoint(mcpUrl);
    }

    @Override
    public boolean isConnected(String userId) {
        return connection(userId) == Connection.CONNECTED;
    }

    @Override
    public Connection connection(String userId) {
        if (!isAvailable()) return Connection.NOT_CONNECTED;
        try {
            return connectionStatus(userId).connected() ? Connection.CONNECTED : Connection.NOT_CONNECTED;
        } catch (Exception e) {
            log.warn("Customer Hub connection check failed: {}", HubFailure.describe(e));
            return Connection.CHECK_FAILED;
        }
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith(TOOL_PREFIX);
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        var token = storedToken(userId).orElseThrow(() -> new IllegalStateException(NOT_CONNECTED_MESSAGE));
        return listToolsWith(token);
    }

    /** Lists the hub's tools with a token that is not stored yet, to check it before keeping it. */
    public List<ToolEntry> listToolsWith(String token) throws Exception {
        try (McpClient client = buildClient(token)) {
            return client.listTools().stream()
                    .map(spec -> McpSchemaUtils.toolEntry(TOOL_PREFIX + spec.name(), spec))
                    .toList();
        }
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) {
        Optional<String> token;
        try {
            token = storedToken(userId);
        } catch (Exception e) {
            log.warn("Customer Hub token lookup failed: {}", HubFailure.describe(e));
            return new ToolCallResult(FAILED_MESSAGE, true);
        }
        if (token.isEmpty()) return new ToolCallResult(NOT_CONNECTED_MESSAGE, true);
        return callWith(userId, token.get(), toolName, arguments);
    }

    public void recordConnectionEvent(String userId, CustomerHubConnectionEvent event) {
        record(new McpInteractionEntity.RecordCommand(userId, MCP_ID, CONNECTION_EVENT_TOOL, Map.of(), event.direction()));
    }

    /** The detail is shown in the history, so it never holds the token. */
    public void recordConnectionEvent(String userId, CustomerHubConnectionEvent event, String detail) {
        record(new McpInteractionEntity.RecordCommand(userId, MCP_ID, CONNECTION_EVENT_TOOL, Map.of(), event.direction(), detail));
    }

    private static boolean isSecureEndpoint(String url) {
        if (url.isBlank()) return false;
        try {
            var uri = URI.create(url);
            var host = uri.getHost();
            if (host == null || uri.getScheme() == null) return false;
            var scheme = uri.getScheme().toLowerCase();
            return scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(host.toLowerCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private ToolCallResult callWith(String userId, String token, String toolName, Map<String, Object> arguments) {
        try (McpClient client = buildClient(token)) {
            var request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(upstreamName(toolName))
                    .arguments(MAPPER.writeValueAsString(arguments))
                    .build();
            var result = client.executeTool(request);
            return new ToolCallResult(result.resultText(), result.isError());
        } catch (ToolExecutionException | ToolArgumentsException e) {
            return answeredByTheHub(e) ? hubErrorResult(e) : failureResult(userId, token, e);
        } catch (Exception e) {
            return failureResult(userId, token, e);
        }
    }

    /** An error the hub itself answered with wraps a bare RuntimeException carrying its text; a failed request wraps its own cause. */
    private static boolean answeredByTheHub(Exception e) {
        var cause = e.getCause();
        return cause != null && cause.getClass() == RuntimeException.class && cause.getCause() == null;
    }

    private static ToolCallResult hubErrorResult(Exception e) {
        return new ToolCallResult(e.getMessage() != null ? e.getMessage() : FAILED_MESSAGE, true);
    }

    private ToolCallResult failureResult(String userId, String usedToken, Exception failure) {
        log.warn("Customer Hub call failed: {}", HubFailure.describe(failure));
        return switch (HubFailure.classify(failure)) {
            case TOKEN_REJECTED -> {
                forgetRejectedToken(userId, usedToken);
                yield new ToolCallResult(REJECTED_MESSAGE, true);
            }
            case UNAVAILABLE -> new ToolCallResult(UNAVAILABLE_MESSAGE, true);
            case OTHER -> new ToolCallResult(FAILED_MESSAGE, true);
        };
    }

    private void forgetRejectedToken(String userId, String rejected) {
        try {
            var forgotten = componentClient
                    .forKeyValueEntity(userId)
                    .method(CustomerHubConnectionEntity::disconnectIfToken)
                    .invoke(rejected);
            if (forgotten) recordConnectionEvent(userId, CustomerHubConnectionEvent.DISCONNECT, "The Customer Hub did not accept the token");
        } catch (Exception e) {
            log.warn("Could not disconnect a rejected Customer Hub token: {}", HubFailure.describe(e));
        }
    }

    private void record(McpInteractionEntity.RecordCommand command) {
        try {
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(command);
        } catch (Exception e) {
            log.warn("Could not record a Customer Hub connection event: {}", HubFailure.describe(e));
        }
    }

    /** Log messages the hub sends are its own content, so none is kept. */
    private static void dropHubLogMessage(McpLogMessage message) { }

    private static String upstreamName(String toolName) {
        return toolName.startsWith(TOOL_PREFIX) ? toolName.substring(TOOL_PREFIX.length()) : toolName;
    }

    private CustomerHubConnectionEntity.Status connectionStatus(String userId) {
        return componentClient
                .forKeyValueEntity(userId)
                .method(CustomerHubConnectionEntity::getStatus)
                .invoke();
    }

    private Optional<String> storedToken(String userId) {
        return componentClient
                .forKeyValueEntity(userId)
                .method(CustomerHubConnectionEntity::getToken)
                .invoke();
    }

    private McpClient buildClient(String token) {
        if (!isAvailable()) throw new IllegalStateException("The Customer Hub is not configured");
        var transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(httpClientProvider)
                .url(mcpUrl)
                .customHeaders(Map.of("Authorization", "Bearer " + token))
                .timeout(TRANSPORT_TIMEOUT)
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .logHandler(CustomerHubMcpClient::dropHubLogMessage)
                .autoHealthCheck(false)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .toolExecutionTimeout(TOOL_EXECUTION_TIMEOUT)
                .toolExecutionTimeoutErrorMessage(UNAVAILABLE_MESSAGE)
                .build();
    }
}
