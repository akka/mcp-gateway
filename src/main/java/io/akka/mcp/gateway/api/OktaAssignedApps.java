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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Looks up the Okta applications assigned to a user.
 *
 * Uses the apps API rather than {@code /users/{id}/appLinks}: appLinks only returns what the user
 * would see on their dashboard, so an app with its icon hidden (such as an app that exists only to
 * be reached through the gateway) would be treated as unassigned.
 */
final class OktaAssignedApps {

    private static final Logger log = LoggerFactory.getLogger(OktaAssignedApps.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 20;

    private final String oktaBaseUrl;
    private final String oktaApiToken;

    OktaAssignedApps(String oktaBaseUrl, String oktaApiToken) {
        this.oktaBaseUrl = oktaBaseUrl;
        this.oktaApiToken = oktaApiToken;
    }

    boolean isConfigured() {
        return !oktaBaseUrl.isBlank() && !oktaApiToken.isBlank();
    }

    /** Empty when the lookup could not be completed, so callers can tell that from "no apps". */
    Optional<List<UserSession.App>> forUser(String email) {
        if (!isConfigured() || email == null || email.isBlank()) return Optional.empty();
        try {
            var userId = userId(email);
            if (userId.isEmpty()) return Optional.empty();
            return assignedApps(userId.get());
        } catch (Exception e) {
            log.warn("Okta apps lookup failed for {}: {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> userId(String email) throws Exception {
        var resp = get(URI.create(oktaBaseUrl + "/api/v1/users/" + encode(email)));
        if (resp.statusCode() != 200) {
            log.warn("Okta apps lookup: user fetch status={} for {}", resp.statusCode(), email);
            return Optional.empty();
        }
        var id = MAPPER.readTree(resp.body()).path("id").asText("");
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    private Optional<List<UserSession.App>> assignedApps(String userId) throws Exception {
        var apps = new ArrayList<UserSession.App>();
        var filter = encode("user.id eq \"" + userId + "\"");
        var next = Optional.of(URI.create(oktaBaseUrl + "/api/v1/apps?limit=" + PAGE_SIZE + "&filter=" + filter));
        for (int page = 0; next.isPresent() && page < MAX_PAGES; page++) {
            var resp = get(next.get());
            if (resp.statusCode() != 200) {
                log.warn("Okta apps lookup: apps fetch status={}", resp.statusCode());
                return Optional.empty();
            }
            var json = MAPPER.readTree(resp.body());
            if (!json.isArray()) {
                log.warn("Okta apps lookup: response is not a JSON array");
                return Optional.empty();
            }
            for (var app : json) {
                var id = app.path("id").asText("");
                var label = app.path("label").asText("");
                if (!id.isBlank() && apps.stream().noneMatch(a -> a.id().equals(id))) {
                    apps.add(new UserSession.App(id, label.isBlank() ? id : label));
                }
            }
            next = nextPage(resp);
        }
        return Optional.of(List.copyOf(apps));
    }

    /** Follows the next link only while it stays on the Okta host the API token is meant for. */
    private Optional<URI> nextPage(HttpResponse<String> resp) {
        return resp.headers().allValues("link").stream()
                .map(NEXT_LINK::matcher)
                .filter(java.util.regex.Matcher::find)
                .map(m -> m.group(1))
                .filter(link -> link.startsWith(oktaBaseUrl + "/"))
                .findFirst()
                .map(URI::create);
    }

    private HttpResponse<String> get(URI uri) throws Exception {
        return HTTP_CLIENT.send(
                HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "SSWS " + oktaApiToken)
                        .header("Accept", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
