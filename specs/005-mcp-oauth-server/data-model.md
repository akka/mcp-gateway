# Data Model: MCP OAuth 2.1 Authorization Server

## New Domain Records

### OAuthClient

Represents a dynamically registered MCP client application.

```
OAuthClient
  clientId        String    UUID assigned at registration
  clientName      String    Optional human-readable name (may be null/empty)
  redirectUri     String    The single registered redirect URI
  registeredAt    Instant   Registration timestamp
```

**State transitions**: Created once via registration; never updated; no deletion needed (orphaned entries are harmless).

**Empty state**: `clientId == null` — indicates the entity does not exist.

---

### OAuthAuthorizationCode

A short-lived, single-use authorization code issued after user consent.

```
OAuthAuthorizationCode
  code              String    The code value (random UUID, this is also the entity key)
  clientId          String    FK → OAuthClient.clientId
  sessionToken      String    FK → UserSessionEntity key (this becomes the access token)
  redirectUri       String    Must match the value used in the authorize request
  codeChallenge     String    PKCE challenge (S256 hash of verifier, Base64URL)
  codeChallengeMethod String  Always "S256"
  scope             String    e.g., "mcp:read"
  expiresAt         Instant   10 minutes from issuance
  used              boolean   Flipped to true on first successful token exchange
```

**Invariants**:
- `used` transitions from `false` → `true` exactly once.
- After `used = true`, no further token exchanges are permitted for this code.
- Any code older than `expiresAt` is treated as invalid regardless of `used`.

**Empty state**: `code == null` — indicates the entity does not exist.

---

### OAuthPendingAuthorization

Temporary record preserving OAuth 2.1 authorize request params while the user completes Okta login. Analogous to `OidcPendingLogin`.

```
OAuthPendingAuthorization
  clientId        String    The MCP client requesting authorization
  redirectUri     String    Client's registered redirect URI
  codeChallenge   String    PKCE challenge from the authorize request
  codeChallengeMethod String  Always "S256"
  scope           String    Requested scope
  clientState     String    The MCP client's original `state` param (passed through)
  expiresAt       Instant   10 minutes from creation
```

**Entity key**: A server-generated UUID that is used as the Okta `state` parameter during the login redirect. This allows the Okta callback to look up the pending authorization by the `state` query param returned by Okta.

**Lifecycle**: Created at `/oauth2/authorize` when no session exists → retrieved in Okta callback → deleted after consent decision.

**Empty state**: `clientId == null`.

---

## Existing Records (unchanged)

### UserSession (existing)

```
UserSession
  email        String
  displayName  String
  createdAt    Instant
  expiresAt    Instant   (8 hours from creation)
  groups       List<String>
```

**Role in OAuth flow**: The `UserSessionEntity` key UUID is the issued access token. No changes to this entity.

---

## Entity Summary

| Entity | Type | Key | New/Existing |
|--------|------|-----|--------------|
| `OAuthClientEntity` | KVE | `client_id` UUID | New |
| `OAuthAuthorizationCodeEntity` | KVE | `code` UUID | New |
| `OAuthPendingAuthorizationEntity` | KVE | Okta `state` UUID | New |
| `UserSessionEntity` | KVE | session token UUID | Existing (unchanged) |
| `OidcPendingLoginEntity` | KVE | Okta `state` UUID | Existing (unchanged) |

## Relationships

```
OAuthPendingAuthorization --[clientId]--> OAuthClient
OAuthAuthorizationCode    --[clientId]--> OAuthClient
OAuthAuthorizationCode    --[sessionToken]--> UserSession
```
