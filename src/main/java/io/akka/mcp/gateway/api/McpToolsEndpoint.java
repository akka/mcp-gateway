package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpClients;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.UserSession;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@HttpEndpoint("/mcp/tools")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpToolsEndpoint extends AbstractProtectedEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, RemoteMcpClient> clientsByMcpId;

    public McpToolsEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.clientsByMcpId = McpClients.serviceClients(componentClient, httpClientProvider, config).stream()
                .collect(Collectors.toMap(RemoteMcpClient::getMcpId, Function.identity()));
    }

    private boolean mayUse(UserSession session, String mcpId) {
        return Optional.ofNullable(clientsByMcpId.get(mcpId)).filter(client -> client.isUsableBy(session)).isPresent();
    }

    private McpConfig withoutShadowedTools(McpConfig config) {
        var client = clientsByMcpId.get(config.mcpId());
        return new McpConfig(config.mcpId(), config.name(), config.tools().stream().filter(tool -> client.listsCachedTool(tool.name())).toList());
    }

    @Get("/")
    public HttpResponse toolsPage() {
        if (requireSession() == null) return redirectToLogin();
        return HttpResponses.staticResource("tools.html");
    }

    @Get("/data")
    public HttpResponse toolsData() {
        var session = requireSession();
        if (session == null) return unauthorized();
        var registry = componentClient
                .forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::list)
                .invoke();
        var state = new McpRegistryEntity.State(registry.configs().stream()
                .filter(config -> mayUse(session, config.mcpId()))
                .map(this::withoutShadowedTools)
                .toList());
        try {
            return HttpResponse.create()
                    .withStatus(200)
                    .withEntity(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsString(state));
        } catch (Exception e) {
            return HttpResponse.create()
                    .withStatus(500)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Error serializing registry");
        }
    }
}
