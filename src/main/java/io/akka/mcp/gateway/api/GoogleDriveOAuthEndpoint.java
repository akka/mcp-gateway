package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.GoogleDriveConnectionEntity;
import io.akka.mcp.gateway.application.GoogleDriveMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/googledrive/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GoogleDriveOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_DRIVE_SCOPE = "openid email https://www.googleapis.com/auth/drive https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.file";

    private final String googleDriveRedirectUri;
    private final String googleDriveMcpUrl;
    private final String oktaAppId;
    private final String googleDriveClientId;
    private final String googleDriveClientSecret;

    public GoogleDriveOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.googleDriveRedirectUri = config.getString("google-drive.redirect-uri");
        this.googleDriveMcpUrl = config.getString("google-drive.mcp-url");
        this.oktaAppId = config.getString("google-drive.okta-app-id");
        this.googleDriveClientId = config.getString("google-drive.client-id");
        this.googleDriveClientSecret = config.getString("google-drive.client-secret");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(GoogleDriveConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return googleDriveClientId; }
    @Override protected String getAuthorizationEndpoint() { return GOOGLE_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return GOOGLE_TOKEN_ENDPOINT; }
    @Override protected String getScope() { return GOOGLE_DRIVE_SCOPE; }
    @Override protected String getExtraAuthParams() { return "&access_type=offline&prompt=consent"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email).method(GoogleDriveConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleDriveConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override protected String getRedirectUri() { return googleDriveRedirectUri; }
    @Override protected String getClientSecret() { return googleDriveClientSecret; }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleDriveConnectionEntity::getStatus).invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(GoogleDriveConnectionEntity::storeToken)
                .invoke(new GoogleDriveConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(GoogleDriveConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleDriveConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override protected String getProviderLabel() { return "Google Drive"; }
    @Override protected RemoteMcpClient createMcpClient() { return new GoogleDriveMcpClient(componentClient, googleDriveMcpUrl, oktaAppId); }
}
