package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.GoogleWorkspaceConnectionEntity;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.WorkspaceCalendarMcpClient;
import io.akka.mcp.gateway.application.WorkspaceDocsMcpClient;
import io.akka.mcp.gateway.application.WorkspaceDriveMcpClient;
import io.akka.mcp.gateway.application.WorkspaceGmailMcpClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@HttpEndpoint("/googleworkspace/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GoogleWorkspaceOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    // Union of scopes for Drive, Docs, Gmail, Calendar — grant them together under one client-id so a file
    // touched by one product's MCP is reachable from the others (drive.file is per-client-id, not per-server).
    private static final String GOOGLE_WORKSPACE_SCOPE = String.join(" ",
            "openid",
            "email",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file",
            "https://www.googleapis.com/auth/documents",
            "https://www.googleapis.com/auth/documents.readonly",
            "https://mail.google.com/",
            "https://www.googleapis.com/auth/calendar");

    private final String workspaceRedirectUri;
    private final String workspaceClientId;
    private final String workspaceClientSecret;
    private final String oktaAppId;
    private final String driveMcpUrl;
    private final String docsMcpUrl;
    private final String gmailMcpUrl;
    private final String calendarMcpUrl;

    public GoogleWorkspaceOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.workspaceRedirectUri = config.getString("google-workspace.redirect-uri");
        this.workspaceClientId = config.getString("google-workspace.client-id");
        this.workspaceClientSecret = config.getString("google-workspace.client-secret");
        this.oktaAppId = config.getString("google-workspace.okta-app-id");
        this.driveMcpUrl = config.getString("google-workspace.drive.mcp-url");
        this.docsMcpUrl = config.getString("google-workspace.docs.mcp-url");
        this.gmailMcpUrl = config.getString("google-workspace.gmail.mcp-url");
        this.calendarMcpUrl = config.getString("google-workspace.calendar.mcp-url");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return workspaceClientId; }
    @Override protected String getAuthorizationEndpoint() { return GOOGLE_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return GOOGLE_TOKEN_ENDPOINT; }
    @Override protected String getScope() { return GOOGLE_WORKSPACE_SCOPE; }
    @Override protected String getExtraAuthParams() { return "&access_type=offline&prompt=consent"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email).method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleWorkspaceConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Override protected String getRedirectUri() { return workspaceRedirectUri; }
    @Override protected String getClientSecret() { return workspaceClientSecret; }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleWorkspaceConnectionEntity::getStatus).invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email).method(GoogleWorkspaceConnectionEntity::storeToken)
                .invoke(new GoogleWorkspaceConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email).method(GoogleWorkspaceConnectionEntity::disconnect).invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient.forKeyValueEntity(email).method(GoogleWorkspaceConnectionEntity::getStatus).invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override protected String getProviderLabel() { return "Google Workspace"; }

    // /test uses this — Docs is a representative sanity check for the shared connection.
    @Override
    protected RemoteMcpClient createMcpClient() {
        return new WorkspaceDocsMcpClient(componentClient, docsMcpUrl, oktaAppId);
    }

    // After connect, prime the tools/list cache for every downstream Workspace MCP.
    @Override
    protected List<RemoteMcpClient> mcpClientsToWarm() {
        return List.of(
                new WorkspaceDriveMcpClient(componentClient, driveMcpUrl, oktaAppId),
                new WorkspaceDocsMcpClient(componentClient, docsMcpUrl, oktaAppId),
                new WorkspaceGmailMcpClient(componentClient, gmailMcpUrl, oktaAppId),
                new WorkspaceCalendarMcpClient(componentClient, calendarMcpUrl, oktaAppId));
    }
}
