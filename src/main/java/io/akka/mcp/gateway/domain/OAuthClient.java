package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record OAuthClient(String clientId, String clientName, String redirectUri, Instant registeredAt) {

    public static OAuthClient empty() {
        return new OAuthClient(null, null, null, null);
    }

    public boolean isEmpty() {
        return clientId == null;
    }
}
