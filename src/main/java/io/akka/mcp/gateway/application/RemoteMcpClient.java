package io.akka.mcp.gateway.application;

import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.UserSession;

import java.util.List;
import java.util.Map;

public interface RemoteMcpClient {

    String getMcpId();
    String getMcpName();

    /** Okta appInstanceId required to access this MCP. Must be implemented by each client. */
    String getRequiredOktaAppId();

    /**
     * Whether this client's tools are local guidance rather than calls to a downstream system. Such
     * tools never reach an upstream, so the write gate does not apply to them. Only the how-to client
     * is; a connector must never override this.
     */
    default boolean isLocalGuidance() { return false; }

    /**
     * Whether the gateway may keep the arguments and result text of this client's tool calls in its
     * interaction history. A client whose data must not be stored by the gateway returns false; the
     * history then holds only who called which tool and whether it succeeded.
     */
    default boolean recordsToolContent() { return true; }

    /**
     * Whether the tools the upstream lists can differ from one user's credentials to another's. The
     * gateway then only adds to its shared tool cache, so a user who may see fewer tools cannot shrink
     * the list, or lose the read/write classification, for everyone else.
     */
    default boolean toolListDependsOnCaller() { return false; }

    /** Whether this client is set up on this gateway at all. An unavailable client's tools are not listed. */
    default boolean isAvailable() { return true; }

    /** Whether the user may use this system: it is set up and they hold its Okta application. */
    default boolean isUsableBy(UserSession session) { return isAvailable() && session.mayUseApp(getRequiredOktaAppId()); }

    /** Whether a tool name found in this client's cached tools is still listed. */
    default boolean listsCachedTool(String toolName) { return true; }

    /** Whether a tool name that another system also handles goes to that system. */
    default boolean yieldsToOtherSystems() { return false; }

    boolean isConnected(String userId);

    /** What is known of a user's connection; a lookup that failed is never reported as not connected. */
    enum Connection { CONNECTED, NOT_CONNECTED, CHECK_FAILED }

    default Connection connection(String userId) {
        return isConnected(userId) ? Connection.CONNECTED : Connection.NOT_CONNECTED;
    }

    boolean canHandle(String toolName);

    List<ToolEntry> listTools(String userId) throws Exception;
    ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) throws Exception;

    /**
     * Returns how-to content for the HowTo MCP client to expose as a tool.
     *
     * @param dashboardUrl the public base URL of the MCP Gateway dashboard
     */
    HowToContent howTo(String dashboardUrl);

    record ToolEntry(Map<String, Object> toolSpec, McpConfig.ToolMeta meta) {}
    record ToolCallResult(String text, boolean isError) {}

    /**
     * Metadata used by {@link HowToMcpClient} to generate a {@code howto_connect_*} tool.
     *
     * @param connectToolDescription description shown in the MCP tool listing
     * @param capabilitiesLine       one-line summary shown in the {@code howto_get_started} table
     * @param markdownBody           full connection instructions (markdown)
     */
    record HowToContent(String connectToolDescription, String capabilitiesLine, String markdownBody) {}
}
