package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.ZohoConnectionEntity;
import io.akka.mcp.gateway.application.ZohoMcpClient;
import java.time.Instant;
import java.util.Optional;

// ── Zoho Desk OAuth 2.1 (MCP discovery + PKCE + DCR) ─────────────────────

@HttpEndpoint("/zoho/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class ZohoDeskOAuthEndpoint extends AbstractDcrOAuthEndpoint {

    private final String zohoMcpUrl;
    private final String oktaAppId;
    private final String zohoRedirectUri;

    public ZohoDeskOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.zohoMcpUrl = config.getString("zoho.mcp-url");
        this.oktaAppId = config.getString("zoho.okta-app-id");
        this.zohoRedirectUri = config.getString("zoho.redirect-uri");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(ZohoConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getMcpUrl() { return zohoMcpUrl; }
    @Override protected String getRedirectUri() { return zohoRedirectUri; }
    @Override protected String getProviderLabel() { return "Zoho Desk"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient
                .forKeyValueEntity(email)
                .method(ZohoConnectionEntity::initiatePkceOAuth)
                .invoke(new ZohoConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(ZohoConnectionEntity::getStatus).invoke();
        if (!connection.isValidPendingState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(ZohoConnectionEntity::storeToken)
                .invoke(new ZohoConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(ZohoConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(ZohoConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override
    protected RemoteMcpClient createMcpClient() { return new ZohoMcpClient(componentClient, zohoMcpUrl, oktaAppId); }
}
