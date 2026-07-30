# Tasks: OAuth Token Flow for Salesforce MCP

**Input**: Design documents from `/specs/002-oauth-token-flow/`
**Branch**: `002-oauth-token-flow`

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Static resources directory and configuration scaffolding

- [ ] T001 Create `src/main/resources/static-resources/` directory (required by Akka static content serving)
- [ ] T002 Add OAuth config block to `src/main/resources/application.conf` (`salesforce.oauth.base-url`, `client-id`, `client-secret`, `redirect-uri` from env vars)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain record and Key Value Entity — required by all three user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T003 Create `src/main/java/com/example/domain/SalesforceConnection.java` — immutable record with fields `accessToken`, `instanceUrl`, `tokenAcquiredAt`, `pendingOAuthState`, `oauthStateExpiresAt`; static `empty()` factory; domain methods `isConnected()`, `isValidOAuthState(String)`, `withPendingState(String, Instant)`, `withToken(String, String, Instant)`, `disconnected()`
- [ ] T004 Create `src/main/java/com/example/application/SalesforceConnectionEntity.java` — `@Component(id = "salesforce-connection")`, extends `KeyValueEntity<SalesforceConnection>`, singleton ID constant `"default"`, command handlers: `getStatus()` → `ReadOnlyEffect<SalesforceConnection>`, `initiateOAuth(String nonce)` → `Effect<Done>` (stores nonce + 10-min expiry), `storeToken(StoreTokenCommand)` → `Effect<Done>` (validates nonce, persists token, clears nonce; returns `effects().error()` if state invalid/expired), `disconnect()` → `Effect<Done>`; inner record `StoreTokenCommand(String accessToken, String instanceUrl, Instant acquiredAt, String oauthState)`
- [ ] T005 Create `src/test/java/com/example/application/SalesforceConnectionEntityTest.java` — `KeyValueEntityTestKit` unit tests: `initiateOAuth_storesNonce`, `initiateOAuth_overwritesPreviousNonce`, `storeToken_validState_storesTokenAndClearsNonce`, `storeToken_invalidState_returnsError`, `storeToken_expiredState_returnsError`, `disconnect_clearsToken`, `getStatus_whenEmpty_returnsDisconnected`, `getStatus_whenConnected_returnsToken`. Verify: `mvn test`

**Checkpoint**: `mvn test` passes — entity and domain logic solid before any story work begins.

---

## Phase 3: User Story 1 — Connect Salesforce via OAuth (Priority: P1) 🎯 MVP

**Goal**: Admin opens UI, clicks Connect, completes OAuth, token is durably stored, UI shows Connected.

**Independent Test**: Start service, navigate to `http://localhost:9000/`, click "Connect to Salesforce", complete Salesforce login, confirm UI shows "Connected" and `/oauth/status` returns `{"connected":true}`.

- [ ] T006 [P] [US1] Create `src/main/resources/static-resources/index.html` — single-page admin UI; on load calls `GET /oauth/status` and renders "Connected (since …)" or "Not connected"; "Connect to Salesforce" button navigates to `/oauth/connect`; auto-refreshes status after OAuth redirect lands back on `/`
- [ ] T007 [P] [US1] Create `src/main/java/com/example/api/OAuthEndpoint.java` — `@HttpEndpoint("/")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))`, injects `ComponentClient`; implements:
  - `GET /` → `HttpResponses.staticResource("index.html")`
  - `GET /oauth/status` → calls `SalesforceConnectionEntity.getStatus()`, returns `ConnectionStatus(boolean connected, Instant tokenAcquiredAt)` as JSON
  - `GET /oauth/connect` → generates UUID nonce, calls `SalesforceConnectionEntity.initiateOAuth(nonce)`, builds Salesforce authorize URL with `response_type=code`, `client_id`, `redirect_uri`, `state=nonce`, `scope=api`, returns `302` redirect
  - `GET /oauth/callback` (query params `code`, `state`) → validates params present (400 if missing), reads entity state to validate nonce via `isValidOAuthState` (400 if invalid/expired), exchanges code via `java.net.http.HttpClient` POST to `{baseUrl}/services/oauth2/token` with form body (`grant_type=authorization_code`, `code`, `client_id`, `client_secret`, `redirect_uri`) (502 if exchange fails), calls `SalesforceConnectionEntity.storeToken(...)`, returns `302` to `/`
  - Inner record `ConnectionStatus(boolean connected, Instant tokenAcquiredAt)`
- [ ] T008 [US1] Create `src/test/java/com/example/api/OAuthEndpointIntegrationTest.java` — extends `TestKitSupport`; tests: `getStatus_whenNoToken_returnsDisconnected` (GET `/oauth/status` → `{connected:false}`), `connect_redirectsToSalesforce` (GET `/oauth/connect` → 302, Location contains `salesforce.com/services/oauth2/authorize` and `state=` param), `callback_missingCode_returns400` (GET `/oauth/callback?state=x` → 400), `callback_invalidState_returns400` (GET `/oauth/callback?code=x&state=unknown` → 400). Verify: `mvn verify`

**Checkpoint**: US1 fully functional — admin can connect Salesforce through the browser UI.

---

## Phase 4: User Story 2 — Query Salesforce Using the Stored Token (Priority: P2)

**Goal**: Queries to `POST /salesforce/query` automatically use the stored token; clear error when not connected.

**Independent Test**: With token pre-seeded in entity, `POST /salesforce/query` with `{"query":"…"}` returns an answer; without token, returns 503 with setup message.

- [ ] T009 [US2] Modify `src/main/java/com/example/application/SalesforceAgent.java` — change command handler from `query(String message)` to `query(QueryRequest request)`; add inner record `QueryRequest(String message, String bearerToken)`; remove env-var token reading from constructor; use `request.bearerToken()` in `RemoteMcpTools.fromServer(...).addClientHeader(Authorization.oauth2(request.bearerToken()))`. Verify: `mvn compile`
- [ ] T010 [US2] Modify `src/main/java/com/example/api/SalesforceEndpoint.java` — in `query()` handler: call `componentClient.forKeyValueEntity(SalesforceConnectionEntity.ENTITY_ID).method(SalesforceConnectionEntity::getStatus).invoke()` first; if `!connection.isConnected()` return `HttpResponses.serviceUnavailable("Salesforce is not connected. Visit / to complete the OAuth setup.")` (503); otherwise invoke `SalesforceAgent.query(new SalesforceAgent.QueryRequest(request.query(), connection.accessToken()))`. Verify: `mvn compile`
- [ ] T011 [US2] Modify `src/test/java/com/example/application/SalesforceAgentTest.java` — update all invocations to pass `new SalesforceAgent.QueryRequest(message, "test-token")` instead of bare string. Verify: `mvn test`
- [ ] T012 [US2] Modify `src/test/java/com/example/api/SalesforceEndpointIntegrationTest.java` — add test `query_whenNotConnected_returns503` (no token seeded → POST `/salesforce/query` → 503); update existing 200 test to pre-seed token into `SalesforceConnectionEntity` via `componentClient` before invoking query. Verify: `mvn verify`

**Checkpoint**: US2 functional — queries use stored token transparently; 503 returned when not connected.

---

## Phase 5: User Story 3 — View Connection Status and Reconnect (Priority: P3)

**Goal**: UI reflects accurate connection state and allows re-initiation of the OAuth flow to replace an expired token.

**Independent Test**: With a stored token, open UI → see "Connected (since …)". Clear token via `disconnect()`, refresh UI → see "Not connected". Click Connect, complete OAuth → status updates to Connected.

- [ ] T013 [US3] Enhance `src/main/resources/static-resources/index.html` — display `tokenAcquiredAt` formatted as human-readable date/time next to Connected status; add "Reconnect" button (same href as Connect, always visible when connected); ensure page auto-refreshes status on load without caching (`Cache-Control: no-store` or JS fetch on every load)
- [ ] T014 [US3] Add `GET /oauth/disconnect` to `src/main/java/com/example/api/OAuthEndpoint.java` — calls `SalesforceConnectionEntity.disconnect()`, redirects to `/` (allows admin to manually disconnect from UI for testing/reset)
- [ ] T015 [US3] Add disconnect button to `src/main/resources/static-resources/index.html` — shown only when connected; navigates to `/oauth/disconnect`

**Checkpoint**: US3 functional — full connect/status/reconnect/disconnect cycle works via browser.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T016 [P] Update `README.md` — add new env vars table (`SALESFORCE_CLIENT_ID`, `SALESFORCE_CLIENT_SECRET`, `SALESFORCE_REDIRECT_URI`, `SALESFORCE_OAUTH_BASE_URL`); remove `SALESFORCE_MCP_TOKEN`; add "Setup OAuth" section with step-by-step instructions (create Salesforce Connected App, set callback URL, start service, open UI); add curl example for `/oauth/status`
- [ ] T017 [P] Run `mvn verify` — full build + all tests green; fix any remaining failures

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — **blocks all user story phases**
- **Phase 3 (US1)**: Depends on Phase 2
- **Phase 4 (US2)**: Depends on Phase 2; integrates SalesforceAgent + SalesforceEndpoint changes
- **Phase 5 (US3)**: Depends on Phase 3 (UI exists) and Phase 2 (entity exists)
- **Phase 6 (Polish)**: Depends on all story phases complete

### User Story Dependencies

- **US1**: Depends only on Foundational (Phase 2) — fully independent
- **US2**: Depends on Foundational (Phase 2) — modifies existing Agent + Endpoint files; independent of US1
- **US3**: Depends on US1 (extends the UI) and Foundational (entity `disconnect()` already exists from T004)

### Parallel Opportunities Within Phases

- **Phase 2**: T003 (domain record) and T005 setup can begin in parallel; T004 (entity) depends on T003
- **Phase 3**: T006 (HTML) and T007 (OAuthEndpoint) can be written in parallel — different files
- **Phase 6**: T016 and T017 are independent

---

## Parallel Example: Phase 3 (US1)

```
# Can start simultaneously:
Task T006: "Create index.html admin UI in src/main/resources/static-resources/index.html"
Task T007: "Create OAuthEndpoint.java in src/main/java/com/example/api/OAuthEndpoint.java"

# Then after both T006 + T007:
Task T008: "Create OAuthEndpointIntegrationTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (T001–T002)
2. Phase 2: Foundational (T003–T005) — `mvn test` must pass
3. Phase 3: US1 (T006–T008) — `mvn verify` must pass
4. **STOP and VALIDATE**: Open browser, connect Salesforce, confirm "Connected"
5. Deploy/demo if ready

### Incremental Delivery

1. Phases 1–2 → Foundation (entity + domain tested)
2. Phase 3 (US1) → Admin can connect Salesforce via UI ← **MVP**
3. Phase 4 (US2) → Queries use stored token automatically
4. Phase 5 (US3) → Reconnect and disconnect from UI
5. Phase 6 → Polish and docs

---

## Task Summary

| Phase | Tasks | Story | Parallelizable |
|---|---|---|---|
| Setup | T001–T002 | — | T001, T002 in parallel |
| Foundational | T003–T005 | — | T003 ‖ T005 setup; T004 after T003 |
| US1 (P1) MVP | T006–T008 | US1 | T006 ‖ T007 |
| US2 (P2) | T009–T012 | US2 | T011 ‖ T012 after T009+T010 |
| US3 (P3) | T013–T015 | US3 | T013 ‖ T014 |
| Polish | T016–T017 | — | T016 ‖ T017 |

**Total**: 17 tasks across 6 phases
