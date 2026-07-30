# Research: MCP OAuth 2.1 Authorization Server

## Decision 1: Access Token Strategy

**Decision**: The issued OAuth 2.1 access token IS the existing `UserSession` UUID token (the same value stored in the `SESSION` cookie).

**Rationale**: The `UserSessionEntity` KVE already stores validated user identity, groups, and expiry. Reusing the session UUID as the bearer token means: no new token store, no token-to-session mapping, no dual invalidation logic. Bearer token auth simply does the same `UserSessionEntity` lookup that cookie auth already does.

**Alternatives considered**: Separate `OAuthAccessToken` store with a foreign key to `UserSession` — rejected as unnecessary complexity per YAGNI.

---

## Decision 2: Endpoint Structure — Two Endpoint Classes

**Decision**: Split into two HTTP endpoint classes:
- `McpOAuthMetadataEndpoint` at `@HttpEndpoint("/.well-known")` — serves the two RFC discovery documents (static/near-static JSON)
- `McpOAuthEndpoint` at `@HttpEndpoint("/oauth2")` — handles all protocol flows (register, authorize, consent, token)

**Rationale**: Single-responsibility. The metadata endpoints have no state and serve static JSON; the protocol endpoints involve entity lookups and redirects. Separate classes make each easier to test and reason about.

**Alternatives considered**: One large endpoint class — rejected, violates single-responsibility.

---

## Decision 3: Service Base URL Configuration

**Decision**: Add `MCP_BASE_URL` environment variable (default: `http://localhost:9000`). Used in all metadata documents (`resource`, `issuer`, `authorization_endpoint`, etc.).

**Rationale**: The service needs to know its own public URL to construct absolute URIs in RFC 8414 and RFC 9728 documents. This follows the existing pattern where `OKTA_REDIRECT_URI` is configured externally.

**Alternatives considered**: Auto-detect from request `Host` header — fragile behind proxies/load balancers. Rejected in favor of explicit configuration.

---

## Decision 4: Token Endpoint Body Format

**Decision**: Accept `application/x-www-form-urlencoded` for `POST /oauth2/token` (OAuth 2.1 spec requirement). Read the raw body via `requestContext()` and parse manually — the same approach used in `AuthEndpoint` for existing Okta token exchange.

**Rationale**: OAuth 2.1 mandates form-encoded bodies for the token endpoint. MCP clients (Claude Desktop, VS Code) send form-encoded. Manual parsing is simple for the small set of expected parameters.

**Alternatives considered**: JSON only — non-compliant with spec and incompatible with standard MCP clients.

---

## Decision 5: Consent Screen as Dynamic HTML

**Decision**: `GET /oauth2/consent?oauth_state=<state>` renders HTML directly from the endpoint handler (server-side generation, returns `text/html`). The page embeds the client name, scope, and a form that POSTs back to `/oauth2/consent`.

**Rationale**: The consent page needs to display the registered client name and scope, which requires a server-side lookup of `OAuthPendingAuthorizationEntity` and `OAuthClientEntity`. Server-rendering is simpler than serving a static page that makes AJAX calls. Matches the style of the existing `login.html` static page.

**Alternatives considered**: Static HTML with JavaScript fetch — requires an extra API endpoint for client info; more moving parts.

---

## Decision 6: Okta Callback Modification — Detect OAuth Flow

**Decision**: Modify `AuthEndpoint.callback()` to check whether the incoming Okta `state` parameter has a matching `OAuthPendingAuthorizationEntity`. If yes → redirect to `/oauth2/consent?oauth_state=<state>` (OAuth flow). If no → redirect to `/` (regular login flow, existing behaviour).

**Rationale**: The cleanest hook point is the existing callback. The `OAuthPendingAuthorizationEntity` key IS the Okta state value, so the lookup is a single KVE read. No separate routing or callback URLs needed.

**Alternatives considered**: Separate Okta redirect URI for OAuth flows — requires registering a second redirect URI with Okta and duplicating the token-exchange logic.

---

## Decision 7: Bearer Token Extraction in AbstractProtectedEndpoint

**Decision**: Extend `AbstractProtectedEndpoint.requireSession()` to first check for `Authorization: Bearer <token>` header, then fall back to SESSION cookie. Both resolve via the same `UserSessionEntity.getSession()` call.

**Rationale**: One place to change; all protected endpoints (SalesforceEndpoint, ZohoDeskEndpoint, GenericMcpEndpoint, etc.) inherit the behaviour automatically.

---

## Decision 8: PKCE Verification

**Decision**: Reuse the existing `generateCodeChallenge(codeVerifier)` method (SHA-256, Base64URL, no padding) already implemented in `AuthEndpoint`. Extract it to a package-private utility or duplicate in `McpOAuthEndpoint`.

**Rationale**: The algorithm is already implemented and tested. Extraction avoids duplication.

---

## Decision 9: Dynamic Client Registration — Open Registration

**Decision**: `POST /oauth2/register` requires no authentication. Any client can register by providing a `redirect_uri`. The consent screen is the user-facing gate.

**Rationale**: Per spec assumption A-003. Open DCR is standard for public MCP servers. The risk is mitigated by: (1) Okta authentication still validates the user identity, (2) the user explicitly approves on the consent screen.

---

## Decision 10: OAuthPendingAuthorization Keyed by Okta State

**Decision**: When an unauthenticated user triggers `/oauth2/authorize`, we generate a new UUID as the Okta `state` value. This UUID keys the `OAuthPendingAuthorizationEntity`. The entity stores all original OAuth params (including the MCP client's own `state`).

**Rationale**: Mirrors the existing `OidcPendingLoginEntity` pattern exactly. The Okta `state` = `OAuthPendingAuthorizationEntity` key = the entity ID used for KVE lookup in the callback.
