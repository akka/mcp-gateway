package io.akka.mcp.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.domain.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks up the active Okta applications assigned to a user.
 *
 * Uses the apps API rather than {@code /users/{id}/appLinks}: appLinks only returns what the user
 * would see on their dashboard, so an app with its icon hidden (such as an app that exists only to
 * be reached through the gateway) would be treated as unassigned.
 */
final class OktaAssignedApps {

    private static final Logger log = LoggerFactory.getLogger(OktaAssignedApps.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final String OKTA_USER_ID_PREFIX = "00u";
    private static final String ACTIVE = "ACTIVE";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;
    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    private static final Duration MIN_RATE_LIMIT_WAIT = Duration.ofSeconds(1);
    private static final Duration MAX_RATE_LIMIT_WAIT = Duration.ofSeconds(5);
    private static final int ERROR_BODY_LOG_LIMIT = 300;

    private final String oktaBaseUrl;
    private final String oktaApiToken;
    private final Consumer<Duration> pause;

    OktaAssignedApps(String oktaBaseUrl, String oktaApiToken) {
        this(oktaBaseUrl, oktaApiToken, OktaAssignedApps::sleep);
    }

    OktaAssignedApps(String oktaBaseUrl, String oktaApiToken, Consumer<Duration> pause) {
        this.oktaBaseUrl = oktaBaseUrl;
        this.oktaApiToken = oktaApiToken;
        this.pause = pause;
    }

    boolean isConfigured() {
        return !oktaBaseUrl.isBlank() && !oktaApiToken.isBlank();
    }

    /** Empty when the lookup could not be completed, so callers can tell that from "no apps". */
    Optional<List<UserSession.App>> forUser(String email) {
        if (!isConfigured() || email == null || email.isBlank()) return Optional.empty();
        return completeLookup(() -> assignedApps(userIdOf(email)));
    }

    /**
     * At sign-in the OIDC subject already is the Okta user id, which saves the lookup by email.
     * Anything that does not look like one falls back to the email lookup.
     */
    Optional<List<UserSession.App>> forSubjectOrEmail(String subject, String email) {
        if (!isConfigured()) return Optional.empty();
        if (subject != null && subject.startsWith(OKTA_USER_ID_PREFIX)) {
            return completeLookup(() -> assignedApps(subject));
        }
        return forUser(email);
    }

    private Optional<List<UserSession.App>> completeLookup(Callable<List<UserSession.App>> lookup) {
        try {
            return Optional.of(lookup.call());
        } catch (TokenRefused e) {
            log.error("Okta refused the apps lookup (HTTP {}). MCP_PROXY_OKTA_API_TOKEN must belong to an admin role"
                    + " that can read applications. Until that is fixed no user is seen as having any app, so every"
                    + " app-gated system is unavailable.", e.status);
            return Optional.empty();
        } catch (LookupFailed e) {
            log.warn("Okta apps lookup failed: {}", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Okta apps lookup interrupted");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Okta apps lookup failed", e);
            return Optional.empty();
        }
    }

    private String userIdOf(String email) throws Exception {
        var resp = get(URI.create(oktaBaseUrl + "/api/v1/users/" + encode(email)));
        var id = MAPPER.readTree(resp.body()).path("id").asText("");
        if (id.isBlank()) throw new LookupFailed("user " + email + " has no id in the Okta response");
        return id;
    }

    private List<UserSession.App> assignedApps(String userId) throws Exception {
        var apps = new ArrayList<UserSession.App>();
        var filter = encode("user.id eq \"" + userId + "\"");
        var next = Optional.of(URI.create(oktaBaseUrl + "/api/v1/apps?limit=" + PAGE_SIZE + "&filter=" + filter));
        for (int page = 0; next.isPresent() && page < MAX_PAGES; page++) {
            var resp = get(next.get());
            var json = MAPPER.readTree(resp.body());
            if (!json.isArray()) throw new LookupFailed("the apps response is not a JSON array");
            for (var app : json) {
                var id = app.path("id").asText("");
                var label = app.path("label").asText("");
                var status = app.path("status").asText("");
                log.debug("Okta apps: id={} label={} status={}", id, label, status);
                if (!id.isBlank() && ACTIVE.equals(status)) {
                    apps.add(new UserSession.App(id, label.isBlank() ? id : label));
                }
            }
            next = nextPage(resp);
        }
        if (next.isPresent()) throw new LookupFailed("the user has more than " + MAX_PAGES + " pages of apps");
        return List.copyOf(apps);
    }

    /** A next link that leaves the Okta host is refused so the API token is never sent elsewhere. */
    private Optional<URI> nextPage(HttpResponse<String> resp) throws LookupFailed {
        var link = resp.headers().allValues("link").stream()
                .map(NEXT_LINK::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst();
        if (link.isPresent() && !link.get().startsWith(oktaBaseUrl + "/")) {
            throw new LookupFailed("the next page link does not point at the Okta host");
        }
        return link.map(URI::create);
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        for (int attempt = 0; ; attempt++) {
            var resp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Authorization", "SSWS " + oktaApiToken)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var status = resp.statusCode();
            if (status == 200) return resp;
            if (status == 401 || status == 403) throw new TokenRefused(status);
            if (status == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                pause.accept(rateLimitWait(resp));
                continue;
            }
            throw new LookupFailed(status == 429
                    ? "Okta is rate limiting the gateway"
                    : "Okta answered HTTP " + status + ": " + abbreviated(resp.body()));
        }
    }

    private static Duration rateLimitWait(HttpResponse<String> resp) {
        var wait = resp.headers().firstValue("x-rate-limit-reset")
                .map(OktaAssignedApps::secondsUntilEpoch)
                .orElse(MIN_RATE_LIMIT_WAIT);
        if (wait.compareTo(MIN_RATE_LIMIT_WAIT) < 0) return MIN_RATE_LIMIT_WAIT;
        return wait.compareTo(MAX_RATE_LIMIT_WAIT) > 0 ? MAX_RATE_LIMIT_WAIT : wait;
    }

    private static Duration secondsUntilEpoch(String epochSeconds) {
        try {
            return Duration.between(Instant.now(), Instant.ofEpochSecond(Long.parseLong(epochSeconds.trim())));
        } catch (NumberFormatException e) {
            return MIN_RATE_LIMIT_WAIT;
        }
    }

    private static String abbreviated(String body) {
        return body.length() <= ERROR_BODY_LOG_LIMIT ? body : body.substring(0, ERROR_BODY_LOG_LIMIT) + "...";
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static class LookupFailed extends Exception {
        LookupFailed(String message) {
            super(message);
        }
    }

    private static final class TokenRefused extends LookupFailed {
        private final int status;

        TokenRefused(int status) {
            super("Okta refused the API token with HTTP " + status);
            this.status = status;
        }
    }
}
