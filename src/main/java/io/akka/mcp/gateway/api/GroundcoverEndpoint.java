package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.GroundcoverConnectionEntity;
import io.akka.mcp.gateway.application.GroundcoverMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;

import java.time.Instant;
import java.util.Optional;

@HttpEndpoint("/groundcover")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class GroundcoverEndpoint extends AbstractDcrOAuthEndpoint {

    private final String mcpUrl;
    private final String oktaAppId;
    private final String redirectUri;

    public GroundcoverEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.mcpUrl = config.getString("groundcover.mcp-url");
        this.oktaAppId = config.getString("groundcover.okta-app-id");
        this.redirectUri = config.getString("groundcover.redirect-uri");
    }

    @Get("/status")
    public HttpResponse status() { return super.status(); }

    @Override
    protected ConnectionStatus fetchConnectionStatus(String email) {
        var connection = componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::getStatus)
                .invoke();
        return new ConnectionStatus(connection.isConnected(), connection.tokenExpiresAt());
    }

    @Override protected String getMcpUrl() { return mcpUrl; }
    @Override protected String getRedirectUri() { return redirectUri; }
    @Override protected String getProviderLabel() { return "Groundcover"; }

    @Override
    protected void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint) {
        componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::initiatePkceOAuth)
                .invoke(new GroundcoverConnectionEntity.InitiateCommand(state, codeVerifier, clientId, tokenEndpoint));
    }

    @Get("/connect")
    public HttpResponse connect() { return super.connect(); }

    @Override
    protected Optional<PendingOAuthState> validatePendingState(String email, String state) {
        var connection = componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::getStatus)
                .invoke();
        if (!connection.isValidPendingState(state)) return Optional.empty();
        return Optional.of(new PendingOAuthState(connection.clientId(), connection.codeVerifier(), connection.tokenEndpoint()));
    }

    @Override
    protected void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state) {
        componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::storeToken)
                .invoke(new GroundcoverConnectionEntity.StoreTokenCommand(accessToken, refreshToken, expiresAt, state));
    }

    @Get("/callback")
    public HttpResponse callback() { return super.callback(); }

    @Override
    protected void clearConnection(String email) {
        componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::disconnect)
                .invoke();
    }

    @Get("/disconnect")
    public HttpResponse disconnect() { return super.disconnect(); }

    @Override
    protected Optional<String> fetchAccessToken(String email) {
        var connection = componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::getStatus)
                .invoke();
        return connection.isConnected() ? Optional.of(connection.accessToken()) : Optional.empty();
    }

    @Override
    protected RemoteMcpClient createMcpClient() { return new GroundcoverMcpClient(componentClient, mcpUrl, oktaAppId); }

    @Get("/token")
    public HttpResponse token() { return super.token(); }

    @Get("/test")
    public HttpResponse test() { return super.test(); }
}
