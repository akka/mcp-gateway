package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.HubspotConnectionEntity;
import io.akka.mcp.gateway.application.HubspotMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;

import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/hubspot/oauth")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class HubspotOAuthEndpoint extends AbstractStaticOAuthEndpoint {

    private static final String HUBSPOT_AUTH_ENDPOINT = "https://app.hubspot.com/oauth/authorize";
    private static final String HUBSPOT_TOKEN_ENDPOINT = "https://api.hubapi.com/oauth/v1/token";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String hubspotMcpUrl;
    private final String oktaAppId;

    public HubspotOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.clientId = config.getString("hubspot.oauth.client-id");
        this.clientSecret = config.getString("hubspot.oauth.client-secret");
        this.redirectUri = config.getString("hubspot.oauth.redirect-uri");
        this.hubspotMcpUrl = config.getString("hubspot.mcp-url");
        this.oktaAppId = config.getString("hubspot.okta-app-id");
    }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(email)
                .method(HubspotConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getClientId() { return clientId; }
    @Override protected String getClientSecret() { return clientSecret; }
    @Override protected String getRedirectUri() { return redirectUri; }
    @Override protected String getAuthorizationEndpoint() { return HUBSPOT_AUTH_ENDPOINT; }
    @Override protected String getTokenEndpoint() { return HUBSPOT_TOKEN_ENDPOINT; }
    @Override protected String getProviderLabel() { return "HubSpot"; }
    @Override protected String getScope() { return "crm.objects.contacts.read crm.objects.companies.read crm.objects.deals.read crm.objects.contacts.write crm.objects.companies.write crm.objects.deals.write tickets"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient.forKeyValueEntity(email)
                .method(HubspotConnectionEntity::initiatePkceOAuth)
                .invoke(new HubspotConnectionEntity.InitiateCommand(state, codeVerifier, clientId));
    }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient.forKeyValueEntity(email)
                .method(HubspotConnectionEntity::getStatus)
                .invoke();
        if (!connection.isValidOAuthState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), HUBSPOT_TOKEN_ENDPOINT));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient.forKeyValueEntity(email)
                .method(HubspotConnectionEntity::storeToken)
                .invoke(new HubspotConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Override
    protected void clearConnection(String email) {
        componentClient.forKeyValueEntity(email)
                .method(HubspotConnectionEntity::disconnect)
                .invoke();
    }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        try {
            return Optional.of(componentClient.forKeyValueEntity(email)
                    .method(HubspotConnectionEntity::getAccessToken)
                    .invoke());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    protected RemoteMcpClient createMcpClient() {
        return new HubspotMcpClient(componentClient, hubspotMcpUrl, oktaAppId);
    }
}
