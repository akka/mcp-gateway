package io.akka.mcp.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.domain.WriteAccess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@HttpEndpoint("/slack/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SlackOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String SLACK_AUTH_ENDPOINT = "https://slack.com/oauth/v2/authorize";
    private static final String SLACK_TOKEN_ENDPOINT = "https://slack.com/api/oauth.v2.access";

    private final HttpClientProvider httpClientProvider;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String slackMcpUrl;
    private final String oktaAppId;

    public SlackOAuthEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.httpClientProvider = httpClientProvider;
        this.clientId = config.getString("slack.oauth.client-id");
        this.clientSecret = config.getString("slack.oauth.client-secret");
        this.redirectUri = config.getString("slack.oauth.redirect-uri");
        this.slackMcpUrl = config.getString("slack.mcp-url");
        this.oktaAppId = config.getString("slack.okta-app-id");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(SlackConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return clientId; }
    @Override protected String getClientSecret() { return clientSecret; }
    @Override protected String getRedirectUri() { return redirectUri; }
    @Override protected String getAuthorizationEndpoint() { return SLACK_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return SLACK_TOKEN_ENDPOINT; }
    @Override protected String getProviderLabel() { return "Slack"; }

    private static final List<String> READ_USER_SCOPES = List.of(
            "channels:read", "channels:history",
            "groups:read", "groups:history",
            "im:read", "im:history",
            "mpim:read", "mpim:history",
            "files:read",
            "users:read", "users:read.email",
            "search:read");
    private static final String WRITE_USER_SCOPE = "chat:write";

    // Slack OAuth v2 uses user_scope (not scope) for user tokens: pass an empty bot scope and
    // inject user_scope via extra params so users only access their own data.
    //
    // chat:write is requested only for users the gateway would let post (write-enabled connector
    // and writer group). The redirect is not signed and the callback does not check what Slack
    // granted, so this limits what we ask for, not what a user can obtain: the write gate is what
    // stops a reader posting. Scopes are fixed at connect time: a user who gains the writer role,
    // or connected before chat:write was added, must disconnect and reconnect before
    // slack_post_message works.
    @Override
    protected String getScope() { return ""; }

    @Override
    protected String getExtraAuthParams(UserSession session) {
        return userScopeParam(session, currentWriteAccess());
    }

    static String userScopeParam(UserSession session, WriteAccess writeAccess) {
        var scopes = new ArrayList<>(READ_USER_SCOPES);
        if (writeAccess.permits(SlackMcpClient.MCP_ID, session)) scopes.add(WRITE_USER_SCOPE);
        return "&user_scope=" + encode(String.join(" ", scopes));
    }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand(state, codeVerifier, clientId));
    }

    // Slack OAuth v2 with user_scope returns token under authed_user, not top-level
    @Override
    protected String extractAccessToken(JsonNode tokenJson) {
        return tokenJson.path("authed_user").path("access_token").asText();
    }

    @Override
    protected String extractRefreshToken(JsonNode tokenJson) {
        return tokenJson.path("authed_user").path("refresh_token").asText(null);
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::getStatus)
                .invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), SLACK_TOKEN_ENDPOINT));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::disconnect)
                .invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        try {
            return Optional.of(componentClient.forKeyValueEntity(email)
                    .method(SlackConnectionEntity::getAccessToken)
                    .invoke());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    protected RemoteMcpClient createMcpClient() {
        return new SlackMcpClient(componentClient, httpClientProvider, slackMcpUrl, oktaAppId);
    }
}
