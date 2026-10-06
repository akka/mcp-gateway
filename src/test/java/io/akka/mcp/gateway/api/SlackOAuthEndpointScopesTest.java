package io.akka.mcp.gateway.api;

import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.domain.WriteAccess;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reader's Slack token must never carry {@code chat:write}: the gateway gate would refuse the
 * post, but the scope should not exist on the token in the first place.
 */
public class SlackOAuthEndpointScopesTest {

    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String READER_GROUP = "mcp-gateway-reader";

    private static List<String> requestedScopes(String writeEnabled, String... groups) {
        var session = new UserSession("user@example.com", "User", null, null, List.of(groups), null, List.of());
        var params = SlackOAuthEndpoint.userScopeParam(session, WriteAccess.parse(writeEnabled, WRITER_GROUP));
        var userScope = URLDecoder.decode(params.substring("&user_scope=".length()), StandardCharsets.UTF_8);
        return List.of(userScope.split(" "));
    }

    @Test
    public void writer_onWriteEnabledSlack_isAskedForChatWrite() {
        assertThat(requestedScopes("slack", READER_GROUP, WRITER_GROUP))
                .contains("chat:write", "search:read");
    }

    @Test
    public void reader_isNeverAskedForChatWrite() {
        assertThat(requestedScopes("slack", READER_GROUP))
                .contains("search:read")
                .doesNotContain("chat:write");
    }

    @Test
    public void writer_isNotAskedForChatWrite_whenSlackIsNotWriteEnabled() {
        assertThat(requestedScopes("", READER_GROUP, WRITER_GROUP))
                .contains("search:read")
                .doesNotContain("chat:write");
    }
}
