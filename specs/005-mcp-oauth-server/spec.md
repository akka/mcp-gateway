# Feature Specification: MCP OAuth 2.1 Authorization Server

**Feature Branch**: `005-mcp-oauth-server`
**Created**: 2026-04-28
**Status**: Draft
**Input**: User description: "Build authentication when connecting to this MCP server from an MCP client — no client id/secret needed, just the URL. This Akka app is the auth server where the user has a consent screen. Okta handles actual authentication (same as the current cookie-based session). The bearer token received by the MCP client must link to the same user session as the SESSION cookie. Both access methods must continue to work. Follow the MCP OAuth 2.1 authorization flow per modelcontextprotocol.io/docs/tutorials/security/authorization."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - MCP Client connects and authenticates end-to-end (Priority: P1)

An AI client (such as Claude Desktop or VS Code with Copilot) is configured with only the server URL. It discovers the authorization server, registers itself dynamically, and guides the user through a browser login and consent flow. After consent the client holds a bearer token it uses for all subsequent MCP requests — with no pre-configured client ID or secret required.

**Why this priority**: This is the core feature. Without it no MCP client can authenticate to this service.

**Independent Test**: Configure an MCP client with only the service URL. The client must complete discover → register → login → consent → token exchange without any manually provided credentials. A successful MCP tool invocation confirms the flow works end-to-end.

**Acceptance Scenarios**:

1. **Given** an MCP client configured with only the server URL, **When** it attempts to connect to a protected MCP endpoint, **Then** the server responds `401 Unauthorized` with a `WWW-Authenticate` header containing `resource_metadata=<PRM_URL>`.

2. **Given** a `GET /.well-known/oauth-protected-resource`, **When** the client fetches it, **Then** the JSON response includes the authorization server URL and supported scopes (`mcp:read`).

3. **Given** a `GET /.well-known/oauth-authorization-server`, **When** the client fetches it, **Then** the response includes `authorization_endpoint`, `token_endpoint`, `registration_endpoint`, `response_types_supported`, `grant_types_supported`, and `code_challenge_methods_supported`.

4. **Given** a `POST /oauth2/register` with a `redirect_uri` and optional `client_name`, **When** the client registers, **Then** a unique `client_id` is returned — no `client_secret` is issued or required.

5. **Given** a browser opened to `/oauth2/authorize` with valid parameters and an existing Okta session, **When** the request arrives, **Then** the user sees a consent screen describing the client and the requested scope.

6. **Given** the user clicks "Allow" on the consent screen, **When** the server processes the approval, **Then** the client's `redirect_uri` receives a short-lived, single-use authorization code and the original `state` value.

7. **Given** a `POST /oauth2/token` with the authorization code, PKCE verifier, `client_id`, and matching `redirect_uri`, **When** the exchange is valid, **Then** an access token (the session token UUID) is returned with `token_type=Bearer` and `expires_in`.

8. **Given** an MCP request with `Authorization: Bearer <token>`, **When** the server validates the token, **Then** it resolves the same `UserSession` as the equivalent SESSION cookie and grants access.

---

### User Story 2 - Unauthenticated user is directed through Okta login first (Priority: P2)

An MCP client opens the `/oauth2/authorize` URL in the user's browser. The user has no active browser session. The system transparently redirects them through Okta OIDC login before presenting the consent screen — preserving all OAuth 2.1 request parameters across the Okta round-trip.

**Why this priority**: Users will not always have an existing browser session. The flow must handle the unauthenticated case seamlessly with no parameters lost.

**Independent Test**: Clear all cookies, trigger the OAuth authorize endpoint, and verify the browser lands on Okta login. After completing Okta login verify the consent screen appears — not the app homepage — with the original client and scope visible.

**Acceptance Scenarios**:

1. **Given** a browser request to `/oauth2/authorize` with valid params but no SESSION cookie, **When** the request arrives, **Then** the user is redirected to the Okta OIDC authorization endpoint, and the original OAuth 2.1 parameters are stored server-side for retrieval after login.

2. **Given** the user completes Okta authentication, **When** the Okta callback is processed, **Then** the stored OAuth 2.1 parameters are restored and the user is directed to the consent screen (not the app dashboard).

3. **Given** the user sees the consent screen, **When** they approve, **Then** the authorization code is issued and the client's `redirect_uri` is called with `code` and `state`.

---

### User Story 3 - Cookie session and bearer token work interchangeably (Priority: P3)

All existing browser-based access via SESSION cookie continues to function exactly as before. Additionally, any valid session token may be submitted as a bearer token in the `Authorization` header to access the same protected resources.

**Why this priority**: Backwards compatibility is non-negotiable. Existing users and integrations must not be disrupted.

**Independent Test**: Log in via the browser (SESSION cookie set). Copy the session token via `GET /auth/session-token`. Issue a request with `Authorization: Bearer <session-token>` to a protected endpoint and verify the response matches what the browser sees.

**Acceptance Scenarios**:

1. **Given** a request with a valid SESSION cookie, **When** any protected endpoint is called, **Then** it behaves identically to the pre-feature behaviour.

2. **Given** a request with `Authorization: Bearer <valid-session-token>`, **When** a protected endpoint is called, **Then** the same `UserSession` is resolved and the same data is returned.

3. **Given** both a SESSION cookie and an `Authorization: Bearer` header are present, **When** a request is processed, **Then** the bearer token is checked first; whichever is valid grants access.

---

### User Story 4 - User denies consent (Priority: P2)

On the consent screen, the user clicks "Deny". The client receives a standards-compliant error response and no access token is ever issued.

**Why this priority**: Consent must be meaningful — denial must have an immediate, irreversible effect on that authorization request.

**Independent Test**: Trigger the OAuth flow and click "Deny". Verify the client's `redirect_uri` receives `error=access_denied` and the original `state`. Verify that no token exists for that flow.

**Acceptance Scenarios**:

1. **Given** the user is shown the consent screen, **When** they click "Deny", **Then** the client's `redirect_uri` receives `?error=access_denied&state=<original_state>`.

2. **Given** the user denied consent, **When** any subsequent attempt is made to redeem a code from that flow, **Then** no such code exists to redeem.

---

### Edge Cases

- What happens when an authorization code is used twice? → The second redemption returns an error; codes are single-use and marked as used on first redemption.
- What happens when an expired access token (expired session) is sent? → The server returns `401 Unauthorized`, prompting the client to re-authenticate.
- What happens when an unknown `client_id` is presented to `/oauth2/authorize`? → The server returns a user-facing error page (not a redirect, to prevent open-redirect attacks).
- What happens when the PKCE `code_verifier` does not match the stored `code_challenge`? → The token exchange returns `error=invalid_grant`.
- What happens when the `redirect_uri` in the token request differs from the registered one? → The token exchange returns `error=invalid_grant`.
- What happens when the authorization code has expired (older than 10 minutes)? → The token exchange returns `error=invalid_grant`.
- What happens when the stored pending OAuth authorization entry expires before the user returns from Okta? → The Okta callback returns a user-facing error; the user must restart the flow.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST expose `GET /.well-known/oauth-protected-resource` returning a JSON document per RFC 9728, including the service URL as `resource`, the authorization server base URL in `authorization_servers`, and `["mcp:read"]` as `scopes_supported`.

- **FR-002**: The service MUST expose `GET /.well-known/oauth-authorization-server` returning a JSON document per RFC 8414, including `issuer`, `authorization_endpoint`, `token_endpoint`, `registration_endpoint`, `response_types_supported: ["code"]`, `grant_types_supported: ["authorization_code"]`, and `code_challenge_methods_supported: ["S256"]`.

- **FR-003**: The service MUST support Dynamic Client Registration at `POST /oauth2/register` per RFC 7591. Clients supply at minimum a `redirect_uris` array and an optional `client_name`; the server assigns a `client_id` (UUID). No `client_secret` is issued.

- **FR-004**: The service MUST expose `GET /oauth2/authorize` accepting `client_id`, `redirect_uri`, `response_type=code`, `state`, `scope`, `code_challenge`, and `code_challenge_method=S256`.

- **FR-005**: If the user visiting `/oauth2/authorize` has no valid session, the system MUST store the complete OAuth 2.1 authorization request parameters server-side (keyed by a server-generated Okta `state` value, distinct from the client's `state`) and redirect to the Okta OIDC login flow. The stored entry MUST expire after 10 minutes.

- **FR-006**: After Okta authentication completes (new login or existing session), if a pending OAuth authorization exists for the current Okta state, the user MUST be shown a consent screen — not the main app dashboard.

- **FR-007**: The consent screen MUST display the registered client name (or `client_id` if unnamed), the application name, and the list of requested scopes in plain language. It MUST include "Allow" and "Deny" buttons.

- **FR-008**: If the user approves consent, the system MUST generate a cryptographically random, single-use authorization code with a 10-minute expiry, persist it with the associated `client_id`, `session_token`, `redirect_uri`, `code_challenge`, and `scope`, and redirect to the registered `redirect_uri` with `code` and the client's original `state`.

- **FR-009**: If the user denies consent, the system MUST redirect to the registered `redirect_uri` with `error=access_denied` and the client's original `state`. No code is created.

- **FR-010**: The service MUST expose `POST /oauth2/token` accepting `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, and `code_verifier`. It MUST validate: code exists and is unexpired, code has not been used, `client_id` matches, `redirect_uri` matches, and the S256 hash of `code_verifier` matches the stored `code_challenge`. On success it MUST mark the code as used and return `{"access_token": "<session_token>", "token_type": "Bearer", "expires_in": <seconds_remaining>}`.

- **FR-011**: The issued `access_token` MUST be the existing `UserSession` token UUID — the same value stored in the SESSION cookie. No separate token storage is introduced.

- **FR-012**: The `AbstractProtectedEndpoint.requireSession()` logic MUST be extended to extract and validate a bearer token from the `Authorization: Bearer <token>` HTTP header in addition to the SESSION cookie, using the same `UserSessionEntity` lookup. All authorization rules, expiry checks, and group membership checks MUST apply equally to both credential types.

- **FR-013**: Protected MCP endpoints accessed without a valid credential MUST return `401 Unauthorized` with `WWW-Authenticate: Bearer realm="mcp", resource_metadata="<PRM_URL>"`.

- **FR-014**: Authorization codes MUST be single-use; a second redemption attempt for the same code MUST return `error=invalid_grant`.

- **FR-015**: PKCE with S256 MUST be enforced on the token endpoint; a mismatched `code_verifier` MUST return `error=invalid_grant`.

- **FR-016**: The `redirect_uri` in the token request MUST exactly match the one used in the original authorization request; a mismatch MUST return `error=invalid_grant`.

- **FR-017**: All existing cookie-based session access MUST continue to work without any change in behaviour.

### Key Entities

- **OAuthClient**: A dynamically registered MCP client. Key: `client_id` (UUID). Attributes: `client_name`, `redirect_uri`, `registered_at`. No client secret. (New persistent store)

- **OAuthAuthorizationCode**: A short-lived, single-use authorization code. Key: `code` (random). Attributes: `client_id`, `session_token` (links to UserSession), `redirect_uri`, `code_challenge`, `code_challenge_method`, `scope`, `expires_at`, `used` (boolean). (New persistent store)

- **OAuthPendingAuthorization**: Temporary state preserving OAuth 2.1 authorization request params while the user completes Okta login. Key: Okta-flow `state` UUID. Attributes: `client_id`, `redirect_uri`, `code_challenge`, `scope`, `client_state` (client's original `state`), `expires_at`. (New short-lived store, analogous to existing `OidcPendingLoginEntity`)

- **UserSession** (existing): Unchanged. The session token UUID serves as both the SESSION cookie value and the OAuth 2.1 access token.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An MCP client configured with only the server URL can complete the full authorization flow — discover, register, authorize, consent, token exchange — with fewer than 5 user-facing steps and under 60 seconds of elapsed user interaction time.

- **SC-002**: After authentication, an MCP client using a bearer token accesses the same data and capabilities as a browser user with a SESSION cookie in 100% of tested scenarios.

- **SC-003**: All existing browser-based users experience zero change in behaviour — cookie-authenticated requests continue to work without modification in 100% of tested scenarios.

- **SC-004**: An authorization code cannot be redeemed twice; a second redemption attempt receives an error within 1 second.

- **SC-005**: A bearer token corresponding to an expired or invalidated session receives a `401` response, allowing the MCP client to re-initiate the authorization flow.

- **SC-006**: The `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server` metadata endpoints respond in under 200 ms as they serve near-static content.

- **SC-007**: The consent screen clearly presents the client name and requested scope in plain language such that a non-technical user understands what they are authorizing before clicking Allow or Deny.

## Assumptions

- **A-001**: The access token lifetime matches the existing session lifetime (8 hours). Refresh tokens are out of scope for this iteration.
- **A-002**: The only supported OAuth 2.1 scope is `mcp:read`. Additional fine-grained scopes are out of scope.
- **A-003**: Dynamic Client Registration requires no authentication (open registration), relying on the consent screen as the user-facing gate against unauthorized access.
- **A-004**: Token revocation is out of scope; the existing logout endpoint (`GET /auth/logout`) invalidating the `UserSession` serves as the revocation mechanism for both cookies and bearer tokens.
- **A-005**: The authorization server and MCP resource server are co-located in the same Akka service at the same host and port.
- **A-006**: HTTPS is enforced in production; HTTP on `localhost` is acceptable during development.
- **A-007**: The email domain restriction (`@example.com`) enforced at the Okta OIDC login step applies equally to MCP client flows — only Lightbend employees can authorize MCP clients.
