package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.AkkaSalesforceMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.SalesforceConnectionEntity;
import io.akka.mcp.gateway.application.SalesforceMcpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

@HttpEndpoint("/salesforce/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SalesforceOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String salesforceMcpUrl;
    private final HttpClientProvider httpClientProvider;
    private final String akkaSalesforceMcpUrl;
    private final String tokenEndpoint;
    private final String authorizationEndpoint;
    private final String oktaAppId;
    private final String akkaSalesforceOktaAppId;

    public SalesforceOAuthEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.httpClientProvider = httpClientProvider;
        this.clientId = config.getString("salesforce.oauth.client-id");
        this.clientSecret = config.getString("salesforce.oauth.client-secret");
        this.redirectUri = config.getString("salesforce.oauth.redirect-uri");
        this.salesforceMcpUrl = config.getString("salesforce.mcp-url");
        this.akkaSalesforceMcpUrl = config.getString("akka-salesforce.mcp-url");
        this.oktaAppId = config.getString("salesforce.okta-app-id");
        this.akkaSalesforceOktaAppId = config.getString("akka-salesforce.okta-app-id");
        // Salesforce org login host (e.g. https://your-org.my.salesforce.com); OAuth endpoints derive from it.
        String loginUrl = config.getString("salesforce.oauth.login-url");
        String base = loginUrl.endsWith("/") ? loginUrl.substring(0, loginUrl.length() - 1) : loginUrl;
        this.tokenEndpoint = base + "/services/oauth2/token";
        this.authorizationEndpoint = base + "/services/oauth2/authorize";
    }

    /**
     * The akka-salesforce ("Salesforce Files") connector reuses this same Salesforce OAuth
     * connection — it has no separate auth. Building it here lets the single Salesforce card
     * surface its tools too, so one Connect covers both.
     */
    private AkkaSalesforceMcpClient akkaSalesforceClient() {
        return new AkkaSalesforceMcpClient(componentClient, httpClientProvider, akkaSalesforceMcpUrl, akkaSalesforceOktaAppId);
    }

    @Get("/status")
    public HttpResponse status() { return super.status(); }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(SalesforceConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return clientId; }
    @Override protected String getAuthorizationEndpoint() { return authorizationEndpoint; }
    @Override protected String getTokenEndpoint() { return tokenEndpoint; }
    @Override protected String getScope() { return "refresh_token mcp_api api"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email).method(SalesforceConnectionEntity::initiatePkceOAuth)
                .invoke(new SalesforceConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Get("/connect")
    public HttpResponse connect() { return super.connect(); }

    @Override protected String getRedirectUri() { return redirectUri; }
    @Override protected String getClientSecret() { return clientSecret; }
    @Override protected long getDefaultTokenExpiry() { return 7200; }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(SalesforceConnectionEntity::getStatus).invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(SalesforceConnectionEntity::storeToken)
                .invoke(new SalesforceConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Get("/callback")
    public HttpResponse callback() {
        var response = super.callback();
        // On a successful connect (the only FOUND redirect callback() emits), also warm the
        // Salesforce Files cache, since it rides on the connection just established.
        if (StatusCodes.FOUND.equals(response.status())) {
            var session = requireSession();
            if (session != null) {
                warmRegistryCache(akkaSalesforceClient(), session.email());
            }
        }
        return response;
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(SalesforceConnectionEntity::disconnect).invoke();
    }

    @Get("/disconnect")
    public HttpResponse disconnect() { return super.disconnect(); }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        try {
            return Optional.of(componentClient.forKeyValueEntity(email).method(SalesforceConnectionEntity::getAccessToken).invoke());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Get("/token")
    public HttpResponse token() { return super.token(); }

    @Override protected String getProviderLabel() { return "Salesforce"; }
    @Override protected RemoteMcpClient createMcpClient() { return new SalesforceMcpClient(componentClient, salesforceMcpUrl, oktaAppId); }

    @Get("/test")
    public HttpResponse test() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var email = session.email();

        var primary = testMcpClient(createMcpClient(), email, "Salesforce");
        if (!primary.ok()) return HttpResponses.ok(primary); // hosted Salesforce down → report that

        // Additive/best-effort: fold in Salesforce Files tools when that server is reachable.
        var files = testMcpClient(akkaSalesforceClient(), email, "Salesforce Files");
        var tools = new ArrayList<>(primary.tools());
        if (files.ok()) tools.addAll(files.tools());
        return HttpResponses.ok(TestResult.ok("Connected — " + tools.size() + " tool(s) available", tools));
    }
}
