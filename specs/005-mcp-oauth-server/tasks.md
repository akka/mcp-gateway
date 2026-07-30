# Tasks: MCP OAuth 2.1 Authorization Server

**Input**: Design documents from `/specs/005-mcp-oauth-server/`
**Prerequisites**: plan.md ✓, spec.md ✓, data-model.md ✓, contracts/oauth-endpoints.md ✓, research.md ✓

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no shared dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)

---

## Phase 1: Setup

**Purpose**: Verify baseline before changes

- [X] T001 Verify project compiles cleanly: `mvn compile` — fix any pre-existing issues before adding new code

---

## Phase 2: Foundational — Domain Records + Key Value Entities

**Purpose**: Shared entities required by all user stories. Must complete before any user story work.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Domain records

- [X] T002 [P] Create `src/main/java/com/example/domain/OAuthClient.java` — record with fields `clientId`, `clientName`, `redirectUri`, `registeredAt (Instant)`; static `empty()`, `isEmpty()` methods following `OidcPendingLogin` pattern
- [X] T003 [P] Create `src/main/java/com/example/domain/OAuthAuthorizationCode.java` — record with fields `code`, `clientId`, `sessionToken`, `redirectUri`, `codeChallenge`, `codeChallengeMethod`, `scope`, `expiresAt (Instant)`, `used (boolean)`; static `empty()`, `isEmpty()`, `isExpired()`, `markUsed()` methods
- [X] T004 [P] Create `src/main/java/com/example/domain/OAuthPendingAuthorization.java` — record with fields `clientId`, `redirectUri`, `codeChallenge`, `codeChallengeMethod`, `scope`, `clientState`, `expiresAt (Instant)`; static `empty()`, `isEmpty()`, `isExpired()` methods following `OidcPendingLogin` pattern

### Key Value Entities

- [X] T005 Create `src/main/java/com/example/application/OAuthClientEntity.java` — KVE keyed by `client_id` UUID; `@Component(id = "oauth-client")`; commands: `register(RegisterCommand)` → `Effect<Done>`, `get()` → `ReadOnlyEffect<OAuthClient>`; inner record `RegisterCommand(String clientId, String clientName, String redirectUri, Instant registeredAt)`
- [X] T006 Create `src/main/java/com/example/application/OAuthAuthorizationCodeEntity.java` — KVE keyed by auth code UUID; `@Component(id = "oauth-auth-code")`; commands: `create(CreateCommand)` → `Effect<Done>`, `get()` → `ReadOnlyEffect<OAuthAuthorizationCode>`, `markUsed()` → `Effect<Done>`; inner record `CreateCommand(...)` with all code fields
- [X] T007 Create `src/main/java/com/example/application/OAuthPendingAuthorizationEntity.java` — KVE keyed by Okta state UUID; `@Component(id = "oauth-pending-auth")`; commands: `create(CreateCommand)` → `Effect<Done>`, `get()` → `ReadOnlyEffect<OAuthPendingAuthorization>`, `delete()` → `Effect<Done>`; inner record `CreateCommand(String clientId, String redirectUri, String codeChallenge, String codeChallengeMethod, String scope, String clientState, Instant expiresAt)`

### Unit tests

- [ ] T008 [P] Create `src/test/java/com/example/application/OAuthClientEntityTest.java` — test `register` creates correct state; test `get` on empty entity returns `isEmpty() == true`
- [ ] T009 [P] Create `src/test/java/com/example/application/OAuthAuthorizationCodeEntityTest.java` — test `create` sets all fields and `used=false`; test `markUsed` flips `used=true`; test domain `isExpired()` logic
- [ ] T010 [P] Create `src/test/java/com/example/application/OAuthPendingAuthorizationEntityTest.java` — test `create` sets all fields; test `delete` resets to empty; test `isExpired()` logic
- [ ] T011 Run `mvn test` — confirm all unit tests pass before proceeding

---

## Phase 3: User Story 1 — MCP Client Authenticates End-to-End (Priority: P1) 🎯 MVP

**Goal**: An already-authenticated user (existing SESSION cookie) can complete the full OAuth flow: discover → register → consent → token exchange → use bearer token.

**Independent Test**: With an existing browser session, configure an MCP client with the server URL only. Verify: `/.well-known/oauth-protected-resource` and `/.well-known/oauth-authorization-server` return correct JSON; `POST /oauth2/register` returns a `client_id`; `GET /oauth2/authorize` (with session) redirects to `/oauth2/consent`; consent page shows client name; `POST /oauth2/consent` with `action=allow` redirects with `code`; `POST /oauth2/token` returns `access_token`; request with `Authorization: Bearer <token>` is accepted by a protected endpoint.

### Implementation

- [X] T012 [US1] Create `src/main/java/com/example/api/McpOAuthMetadataEndpoint.java` (implemented as updated OAuthMetadataEndpoint.java) — `@HttpEndpoint("/.well-known")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`; reads `MCP_BASE_URL` env var (default `http://localhost:9000`) in constructor; `@Get("/oauth-protected-resource")` returns `ProtectedResourceMetadata` record; `@Get("/oauth-authorization-server")` returns `AuthorizationServerMetadata` record; both inner records defined in this class

- [X] T013 [US1] Create `src/main/java/com/example/api/McpOAuthEndpoint.java` — `@HttpEndpoint("/oauth2")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`, extends `AbstractProtectedEndpoint`; reads `MCP_BASE_URL`, Okta env vars; defines inner records `RegisterRequest(List<String> redirect_uris, String client_name)`, `RegisterResponse`, `TokenErrorResponse(String error, String error_description)`, `TokenResponse(String access_token, String token_type, long expires_in)`; include private static `pkceChallenge(String verifier)` using SHA-256 + Base64URL no-padding (same algorithm as `AuthEndpoint.generateCodeChallenge`) and `parseFormBody(String body)` returning `Map<String, String>`

- [X] T014 [US1] Implement `POST /oauth2/register` in `McpOAuthEndpoint` — validates `redirect_uris` non-empty; generates UUID `client_id`; stores via `OAuthClientEntity.register`; returns `RegisterResponse` with 201 Created; returns 400 if `redirect_uris` missing or empty

- [X] T015 [US1] Implement `GET /oauth2/authorize` in `McpOAuthEndpoint` — reads query params: `client_id`, `redirect_uri`, `response_type`, `state`, `code_challenge`, `code_challenge_method`, `scope`; validates `response_type=code`, `code_challenge_method=S256`; looks up `OAuthClientEntity` — returns 400 error page (not redirect) if unknown or `redirect_uri` mismatch; if `requireSession() != null` → generates `oauth_state` UUID, stores `OAuthPendingAuthorizationEntity` (keyed by `oauth_state`), redirects to `/oauth2/consent?oauth_state=<oauth_state>`; if no session → stores `OAuthPendingAuthorizationEntity` (keyed by `oauth_state`), redirects to Okta with `state=oauth_state` (reuses existing Okta env vars and PKCE flow from `AuthEndpoint.start()`)

- [X] T016 [US1] Implement `GET /oauth2/consent` in `McpOAuthEndpoint` — requires session (redirect to `/login` if none); reads `oauth_state` query param; looks up `OAuthPendingAuthorizationEntity` — returns 400 if missing or expired; looks up `OAuthClientEntity` for display name; returns inline HTML response (`text/html`) showing app name, client name (or `client_id`), scope in plain language, and a form with hidden `oauth_state` field and `Allow` / `Deny` submit buttons; style consistent with existing pages

- [X] T017 [US1] Implement `POST /oauth2/consent` in `McpOAuthEndpoint` — requires session; parses form body for `oauth_state` and `action`; looks up and deletes `OAuthPendingAuthorizationEntity`; on `action=allow`: generates random UUID auth `code`, stores `OAuthAuthorizationCodeEntity` (10-minute expiry, `sessionToken = getSessionToken()`), redirects to `redirect_uri?code=<code>&state=<clientState>`; on `action=deny`: redirects to `redirect_uri?error=access_denied&state=<clientState>`; returns 400 on unknown/expired `oauth_state`

- [X] T018 [US1] Implement `POST /oauth2/token` in `McpOAuthEndpoint` — parses form body; validates `grant_type=authorization_code`; looks up `OAuthAuthorizationCodeEntity`; validates: exists and not empty, not expired, not used, `client_id` matches, `redirect_uri` matches, `pkceChallenge(code_verifier)` equals stored `code_challenge`; marks code as used; looks up `UserSessionEntity` with `code.sessionToken()` to compute `expires_in`; returns `TokenResponse`; returns RFC 6749 JSON error on any validation failure

- [X] T019 [US1] Run `mvn compile` — verify all new files compile correctly

- [ ] T020 [US1] Create `src/test/java/com/example/api/McpOAuthEndpointIntegrationTest.java` — extends `TestKitSupport`; test `GET /.well-known/oauth-protected-resource` returns correct JSON fields; test `POST /oauth2/register` returns `client_id`; test `GET /oauth2/token` with mismatched PKCE returns `invalid_grant`; test double-redemption of a code returns `invalid_grant`

- [ ] T021 [US1] Run `mvn verify` — confirm integration tests pass

**Checkpoint**: Metadata endpoints and the full consent+token flow work for an already-logged-in user.

---

## Phase 4: User Story 2 — Unauthenticated User Routed via Okta (Priority: P2)

**Goal**: When an MCP client triggers `/oauth2/authorize` and the user has no session, they are redirected through Okta login and land on the consent screen (not the dashboard) after authenticating.

**Independent Test**: Clear all cookies. Hit `/oauth2/authorize` with valid params — verify redirect to Okta. After Okta callback, verify redirect to `/oauth2/consent` (not to `/` or dashboard).

### Implementation

- [X] T022 [US2] Modify `AuthEndpoint.callback()` in `src/main/java/com/example/api/AuthEndpoint.java` — after creating the `UserSession` (before the final redirect), look up `OAuthPendingAuthorizationEntity` by the Okta `state` param; if not empty and not expired → redirect to `/oauth2/consent?oauth_state=<state>` with `Set-Cookie: SESSION=...`; otherwise → existing redirect to `/?flash=login`; add `OAuthPendingAuthorizationEntity` injection via `ComponentClient` (already available as field)

- [X] T023 [US2] Run `mvn compile` — verify callback modification compiles

**Checkpoint**: Unauthenticated OAuth flow routes to consent screen after Okta login.

---

## Phase 5: User Story 3 — Bearer Token Interchangeable with Cookie (Priority: P3)

**Goal**: Any valid session token sent as `Authorization: Bearer <token>` grants access to protected resources identically to the SESSION cookie. MCP endpoints return 401+WWW-Authenticate when no valid credential is present.

**Independent Test**: Log in via browser. Retrieve session token from `GET /auth/session-token`. Make a request to `GET /auth/me` with `Authorization: Bearer <token>` — verify same response as cookie-authenticated request. Request to a protected MCP endpoint with no credentials returns 401 with `WWW-Authenticate` header.

### Implementation

- [X] T024 [US3] Modify `getSessionToken()` in `src/main/java/com/example/api/AbstractProtectedEndpoint.java` — check `Authorization` header first: if value starts with `Bearer ` extract and return the token; otherwise fall back to existing SESSION cookie extraction; no other changes to the class

- [X] T025 [US3] Add `unauthorizedForMcp()` method to `src/main/java/com/example/api/AbstractProtectedEndpoint.java` — returns 401 response with `WWW-Authenticate: Bearer realm="mcp", resource_metadata="<MCP_BASE_URL>/.well-known/oauth-protected-resource"` header; reads `MCP_BASE_URL` env var (same default as McpOAuthMetadataEndpoint)

- [X] T026 [US3] Update MCP-facing endpoints to return 401+WWW-Authenticate when unauthenticated: in `src/main/java/com/example/api/SalesforceEndpoint.java`, `ZohoDeskEndpoint.java`, and `GenericMcpEndpoint.java` replace `redirectToLogin()` calls on unauthenticated MCP query routes with `unauthorizedForMcp()`; browser-only routes (dashboard, OAuth pages) retain `redirectToLogin()`

- [X] T027 [US3] Run `mvn compile` — verify all modifications compile

- [ ] T028 [US3] Add bearer token integration test to `src/test/java/com/example/api/McpOAuthEndpointIntegrationTest.java` — test that `GET /auth/me` with `Authorization: Bearer <valid-session-token>` returns 200; test that request with no credentials to an MCP endpoint returns 401 with `WWW-Authenticate` header containing `resource_metadata`

- [ ] T029 [US3] Run `mvn verify`

**Checkpoint**: Bearer token and cookie authentication are interchangeable. MCP endpoints return proper 401 challenge.

---

## Phase 6: User Story 4 — User Denies Consent (Priority: P2)

**Goal**: Clicking Deny on the consent screen redirects the client to `redirect_uri?error=access_denied&state=<state>` with no code issued.

**Independent Test**: Trigger OAuth flow, reach consent screen, click Deny — verify `error=access_denied` in the redirect URL and no authorization code exists.

### Implementation

- [ ] T030 [US4] Add deny-path integration test to `src/test/java/com/example/api/McpOAuthEndpointIntegrationTest.java` — simulate `POST /oauth2/consent` with `action=deny`; verify redirect URL contains `error=access_denied` and the client's `state`; verify no `OAuthAuthorizationCodeEntity` was created

- [ ] T031 [US4] Run `mvn verify`

**Checkpoint**: All four user stories are independently functional and tested.

---

## Phase 7: Polish

- [ ] T032 Update `src/main/resources/application.conf` — add `mcp.base-url = ${?MCP_BASE_URL}` config key with inline comment documenting the default value
- [ ] T033 Update `README.md` — add `MCP_BASE_URL` to the configuration table; add a "MCP Client Connection" section explaining that MCP clients connect with only the server URL and noting the OAuth 2.1 flow
- [ ] T034 Run `mvn verify` — final clean build confirming all phases pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: Start immediately
- **Phase 2 (Foundational)**: After Phase 1 — blocks all user stories
- **Phase 3 (US1)**: After Phase 2 — no dependency on US2/US3/US4
- **Phase 4 (US2)**: After Phase 2 — no dependency on US1 except uses same entities; can run in parallel with Phase 3
- **Phase 5 (US3)**: After Phase 2 — modifies AbstractProtectedEndpoint which all endpoints inherit; can run in parallel with Phase 3/4
- **Phase 6 (US4)**: After Phase 3 (deny case is already coded in T017; US4 just adds the integration test)
- **Phase 7 (Polish)**: After all user story phases complete

### Within Phase Dependencies

- T002, T003, T004 [P] — parallel (different files)
- T005 depends on T002; T006 depends on T003; T007 depends on T004
- T008, T009, T010 [P] — parallel entity tests (different files)
- T012, T013 [P] within Phase 3 — new files, no shared dependency
- T014, T015, T016, T017, T018 — sequential within McpOAuthEndpoint (same file)

---

## Parallel Execution Examples

### Phase 2 Parallel Opportunities
```
# Parallel: create all domain records
T002: OAuthClient.java
T003: OAuthAuthorizationCode.java
T004: OAuthPendingAuthorization.java

# Then parallel: create all entities
T005: OAuthClientEntity.java
T006: OAuthAuthorizationCodeEntity.java
T007: OAuthPendingAuthorizationEntity.java

# Then parallel: write unit tests
T008: OAuthClientEntityTest.java
T009: OAuthAuthorizationCodeEntityTest.java
T010: OAuthPendingAuthorizationEntityTest.java
```

### Phase 3 + Phase 5 Parallel Start
```
# Once Phase 2 is done, these can start in parallel:
Phase 3: Build McpOAuthMetadataEndpoint + McpOAuthEndpoint (T012–T021)
Phase 5: Modify AbstractProtectedEndpoint (T024–T029)
```

---

## Implementation Strategy

### MVP (Phase 1 + 2 + 3 only)

1. Complete Phase 1 + 2 — foundational entities
2. Complete Phase 3 — full OAuth flow for authenticated users
3. **Validate**: Register a client, visit `/oauth2/authorize` with an active session, approve consent, exchange code for token, use bearer token
4. Deploy or demo if ready — this is a complete, usable OAuth 2.1 flow

### Incremental Delivery

1. Phase 1+2 → Foundation
2. Phase 3 → MVP: OAuth works for logged-in users
3. Phase 4 → Adds unauthenticated entry path (MCP clients auto-trigger Okta login)
4. Phase 5 → Bearer tokens accepted everywhere; MCP endpoints return proper 401
5. Phase 6 → Deny consent test coverage
6. Phase 7 → Documentation + final verification
