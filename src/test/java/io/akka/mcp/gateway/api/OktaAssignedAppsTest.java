package io.akka.mcp.gateway.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.akka.mcp.gateway.domain.UserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

public class OktaAssignedAppsTest {

    private static final String TOKEN = "api-token";

    private HttpServer okta;
    private String baseUrl;
    private final List<String> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    @BeforeEach
    void startFakeOkta() throws IOException {
        okta = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + okta.getAddress().getPort();
        okta.start();
    }

    @AfterEach
    void stopFakeOkta() {
        okta.stop(0);
    }

    private void respond(String path, int status, String body) {
        respond(path, status, body, exchange -> {});
    }

    private void respond(String path, int status, String body, Consumer<HttpExchange> headers) {
        okta.createContext(path, exchange -> {
            requests.add(URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            headers.accept(exchange);
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private OktaAssignedApps lookup() {
        return new OktaAssignedApps(baseUrl, TOKEN);
    }

    @Test
    void returnsAssignedAppsFromTheAppsApiRatherThanTheUsersDashboardLinks() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 200, """
                [{"id":"0oa-visible","label":"Slack"},{"id":"0oa-hidden","label":"Seamless"}]
                """);

        var apps = lookup().forUser("user@akka.io");

        assertThat(apps).contains(List.of(
                new UserSession.App("0oa-visible", "Slack"),
                new UserSession.App("0oa-hidden", "Seamless")));
        assertThat(requests).noneMatch(r -> r.contains("appLinks"));
        assertThat(requests).anyMatch(r -> r.contains("filter=user.id eq \"00u1\""));
        assertThat(authHeaders).allMatch(h -> h.equals("SSWS " + TOKEN));
    }

    @Test
    void fallsBackToTheAppIdWhenAnAppHasNoLabel() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 200, "[{\"id\":\"0oa-1\"}]");

        assertThat(lookup().forUser("user@akka.io")).contains(List.of(new UserSession.App("0oa-1", "0oa-1")));
    }

    @Test
    void followsTheNextPageLink() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 200, "[{\"id\":\"0oa-1\",\"label\":\"One\"}]",
                exchange -> exchange.getResponseHeaders().add("Link", "<" + baseUrl + "/page2>; rel=\"next\""));
        respond("/page2", 200, "[{\"id\":\"0oa-2\",\"label\":\"Two\"}]");

        assertThat(lookup().forUser("user@akka.io")).contains(List.of(
                new UserSession.App("0oa-1", "One"),
                new UserSession.App("0oa-2", "Two")));
    }

    @Test
    void doesNotSendTheApiTokenToANextLinkOnAnotherHost() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 200, "[{\"id\":\"0oa-1\",\"label\":\"One\"}]",
                exchange -> exchange.getResponseHeaders().add("Link", "<https://evil.example/steal>; rel=\"next\""));

        assertThat(lookup().forUser("user@akka.io")).contains(List.of(new UserSession.App("0oa-1", "One")));
    }

    @Test
    void reportsALookupFailureSoCallersCanTellItFromNoApps() {
        respond("/api/v1/users/", 404, "{}");

        assertThat(lookup().forUser("user@akka.io")).isEmpty();
    }

    @Test
    void reportsAnAppsApiFailure() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 403, "{}");

        assertThat(lookup().forUser("user@akka.io")).isEmpty();
    }

    @Test
    void aUserWithNoAssignmentsGetsAnEmptyListNotAFailure() {
        respond("/api/v1/users/", 200, "{\"id\":\"00u1\"}");
        respond("/api/v1/apps", 200, "[]");

        assertThat(lookup().forUser("user@akka.io")).contains(List.of());
    }

    @Test
    void doesNothingWhenOktaIsNotConfigured() {
        assertThat(new OktaAssignedApps("", TOKEN).forUser("user@akka.io")).isEmpty();
        assertThat(new OktaAssignedApps(baseUrl, "").forUser("user@akka.io")).isEmpty();
        assertThat(requests).isEmpty();
    }
}
