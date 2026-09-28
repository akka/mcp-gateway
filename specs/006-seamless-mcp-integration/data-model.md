# Data Model: Seamless.AI MCP Integration

Only one new persisted concept; everything else (contacts, campaigns, etc.) lives in Seamless.AI and is never stored by the gateway.

## SeamlessConnection (domain record, state of `SeamlessConnectionEntity`, key = user email)

| Field | Type | Notes |
|---|---|---|
| accessToken | String | null when not connected |
| refreshToken | String | may be null |
| tokenExpiresAt | Instant | null = no expiry known |
| pendingState | String | OAuth `state` for the in-flight connect |
| codeVerifier | String | PKCE verifier for the in-flight connect |
| clientId | String | dynamically registered client id |
| tokenEndpoint | String | discovered token endpoint (used for refresh) |
| pendingExpiresAt | Instant | pending state valid for 10 minutes |

Shape is identical to `ReoConnection` (DCR pattern).

### Behaviour
- `empty()`: all null.
- `isConnected()`: `accessToken != null`.
- `isValidPendingState(candidate)`: pending present, not expired, equals candidate.
- `isTokenExpired()`: expiry known and within 60 s of now.
- `withPending(state, verifier, clientId, tokenEndpoint, expiresAt)`: sets pending fields, keeps tokens.
- `withToken(access, refresh, expiresAt)`: stores tokens, clears pending, keeps `clientId`/`tokenEndpoint` for refresh.
- `disconnected()`: back to `empty()`.

### State transitions

```text
Not connected --initiatePkceOAuth--> Pending --storeToken(valid state)--> Connected
Pending --(10 min)--> Not connected (pending invalid)
Connected --getAccessToken (expired, refresh ok)--> Connected (new token)
Connected --getAccessToken (expired, refresh fails/absent)--> error: reconnect
Connected --disconnect--> Not connected
```

### Validation rules
- `storeToken` rejects an invalid or expired `state` (spec: sign-in must complete for the initiating user).
- `getAccessToken` on a not-connected user errors with a "connect Seamless.AI" message; on expired-without-refresh, a "reconnect" message (FR-017).

## Derived / transient (not persisted by this feature)
- **Tool metadata**: per-tool `ToolMeta` (name, schema, `readOnlyHint`) cached in the existing `McpRegistryEntity` under mcp id `seamless`.
- **Interaction record**: existing `McpInteraction` events for every call (FR-011).
