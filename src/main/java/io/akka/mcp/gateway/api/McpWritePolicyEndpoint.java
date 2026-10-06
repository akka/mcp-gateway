package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.McpClients;
import io.akka.mcp.gateway.application.McpInteractionEntity;
import io.akka.mcp.gateway.application.McpWritePolicyEntity;
import io.akka.mcp.gateway.application.RemoteMcpClient;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin control for which MCPs may run write tools. Saving a selection overrides the deployment
 * config ({@code mcp.write-enabled}); every change is recorded in the interaction log so it is
 * auditable and can be flagged like any other entry.
 *
 * Routes: {@code GET /admin/write-access} (admin page), {@code GET /admin/write-access/data},
 * {@code PUT /admin/write-access}.
 */
@HttpEndpoint("/admin/write-access")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpWritePolicyEndpoint extends AbstractProtectedEndpoint {

    private final List<RemoteMcpClient> connectors;

    public McpWritePolicyEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.connectors = McpClients.serviceClients(componentClient, httpClientProvider, config);
    }

    public record Connector(String mcpId, String mcpName, boolean enabled) {}

    /** @param source {@code "admin"} once an admin has saved a selection, otherwise {@code "config"} */
    public record WritePolicyResponse(List<Connector> connectors, String source, String updatedBy, Instant updatedAt,
                                      boolean writerGroupConfigured) {}

    /** @param basedOn the enabled ids the admin was looking at, so a stale page cannot overwrite a newer change */
    public record UpdateRequest(List<String> enabledMcpIds, List<String> basedOn) {}

    @Get("")
    public HttpResponse page() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        return HttpResponses.staticResource("write-access.html");
    }

    @Get("/data")
    public HttpResponse data() {
        var session = requireSession();
        if (session == null) return unauthorized();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        return HttpResponses.ok(view(savedPolicy()));
    }

    @Put("")
    public HttpResponse update(UpdateRequest request) {
        var session = requireSession();
        if (session == null) return unauthorized();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        if (request == null || request.enabledMcpIds() == null || request.basedOn() == null) {
            return HttpResponses.badRequest("enabledMcpIds and basedOn are required");
        }

        var known = connectors.stream().map(RemoteMcpClient::getMcpId).collect(Collectors.toSet());
        var unknown = request.enabledMcpIds().stream().filter(id -> !known.contains(id)).distinct().sorted().toList();
        if (!unknown.isEmpty()) return HttpResponses.badRequest("Unknown MCP ids: " + String.join(", ", unknown));

        var saved = savedPolicy();
        var current = enabledIds(saved);
        if (!new HashSet<>(request.basedOn()).equals(new HashSet<>(current))) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.CONFLICT)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                            "The selection was changed by someone else since you loaded this page. Reload and try again.");
        }

        var next = request.enabledMcpIds().stream().distinct().sorted().toList();
        if (next.equals(current)) return HttpResponses.ok(view(saved));

        var updated = componentClient
                .forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::set)
                .invoke(new McpWritePolicyEntity.SetCommand(next, session.email()));
        recordChange(session.email(), current, next);
        return HttpResponses.ok(view(updated));
    }

    private McpWritePolicyEntity.State savedPolicy() {
        return componentClient
                .forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke();
    }

    /** The ids currently in force among the connectors that exist, sorted. */
    private List<String> enabledIds(McpWritePolicyEntity.State saved) {
        Set<String> enabled = saved.configured() ? Set.copyOf(saved.enabledMcpIds()) : configuredWriteAccess.enabledMcpIds();
        return connectors.stream()
                .map(RemoteMcpClient::getMcpId)
                .filter(enabled::contains)
                .sorted()
                .toList();
    }

    private WritePolicyResponse view(McpWritePolicyEntity.State saved) {
        var enabled = Set.copyOf(enabledIds(saved));
        var rows = connectors.stream()
                .map(c -> new Connector(c.getMcpId(), c.getMcpName(), enabled.contains(c.getMcpId())))
                .sorted(Comparator.comparing(Connector::mcpName))
                .toList();
        return new WritePolicyResponse(rows, saved.configured() ? "admin" : "config",
                saved.updatedBy(), saved.updatedAt(), writerGroup != null && !writerGroup.isBlank());
    }

    private void recordChange(String adminEmail, List<String> before, List<String> after) {
        var added = after.stream().filter(id -> !before.contains(id)).toList();
        var removed = before.stream().filter(id -> !after.contains(id)).toList();
        componentClient
                .forEventSourcedEntity(UUID.randomUUID().toString())
                .method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand(
                        adminEmail, "proxy", "write-policy",
                        Map.of("added", String.join(",", added),
                                "removed", String.join(",", removed),
                                "enabledAfter", String.join(",", after)),
                        "policy-change"));
    }
}
