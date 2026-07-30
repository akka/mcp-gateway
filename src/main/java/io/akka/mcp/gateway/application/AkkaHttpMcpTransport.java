package io.akka.mcp.gateway.application;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.javasdk.http.HttpClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.RequestBuilder;
import akka.javasdk.http.StrictResponse;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An {@link McpTransport} for the MCP "Streamable HTTP" protocol, equivalent to langchain4j's
 * {@code dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport}, but routing
 * every request through Akka's {@link HttpClientProvider#httpClientFor(String)} instead of
 * opening its own {@code java.net.http.HttpClient}. This lets MCP calls share the runtime's
 * connection pool, default timeouts, and outbound tracing headers, the same as any other
 * outbound call made from an Akka component.
 *
 * <p>One consequence of going through {@link HttpClientProvider}: responses always come back
 * fully buffered ({@link StrictResponse}) rather than streamed line by line. That's transparent
 * for plain JSON responses and for the common single-shot SSE case (one HTTP POST, one
 * {@code data:} event, then the stream closes) — which is all every {@code RemoteMcpClient} in
 * this gateway relies on. It does mean this transport cannot keep a long-lived SSE connection
 * open for server-initiated pushes (the reference implementation's optional "subsidiary
 * channel"); none of the clients here use that feature.
 */
public class AkkaHttpMcpTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(AkkaHttpMcpTransport.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final McpHeadersSupplier customHeadersSupplier;
    private final Duration timeout;
    private final boolean logRequests;
    private final boolean logResponses;
    private final String mcpPath = "/mcp";

    private volatile McpOperationHandler operationHandler;
    private volatile McpInitializeRequest initializeRequest;
    private volatile Runnable onFailureCallback;
    private final AtomicReference<String> mcpSessionId = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<JsonNode>> initializeInProgress = new AtomicReference<>();

    private AkkaHttpMcpTransport(Builder builder) {
        Objects.requireNonNull(builder.httpClientProvider, "httpClientProvider must be set");
        Objects.requireNonNull(builder.url, "url must be set");
        log.info("AkkaHttpMcpTransport connecting to  {}{}", builder.url, mcpPath);
        this.httpClient = builder.httpClientProvider.httpClientFor(builder.url);
        this.customHeadersSupplier = builder.customHeadersSupplier != null
                ? builder.customHeadersSupplier
                : ctx -> Map.of();
        this.timeout = builder.timeout != null ? builder.timeout : Duration.ofSeconds(60);
        this.logRequests = builder.logRequests;
        this.logResponses = builder.logResponses;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void start(McpOperationHandler operationHandler) {
        this.operationHandler = operationHandler;
    }

    @Override
    public CompletableFuture<JsonNode> initialize(McpInitializeRequest operation) {
        this.initializeRequest = operation;
        CompletableFuture<JsonNode> future = execute(new McpCallContext(null, operation), false);
        initializeInProgress.set(future);
        return future
                .thenApply(response -> {
                    initializeInProgress.set(null);
                    return response;
                })
                .thenCompose(response -> execute(new McpCallContext(null, new McpInitializationNotification()), false)
                        .thenApply(ignored -> response));
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage operation) {
        return executeOperationWithResponse(new McpCallContext(null, operation));
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) {
        return execute(context, false);
    }

    @Override
    public void executeOperationWithoutResponse(McpClientMessage operation) {
        executeOperationWithoutResponse(new McpCallContext(null, operation));
    }

    @Override
    public void executeOperationWithoutResponse(McpCallContext context) {
        execute(context, false);
    }

    @Override
    public void checkHealth() {
        // no transport-specific checks right now
    }

    @Override
    public void onFailure(Runnable actionOnFailure) {
        this.onFailureCallback = actionOnFailure;
    }

    /**
     * Returns the MCP session ID assigned by the server, or {@code null} if no session has been
     * established yet (or the server does not use sessions). Captured from the
     * {@code Mcp-Session-Id} response header during initialization and reused on subsequent
     * requests via the same header.
     */
    public String getMcpSessionId() {
        return mcpSessionId.get();
    }

    /**
     * Sets the MCP session ID to send on subsequent requests via the {@code Mcp-Session-Id}
     * header, for resuming a session obtained elsewhere without re-initializing.
     */
    public void setMcpSessionId(String mcpSessionId) {
        this.mcpSessionId.set(mcpSessionId);
    }

    @Override
    public void close() throws IOException {
        // Akka's HttpClient is a thin facade over the actor system's shared connection pool; it
        // owns no per-transport resource (socket, thread, ...) that needs releasing here.
    }

    private CompletableFuture<JsonNode> execute(McpCallContext context, boolean isRetry) {
        Long id = context.message().getId();

        if (!(context.message() instanceof McpInitializeRequest)) {
            CompletableFuture<JsonNode> inProgress = initializeInProgress.get();
            if (inProgress != null) {
                inProgress.join();
            }
        }

        String requestBody;
        try {
            requestBody = OBJECT_MAPPER.writeValueAsString(context.message());
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        if (logRequests) {
            log.info("MCP request: {}", requestBody);
        }

        RequestBuilder<ByteString> request = httpClient.POST(mcpPath)
                .withTimeout(timeout)
                .withRequestBody(ContentTypes.APPLICATION_JSON, requestBody.getBytes(StandardCharsets.UTF_8))
                .addHeader("Accept", "application/json, text/event-stream");

        String sessionId = mcpSessionId.get();
        if (sessionId != null && !(context.message() instanceof McpInitializeRequest)) {
            request = request.addHeader("Mcp-Session-Id", sessionId);
        }

        Map<String, String> extraHeaders = customHeadersSupplier.apply(context);
        if (extraHeaders != null) {
            for (var header : extraHeaders.entrySet()) {
                request = request.addHeader(header.getKey(), header.getValue());
            }
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        if (id != null) {
            operationHandler.startOperation(id, future);
        }

        request.invokeAsync().toCompletableFuture().whenComplete((response, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
                return;
            }
            try {
                onResponse(context, id, response, isRetry, future);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private void onResponse(
            McpCallContext context,
            Long id,
            StrictResponse<ByteString> response,
            boolean isRetry,
            CompletableFuture<JsonNode> future) throws IOException {

        response.httpResponse().getHeader("Mcp-Session-Id")
                .map(HttpHeader::value)
                .ifPresent(mcpSessionId::set);

        if (!response.status().isSuccess()) {
            if (response.status().intValue() == 404
                    && !(context.message() instanceof McpInitializeRequest)
                    && !isRetry) {
                // Session likely expired/unknown to the server: reinitialize and retry once,
                // mirroring StreamableHttpMcpTransport's own 404 recovery.
                initialize(initializeRequest)
                        .thenCompose(ignored -> execute(context, true))
                        .whenComplete((node, err) -> {
                            if (err != null) future.completeExceptionally(err);
                            else future.complete(node);
                        });
                return;
            }
            future.completeExceptionally(
                    new RuntimeException("Unexpected status code: " + response.status().intValue()));
            return;
        }

        String responseBody = response.body().utf8String();
        if (logResponses) {
            log.info("MCP response: {}", responseBody);
        }

        if (responseBody.isBlank()) {
            // e.g. a 202/204 for a fire-and-forget notification
            if (id == null) future.complete(null);
            return;
        }

        var mediaType = response.httpResponse().entity().getContentType().mediaType();
        boolean isSse = "text".equals(mediaType.mainType()) && "event-stream".equals(mediaType.subType());

        for (JsonNode node : isSse ? parseSseMessages(responseBody) : List.of(OBJECT_MAPPER.readTree(responseBody))) {
            operationHandler.handle(node);
        }
        // For requests with an id, operationHandler.handle(...) above resolves `future` itself:
        // it was registered against that id via startOperation(), sharing the same pending-ops map.
        if (id == null) {
            future.complete(null);
        }
    }

    /**
     * The whole SSE body arrives at once (no incremental delivery via {@link HttpClientProvider}),
     * so this splits it into its individual events after the fact, rather than subscribing to
     * each line as the reference {@code SseSubscriber} does.
     */
    private static List<JsonNode> parseSseMessages(String body) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        for (String eventBlock : body.split("\n\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : eventBlock.split("\n")) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).strip());
                }
            }
            if (data.length() > 0) {
                messages.add(OBJECT_MAPPER.readTree(data.toString()));
            }
        }
        return messages;
    }

    public static class Builder {
        private HttpClientProvider httpClientProvider;
        private String url;
        private McpHeadersSupplier customHeadersSupplier;
        private Duration timeout;
        private boolean logRequests = false;
        private boolean logResponses = false;

        /** The {@link HttpClientProvider} used to obtain the underlying Akka {@link HttpClient}. */
        public Builder httpClientProvider(HttpClientProvider httpClientProvider) {
            this.httpClientProvider = httpClientProvider;
            return this;
        }

        /** The URL of the MCP server. */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /** Fixed request headers sent with every request. */
        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeadersSupplier = ctx -> customHeaders;
            return this;
        }

        /** A supplier for dynamic request headers, called once per request. */
        public Builder customHeaders(Supplier<Map<String, String>> customHeadersSupplier) {
            this.customHeadersSupplier = ctx -> customHeadersSupplier.get();
            return this;
        }

        /** A supplier for dynamic request headers with access to the call context. */
        public Builder customHeaders(McpHeadersSupplier customHeadersSupplier) {
            this.customHeadersSupplier = customHeadersSupplier;
            return this;
        }

        /** The request timeout (applied on the Akka HTTP client level). Defaults to 60 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Whether to log all requests sent over this transport. */
        public Builder logRequests(boolean logRequests) {
            this.logRequests = logRequests;
            return this;
        }

        /** Whether to log all responses received over this transport. */
        public Builder logResponses(boolean logResponses) {
            this.logResponses = logResponses;
            return this;
        }

        public AkkaHttpMcpTransport build() {
            return new AkkaHttpMcpTransport(this);
        }
    }
}
