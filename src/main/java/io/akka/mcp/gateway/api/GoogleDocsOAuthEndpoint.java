package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.GoogleDocsConnectionEntity;
import io.akka.mcp.gateway.application.GoogleDocsMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/googledocs/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GoogleDocsOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    // documents = Docs API read/write; drive.file lets comment tools act on app-touched files.
    private static final String GOOGLE_DOCS_SCOPE = "openid email https://www.googleapis.com/auth/documents https://www.googleapis.com/auth/drive.file";

    private final String googleDocsRedirectUri;
    private final String googleDocsMcpUrl;
    private final String oktaAppId;
    private final String googleDocsClientId;
    private final String googleDocsClientSecret;

    public GoogleDocsOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.googleDocsRedirectUri = config.getString("google-docs.redirect-uri");
        this.googleDocsMcpUrl = config.getString("google-docs.mcp-url");
        this.oktaAppId = config.getString("google-docs.okta-app-id");
        this.googleDocsClientId = config.getString("google-docs.client-id");
        this.googleDocsClientSecret = config.getString("google-docs.client-secret");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(GoogleDocsConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return googleDocsClientId; }
    @Override protected String getAuthorizationEndpoint() { return GOOGLE_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return GOOGLE_TOKEN_ENDPOINT; }
    @Override protected String getScope() { return GOOGLE_DOCS_SCOPE; }
    @Override protected String getExtraAuthParams() { return "&access_type=offline&prompt=consent"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email).method(GoogleDocsConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleDocsConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override protected String getRedirectUri() { return googleDocsRedirectUri; }
    @Override protected String getClientSecret() { return googleDocsClientSecret; }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleDocsConnectionEntity::getStatus).invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(GoogleDocsConnectionEntity::storeToken)
                .invoke(new GoogleDocsConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(GoogleDocsConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleDocsConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override protected String getProviderLabel() { return "Google Docs"; }
    @Override protected RemoteMcpClient createMcpClient() { return new GoogleDocsMcpClient(componentClient, googleDocsMcpUrl, oktaAppId); }
}
