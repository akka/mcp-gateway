package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.McpClients;
import io.akka.mcp.gateway.application.McpOAuthFlow;
import io.akka.mcp.gateway.application.OAuthGrant;
import io.akka.mcp.gateway.application.OAuthTokenSource;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.TokenConnectionEvent;
import io.akka.mcp.gateway.application.TokenMcpClient;
import io.akka.mcp.gateway.application.UpstreamFailure;
import io.akka.mcp.gateway.domain.OAuthClientRegistration;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Pending;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;
import io.akka.mcp.gateway.domain.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Connects a user to an OAuth token connector by sending the browser to the server's own sign-in. The callback
 * checks the access it got with the server before keeping it, and no route returns it or any text the server sent.
 */
@HttpEndpoint("/token-mcp-oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class TokenMcpOAuthEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TokenMcpOAuthEndpoint.class);
    private static final String CALLBACK_ROUTE = "/callback";
    private static final String STATE_SEPARATOR = ".";
    private static final Set<String> NAVIGATIONS_FROM_THE_GATEWAY = Set.of("same-origin", "none");

    private record SignIn(TokenMcpClient client, OAuthTokenSource source) {
        McpOAuthFlow flow() {
            return source.flow();
        }
    }

    private record Verified(OAuthGrant.Issued issued, List<RemoteMcpClient.ToolEntry> tools) {}

    private final Map<String, SignIn> signInsById;
    private final String redirectUri;

    public TokenMcpOAuthEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.signInsById = McpClients.tokenClients(componentClient, httpClientProvider, config).stream()
                .flatMap(client -> client.bearers() instanceof OAuthTokenSource source ? Stream.of(new SignIn(client, source)) : Stream.empty())
                .collect(Collectors.toMap(signIn -> signIn.client().getMcpId(), Function.identity()));
        this.redirectUri = mcpBaseUrl.replaceAll("/+$", "") + endpointPath(CALLBACK_ROUTE);
    }

    @Get("/{id}/connect")
    public HttpResponse connect(String id) {
        var session = requireSession();
        if (session == null) return redirectToLogin("/");
        var signIn = signInsById.get(id);
        if (signIn == null) return page(StatusCodes.NOT_FOUND, "Unknown connector.");
        if (!startedFromTheGateway()) return page(StatusCodes.FORBIDDEN, "Start the sign-in from the gateway dashboard.");
        var client = signIn.client();
        var unavailable = unavailableTo(client, session);
        if (unavailable.isPresent()) {
            client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_BLOCKED, client.isAvailable() ? "NO_APP" : "NOT_SET_UP");
            return unavailable.get();
        }
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_ATTEMPT);
        try {
            var registration = registrationFor(signIn);
            var state = id + STATE_SEPARATOR + UUID.randomUUID();
            var verifier = McpOAuthFlow.newCodeVerifier();
            signIn.source().begin(session.email(),
                    Pending.startedAt(Instant.now(), state, verifier, registration.clientId(), registration.tokenEndpoint(), signIn.flow().resource()));
            return redirectTo(signIn.flow().authorizeUrl(registration, state, verifier));
        } catch (Exception e) {
            log.warn("{} sign-in could not be started: {}", id, e instanceof McpOAuthFlow.FlowException ? e.getMessage() : UpstreamFailure.describe(e));
            return failed(client, session, "SIGN_IN_NOT_STARTED",
                    page(StatusCodes.BAD_GATEWAY, client.getMcpName() + " sign-in could not be started. Try again shortly."));
        }
    }

    @Get(CALLBACK_ROUTE)
    public HttpResponse callback() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var params = requestContext().queryParams();
        var state = params.getString("state").orElse("");
        var signIn = signInForState(state);
        if (signIn.isEmpty()) return page(StatusCodes.BAD_REQUEST, "Invalid or expired sign-in.");
        var unavailable = unavailableTo(signIn.get().client(), session);
        if (unavailable.isPresent()) return failed(signIn.get().client(), session, "BLOCKED", unavailable.get());
        return complete(signIn.get(), session, state, params.getString("code").filter(code -> !code.isBlank()));
    }

    private HttpResponse complete(SignIn signIn, UserSession session, String state, Optional<String> code) {
        var client = signIn.client();
        var pending = signIn.source().takePending(session.email(), state);
        if (pending.isEmpty()) return failed(client, session, "BAD_STATE", page(StatusCodes.BAD_REQUEST, "Invalid or expired sign-in."));
        if (!pending.get().resource().equals(signIn.flow().resource())) {
            return failed(client, session, "RESOURCE_CHANGED", page(StatusCodes.BAD_REQUEST, "Invalid or expired sign-in."));
        }
        if (code.isEmpty()) return failed(client, session, "DENIED", page(StatusCodes.BAD_REQUEST, "The sign-in was not completed."));
        Verified verified;
        try {
            verified = exchangeAndVerify(signIn, pending.get(), code.get());
        } catch (McpOAuthFlow.FlowException e) {
            log.warn("{} sign-in was refused: {}", client.getMcpId(), e.getMessage());
            if (e.clientRejected()) forgetRegistration(signIn);
            return failed(client, session, "TOKEN_EXCHANGE", page(StatusCodes.BAD_GATEWAY, "The sign-in could not be completed. Try again."));
        } catch (Exception e) {
            log.warn("{} access check failed: {}", client.getMcpId(), UpstreamFailure.describe(e));
            return failed(client, session, UpstreamFailure.classify(e).name(),
                    page(StatusCodes.BAD_GATEWAY, client.getMcpName() + " did not accept the sign-in. Try again."));
        }
        if (verified.tools().isEmpty()) {
            return failed(client, session, "NO_TOOLS", page(StatusCodes.BAD_REQUEST, client.getMcpName() + " lists no tools for you."));
        }
        var issued = verified.issued();
        try {
            signIn.source().connect(session.email(), Tokens.issued(issued.accessToken(), issued.refreshToken(), issued.issuedAt(), issued.expiresAt(),
                    pending.get().clientId(), pending.get().tokenEndpoint(), pending.get().resource()));
        } catch (Exception e) {
            log.warn("Could not keep the {} sign-in: {}", client.getMcpId(), UpstreamFailure.describe(e));
            return failed(client, session, "NOT_SAVED", page(StatusCodes.SERVICE_UNAVAILABLE, "The sign-in could not be saved. Try again shortly."));
        }
        client.cacheTools(verified.tools());
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_SUCCESS);
        return redirectTo("/");
    }

    private Verified exchangeAndVerify(SignIn signIn, Pending pending, String code) throws Exception {
        var issued = signIn.flow().exchangeCode(pending.tokenEndpoint(), pending.clientId(), redirectUri, code, pending.codeVerifier());
        return new Verified(issued, signIn.client().listToolsWith(issued.accessToken()));
    }

    /** One registered client serves every user, so it is registered again only when it is missing, stale, or for another redirect address or resource. */
    private OAuthClientRegistration registrationFor(SignIn signIn) throws McpOAuthFlow.FlowException {
        var kept = signIn.source().keptRegistration()
                .filter(registration -> registration.isReusableFor(redirectUri, signIn.flow().resource(), Instant.now()));
        if (kept.isPresent()) return kept.get();
        var registration = signIn.flow().register(redirectUri, signIn.client().getMcpName() + " via MCP Gateway");
        try {
            signIn.source().keepRegistration(registration);
        } catch (Exception e) {
            log.warn("Could not keep the {} client registration: {}", signIn.client().getMcpId(), UpstreamFailure.describe(e));
        }
        return registration;
    }

    private void forgetRegistration(SignIn signIn) {
        try {
            signIn.source().forgetRegistration();
        } catch (Exception e) {
            log.warn("Could not forget the {} client registration: {}", signIn.client().getMcpId(), UpstreamFailure.describe(e));
        }
    }

    private Optional<SignIn> signInForState(String state) {
        var separator = state.indexOf(STATE_SEPARATOR);
        return separator <= 0 ? Optional.empty() : Optional.ofNullable(signInsById.get(state.substring(0, separator)));
    }

    /** A page another site embeds or links to is marked as such by the browser, so it cannot start a sign-in for a signed-in user. */
    private boolean startedFromTheGateway() {
        return requestContext().requestHeader("Sec-Fetch-Site").map(HttpHeader::value).map(NAVIGATIONS_FROM_THE_GATEWAY::contains).orElse(true);
    }

    private static Optional<HttpResponse> unavailableTo(TokenMcpClient client, UserSession session) {
        if (!client.isAvailable()) {
            return Optional.of(page(StatusCodes.SERVICE_UNAVAILABLE, client.getMcpName() + " is not set up on this gateway."));
        }
        if (!session.mayUseApp(client.getRequiredOktaAppId())) {
            return Optional.of(page(StatusCodes.FORBIDDEN, client.getMcpName() + " is not available to you."));
        }
        return Optional.empty();
    }

    private static HttpResponse failed(TokenMcpClient client, UserSession session, String reason, HttpResponse response) {
        client.recordConnectionEvent(session.email(), TokenConnectionEvent.CONNECT_FAILED, reason);
        return response;
    }

    private static HttpResponse page(StatusCode status, String message) {
        var html = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>MCP Gateway</title><main><h1>Sign-in problem</h1><p>" + escaped(message)
                + "</p><p><a href=\"/\">Back to the dashboard</a></p></main></html>";
        return HttpResponse.create().withStatus(status).withEntity(ContentTypes.TEXT_HTML_UTF8, html);
    }

    private static String escaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
