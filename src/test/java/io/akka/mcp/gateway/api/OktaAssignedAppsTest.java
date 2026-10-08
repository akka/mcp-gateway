package io.akka.mcp.gateway.api;

import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeOkta;
import io.akka.mcp.gateway.testsupport.FakeOkta.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class OktaAssignedAppsTest {

    private static final String TOKEN = "api-token";
    private static final String EMAIL = "user@akka.io";
    private static final String USER_PATH = "/api/v1/users/" + EMAIL;
    private static final String USER_ID = "00u1";
    private static final String USER_JSON = "{\"id\":\"" + USER_ID + "\"}";

    private FakeOkta okta;
    private final List<Duration> pauses = new ArrayList<>();

    @BeforeEach
    void startFakeOkta() {
        okta = FakeOkta.start();
    }

    @AfterEach
    void stopFakeOkta() {
        okta.close();
    }

    private OktaAssignedApps lookup() {
        return new OktaAssignedApps(okta.url(), TOKEN, pauses::add);
    }

    private static String app(String id, String label, String status) {
        return "{\"id\":\"%s\",\"label\":\"%s\",\"status\":\"%s\"}".formatted(id, label, status);
    }

    private static String apps(String... apps) {
        return "[" + String.join(",", apps) + "]";
    }

    private List<String> requestedPaths() {
        return okta.requests().stream().map(FakeOkta.ReceivedRequest::pathAndQuery).toList();
    }

    @Test
    void returnsAssignedAppsFromTheAppsApiRatherThanTheUsersDashboardLinks() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, apps(app("0oa-visible", "Slack", "ACTIVE"), app("0oa-hidden", "Seamless", "ACTIVE")));

        var result = lookup().forUser(EMAIL);

        assertThat(result).contains(List.of(
                new UserSession.App("0oa-visible", "Slack"),
                new UserSession.App("0oa-hidden", "Seamless")));
        assertThat(requestedPaths()).noneMatch(r -> r.contains("appLinks"));
        assertThat(requestedPaths()).anyMatch(r -> r.contains("filter=user.id eq \"" + USER_ID + "\""));
        assertThat(okta.requests()).allMatch(r -> r.authorization().equals("SSWS " + TOKEN));
    }

    @Test
    void onlyActiveAppsCount() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, apps(app("0oa-on", "On", "ACTIVE"), app("0oa-off", "Off", "INACTIVE")));

        assertThat(lookup().forUser(EMAIL)).contains(List.of(new UserSession.App("0oa-on", "On")));
    }

    @Test
    void fallsBackToTheAppIdWhenAnAppHasNoLabel() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, "[{\"id\":\"0oa-1\",\"status\":\"ACTIVE\"}]");

        assertThat(lookup().forUser(EMAIL)).contains(List.of(new UserSession.App("0oa-1", "0oa-1")));
    }

    @Test
    void aUserWithNoAssignmentsGetsAnEmptyListNotAFailure() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, "[]");

        assertThat(lookup().forUser(EMAIL)).contains(List.of());
    }

    @Test
    void usesTheOidcSubjectWithoutLookingTheUserUp() {
        okta.respond("/api/v1/apps", 200, apps(app("0oa-1", "One", "ACTIVE")));

        var result = lookup().forSubjectOrEmail(USER_ID, EMAIL);

        assertThat(result).contains(List.of(new UserSession.App("0oa-1", "One")));
        assertThat(requestedPaths()).noneMatch(r -> r.startsWith("/api/v1/users/"));
    }

    @Test
    void looksTheUserUpByEmailWhenTheSubjectIsNotAnOktaUserId() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, apps(app("0oa-1", "One", "ACTIVE")));

        var result = lookup().forSubjectOrEmail("some-other-subject", EMAIL);

        assertThat(result).contains(List.of(new UserSession.App("0oa-1", "One")));
        assertThat(requestedPaths()).anyMatch(r -> r.startsWith(USER_PATH));
    }

    @Test
    void followsTheNextPageLink() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps", Reply.of(200, apps(app("0oa-1", "One", "ACTIVE")))
                .withHeader("Link", "<" + okta.url() + "/page2>; rel=\"next\""));
        okta.respond("/page2", 200, apps(app("0oa-2", "Two", "ACTIVE")));

        assertThat(lookup().forUser(EMAIL)).contains(List.of(
                new UserSession.App("0oa-1", "One"),
                new UserSession.App("0oa-2", "Two")));
    }

    @Test
    void refusesANextLinkOnAnotherHostWithoutRequestingIt() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps", Reply.of(200, apps(app("0oa-1", "One", "ACTIVE")))
                .withHeader("Link", "<https://evil.example/steal>; rel=\"next\""));

        assertThat(lookup().forUser(EMAIL)).isEmpty();
        assertThat(requestedPaths()).noneMatch(r -> r.contains("evil.example"));
    }

    @Test
    void refusesANextLinkOnAHostThatMerelyStartsWithTheOktaHost() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps", Reply.of(200, apps(app("0oa-1", "One", "ACTIVE")))
                .withHeader("Link", "<" + okta.url() + ".evil.example/steal>; rel=\"next\""));

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void reportsAFailureInsteadOfAPartialListWhenThePageLimitIsHit() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps", Reply.of(200, apps(app("0oa-1", "One", "ACTIVE")))
                .withHeader("Link", "<" + okta.url() + "/api/v1/apps?after=again>; rel=\"next\""));

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void reportsAFailureWhenTheUserCannotBeFound() {
        okta.respond(USER_PATH, 404, "{}");

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void reportsAFailureWhenTheUserHasNoId() {
        okta.respond(USER_PATH, 200, "{}");

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void reportsAFailureWhenTheAppsResponseIsNotAList() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 200, "{\"errorCode\":\"E0000000\"}");

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void reportsAFailureWhenOktaRefusesTheToken() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 403, "{\"errorCode\":\"E0000006\"}");

        assertThat(lookup().forUser(EMAIL)).isEmpty();
    }

    @Test
    void retriesAfterARateLimitAndThenSucceeds() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps",
                Reply.of(429, "{}"),
                Reply.of(200, apps(app("0oa-1", "One", "ACTIVE"))));

        assertThat(lookup().forUser(EMAIL)).contains(List.of(new UserSession.App("0oa-1", "One")));
        assertThat(pauses).hasSize(1);
    }

    @Test
    void givesUpWhenOktaKeepsRateLimiting() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respond("/api/v1/apps", 429, "{}");

        assertThat(lookup().forUser(EMAIL)).isEmpty();
        assertThat(pauses).hasSize(2);
    }

    @Test
    void waitsUntilTheRateLimitResetsButNeverForLong() {
        okta.respond(USER_PATH, 200, USER_JSON);
        okta.respondInOrder("/api/v1/apps",
                Reply.of(429, "{}").withHeader("x-rate-limit-reset", String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond())),
                Reply.of(200, "[]"));

        lookup().forUser(EMAIL);

        assertThat(pauses).singleElement().satisfies(wait ->
                assertThat(wait).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    @Test
    void doesNothingWhenOktaIsNotConfigured() {
        assertThat(new OktaAssignedApps("", TOKEN, pauses::add).forUser(EMAIL)).isEmpty();
        assertThat(new OktaAssignedApps(okta.url(), "", pauses::add).forUser(EMAIL)).isEmpty();
        assertThat(okta.requests()).isEmpty();
    }
}
