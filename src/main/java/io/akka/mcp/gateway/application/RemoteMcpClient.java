package io.akka.mcp.gateway.application;

import io.akka.mcp.gateway.domain.McpConfig;

import java.util.List;
import java.util.Map;

public interface RemoteMcpClient {

    String getMcpId();
    String getMcpName();

    /** Okta appInstanceId required to access this MCP. Must be implemented by each client. */
    String getRequiredOktaAppId();

    boolean isConnected(String userId);
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
