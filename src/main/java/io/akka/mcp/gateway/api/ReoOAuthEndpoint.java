package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.ReoConnectionEntity;
import io.akka.mcp.gateway.application.ReoMcpClient;
import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/reo/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class ReoOAuthEndpoint extends AbstractDcrOAuthEndpoint {

    private final String reoMcpUrl;
    private final String oktaAppId;
    private final String reoRedirectUri;

    public ReoOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.reoMcpUrl = config.getString("reo.mcp-url");
        this.oktaAppId = config.getString("reo.okta-app-id");
        this.reoRedirectUri = config.getString("reo.redirect-uri");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(ReoConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getMcpUrl() { return reoMcpUrl; }
    @Override protected String getRedirectUri() { return reoRedirectUri; }
    @Override protected String getProviderLabel() { return "Reo"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient
                .forKeyValueEntity(email)
                .method(ReoConnectionEntity::initiatePkceOAuth)
                .invoke(new ReoConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(ReoConnectionEntity::getStatus).invoke();
        if (!connection.isValidPendingState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(ReoConnectionEntity::storeToken)
                .invoke(new ReoConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(ReoConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(ReoConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override
    protected RemoteMcpClient createMcpClient() { return new ReoMcpClient(componentClient, reoMcpUrl, oktaAppId); }
}
