package io.akka.mcp.gateway.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record UserSession(
        String email,
        String displayName,
        Instant createdAt,
        Instant expiresAt,
        List<String> groups,
        String idToken,
        List<App> apps
) {
    /**
     * An Okta application assigned to the user. {@code id} is the Okta app instance id
     * (stable identifier); {@code label} is the human-friendly display name.
     */
    public record App(String id, String label) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static App fromJson(JsonNode node) {
            // Legacy sessions stored apps as bare id strings; tolerate them on deserialization
            // so already-active sessions survive a deploy.
            if (node.isTextual()) {
                var id = node.asText();
                return new App(id, id);
            }
            var id = node.path("id").asText("");
            var label = node.path("label").asText("");
            return new App(id, label.isBlank() ? id : label);
        }
    }

    public static UserSession empty() {
        return new UserSession(null, null, null, null, List.of(), null, List.of());
    }

    public List<App> apps() {
        return apps != null ? apps : List.of();
    }

    /** Whether the user is assigned the Okta application with the given stable app id. */
    public boolean hasApp(String appId) {
        return apps().stream().anyMatch(a -> a.id().equals(appId));
    }

    public boolean isEmpty() {
        return email == null;
    }

    public boolean isExpired() {
        if (expiresAt == null) return true;
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * Whether the user belongs to the given Okta group. A null/blank group name fails closed
     * (returns false) so an unconfigured role never grants access.
     */
    public boolean hasRole(String group) {
        return group != null && !group.isBlank() && groups != null && groups.contains(group);
    }

    public boolean canRead(String readerGroup) {
        return hasRole(readerGroup);
    }

    public boolean canWrite(String writerGroup) {
        return hasRole(writerGroup);
    }

    /** Whether this session may perform a tool call of the given operation type. */
    public boolean canInteract(boolean isWrite, String readerGroup, String writerGroup) {
        return isWrite ? canWrite(writerGroup) : canRead(readerGroup);
    }

    public boolean isAdmin(String adminGroup) {
        return hasRole(adminGroup);
    }

    public boolean isEscalater(String escalaterGroup) {
        return hasRole(escalaterGroup);
    }
}
