package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.domain.SalesforceConnection;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component(id = "salesforce-connection")
public class SalesforceConnectionEntity extends KeyValueEntity<SalesforceConnection> {

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String clientSecret;

    public SalesforceConnectionEntity(Config config) {
        this.clientSecret = config.getString("salesforce.oauth.client-secret");
    }

    @Override
    public SalesforceConnection emptyState() {
        return SalesforceConnection.empty();
    }

    public ReadOnlyEffect<SalesforceConnection> getStatus() {
        return effects().reply(currentState());
    }

    public Effect<String> getAccessToken() {
        var state = currentState();
        if (!state.isConnected()) {
            return effects().error("Salesforce is not connected. Please complete the OAuth setup.");
        }
        if (!state.isTokenExpired()) {
            return effects().reply(state.accessToken());
        }
        if (state.refreshToken() == null || state.tokenEndpoint() == null) {
            return effects().error("Salesforce token has expired. Please reconnect.");
        }
        try {
            var fresh = performRefresh(state);
            var newState = state.withToken(fresh.accessToken(), fresh.refreshToken(), fresh.expiresAt());
            return effects().updateState(newState).thenReply(newState.accessToken());
        } catch (Exception e) {
            return effects().error("Failed to refresh Salesforce token: " + e.getMessage());
        }
    }

    public Effect<Done> initiatePkceOAuth(InitiateCommand cmd) {
        var newState = currentState().withPending(
                cmd.state(), cmd.codeVerifier(), cmd.clientId(), cmd.tokenEndpoint(),
                Instant.now().plusSeconds(600));
        return effects().updateState(newState).thenReply(Done.getInstance());
    }

    public Effect<Done> storeToken(StoreTokenCommand cmd) {
        if (!currentState().isValidOAuthState(cmd.state())) {
            return effects().error("Invalid or expired OAuth state");
        }
        var newState = currentState().withToken(cmd.accessToken(), cmd.refreshToken(), cmd.expiresAt());
        return effects().updateState(newState).thenReply(Done.getInstance());
    }

    public Effect<Done> disconnect() {
        return effects().updateState(currentState().disconnected()).thenReply(Done.getInstance());
    }

    private record FreshToken(String accessToken, String refreshToken, Instant expiresAt) {}

    private FreshToken performRefresh(SalesforceConnection connection) throws Exception {
        String formBody = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(connection.refreshToken(), StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(connection.clientId(), StandardCharsets.UTF_8)
                + (clientSecret.isBlank() ? "" : "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));

        var resp = HTTP_CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(connection.tokenEndpoint()))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }

        var json = MAPPER.readTree(resp.body());
        String newAccessToken = json.path("access_token").asText();
        String newRefreshToken = json.path("refresh_token").asText(connection.refreshToken());
        long expiresIn = json.path("expires_in").asLong(7200);

        return new FreshToken(newAccessToken, newRefreshToken, Instant.now().plusSeconds(expiresIn));
    }

    public record InitiateCommand(String state, String codeVerifier, String clientId, String tokenEndpoint) {}

    public record StoreTokenCommand(String accessToken, String refreshToken, Instant expiresAt, String state) {}
}
