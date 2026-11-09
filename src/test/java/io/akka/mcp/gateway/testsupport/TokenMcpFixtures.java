package io.akka.mcp.gateway.testsupport;

/** A token connector as an operator would set it up in {@code token-mcps}, for tests that stand a fake upstream behind it. */
public final class TokenMcpFixtures {

    public static final String ID = "test-token-service";
    public static final String NAME = "Test Token Service";
    public static final String TOOL_PREFIX = "TestToken_";

    private TokenMcpFixtures() {}

    /** The HOCON setting that adds the connector, pointing at {@code mcpUrl} and gated by {@code oktaAppId}. */
    public static String setting(String mcpUrl, String oktaAppId) {
        return "token-mcps = \"\"\"" + entries(entry(mcpUrl, oktaAppId)) + "\"\"\"";
    }

    public static String entry(String mcpUrl, String oktaAppId) {
        return """
                {"id":"%s","name":"%s","tool-prefix":"%s","mcp-url":"%s","okta-app-id":"%s"}
                """.formatted(ID, NAME, TOOL_PREFIX, mcpUrl, oktaAppId).strip();
    }

    public static String entries(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    /** The path of one of the connector's routes, such as {@code status}. */
    public static String path(String route) {
        return "/token-mcp/" + ID + "/" + route;
    }
}
