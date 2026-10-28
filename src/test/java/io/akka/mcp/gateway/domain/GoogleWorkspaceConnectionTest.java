package io.akka.mcp.gateway.domain;

import akka.javasdk.JsonSupport;
import io.akka.mcp.gateway.domain.GoogleWorkspaceConnection.ScopeGrant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleWorkspaceConnectionTest {

    private static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");

    @Test
    public void scopesFrom_splitsGoogleScopeField() {
        assertThat(GoogleWorkspaceConnection.scopesFrom(" openid  " + SHEETS_SCOPE + " email "))
                .containsExactlyInAnyOrder("openid", SHEETS_SCOPE, "email");
        assertThat(GoogleWorkspaceConnection.scopesFrom("")).isEmpty();
    }

    @Test
    public void scopeGrant_recordedScopes_grantedOrMissing() {
        var connection = GoogleWorkspaceConnection.empty()
                .withToken("access", "refresh", EXPIRES_AT, Set.of("openid", SHEETS_SCOPE));

        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.GRANTED);
        assertThat(connection.scopeGrant("https://www.googleapis.com/auth/documents")).isEqualTo(ScopeGrant.MISSING);
    }

    @Test
    public void scopeGrant_scopesNeverRecorded_isUnknown() {
        var connection = GoogleWorkspaceConnection.empty().withToken("access", "refresh", EXPIRES_AT);
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.UNKNOWN);
    }

    @Test
    public void scopeGrant_emptyRecordedScopes_isMissingNotUnknown() {
        var connection = GoogleWorkspaceConnection.empty().withToken("access", "refresh", EXPIRES_AT, Set.of());
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.MISSING);
    }

    @Test
    public void withToken_withoutScopes_keepsRecordedScopes() {
        var connection = GoogleWorkspaceConnection.empty()
                .withToken("access", "refresh", EXPIRES_AT, Set.of(SHEETS_SCOPE))
                .withToken("access-2", "refresh", EXPIRES_AT);
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.GRANTED);
    }

    @Test
    public void withPending_keepsRecordedScopes() {
        var connection = GoogleWorkspaceConnection.empty()
                .withToken("access", "refresh", EXPIRES_AT, Set.of(SHEETS_SCOPE))
                .withPending("state", "verifier", "client", "https://oauth2.googleapis.com/token", EXPIRES_AT);
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.GRANTED);
    }

    @Test
    public void withGrantedScopes_replacesScopesAndKeepsToken() {
        var connection = GoogleWorkspaceConnection.empty()
                .withToken("access", "refresh", EXPIRES_AT, Set.of("openid"))
                .withGrantedScopes(Set.of(SHEETS_SCOPE));

        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.GRANTED);
        assertThat(connection.scopeGrant("openid")).isEqualTo(ScopeGrant.MISSING);
        assertThat(connection.accessToken()).isEqualTo("access");
    }

    @Test
    public void disconnected_forgetsRecordedScopes() {
        var connection = GoogleWorkspaceConnection.empty()
                .withToken("access", "refresh", EXPIRES_AT, Set.of(SHEETS_SCOPE))
                .disconnected();
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.UNKNOWN);
    }

    @Test
    public void deserialization_stateStoredBeforeScopesWereRecorded_isUnknown() throws Exception {
        var legacyJson = """
            {"accessToken":"access","refreshToken":"refresh","tokenExpiresAt":"2099-01-01T00:00:00Z",
             "clientId":"client","tokenEndpoint":"https://oauth2.googleapis.com/token"}
            """;
        var connection = JsonSupport.getObjectMapper().readValue(legacyJson, GoogleWorkspaceConnection.class);

        assertThat(connection.isConnected()).isTrue();
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.UNKNOWN);
    }
}
