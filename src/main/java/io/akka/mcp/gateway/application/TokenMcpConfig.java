package io.akka.mcp.gateway.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One operator-defined connector that forwards each user's own token to an MCP server. The connectors come
 * from the {@code token-mcps} setting, a JSON list; an entry that is not valid is skipped and logged, so a typo
 * turns a connector off and never opens it.
 */
public record TokenMcpConfig(String id, String name, String toolPrefix, String mcpUrl, String oktaAppId,
                             boolean recordToolContent, Optional<String> connectHint) {

    public static final String DEFAULT_CONNECT_HINT = "Get an access token from the service, then paste it here.";

    private static final Logger log = LoggerFactory.getLogger(TokenMcpConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern TOOL_PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,30}_");
    private static final String RESERVED_TOOL_PREFIX = "howto";
    private static final Set<String> RESERVED_IDS = Set.of("proxy", "howto");
    private static final String RESERVED_ID_PREFIX = "google-workspace";
    private static final String RESERVED_NAME_PREFIX = "google workspace";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String MCP_PATH = "/mcp";
    private static final int MAX_NAME_LENGTH = 60;
    private static final int MAX_HINT_LENGTH = 300;
    private static final Set<String> KNOWN_KEYS = Set.of(
            "id", "name", "tool-prefix", "mcp-url", "okta-app-id", "record-tool-content", "connect-hint");
    private static final Map<String, List<TokenMcpConfig>> PARSED = new ConcurrentHashMap<>();

    /** The connectors the operator set up. Each distinct setting is parsed, and its problems logged, once. */
    public static List<TokenMcpConfig> from(Config config) {
        return PARSED.computeIfAbsent(config.getString("token-mcps"), TokenMcpConfig::parse);
    }

    public String connectHintOrDefault() {
        return connectHint.orElse(DEFAULT_CONNECT_HINT);
    }

    /** Usable only with an Okta application to gate it and a URL that does not send tokens in the clear. */
    public boolean isUsable() {
        return !oktaAppId.isBlank() && hasSecureEndpoint();
    }

    private boolean hasSecureEndpoint() {
        if (mcpUrl.isBlank()) return false;
        try {
            var uri = URI.create(mcpUrl);
            if (uri.getHost() == null || uri.getScheme() == null) return false;
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            var scheme = uri.getScheme();
            return scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static List<TokenMcpConfig> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json.isBlank() ? "[]" : json);
        } catch (Exception e) {
            log.error("token-mcps is not valid JSON, so no token connector is set up");
            return List.of();
        }
        if (!root.isArray()) {
            log.error("token-mcps must be a JSON list, so no token connector is set up");
            return List.of();
        }
        var accepted = new ArrayList<TokenMcpConfig>();
        for (int i = 0; i < root.size(); i++) {
            switch (parseEntry(root.get(i))) {
                case Invalid invalid -> log.error("token-mcps entry {} is skipped: {}", i + 1, invalid.problem());
                case Valid valid -> addUnlessClashing(accepted, valid.config(), i + 1);
            }
        }
        return List.copyOf(accepted);
    }

    private static void addUnlessClashing(List<TokenMcpConfig> accepted, TokenMcpConfig candidate, int position) {
        var clash = clashWith(accepted, candidate);
        if (clash.isPresent()) {
            log.error("token-mcps entry {} ({}) is skipped: {}", position, candidate.id(), clash.get());
            return;
        }
        if (!candidate.isUsable() && !candidate.mcpUrl().isBlank()) {
            log.warn("token-mcps entry {} ({}) stays off: mcp-url must be https (http only for loopback) with no credentials, query or fragment,"
                    + " and okta-app-id must be set", position, candidate.id());
        }
        if (candidate.mcpUrl().endsWith(MCP_PATH)) {
            log.warn("token-mcps entry {} ({}): mcp-url is the server's base URL and the gateway adds {} itself", position, candidate.id(), MCP_PATH);
        }
        accepted.add(candidate);
    }

    private sealed interface Parsed permits Valid, Invalid {}

    private record Valid(TokenMcpConfig config) implements Parsed {}

    private record Invalid(String problem) implements Parsed {}

    private static Parsed parseEntry(JsonNode node) {
        if (!node.isObject()) return new Invalid("it is not an object");
        var unknown = node.properties().stream().map(Map.Entry::getKey).filter(key -> !KNOWN_KEYS.contains(key)).findFirst();
        if (unknown.isPresent()) return new Invalid("unknown setting " + shortened(unknown.get()));

        var id = text(node, "id").filter(TokenMcpConfig::isValidId);
        if (id.isEmpty()) {
            return new Invalid("id must be lowercase letters, digits and hyphens, starting with a letter, and not proxy, howto or "
                    + RESERVED_ID_PREFIX + "...");
        }
        var name = text(node, "name").map(String::strip).filter(value -> !value.isEmpty() && value.length() <= MAX_NAME_LENGTH
                && value.chars().noneMatch(Character::isISOControl) && !value.toLowerCase().startsWith(RESERVED_NAME_PREFIX));
        if (name.isEmpty()) {
            return new Invalid("name must be 1 to " + MAX_NAME_LENGTH + " characters with no control characters and not start with Google Workspace");
        }
        var prefix = text(node, "tool-prefix").filter(value -> TOOL_PREFIX.matcher(value).matches());
        if (prefix.isEmpty()) return new Invalid("tool-prefix must be letters and digits ending in an underscore");
        if (prefix.get().toLowerCase().startsWith(RESERVED_TOOL_PREFIX)) return new Invalid("tool-prefix cannot start with " + RESERVED_TOOL_PREFIX);
        var mcpUrl = text(node, "mcp-url");
        if (mcpUrl.isEmpty()) return new Invalid("mcp-url must be a string, blank to leave the connector off");
        var oktaAppId = text(node, "okta-app-id");
        if (oktaAppId.isEmpty()) return new Invalid("okta-app-id must be a string, blank to leave the connector off");
        if (node.has("record-tool-content") && !node.get("record-tool-content").isBoolean()) {
            return new Invalid("record-tool-content must be true or false");
        }
        var hint = node.has("connect-hint") ? text(node, "connect-hint").map(String::strip) : Optional.<String>empty();
        if (node.has("connect-hint") && !isValidHint(hint)) {
            return new Invalid("connect-hint must be 1 to " + MAX_HINT_LENGTH + " characters with no control characters");
        }
        return new Valid(new TokenMcpConfig(id.get(), name.get(), prefix.get(), withoutTrailingSlashes(mcpUrl.get().strip()), oktaAppId.get().strip(),
                node.path("record-tool-content").asBoolean(false), hint));
    }

    private static String withoutTrailingSlashes(String url) {
        var end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') end--;
        return url.substring(0, end);
    }

    private static boolean isValidId(String candidate) {
        return ID.matcher(candidate).matches() && !RESERVED_IDS.contains(candidate) && !candidate.startsWith(RESERVED_ID_PREFIX);
    }

    private static boolean isValidHint(Optional<String> hint) {
        return hint.filter(value -> !value.isEmpty() && value.length() <= MAX_HINT_LENGTH
                && value.chars().noneMatch(Character::isISOControl)).isPresent();
    }

    private static Optional<String> text(JsonNode node, String key) {
        var value = node.get(key);
        return value != null && value.isTextual() ? Optional.of(value.asText()) : Optional.empty();
    }

    private static Optional<String> clashWith(List<TokenMcpConfig> accepted, TokenMcpConfig candidate) {
        for (var other : accepted) {
            if (other.id().equals(candidate.id())) return Optional.of("the id is already used");
            if (other.name().equalsIgnoreCase(candidate.name())) return Optional.of("its name is already used by " + other.id());
            if (other.toolPrefix().equals(candidate.toolPrefix())) {
                return Optional.of("its tool-prefix is already used by " + other.id());
            }
        }
        return Optional.empty();
    }

    private static String shortened(String key) {
        return key.length() <= 40 ? key : key.substring(0, 40) + "...";
    }

    @Override
    public String toString() {
        return "TokenMcpConfig[id=" + id + "]";
    }
}
