# Data Model: OAuth Token Flow for Salesforce MCP

**Feature**: 002-oauth-token-flow
**Date**: 2026-04-21

---

## Entities

### SalesforceConnection (Key Value Entity)

**Purpose**: Singleton entity representing the Salesforce integration state for this service deployment.
**Entity ID**: fixed string `"default"` (only one connection per service instance)
**Package**: `com.example.application.SalesforceConnectionEntity`

#### State Record

```
SalesforceConnection (domain/SalesforceConnection.java)
├── String accessToken          — null when not connected
├── String instanceUrl          — Salesforce instance URL (null when not connected)
├── Instant tokenAcquiredAt     — null when not connected
├── String pendingOAuthState    — random nonce for in-flight OAuth exchange; null when idle
└── Instant oauthStateExpiresAt — expiry for the pending nonce; null when idle
```

**Derived behaviour (domain methods)**:
- `isConnected()` → `accessToken != null`
- `isOAuthStatePending()` → `pendingOAuthState != null && now < oauthStateExpiresAt`
- `isValidOAuthState(String candidate)` → `isOAuthStatePending() && pendingOAuthState.equals(candidate)`
- `withPendingState(String nonce, Instant expiresAt)` → returns new state with nonce fields set
- `withToken(String token, String instanceUrl, Instant acquiredAt)` → returns connected state, clears nonce
- `disconnected()` → returns empty state (all fields null)

#### Command Handlers

| Method | Input | Output | Description |
|---|---|---|---|
| `getStatus()` | — | `SalesforceConnection` | Returns current state (read-only) |
| `initiateOAuth(String nonce)` | 16-char random nonce | `Done` | Stores nonce + 10-min expiry |
| `storeToken(StoreTokenCommand)` | token, instanceUrl, acquiredAt | `Done` | Validates nonce consumed, persists token |
| `disconnect()` | — | `Done` | Clears token and nonce fields |

```
StoreTokenCommand (inner record of SalesforceConnectionEntity)
├── String accessToken
├── String instanceUrl
├── Instant acquiredAt
└── String oauthState   — must match stored pending state for validation
```

---

## Component Changes

### SalesforceAgent (modified)

The `query` method signature changes from `query(String message)` to `query(QueryRequest request)`.

```
QueryRequest (inner record of SalesforceAgent)
├── String message      — the natural-language query
└── String bearerToken  — the OAuth token fetched by the endpoint
```

The agent no longer reads environment variables. The bearer token is supplied by the caller.

### SalesforceEndpoint (modified)

Before invoking the agent, the endpoint calls `SalesforceConnectionEntity.getStatus()`:
- If `isConnected()` → pass `bearerToken` from the entity to `SalesforceAgent`
- If not connected → return `503 Service Unavailable` with message directing the admin to connect via the UI

---

## New Components

### OAuthEndpoint (new HTTP Endpoint)

**Package**: `com.example.api.OAuthEndpoint`

Handles the OAuth flow and serves the admin UI.

| Route | Method | Description |
|---|---|---|
| `/` | GET | Serves static admin UI (`index.html`) |
| `/oauth/connect` | GET | Generates nonce, stores via entity, redirects browser to Salesforce |
| `/oauth/callback` | GET | Validates state, exchanges code for token, stores token, redirects to `/` |
| `/oauth/status` | GET | Returns `ConnectionStatus` JSON |

```
ConnectionStatus (inner record of OAuthEndpoint)
├── boolean connected
└── Instant tokenAcquiredAt  — null if not connected
```

---

## State Transitions

```
DISCONNECTED
    │
    │ GET /oauth/connect
    ▼
PENDING (nonce stored, 10-min TTL)
    │
    ├─── GET /oauth/callback (valid state + code)
    │         ▼
    │     CONNECTED (token stored, nonce cleared)
    │         │
    │         └─── GET /oauth/connect (reconnect)
    │                   └─── loops back to PENDING
    │
    └─── GET /oauth/callback (invalid/expired state)
              ▼
          DISCONNECTED (nonce cleared, error shown in UI)
```
