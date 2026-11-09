package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.api.AbstractMcpConnectionEndpoint.TestResult;
import io.akka.mcp.gateway.application.McpClients;
import io.akka.mcp.gateway.application.PastedTokenSource;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.TokenConnectionEvent;
import io.akka.mcp.gateway.application.TokenMcpClient;
import io.akka.mcp.gateway.application.ToolRegistry;
import io.akka.mcp.gateway.application.UpstreamFailure;
import io.akka.mcp.gateway.domain.TokenConnection;
import io.akka.mcp.gateway.domain.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Connects a user to a token connector with a token the upstream issued to them. The token is checked with the
 * upstream before it is kept, and no route ever returns it or the upstream's own error text.
 */
@HttpEndpoint("/token-mcp")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class TokenMcpEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TokenMcpEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BEARER_PREFIX = Pattern.compile("^Bearer\\s+", Pattern.CASE_INSENSITIVE);

    /** What the dashboard needs to draw one connector's card. */
    public record Connector(String id, String name, String connectHint) {}

    public record Status(boolean connected) {}

    public record ConnectRequest(String token) {
        @Override
        public String toString() {
            return "ConnectRequest[token=hidden]";
        }
    }

    private final Map<String, TokenMcpClient> clientsById;
    private final Optional<String> ownOrigin;

    public TokenMcpEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.clientsById = McpClients.tokenClients(componentClient, httpClientProvider, config).stream()
                .collect(Collectors.toMap(client -> client.config().id(), Function.identity()));
        this.ownOrigin = originOf(mcpBaseUrl);
    }

    @Get("/connectors")
    public HttpResponse connectors() {
        if (requireSession() == null) return unauthorized();
        return HttpResponses.ok(clientsById.values().stream()
                .filter(TokenMcpClient::isAvailable)
                .map(client -> new Connector(client.getMcpId(), client.getMcpName(), client.config().connectHintOrDefault()))
                .sorted(Comparator.comparing(Connector::name))
                .toList());
    }

    @Get("/{id}/status")
    public HttpResponse status(String id) {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var client = clientsById.get(id);
        if (client == null) return unknownConnector();
        var connection = session.mayUseApp(client.getRequiredOktaAppId())
                ? client.connection(session.email())
                : RemoteMcpClient.Connection.NOT_CONNECTED;
        if (connection == RemoteMcpClient.Connection.CHECK_FAILED) return connectionCheckFailed(StatusCodes.SERVICE_UNAVAILABLE);
        return HttpResponses.ok(new Status(connection == RemoteMcpClient.Connection.CONNECTED));
    }

    @Post("/{id}/connect")
    public HttpResponse connect(String id, ConnectRequest request) {
        var session = requireSession();
        if (session == null) return unauthorized();
        var crossOrigin = crossOriginRefusal();
        if (crossOrigin.isPresent()) return crossOrigin.get();
        var client = clientsById.get(id);
        if (client == null) return unknownConnector();
        return connectWithPastedToken(client, session, request);
    }

    @Post("/{id}/disconnect")
    public HttpResponse disconnect(String id) {
        var session = requireSession();
        if (session == null) return unauthorized();
        var crossOrigin = crossOriginRefusal();
        if (crossOrigin.isPresent()) return crossOrigin.get();
        var client = clientsById.get(id);
        if (client == null) return unknownConnector();
        client.disconnect(session.email());
        return HttpResponses.ok();
    }

    @Get("/{id}/test")
    public HttpResponse test(String id) {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var client = clientsById.get(id);
        if (client == null) return unknownConnector();
        var unavailable = unavailableTo(client, session);
        if (unavailable.isPresent()) return unavailable.get();
        var connection = client.connection(session.email());
        if (connection == RemoteMcpClient.Connection.CHECK_FAILED) return connectionCheckFailed(StatusCodes.OK);
        if (connection == RemoteMcpClient.Connection.NOT_CONNECTED) return testResultResponse(StatusCodes.OK, TestResult.fail("Not connected."));
        try {
            var tools = client.listTools(session.email());
            recordTools(client, tools);
            return testResultResponse(StatusCodes.OK, connectedResult(tools));
        } catch (Exception e) {
            log.warn("{} connection test failed: {}", client.getMcpId(), UpstreamFailure.describe(e));
            return testResultResponse(StatusCodes.OK, resultFor(client, UpstreamFailure.classify(e)));
        }
    }

    private HttpResponse connectWithPastedToken(TokenMcpClient client, UserSession session, ConnectRequest request) {
        var unavailable = unavailableTo(client, session);
        if (unavailable.isPresent()) {
            client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_BLOCKED, client.isAvailable() ? "NO_APP" : "NOT_SET_UP");
            return unavailable.get();
        }
        var token = pastedToken(request);
        if (token.isEmpty()) {
            return testResultResponse(StatusCodes.BAD_REQUEST, TestResult.fail("That does not look like a " + client.getMcpName() + " token."));
        }
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_ATTEMPT);
        List<RemoteMcpClient.ToolEntry> tools;
        try {
            tools = client.listToolsWith(token.get());
        } catch (Exception e) {
            log.warn("{} token check failed: {}", client.getMcpId(), UpstreamFailure.describe(e));
            return refused(client, session, UpstreamFailure.classify(e));
        }
        if (tools.isEmpty()) return noToolsForTheToken(client, session);
        try {
            new PastedTokenSource(componentClient, client.getMcpId()).keep(session.email(), token.get());
        } catch (Exception e) {
            log.warn("Could not keep the {} token: {}", client.getMcpId(), UpstreamFailure.describe(e));
            client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_FAILED, "NOT_SAVED");
            return testResultResponse(StatusCodes.SERVICE_UNAVAILABLE, TestResult.fail("The token could not be saved. Try again shortly."));
        }
        recordTools(client, tools);
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_SUCCESS);
        return testResultResponse(StatusCodes.OK, connectedResult(tools));
    }

    private static HttpResponse unknownConnector() {
        return testResultResponse(StatusCodes.NOT_FOUND, TestResult.fail("Unknown connector."));
    }

    private static HttpResponse connectionCheckFailed(StatusCode status) {
        return testResultResponse(status, TestResult.fail("Your connection could not be checked. Try again shortly."));
    }

    private HttpResponse noToolsForTheToken(TokenMcpClient client, UserSession session) {
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_FAILED, "NO_TOOLS");
        return testResultResponse(StatusCodes.BAD_REQUEST, TestResult.fail(client.getMcpName() + " lists no tools for that token."));
    }

    private HttpResponse refused(TokenMcpClient client, UserSession session, UpstreamFailure failure) {
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_FAILED, failure.name());
        var status = failure == UpstreamFailure.TOKEN_REJECTED ? StatusCodes.BAD_REQUEST : StatusCodes.BAD_GATEWAY;
        return testResultResponse(status, resultFor(client, failure));
    }

    /** The refusal to send when the connector is off or the user lacks its Okta application; empty when they may use it. */
    private static Optional<HttpResponse> unavailableTo(TokenMcpClient client, UserSession session) {
        if (!client.isAvailable()) {
            return Optional.of(testResultResponse(StatusCodes.SERVICE_UNAVAILABLE,
                    TestResult.fail(client.getMcpName() + " is not set up on this gateway.")));
        }
        if (!session.mayUseApp(client.getRequiredOktaAppId())) {
            return Optional.of(testResultResponse(StatusCodes.FORBIDDEN, TestResult.fail(client.getMcpName() + " is not available to you.")));
        }
        return Optional.empty();
    }

    /** A browser names the page a form or script came from; a request that names a page other than the gateway's own is refused. */
    private Optional<HttpResponse> crossOriginRefusal() {
        var sent = requestContext().requestHeader("Origin").map(HttpHeader::value);
        if (sent.isEmpty() || isOwnOrigin(sent.get())) return Optional.empty();
        return Optional.of(testResultResponse(StatusCodes.FORBIDDEN, TestResult.fail("Cross-site requests are not accepted.")));
    }

    private boolean isOwnOrigin(String sent) {
        return ownOrigin.isPresent() && originOf(sent).equals(ownOrigin);
    }

    private void recordTools(TokenMcpClient client, List<RemoteMcpClient.ToolEntry> tools) {
        try {
            ToolRegistry.record(componentClient, client, tools.stream().map(RemoteMcpClient.ToolEntry::meta).toList());
        } catch (Exception e) {
            log.warn("Could not cache the {} tools: {}", client.getMcpId(), UpstreamFailure.describe(e));
        }
    }

    private static Optional<String> pastedToken(ConnectRequest request) {
        if (request == null || request.token() == null) return Optional.empty();
        return Optional.of(BEARER_PREFIX.matcher(request.token().trim()).replaceFirst(""))
                .filter(TokenConnection::isWellFormedToken);
    }

    /** The scheme, host and port a browser would send as an Origin, so that equivalent spellings compare equal. */
    static Optional<String> originOf(String url) {
        try {
            var uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) return Optional.empty();
            var scheme = uri.getScheme().toLowerCase();
            var defaultPort = scheme.equals("https") ? 443 : 80;
            var port = uri.getPort() == -1 || uri.getPort() == defaultPort ? "" : ":" + uri.getPort();
            return Optional.of(scheme + "://" + uri.getHost().toLowerCase() + port);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static TestResult connectedResult(List<RemoteMcpClient.ToolEntry> tools) {
        var toolNames = tools.stream().map(t -> (String) t.toolSpec().get("name")).toList();
        return TestResult.ok("Connected, " + toolNames.size() + " tool(s) available", toolNames);
    }

    private static TestResult resultFor(TokenMcpClient client, UpstreamFailure failure) {
        var name = client.getMcpName();
        return TestResult.fail(switch (failure) {
            case TOKEN_REJECTED -> name + " did not accept that token. Create a new one there and connect with it.";
            case UNAVAILABLE -> name + " could not be reached. Try again shortly.";
            case OTHER -> "The token could not be checked with " + name + ".";
        });
    }

    private static HttpResponse testResultResponse(StatusCode status, TestResult result) {
        try {
            return HttpResponse.create()
                    .withStatus(status)
                    .withEntity(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            return HttpResponses.internalServerError("Could not write the response");
        }
    }
}
