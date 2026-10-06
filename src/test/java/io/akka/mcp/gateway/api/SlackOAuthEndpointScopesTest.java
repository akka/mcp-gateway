package io.akka.mcp.gateway.api;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import io.akka.mcp.gateway.domain.UserSession;
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

    private static SlackOAuthEndpoint endpoint(String writeEnabled) {
        Config config = ConfigFactory.load()
                .withValue("okta.groups.reader", ConfigValueFactory.fromAnyRef(READER_GROUP))
                .withValue("okta.groups.writer", ConfigValueFactory.fromAnyRef(WRITER_GROUP))
                .withValue("mcp.write-enabled", ConfigValueFactory.fromAnyRef(writeEnabled));
        return new SlackOAuthEndpoint(null, null, config);
    }

    private static List<String> requestedScopes(SlackOAuthEndpoint endpoint, String... groups) {
        var session = new UserSession("user@example.com", "User", null, null, List.of(groups), null, List.of());
        var params = endpoint.getExtraAuthParams(session);
        var userScope = URLDecoder.decode(params.substring("&user_scope=".length()), StandardCharsets.UTF_8);
        return List.of(userScope.split(" "));
    }

    @Test
    public void writer_onWriteEnabledSlack_isAskedForChatWrite() {
        assertThat(requestedScopes(endpoint("slack"), READER_GROUP, WRITER_GROUP))
                .contains("chat:write", "search:read");
    }

    @Test
    public void reader_isNeverAskedForChatWrite() {
        assertThat(requestedScopes(endpoint("slack"), READER_GROUP))
                .contains("search:read")
                .doesNotContain("chat:write");
    }

    @Test
    public void writer_isNotAskedForChatWrite_whenSlackIsNotWriteEnabled() {
        assertThat(requestedScopes(endpoint(""), READER_GROUP, WRITER_GROUP))
                .contains("search:read")
                .doesNotContain("chat:write");
    }
}
