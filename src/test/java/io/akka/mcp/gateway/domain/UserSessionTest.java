package io.akka.mcp.gateway.domain;

import akka.javasdk.JsonSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UserSessionTest {

    @Test
    public void appDeserialization_toleratesLegacyBareIdStrings() throws Exception {
        // Sessions persisted before apps became {id,label} objects stored bare id strings.
        // Those states must still deserialize after a deploy so active sessions survive.
        var mapper = JsonSupport.getObjectMapper();
        var legacyJson = """
            {"email":"user@lightbend.com","displayName":"User","createdAt":"2024-01-01T00:00:00Z",
             "expiresAt":"2099-01-01T00:00:00Z","groups":["mcp-gateway-reader"],"idToken":"tok",
             "apps":["0oa123","0oa456"]}""";

        var session = mapper.readValue(legacyJson, UserSession.class);

        assertThat(session.apps()).containsExactly(
                new UserSession.App("0oa123", "0oa123"),
                new UserSession.App("0oa456", "0oa456"));
    }

    @Test
    public void appRoundTrip_preservesIdAndLabel() throws Exception {
        var mapper = JsonSupport.getObjectMapper();
        var original = new UserSession(
                "user@lightbend.com", "User",
                Instant.now(), Instant.now().plusSeconds(3600),
                List.of("mcp-gateway-reader"), "tok",
                List.of(new UserSession.App("0oa123", "Salesforce")));

        var round = mapper.readValue(mapper.writeValueAsString(original), UserSession.class);

        assertThat(round.apps()).containsExactly(new UserSession.App("0oa123", "Salesforce"));
    }

    private UserSession sessionWithGroups(List<String> groups) {
        return new UserSession(
                "user@lightbend.com", "User",
                Instant.now(), Instant.now().plusSeconds(3600),
                groups, "id-token", List.of());
    }

    private static final String READER = "mcp-gateway-reader";
    private static final String WRITER = "mcp-gateway-writer";

    @Test
    public void reader_canPerformReads_butNotWrites() {
        var session = sessionWithGroups(List.of(READER));
        assertThat(session.canRead(READER)).isTrue();
        assertThat(session.canWrite(WRITER)).isFalse();
        // A read tool call (isWrite=false) must be permitted for a reader.
        assertThat(session.canInteract(false, READER, WRITER)).isTrue();
        assertThat(session.canInteract(true, READER, WRITER)).isFalse();
    }

    @Test
    public void writer_canPerformWrites_butNotReads() {
        var session = sessionWithGroups(List.of(WRITER));
        assertThat(session.canInteract(true, READER, WRITER)).isTrue();
        assertThat(session.canInteract(false, READER, WRITER)).isFalse();
    }

    @Test
    public void noGroups_cannotInteractAtAll() {
        var session = sessionWithGroups(List.of());
        assertThat(session.canInteract(false, READER, WRITER)).isFalse();
        assertThat(session.canInteract(true, READER, WRITER)).isFalse();
    }

    @Test
    public void blankGroupName_failsClosed() {
        var session = sessionWithGroups(List.of(READER));
        assertThat(session.canRead("")).isFalse();
        assertThat(session.hasRole("")).isFalse();
        assertThat(session.hasRole(null)).isFalse();
    }

    @Test
    public void hasApp_matchesOnStableId_notLabel() {
        var session = new UserSession(
                "user@lightbend.com", "User",
                Instant.now(), Instant.now().plusSeconds(3600),
                List.of(), "tok",
                List.of(new UserSession.App("0oa123", "Salesforce")));

        assertThat(session.hasApp("0oa123")).isTrue();
        // The friendly label must never satisfy an access check.
        assertThat(session.hasApp("Salesforce")).isFalse();
        assertThat(session.hasApp("0oa999")).isFalse();
    }
}
