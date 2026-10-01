package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;

/**
 * Seamless.AI has no per-user connection to report — it authenticates upstream with a single
 * operator-configured API key (see {@code SeamlessMcpClient}), not a per-user OAuth grant. So
 * "connected" here means only that the operator has actually set {@code SEAMLESS_API_KEY} and
 * {@code SEAMLESS_MCP_URL} — the same check {@code SeamlessMcpClient#isConnected} makes — not
 * that any particular user has signed in anywhere. This mirrors the boolean the dashboard shows
 * for every other system, so the Seamless.AI card stops always reporting connected regardless of
 * whether the operator has configured it.
 */
@HttpEndpoint("/seamless")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SeamlessStatusEndpoint extends AbstractProtectedEndpoint {

    public record Status(boolean connected) {}

    private final String mcpUrl;
    private final String apiKey;

    public SeamlessStatusEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.mcpUrl = config.getString("seamless.mcp-url");
        this.apiKey = config.getString("seamless.api-key");
    }

    @Get("/status")
    public HttpResponse status() {
        if (requireSession() == null) return redirectToLogin();
        return HttpResponses.ok(new Status(!mcpUrl.isBlank() && !apiKey.isBlank()));
    }
}
