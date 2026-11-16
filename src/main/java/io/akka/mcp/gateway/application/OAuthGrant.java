package io.akka.mcp.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * What a token endpoint answers: the tokens it issued, and what its answer to a refresh means. The refresh uses a
 * plain HTTP client, one per refresh so that a connection the server has closed is never reused, because it runs inside
 * {@link OAuthTokenConnectionEntity}, which cannot be given the SDK's. Redirects are not followed, and nothing the server
 * said is kept in a failure.
 */
public final class OAuthGrant {

    public record Issued(String accessToken, Optional<String> refreshToken, Instant issuedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "Issued[hidden]";
        }
    }

    public sealed interface RefreshResult permits Refreshed, GrantGone, TryLater {}

    public record Refreshed(Issued issued) implements RefreshResult {}

    /** The authorization server says the grant is no longer good, so the user has to sign in again; {@code clientUnknown} if it forgot the client too. */
    public record GrantGone(boolean clientUnknown) implements RefreshResult {}

    public record TryLater() implements RefreshResult {}

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);
    private static final long MAX_LIFETIME_SECONDS = Duration.ofDays(30).toSeconds();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OAuthGrant() {}

    /** Only an answer that says the grant is gone counts as a refusal; anything else may pass, so it never disconnects anyone. */
    public static RefreshResult refresh(String tokenEndpoint, String clientId, String refreshToken, String resource) {
        var form = "grant_type=refresh_token&refresh_token=" + encode(refreshToken) + "&client_id=" + encode(clientId) + "&resource=" + encode(resource);
        var request = HttpRequest.newBuilder(URI.create(tokenEndpoint)).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        try {
            var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(OAuthGrant::answerOf)
                    .orTimeout(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS).get();
            if (response.status() == 200) {
                return issuedFrom(response.body(), Optional.of(refreshToken)).<RefreshResult>map(Refreshed::new).orElseGet(TryLater::new);
            }
            return switch (errorCode(response.status(), response.body()).orElse("")) {
                case "invalid_grant" -> new GrantGone(false);
                case "invalid_client" -> new GrantGone(true);
                default -> new TryLater();
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TryLater();
        } catch (Exception e) {
            return new TryLater();
        } finally {
            http.shutdownNow();
        }
    }

    /** The OAuth error code in a 400 or 401 answer, empty for any other answer. */
    static Optional<String> errorCode(int status, String body) {
        if (status != 400 && status != 401) return Optional.empty();
        try {
            return Optional.of(MAPPER.readTree(body).path("error").asText("")).filter(code -> !code.isEmpty());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Answer(int status, String body) {}

    /** Reads at most a bounded amount of the body, because a token endpoint's answer is small. */
    private static Answer answerOf(HttpResponse<InputStream> response) {
        try (var body = response.body()) {
            return new Answer(response.statusCode(), new String(body.readNBytes(MAX_RESPONSE_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The tokens in a successful answer, empty when it holds no access token; a lifetime that is missing or unusable counts as an hour. */
    static Optional<Issued> issuedFrom(String body, Optional<String> previousRefreshToken) {
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (Exception e) {
            return Optional.empty();
        }
        var accessToken = json.path("access_token").asText("");
        if (accessToken.isEmpty()) return Optional.empty();
        var refreshToken = Optional.of(json.path("refresh_token").asText("")).filter(token -> !token.isEmpty()).or(() -> previousRefreshToken);
        var seconds = json.path("expires_in").asLong(DEFAULT_LIFETIME.toSeconds());
        var lifetime = Math.min(seconds <= 0 ? DEFAULT_LIFETIME.toSeconds() : seconds, MAX_LIFETIME_SECONDS);
        var now = Instant.now();
        return Optional.of(new Issued(accessToken, refreshToken, now, now.plusSeconds(lifetime)));
    }
}
