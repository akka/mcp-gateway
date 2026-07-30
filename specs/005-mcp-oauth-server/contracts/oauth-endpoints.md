# API Contracts: MCP OAuth 2.1 Authorization Server

## Metadata Endpoints (McpOAuthMetadataEndpoint)

### GET /.well-known/oauth-protected-resource

No authentication required.

**Response 200 OK** `application/json`:
```json
{
  "resource": "http://localhost:9000",
  "authorization_servers": ["http://localhost:9000"],
  "scopes_supported": ["mcp:read"],
  "bearer_methods_supported": ["header"]
}
```

---

### GET /.well-known/oauth-authorization-server

No authentication required.

**Response 200 OK** `application/json`:
```json
{
  "issuer": "http://localhost:9000",
  "authorization_endpoint": "http://localhost:9000/oauth2/authorize",
  "token_endpoint": "http://localhost:9000/oauth2/token",
  "registration_endpoint": "http://localhost:9000/oauth2/register",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code"],
  "code_challenge_methods_supported": ["S256"]
}
```

---

## Protocol Endpoints (McpOAuthEndpoint)

### POST /oauth2/register

Dynamically register an MCP client. No authentication required (open DCR).

**Request** `application/json`:
```json
{
  "redirect_uris": ["http://localhost:12345/callback"],
  "client_name": "My MCP Client"
}
```

**Response 201 Created** `application/json`:
```json
{
  "client_id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "client_name": "My MCP Client",
  "redirect_uris": ["http://localhost:12345/callback"]
}
```

**Errors**:
- `400 Bad Request` — missing or empty `redirect_uris`

---

### GET /oauth2/authorize

Initiates the OAuth 2.1 authorization code flow with PKCE.

**Query Parameters**:
| Param | Required | Description |
|-------|----------|-------------|
| `client_id` | Yes | Registered client UUID |
| `redirect_uri` | Yes | Must match registered URI |
| `response_type` | Yes | Must be `code` |
| `state` | Yes | Client-generated CSRF state |
| `code_challenge` | Yes | S256 PKCE challenge |
| `code_challenge_method` | Yes | Must be `S256` |
| `scope` | No | Defaults to `mcp:read` |

**Responses**:
- `302 → Okta login` — user has no session; OAuth params stored server-side
- `302 → /oauth2/consent?oauth_state=<state>` — user already has session; show consent screen
- `400 Bad Request` — unknown `client_id`, mismatched `redirect_uri`, or missing required params (rendered as error page, NOT a redirect)

---

### GET /oauth2/consent

Renders the consent screen HTML. Requires valid SESSION cookie or Bearer token.

**Query Parameters**:
| Param | Required | Description |
|-------|----------|-------------|
| `oauth_state` | Yes | Server-side OAuth state UUID (key to OAuthPendingAuthorization) |

**Response 200 OK** `text/html` — HTML page displaying client name, scope, Allow/Deny buttons.

**Errors**:
- `400 Bad Request` — unknown or expired `oauth_state`
- `302 → /login` — user not authenticated

---

### POST /oauth2/consent

Processes the user's consent decision.

**Request** `application/x-www-form-urlencoded`:
| Field | Required | Description |
|-------|----------|-------------|
| `oauth_state` | Yes | Server-side OAuth state UUID |
| `action` | Yes | `allow` or `deny` |

**On Allow — Response 302**:
```
Location: <redirect_uri>?code=<auth_code>&state=<client_state>
```

**On Deny — Response 302**:
```
Location: <redirect_uri>?error=access_denied&state=<client_state>
```

**Errors**:
- `400 Bad Request` — unknown/expired `oauth_state` or invalid `action`
- `302 → /login` — user not authenticated

---

### POST /oauth2/token

Exchanges an authorization code for an access token.

**Request** `application/x-www-form-urlencoded`:
| Field | Required | Description |
|-------|----------|-------------|
| `grant_type` | Yes | Must be `authorization_code` |
| `code` | Yes | The authorization code |
| `redirect_uri` | Yes | Must match the one used in authorize |
| `client_id` | Yes | The registered client UUID |
| `code_verifier` | Yes | PKCE verifier (pre-hash value) |

**Response 200 OK** `application/json`:
```json
{
  "access_token": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "token_type": "Bearer",
  "expires_in": 28800
}
```

**Error Response** `application/json` (RFC 6749 format):
```json
{
  "error": "invalid_grant",
  "error_description": "Authorization code is invalid, expired, or already used"
}
```

**Error codes**:
| Code | Condition |
|------|-----------|
| `unsupported_grant_type` | `grant_type` != `authorization_code` |
| `invalid_grant` | code not found, expired, already used, `redirect_uri` mismatch, PKCE failure |
| `invalid_client` | `client_id` not found |

---

## MCP Endpoint Authentication Requirement

All MCP endpoints (existing) must return the following when accessed without valid credentials:

**Response 401 Unauthorized**:
```
WWW-Authenticate: Bearer realm="mcp", resource_metadata="http://localhost:9000/.well-known/oauth-protected-resource"
```

## AbstractProtectedEndpoint Bearer Token Support

`requireSession()` resolution order:
1. Check `Authorization: Bearer <token>` header — look up `UserSessionEntity` with that token value
2. Check `SESSION` cookie — look up `UserSessionEntity` with cookie value
3. Return `null` if neither resolves to a valid, non-expired session
