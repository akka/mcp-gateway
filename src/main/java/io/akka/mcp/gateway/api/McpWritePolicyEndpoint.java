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
import io.akka.mcp.gateway.domain.WritePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin control for which MCPs may run write tools. Nothing is enabled until an admin saves a
 * selection; every change is recorded in the interaction log so it is auditable and can be
 * flagged like any other entry.
 *
 * Routes: {@code GET /admin/write-access} (admin page), {@code GET /admin/write-access/data},
 * {@code PUT /admin/write-access}.
 */
@HttpEndpoint("/admin/write-access")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpWritePolicyEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpWritePolicyEndpoint.class);

    private final List<RemoteMcpClient> connectors;

    public McpWritePolicyEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        this.connectors = McpClients.serviceClients(componentClient, httpClientProvider, config);
    }

    public record Connector(String mcpId, String mcpName, boolean enabled) {}

    /**
     * @param version pass this back as {@code basedOnVersion} when saving
     * @param updatedBy who last saved the selection, null if nobody ever has
     */
    public record WritePolicyResponse(List<Connector> connectors, long version, String updatedBy, Instant updatedAt,
                                      boolean writerGroupConfigured) {}

    /** @param basedOnVersion the {@code version} the admin was looking at, so a stale page cannot overwrite a newer change */
    public record UpdateRequest(List<String> enabledMcpIds, Long basedOnVersion) {}

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
        if (request == null || request.enabledMcpIds() == null || request.basedOnVersion() == null) {
            return HttpResponses.badRequest("enabledMcpIds and basedOnVersion are required");
        }

        var known = connectors.stream().map(RemoteMcpClient::getMcpId).collect(Collectors.toSet());
        var unknown = request.enabledMcpIds().stream().filter(id -> !known.contains(id)).distinct().sorted().toList();
        if (!unknown.isEmpty()) return HttpResponses.badRequest("Unknown MCP ids: " + String.join(", ", unknown));

        var result = componentClient
                .forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::select)
                .invoke(new McpWritePolicyEntity.SelectCommand(
                        request.enabledMcpIds(), request.basedOnVersion(), session.email()));

        return switch (result.outcome()) {
            case STALE -> HttpResponse.create()
                    .withStatus(StatusCodes.CONFLICT)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                            "The selection was changed by someone else since you loaded this page. Reload and try again.");
            case UNCHANGED -> HttpResponses.ok(view(result.policy()));
            case SAVED -> {
                auditChange(session.email(), result);
                yield HttpResponses.ok(view(result.policy()));
            }
        };
    }

    private WritePolicy savedPolicy() {
        return componentClient
                .forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke();
    }

    private WritePolicyResponse view(WritePolicy policy) {
        var enabled = Set.copyOf(policy.enabledMcpIds());
        var rows = connectors.stream()
                .map(c -> new Connector(c.getMcpId(), c.getMcpName(), enabled.contains(c.getMcpId())))
                .sorted(Comparator.comparing(Connector::mcpName))
                .toList();
        return new WritePolicyResponse(rows, policy.version(), policy.updatedBy(), policy.updatedAt(),
                writerGroup != null && !writerGroup.isBlank());
    }

    /**
     * The change is already in force when this runs, and saving it again would be a no-op, so a failed
     * audit write is logged with the details instead of failing the request.
     */
    private void auditChange(String adminEmail, McpWritePolicyEntity.SelectResult saved) {
        var added = String.join(",", saved.policy().addedSince(saved.previousEnabledMcpIds()));
        var removed = String.join(",", saved.policy().removedSince(saved.previousEnabledMcpIds()));
        try {
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            adminEmail, "proxy", "write-policy",
                            Map.of("added", added, "removed", removed,
                                    "enabledAfter", String.join(",", saved.policy().enabledMcpIds())),
                            "policy-change"));
        } catch (RuntimeException e) {
            log.error("Write policy changed by {} (added=[{}], removed=[{}]) but the audit record could not be written: {}",
                    adminEmail, added, removed, e.getMessage(), e);
        }
    }
}
