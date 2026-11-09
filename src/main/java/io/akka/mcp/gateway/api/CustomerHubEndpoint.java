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
import io.akka.mcp.gateway.application.CustomerHubConnectionEvent;
import io.akka.mcp.gateway.application.CustomerHubConnectionEntity;
import io.akka.mcp.gateway.application.CustomerHubMcpClient;
import io.akka.mcp.gateway.application.HubFailure;
import io.akka.mcp.gateway.application.McpClients;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.ToolRegistry;
import io.akka.mcp.gateway.domain.CustomerHubConnection;
import io.akka.mcp.gateway.domain.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Connects a user to the Customer Hub with a token the hub issued to them. The token is checked with the hub
 * before it is kept, and no route ever returns it or the hub's own error text.
 */
@HttpEndpoint("/customer-hub")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class CustomerHubEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(CustomerHubEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BEARER_PREFIX = Pattern.compile("^Bearer\\s+", Pattern.CASE_INSENSITIVE);

    public record HubStatus(boolean connected) {}

    public record ConnectRequest(String token) {
        @Override
        public String toString() {
            return "ConnectRequest[token=hidden]";
        }
    }

    private final CustomerHubMcpClient client;
    private final String ownOrigin;

    public CustomerHubEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.client = McpClients.customerHubClient(componentClient, httpClientProvider, config);
        this.ownOrigin = originOf(mcpBaseUrl).orElseThrow(() -> new IllegalStateException("mcp.base-url must be an absolute URL"));
    }

    @Get("/status")
    public HttpResponse status() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var connected = session.mayUseApp(client.getRequiredOktaAppId()) && client.isConnected(session.email());
        return HttpResponses.ok(new HubStatus(connected));
    }

    @Post("/connect")
    public HttpResponse connect(ConnectRequest request) {
        var session = requireSession();
        if (session == null) return unauthorized();
        var crossOrigin = crossOriginRefusal();
        if (crossOrigin.isPresent()) return crossOrigin.get();
        var unavailable = unavailableTo(session);
        if (unavailable.isPresent()) {
            client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_BLOCKED);
            return unavailable.get();
        }
        var token = pastedToken(request);
        if (token.isEmpty()) {
            return testResultResponse(StatusCodes.BAD_REQUEST, TestResult.fail("That does not look like a Customer Hub token."));
        }
        client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_ATTEMPT);
        List<RemoteMcpClient.ToolEntry> tools;
        try {
            tools = client.listToolsWith(token.get());
        } catch (Exception e) {
            log.warn("Customer Hub token check failed: {}", HubFailure.describe(e));
            return refused(session, HubFailure.classify(e));
        }
        if (tools.isEmpty()) return noToolsForTheToken(session);
        try {
            componentClient
                    .forKeyValueEntity(session.email())
                    .method(CustomerHubConnectionEntity::connect)
                    .invoke(token.get());
        } catch (Exception e) {
            log.warn("Could not keep the Customer Hub token: {}", HubFailure.describe(e));
            client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_FAILED, "NOT_SAVED");
            return testResultResponse(StatusCodes.SERVICE_UNAVAILABLE, TestResult.fail("The token could not be saved. Try again shortly."));
        }
        recordTools(tools);
        client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_SUCCESS);
        return testResultResponse(StatusCodes.OK, connectedResult(tools));
    }

    @Post("/disconnect")
    public HttpResponse disconnect() {
        var session = requireSession();
        if (session == null) return unauthorized();
        var crossOrigin = crossOriginRefusal();
        if (crossOrigin.isPresent()) return crossOrigin.get();
        componentClient
                .forKeyValueEntity(session.email())
                .method(CustomerHubConnectionEntity::disconnect)
                .invoke();
        client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.DISCONNECT);
        return HttpResponses.ok();
    }

    @Get("/test")
    public HttpResponse test() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var unavailable = unavailableTo(session);
        if (unavailable.isPresent()) return unavailable.get();
        if (!client.isConnected(session.email())) return testResultResponse(StatusCodes.OK, TestResult.fail("Not connected."));
        try {
            var tools = client.listTools(session.email());
            recordTools(tools);
            return testResultResponse(StatusCodes.OK, connectedResult(tools));
        } catch (Exception e) {
            log.warn("Customer Hub connection test failed: {}", HubFailure.describe(e));
            return testResultResponse(StatusCodes.OK, resultFor(HubFailure.classify(e)));
        }
    }

    private HttpResponse noToolsForTheToken(UserSession session) {
        client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_FAILED, "NO_TOOLS");
        return testResultResponse(StatusCodes.BAD_REQUEST, TestResult.fail("The Customer Hub lists no tools for that token."));
    }

    private HttpResponse refused(UserSession session, HubFailure failure) {
        client.recordConnectionEvent(session.email(), CustomerHubConnectionEvent.CONNECT_FAILED, failure.name());
        var status = failure == HubFailure.TOKEN_REJECTED ? StatusCodes.BAD_REQUEST : StatusCodes.BAD_GATEWAY;
        return testResultResponse(status, resultFor(failure));
    }

    /** The refusal to send when the connector is off or the user lacks its Okta application; empty when they may use it. */
    private Optional<HttpResponse> unavailableTo(UserSession session) {
        if (!client.isAvailable()) {
            return Optional.of(testResultResponse(StatusCodes.SERVICE_UNAVAILABLE, TestResult.fail("The Customer Hub is not configured on this gateway.")));
        }
        if (!session.mayUseApp(client.getRequiredOktaAppId())) {
            return Optional.of(testResultResponse(StatusCodes.FORBIDDEN, TestResult.fail("The Customer Hub is not available to you.")));
        }
        return Optional.empty();
    }

    /** A browser always names the page a form or script came from; only the gateway's own pages may change a connection. */
    private Optional<HttpResponse> crossOriginRefusal() {
        var origin = requestContext().requestHeader("Origin").map(HttpHeader::value);
        if (origin.isEmpty() || origin.flatMap(CustomerHubEndpoint::originOf).filter(ownOrigin::equals).isPresent()) return Optional.empty();
        return Optional.of(testResultResponse(StatusCodes.FORBIDDEN, TestResult.fail("Cross-site requests are not accepted.")));
    }

    private void recordTools(List<RemoteMcpClient.ToolEntry> tools) {
        try {
            ToolRegistry.record(componentClient, client, tools.stream().map(RemoteMcpClient.ToolEntry::meta).toList());
        } catch (Exception e) {
            log.warn("Could not cache the Customer Hub tools: {}", HubFailure.describe(e));
        }
    }

    private static Optional<String> pastedToken(ConnectRequest request) {
        if (request == null || request.token() == null) return Optional.empty();
        return Optional.of(BEARER_PREFIX.matcher(request.token().trim()).replaceFirst(""))
                .filter(CustomerHubConnection::isWellFormedToken);
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

    private static TestResult resultFor(HubFailure failure) {
        return TestResult.fail(switch (failure) {
            case TOKEN_REJECTED -> "The Customer Hub did not accept that token. Create a new one in the hub and connect with it.";
            case UNAVAILABLE -> "The Customer Hub could not be reached. Try again shortly.";
            case OTHER -> "The token could not be checked with the Customer Hub.";
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
