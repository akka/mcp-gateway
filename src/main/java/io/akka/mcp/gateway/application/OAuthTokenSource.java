package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.client.KeyValueEntityClient;
import io.akka.mcp.gateway.domain.OAuthClientRegistration;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Pending;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** The access token a user's OAuth sign-in to one connector issued, kept in {@link OAuthTokenConnectionEntity}. */
public class OAuthTokenSource implements BearerSource {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenSource.class);

    private final ComponentClient componentClient;
    private final McpOAuthFlow flow;
    private final String connectorId;

    public OAuthTokenSource(ComponentClient componentClient, McpOAuthFlow flow, String connectorId) {
        this.componentClient = componentClient;
        this.flow = flow;
        this.connectorId = connectorId;
    }

    public McpOAuthFlow flow() {
        return flow;
    }

    @Override
    public boolean isConnected(String userId) {
        return entity(userId).method(OAuthTokenConnectionEntity::getStatus).invoke(flow.resource()).connected();
    }

    @Override
    public Optional<String> bearerFor(String userId) {
        var bearer = entity(userId).method(OAuthTokenConnectionEntity::getAccessToken).invoke(flow.resource());
        return switch (bearer.outcome()) {
            case CURRENT -> bearer.accessToken();
            case NOT_CONNECTED -> Optional.empty();
            case ENDED_WITHOUT_REFRESH_TOKEN -> throw new SignInEnded("the access expired and there is no refresh token");
            case GRANT_GONE -> throw new SignInEnded("the authorization server refused the refresh token");
            case RENEWAL_KEEPS_FAILING -> throw new SignInEnded("the access has been expired for ten minutes and could not be renewed");
            case CLIENT_UNKNOWN -> {
                forgetRegistrationQuietly();
                throw new SignInEnded("the authorization server no longer knows the client");
            }
            case TRY_LATER -> throw new Unavailable("The sign-in could not be renewed");
        };
    }

    /** A token the upstream refused is renewed on the next call unless it was only just issued. */
    @Override
    public boolean forgetIfRefused(String userId, String refusedBearer) {
        return entity(userId).method(OAuthTokenConnectionEntity::rejectAccessToken).invoke(refusedBearer);
    }

    @Override
    public void disconnect(String userId) {
        entity(userId).method(OAuthTokenConnectionEntity::disconnect).invoke();
    }

    /** The registration shared by every user of this connector, if one is kept. */
    public Optional<OAuthClientRegistration> keptRegistration() {
        return componentClient.forKeyValueEntity(connectorId).method(OAuthRegistrationEntity::get).invoke().registration();
    }

    public void keepRegistration(OAuthClientRegistration registration) {
        componentClient.forKeyValueEntity(connectorId).method(OAuthRegistrationEntity::keep).invoke(registration);
    }

    /** Drops the shared client registration, so the next sign-in registers a client again. */
    public void forgetRegistration() {
        componentClient.forKeyValueEntity(connectorId).method(OAuthRegistrationEntity::forget).invoke();
    }

    private void forgetRegistrationQuietly() {
        try {
            forgetRegistration();
        } catch (RuntimeException e) {
            log.warn("{} could not forget its client registration: {}", connectorId, UpstreamFailure.describe(e));
        }
    }

    public void begin(String userId, Pending started) {
        entity(userId).method(OAuthTokenConnectionEntity::begin).invoke(started);
    }

    public Optional<Pending> takePending(String userId, String state) {
        return entity(userId).method(OAuthTokenConnectionEntity::takePending).invoke(state);
    }

    public void connect(String userId, Tokens issued) {
        entity(userId).method(OAuthTokenConnectionEntity::connect).invoke(issued);
    }

    private KeyValueEntityClient entity(String userId) {
        return componentClient.forKeyValueEntity(TokenConnectionEntity.keyFor(connectorId, userId));
    }
}
