package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.SeamlessMcpClient;

/**
 * Seamless.AI has no per-user connection to report — it authenticates upstream with a single
 * operator-configured API key (see {@link SeamlessMcpClient}), not a per-user OAuth grant. So
 * "connected" here means only that the operator has actually set {@code SEAMLESS_API_KEY} and
 * {@code SEAMLESS_MCP_URL} — asked of {@link SeamlessMcpClient#isConnected} directly rather than
 * re-deriving the same blank check here, so the two can't drift — not that any particular user
 * has signed in anywhere. This mirrors the boolean the dashboard shows for every other system, so
 * the Seamless.AI card stops always reporting connected regardless of whether the operator has
 * configured it.
 */
@HttpEndpoint("/seamless")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SeamlessStatusEndpoint extends AbstractProtectedEndpoint {

    public record Status(boolean connected) {}

    private final SeamlessMcpClient client;

    public SeamlessStatusEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.client = new SeamlessMcpClient(
                config.getString("seamless.mcp-url"),
                config.getString("seamless.api-key"),
                config.getString("seamless.okta-app-id"));
    }

    @Get("/status")
    public HttpResponse status() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        return HttpResponses.ok(new Status(client.isConnected(session.email())));
    }
}
