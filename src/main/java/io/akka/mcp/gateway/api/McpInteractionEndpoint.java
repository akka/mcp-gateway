package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.McpInteractionEntity;
import io.akka.mcp.gateway.application.McpInteractionsByMcpView;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.UniqueInteractionMcpsView;
import io.akka.mcp.gateway.application.UniqueInteractionUsersView;

import java.time.Instant;
import java.util.List;

/**
 * Audit log viewer for MCP tool calls routed through {@link AkkaMcpGateway}.
 *
 * Every request and response passing through the proxy is recorded in
 * {@link io.akka.mcp.gateway.application.McpInteractionEntity} (event-sourced).
 * This endpoint exposes those records via two views — by user and by MCP server —
 * and allows escalation status to be updated on individual interactions.
 *
 * Routes: {@code GET /interactions} (browser log UI),
 * {@code GET /interactions/user/{userId}} (all interactions for a user),
 * {@code GET /interactions/by-mcp/{mcpId}} (all interactions for an MCP server),
 * {@code PUT /interactions/{id}/escalation} (update escalation status).
 */
@HttpEndpoint("/interactions")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpInteractionEndpoint extends AbstractProtectedEndpoint {

    private static final int DEFAULT_PAGE_SIZE = 25;

    public McpInteractionEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
    }

    public record InteractionResponse(
            String interactionId,
            String userId,
            String mcpId,
            String tool,
            String params,
            String escalationStatus,
            Instant timestamp,
            String direction,
            String output
    ) {}

    public record InteractionsResponse(List<InteractionResponse> interactions, long totalCount, int page, int pageSize) {}

    public record UpdateEscalationStatusRequest(String status) {}

    public record DistinctValuesResponse(List<String> items) {}

    @Get("/distinct-users")
    public HttpResponse getDistinctUsers() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        var result = componentClient
                .forView()
                .method(UniqueInteractionUsersView::getAll)
                .invoke();
        return HttpResponses.ok(new DistinctValuesResponse(result.items()));
    }

    @Get("/distinct-mcps")
    public HttpResponse getDistinctMcps() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        var result = componentClient
                .forView()
                .method(UniqueInteractionMcpsView::getAll)
                .invoke();
        return HttpResponses.ok(new DistinctValuesResponse(result.items()));
    }

    @Get("")
    public HttpResponse interactionsPage() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        return HttpResponses.staticResource("interactions.html");
    }

    @Get("/user")
    public HttpResponse getAllByUser() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        int page = pageParam();
        int pageSize = pageSizeParam();
        var result = componentClient
                .forView()
                .method(McpInteractionsByUserView::getAll)
                .invoke(new McpInteractionsByUserView.PageRequest(page * pageSize, pageSize));
        return HttpResponses.ok(toUserResponse(result, page, pageSize));
    }

    @Get("/user/{userId}")
    public HttpResponse getByUser(String userId) {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        int page = pageParam();
        int pageSize = pageSizeParam();
        var result = componentClient
                .forView()
                .method(McpInteractionsByUserView::getByUser)
                .invoke(new McpInteractionsByUserView.UserPageRequest(userId, page * pageSize, pageSize));
        return HttpResponses.ok(toUserResponse(result, page, pageSize));
    }

    @Get("/by-mcp/{mcpId}")
    public HttpResponse getByMcp(String mcpId) {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        int page = pageParam();
        int pageSize = pageSizeParam();
        var result = componentClient
                .forView()
                .method(McpInteractionsByMcpView::getByMcp)
                .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, page * pageSize, pageSize));
        var interactions = result.interactions().stream()
                .map(e -> new InteractionResponse(e.interactionId(), e.userId(), e.mcpId(),
                        e.tool(), e.params(), e.escalationStatus(), e.timestamp(), e.direction(), e.output().orElse(null)))
                .toList();
        return HttpResponses.ok(new InteractionsResponse(interactions, result.totalCount(), page, pageSize));
    }

    @Put("/{interactionId}/escalation")
    public HttpResponse updateEscalationStatus(String interactionId, UpdateEscalationStatusRequest request) {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        if (request.status() == null || request.status().isBlank()) {
            return HttpResponses.badRequest("Status must not be empty");
        }
        componentClient
                .forEventSourcedEntity(interactionId)
                .method(McpInteractionEntity::updateEscalationStatus)
                .invoke(request.status());
        return HttpResponses.ok();
    }

    private int pageParam() {
        try { return Math.max(0, Integer.parseInt(requestContext().queryParams().getString("page").orElse("0"))); }
        catch (NumberFormatException e) { return 0; }
    }

    private int pageSizeParam() {
        try { return Math.max(1, Math.min(100, Integer.parseInt(requestContext().queryParams().getString("pageSize").orElse(String.valueOf(DEFAULT_PAGE_SIZE))))); }
        catch (NumberFormatException e) { return DEFAULT_PAGE_SIZE; }
    }

    private InteractionsResponse toUserResponse(McpInteractionsByUserView.McpInteractionEntries result, int page, int pageSize) {
        var interactions = result.interactions().stream()
                .map(e -> new InteractionResponse(e.interactionId(), e.userId(), e.mcpId(),
                        e.tool(), e.params(), e.escalationStatus(), e.timestamp(), e.direction(), e.output().orElse(null)))
                .toList();
        return new InteractionsResponse(interactions, result.totalCount(), page, pageSize);
    }
}
