package io.akka.mcp.gateway.api;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests for {@link McpOAuthEndpoint} logic that doesn't need the full TestKit — in
 * particular {@code isAllowedRedirectUri}, the pure function load-bearing for the DCR leg of the
 * account-takeover fix (see the class javadoc). No HTTP, no entities: just the redirect_uri policy.
 */
public class McpOAuthEndpointTest {

    private static McpOAuthEndpoint endpointWithAllowlist(String allowlist) {
        Config config = ConfigFactory.load()
                .withValue("mcp.oauth.redirect-host-allowlist", ConfigValueFactory.fromAnyRef(allowlist));
        return new McpOAuthEndpoint(null, config);
    }

    private static final McpOAuthEndpoint NO_ALLOWLIST = endpointWithAllowlist("");
    private static final McpOAuthEndpoint WITH_ALLOWLIST = endpointWithAllowlist("claude.ai,claude.com");

    @Test
    public void loopbackHttp_isAllowed() {
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("http://127.0.0.1:1234/callback")).isTrue();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("http://localhost:1234/callback")).isTrue();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("http://[::1]:1234/callback")).isTrue();
    }

    @Test
    public void https_withoutAllowlist_isAllowedForAnyHost() {
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("https://example.com/callback")).isTrue();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("https://anything.example.org/cb")).isTrue();
    }

    @Test
    public void https_withAllowlist_allowsOnlyListedHosts() {
        assertThat(WITH_ALLOWLIST.isAllowedRedirectUri("https://claude.ai/callback")).isTrue();
        assertThat(WITH_ALLOWLIST.isAllowedRedirectUri("https://claude.com/callback")).isTrue();
        assertThat(WITH_ALLOWLIST.isAllowedRedirectUri("https://evil.example/callback")).isFalse();
    }

    @Test
    public void plainHttp_toNonLoopbackHost_isDenied() {
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("http://evil.example/callback")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("http://attacker.com/callback")).isFalse();
    }

    @Test
    public void nonHttpScheme_isDenied() {
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("javascript:alert(1)")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("custom://callback")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("data:text/html,<script>alert(1)</script>")).isFalse();
    }

    @Test
    public void embeddedUserInfo_isDenied() {
        // Authority-confusion: looks like a trusted host to a human reviewer, resolves to evil.example.
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("https://trusted.com@evil.example/callback")).isFalse();
    }

    @Test
    public void malformedBlankOrNull_isDenied() {
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri(null)).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("   ")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("not a uri at all")).isFalse();
        assertThat(NO_ALLOWLIST.isAllowedRedirectUri("https:///no-host")).isFalse();
    }
}
