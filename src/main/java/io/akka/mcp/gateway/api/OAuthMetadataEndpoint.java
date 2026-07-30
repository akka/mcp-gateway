package io.akka.mcp.gateway.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.typesafe.config.Config;

import java.util.List;

/**
 * Serves the OAuth 2.0 discovery documents required by MCP clients (RFC 9728 + RFC 8414).
 *
 * Flow:
 *   1. MCP client hits a protected endpoint → 401 with resource_metadata URL
 *   2. Client fetches /.well-known/oauth-protected-resource → finds this service as the AS
 *   3. Client fetches /.well-known/oauth-authorization-server → discovers register/authorize/token endpoints
 *   4. Client does DCR, PKCE authorize, token exchange — all handled by McpOAuthEndpoint
 */
@HttpEndpoint("/.well-known")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
public class OAuthMetadataEndpoint {

    private final String baseUrl;

    public OAuthMetadataEndpoint(Config config) {
        this.baseUrl = config.getString("mcp.base-url");
    }

    @Get("/oauth-protected-resource")
    public ProtectedResourceMetadata oauthProtectedResource() {
        return new ProtectedResourceMetadata(
                baseUrl,
                List.of(baseUrl),
                List.of("mcp:read"),
                List.of("header"));
    }

    @Get("/oauth-authorization-server")
    public AuthorizationServerMetadata oauthAuthorizationServer() {
        return new AuthorizationServerMetadata(
                baseUrl,
                baseUrl + "/oauth2/authorize",
                baseUrl + "/oauth2/token",
                baseUrl + "/oauth2/register",
                List.of("code"),
                List.of("authorization_code", "refresh_token"),
                List.of("S256"));
    }

    public record ProtectedResourceMetadata(
            String resource,
            List<String> authorization_servers,
            List<String> scopes_supported,
            List<String> bearer_methods_supported) {}

    public record AuthorizationServerMetadata(
            String issuer,
            String authorization_endpoint,
            String token_endpoint,
            String registration_endpoint,
            List<String> response_types_supported,
            List<String> grant_types_supported,
            List<String> code_challenge_methods_supported) {}
}
