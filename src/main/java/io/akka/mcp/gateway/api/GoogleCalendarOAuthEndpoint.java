package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.GoogleCalendarConnectionEntity;
import io.akka.mcp.gateway.application.GoogleCalendarMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;

import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/google-calendar/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GoogleCalendarOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String calendarMcpUrl;
    private final String oktaAppId;

    public GoogleCalendarOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.clientId = config.getString("google-calendar.oauth.client-id");
        this.clientSecret = config.getString("google-calendar.oauth.client-secret");
        this.redirectUri = config.getString("google-calendar.oauth.redirect-uri");
        this.calendarMcpUrl = config.getString("google-calendar.mcp-url");
        this.oktaAppId = config.getString("google-calendar.okta-app-id");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(GoogleCalendarConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return clientId; }
    @Override protected String getClientSecret() { return clientSecret; }
    @Override protected String getRedirectUri() { return redirectUri; }
    @Override protected String getAuthorizationEndpoint() { return GOOGLE_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return GOOGLE_TOKEN_ENDPOINT; }
    @Override protected String getProviderLabel() { return "Google Calendar"; }
    @Override protected String getScope() { return "https://www.googleapis.com/auth/calendar"; }

    @Override
    protected String getExtraAuthParams() {
        return "&access_type=offline&prompt=consent";
    }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email)
                .method(GoogleCalendarConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleCalendarConnectionEntity.InitiateCommand(state, codeVerifier, clientId));
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email)
                .method(GoogleCalendarConnectionEntity::getStatus)
                .invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), GOOGLE_TOKEN_ENDPOINT));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email)
                .method(GoogleCalendarConnectionEntity::storeToken)
                .invoke(new GoogleCalendarConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email)
                .method(GoogleCalendarConnectionEntity::disconnect)
                .invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        try {
            return Optional.of(componentClient.forKeyValueEntity(email)
                    .method(GoogleCalendarConnectionEntity::getAccessToken)
                    .invoke());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    protected RemoteMcpClient createMcpClient() {
        return new GoogleCalendarMcpClient(componentClient, calendarMcpUrl, oktaAppId);
    }
}
