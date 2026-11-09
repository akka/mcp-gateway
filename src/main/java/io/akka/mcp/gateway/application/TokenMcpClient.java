package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.logging.McpLogMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Forwards each user's own credential to an MCP server as {@code Authorization: Bearer}. The upstream validates it
 * on every call and decides what that person may see, on top of the gateway's own Okta application, role and write
 * checks. Whether the gateway keeps the arguments and results of these calls is the connector's setting (see
 * {@link #recordsToolContent()}).
 */
public class TokenMcpClient implements RemoteMcpClient {

    private static final String CONNECTION_EVENT_TOOL = "token";
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final long LISTING_TIMEOUT_SECONDS = 20;
    private static final ExecutorService LISTING_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final int MAX_TOOL_SPEC_CHARS = 8_000;
    private static final Duration TRANSPORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TOOL_EXECUTION_TIMEOUT = TRANSPORT_TIMEOUT.plusSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(TokenMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComponentClient componentClient;
    private final HttpClientProvider httpClientProvider;
    private final TokenMcpConfig config;
    private final BearerSource bearers;
    private final Predicate<String> toolNameTakenByAnotherSystem;
    private final String notConnectedMessage;
    private final String rejectedMessage;
    private final String unavailableMessage;
    private final String failedMessage;

    /** A tool another system already answers to is never listed here, so a connector cannot shadow it. */
    public TokenMcpClient(ComponentClient componentClient, HttpClientProvider httpClientProvider,
                          TokenMcpConfig config, BearerSource bearers, Predicate<String> toolNameTakenByAnotherSystem) {
        this.componentClient = componentClient;
        this.httpClientProvider = httpClientProvider;
        this.config = config;
        this.bearers = bearers;
        this.toolNameTakenByAnotherSystem = toolNameTakenByAnotherSystem;
        this.notConnectedMessage = config.name() + " is not connected. Connect it on the gateway dashboard.";
        this.rejectedMessage = config.name() + " did not accept your token. "
                + "Create a new one there and connect again on the gateway dashboard.";
        this.unavailableMessage = config.name() + " is temporarily unavailable. Please try again shortly.";
        this.failedMessage = config.name() + " request failed. If this keeps happening, contact your administrator.";
    }

    public TokenMcpConfig config() { return config; }

    @Override
    public String getMcpId() { return config.id(); }

    @Override
    public String getMcpName() { return config.name(); }

    @Override
    public String getRequiredOktaAppId() { return config.oktaAppId(); }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        var name = config.name();
        var hint = config.connectHintOrDefault();
        return new HowToContent(
                "Step-by-step instructions for connecting " + name + " to the MCP Gateway.",
                "Your own access to " + name,
                """
                # How to Connect %1$s

                %1$s checks every call against your own access. You connect with a token it issues to you,
                not through a sign-in redirect.

                ## Prerequisites
                - Access to %1$s
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Get a token from %1$s**
                   %2$s

                2. **Log in to the MCP Gateway dashboard**
                   Open %3$s and sign in with Okta.

                3. **Paste the token**
                   Find the %1$s card, paste the token and click **Connect**. The gateway checks it with
                   %1$s before keeping it.

                4. **Verify in your MCP client**
                   Run `tools/list`. %1$s tools are prefixed `%4$s`. If they are missing, call
                   `howto_refresh_tools`.

                ## What the gateway keeps
                The gateway stores your token to forward it, %5$s
                Its history shows that you called a tool, when, and whether it succeeded, plus your connects and
                disconnects.

                ## Troubleshooting
                - "did not accept your token": the token expired or was revoked. Create a new one and connect again.
                - "temporarily unavailable": %1$s could not be reached. Try again shortly.
                """.formatted(name, hint, dashboardUrl, config.toolPrefix(), keptContentSentence()));
    }

    private String keptContentSentence() {
        return config.recordToolContent()
                ? "and keeps the arguments and results of your calls in its history."
                : "and keeps no arguments or results of these calls.";
    }

    @Override
    public boolean recordsToolContent() { return config.recordToolContent(); }

    @Override
    public boolean toolListDependsOnCaller() { return true; }

    @Override
    public boolean isAvailable() {
        return config.isUsable();
    }

    @Override
    public boolean isConnected(String userId) {
        return connection(userId) == Connection.CONNECTED;
    }

    @Override
    public Connection connection(String userId) {
        if (!isAvailable()) return Connection.NOT_CONNECTED;
        try {
            return bearers.isConnected(userId) ? Connection.CONNECTED : Connection.NOT_CONNECTED;
        } catch (Exception e) {
            log.warn("{} connection check failed: {}", config.id(), UpstreamFailure.describe(e));
            return Connection.CHECK_FAILED;
        }
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith(config.toolPrefix());
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        var token = bearers.bearerFor(userId).orElseThrow(() -> new IllegalStateException(notConnectedMessage));
        return listToolsWith(token);
    }

    /** Lists the upstream's tools with a token that is not stored yet, to check it before keeping it. */
    public List<ToolEntry> listToolsWith(String token) throws Exception {
        try (McpClient client = buildClient(token)) {
            return listedWithinTheTimeout(client).stream()
                    .map(spec -> McpSchemaUtils.toolEntry(config.toolPrefix() + spec.name(), spec))
                    .filter(entry -> !toolNameTakenByAnotherSystem.test((String) entry.toolSpec().get("name")))
                    .filter(TokenMcpClient::isAcceptableTool)
                    .limit(McpRegistryEntity.State.MAX_ADDED_TOOLS_PER_SYSTEM)
                    .toList();
        }
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) {
        Optional<String> token;
        try {
            token = bearers.bearerFor(userId);
        } catch (Exception e) {
            log.warn("{} token lookup failed: {}", config.id(), UpstreamFailure.describe(e));
            return new ToolCallResult(failedMessage, true);
        }
        if (token.isEmpty()) return new ToolCallResult(notConnectedMessage, true);
        return callWith(userId, token.get(), toolName, arguments);
    }

    public void disconnect(String userId) {
        bearers.disconnect(userId);
        recordConnectionEvent(userId, TokenConnectionEvent.DISCONNECT);
    }

    public void recordConnectionEvent(String userId, TokenConnectionEvent event) {
        record(new McpInteractionEntity.RecordCommand(userId, config.id(), CONNECTION_EVENT_TOOL, Map.of(), event.direction()));
    }

    /** The detail is shown in the history, so it never holds the token. */
    public void recordConnectionEvent(String userId, TokenConnectionEvent event, String detail) {
        record(new McpInteractionEntity.RecordCommand(userId, config.id(), CONNECTION_EVENT_TOOL, Map.of(), event.direction(), detail));
    }

    /** A server that pages forever, or never answers, must not hold the request open. */
    private static List<ToolSpecification> listedWithinTheTimeout(McpClient client) throws Exception {
        Future<List<ToolSpecification>> listing = LISTING_EXECUTOR.submit(() -> client.listTools());
        try {
            return listing.get(LISTING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } finally {
            listing.cancel(true);
        }
    }

    /** The listing is cached for everyone, so a tool with an unusual name or an oversized definition is left out. */
    static boolean isAcceptableTool(ToolEntry entry) {
        try {
            return TOOL_NAME.matcher((String) entry.toolSpec().get("name")).matches()
                    && MAPPER.writeValueAsString(entry.toolSpec()).length() <= MAX_TOOL_SPEC_CHARS;
        } catch (JsonProcessingException e) {
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
            return answeredByTheUpstream(e) ? upstreamErrorResult(e) : failureResult(userId, token, e);
        } catch (Exception e) {
            return failureResult(userId, token, e);
        }
    }

    /** An error the upstream itself answered with wraps a bare RuntimeException carrying its text; a failed request wraps its own cause. */
    private static boolean answeredByTheUpstream(Exception e) {
        var cause = e.getCause();
        return cause != null && cause.getClass() == RuntimeException.class && cause.getCause() == null;
    }

    private ToolCallResult upstreamErrorResult(Exception e) {
        return new ToolCallResult(e.getMessage() != null ? e.getMessage() : failedMessage, true);
    }

    private ToolCallResult failureResult(String userId, String usedToken, Exception failure) {
        log.warn("{} call failed: {}", config.id(), UpstreamFailure.describe(failure));
        return switch (UpstreamFailure.classify(failure)) {
            case TOKEN_REJECTED -> {
                forgetRejectedToken(userId, usedToken);
                yield new ToolCallResult(rejectedMessage, true);
            }
            case UNAVAILABLE -> new ToolCallResult(unavailableMessage, true);
            case OTHER -> new ToolCallResult(failedMessage, true);
        };
    }

    private void forgetRejectedToken(String userId, String rejected) {
        try {
            if (bearers.forgetIfRefused(userId, rejected)) {
                recordConnectionEvent(userId, TokenConnectionEvent.DISCONNECT, config.name() + " did not accept the token");
            }
        } catch (Exception e) {
            log.warn("Could not disconnect a rejected {} token: {}", config.id(), UpstreamFailure.describe(e));
        }
    }

    private void record(McpInteractionEntity.RecordCommand command) {
        try {
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(command);
        } catch (Exception e) {
            log.warn("Could not record a {} connection event: {}", config.id(), UpstreamFailure.describe(e));
        }
    }

    /** Log messages the upstream sends are its own content, so none is kept. */
    private static void dropUpstreamLogMessage(McpLogMessage message) { }

    private String upstreamName(String toolName) {
        return toolName.startsWith(config.toolPrefix()) ? toolName.substring(config.toolPrefix().length()) : toolName;
    }

    private McpClient buildClient(String token) {
        if (!isAvailable()) throw new IllegalStateException(config.name() + " is not set up");
        var transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(httpClientProvider)
                .url(config.mcpUrl())
                .customHeaders(Map.of("Authorization", "Bearer " + token))
                .timeout(TRANSPORT_TIMEOUT)
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .logHandler(TokenMcpClient::dropUpstreamLogMessage)
                .autoHealthCheck(false)
                .initializationTimeout(INITIALIZATION_TIMEOUT)
                .toolExecutionTimeout(TOOL_EXECUTION_TIMEOUT)
                .toolExecutionTimeoutErrorMessage(unavailableMessage)
                .build();
    }
}
