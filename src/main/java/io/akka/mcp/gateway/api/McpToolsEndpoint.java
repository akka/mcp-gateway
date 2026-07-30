package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpRegistryEntity;

@HttpEndpoint("/mcp/tools")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpToolsEndpoint extends AbstractProtectedEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public McpToolsEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
    }

    @Get("/")
    public HttpResponse toolsPage() {
        if (requireSession() == null) return redirectToLogin();
        return HttpResponses.staticResource("tools.html");
    }

    @Get("/data")
    public HttpResponse toolsData() {
        if (requireSession() == null) return unauthorized();
        var state = componentClient
                .forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::list)
                .invoke();
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
