package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.SeamlessConnectionEntity;
import io.akka.mcp.gateway.application.SeamlessMcpClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@HttpEndpoint("/seamless/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SeamlessOAuthEndpoint extends AbstractDcrOAuthEndpoint {

    private final String seamlessMcpUrl;
    private final String oktaAppId;
    private final String seamlessRedirectUri;

    public SeamlessOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.seamlessMcpUrl = config.getString("seamless.mcp-url");
        this.oktaAppId = config.getString("seamless.okta-app-id");
        this.seamlessRedirectUri = config.getString("seamless.redirect-uri");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(SeamlessConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getMcpUrl() { return seamlessMcpUrl; }
    @Override protected String getRedirectUri() { return seamlessRedirectUri; }
    @Override protected String getProviderLabel() { return "Seamless.AI"; }
    // Seamless advertises scope "mcp.all" in its 401 challenge and supports refresh tokens.
    @Override protected String getScope() { return "mcp.all"; }
    @Override protected List<String> getGrantTypes() { return List.of("authorization_code", "refresh_token"); }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient
                .forKeyValueEntity(email)
                .method(SeamlessConnectionEntity::initiatePkceOAuth)
                .invoke(new SeamlessConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(SeamlessConnectionEntity::getStatus).invoke();
        if (!connection.isValidPendingState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(SeamlessConnectionEntity::storeToken)
                .invoke(new SeamlessConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(SeamlessConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(SeamlessConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override
    protected RemoteMcpClient createMcpClient() { return new SeamlessMcpClient(componentClient, seamlessMcpUrl, oktaAppId); }
}
