---

description: "Task list for Seamless.AI MCP Integration"
---

# Tasks: Seamless.AI MCP Integration

**Input**: Design documents from `/specs/006-seamless-mcp-integration/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The constitution (Principle III) requires tests with every behavioural change.

**Organization**: Grouped by user story. Stories are ordered by priority: US1 (P1), US2 (P2), US4 (P2), US3 (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4 from spec.md
- All paths are relative to the repository root. `G` = `src/main/java/io/akka/mcp/gateway`, `T` = `src/test/java/io/akka/mcp/gateway`.
- Reference analogs for every new class: `ReoConnection`, `ReoConnectionEntity`, `ReoOAuthEndpoint`, `ReoMcpClient` (copy the shape, rename, adjust as noted).
- Per CLAUDE.md, implementation proceeds one component at a time with user approval between steps.

---

## Phase 1: Setup

**Purpose**: Configuration surface shared by all stories

- [X] T001 Add a `seamless { mcp-url, redirect-uri, okta-app-id }` block to `src/main/resources/application.conf` (defaults: `mcp-url = "https://mcp.seamless.ai/mcp"` overridable by `SEAMLESS_MCP_URL`; `redirect-uri` by `SEAMLESS_REDIRECT_URI`; `okta-app-id` by `SEAMLESS_OKTA_APP_ID`; empty string defaults for the last two), following the `reo` block layout

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain model every story depends on

**⚠️ CRITICAL**: Complete before any user story

- [X] T002 Create `G/domain/SeamlessConnection.java` record per data-model.md (fields accessToken, refreshToken, tokenExpiresAt, pendingState, codeVerifier, clientId, tokenEndpoint, pendingExpiresAt; methods `empty`, `isConnected`, `isValidPendingState`, `isTokenExpired`, `withPending`, `withToken`, `disconnected`), copied from `ReoConnection`; no Akka imports
- [X] T003 Verify `G/domain/SeamlessConnection.java` compiles with `mvn compile` (project `pom.xml`)

**Checkpoint**: Domain compiles

---

## Phase 3: User Story 1 - Connect Seamless.AI and use search, research and account tools (Priority: P1) 🎯 MVP

**Goal**: A user connects Seamless.AI from the dashboard and their assistant can list and call all Seamless tools as themselves; unassigned users are refused.

**Independent Test**: Connect a test user, run `tools/list` (expect `Seamless_*` tools), call `Seamless_search_contacts` and `Seamless_get_credits`; a user without the Okta app gets nothing/refusal.

### Tests for User Story 1

- [X] T004 [P] [US1] Create `T/application/SeamlessConnectionEntityTest.java` using `KeyValueEntityTestKit` (model on `ReoConnectionEntityTest`): empty status disconnected; `initiatePkceOAuth` stores pending state; `storeToken` with valid state stores token and clears pending; invalid/expired state rejected; `getAccessToken` when not connected errors with a "connect" message; expired token with no refresh token errors with a "reconnect" message; `disconnect` clears state
- [X] T005 [P] [US1] Create `T/application/SeamlessMcpClientTest.java` covering: `getMcpId()` is `seamless`; `getMcpName()` is `Seamless.AI`; `canHandle` true only for names starting `Seamless_`; `isConnected` false when mcp-url empty; `getRequiredOktaAppId` returns the configured id
- [X] T006 [P] [US1] Create `T/api/SeamlessOAuthEndpointTest.java` asserting `SeamlessOAuthEndpoint` provider label, configured MCP URL/redirect URI, scope `mcp.all` and `refresh_token` grant; that Reo keeps the default (no scope, authorization_code only); and that discovery finds `resource_metadata` in the exact challenge Seamless returns (verified live 2026-09-25)

### Implementation for User Story 1

- [X] T007 [US1] Create `G/application/SeamlessConnectionEntity.java` (`@Component(id = "seamless-connection")`, `KeyValueEntity<SeamlessConnection>`) with `getStatus`, `getAccessToken` (refresh via `grant_type=refresh_token` to the stored `tokenEndpoint` with the stored `clientId`, no client secret), `initiatePkceOAuth(InitiateCommand)`, `storeToken(StoreTokenCommand)`, `disconnect`, copying `ReoConnectionEntity`; run T004 with `mvn test`
- [X] T008 [US1] Create `G/application/SeamlessMcpClient.java` implementing `RemoteMcpClient` (MCP_ID `seamless`, name `Seamless.AI`, tool prefix `Seamless_`): `listTools` prefixes names and forwards annotations plus `readOnlyHint`/`hasBodyParam` into `McpConfig.ToolMeta`; `callTool` strips the prefix and executes upstream with `Authorization: Bearer <user token>` via `StreamableHttpMcpTransport` (init timeout 15 s, tool timeout 30 s); token fetched from `SeamlessConnectionEntity::getAccessToken`; `howTo` returns connect instructions (workflow recipes added in US3). Base it on `ReoMcpClient`/`HubspotMcpClient`
- [X] T009 [US1] Create `G/api/SeamlessOAuthEndpoint.java` (`@HttpEndpoint("/seamless/oauth")`, `@Acl` INTERNET) extending `AbstractDcrOAuthEndpoint`, provider label `Seamless.AI`, scope `mcp.all`, grants `authorization_code` + `refresh_token`, wired to `SeamlessConnectionEntity` and `SeamlessMcpClient`; adds optional `getScope()`/`getGrantTypes()` hooks (defaults unchanged) to `G/api/AbstractDcrOAuthEndpoint.java`
- [X] T010 [US1] Register `new SeamlessMcpClient(componentClient, config.getString("seamless.mcp-url"), config.getString("seamless.okta-app-id"))` in the `serviceClients` list in `G/api/AkkaMcpGateway.java` (after `HubspotMcpClient`)
- [X] T011 [P] [US1] Add a Seamless.AI card (`data-mcp-id="seamless"`, ids prefixed `seamless-`, endpoints under `/seamless/oauth`) to `src/main/resources/static-resources/index.html`, cloned from the Reo card and any per-system JS lists in that file
- [X] T012 [US1] Create `T/api/SeamlessOktaAppGatingIntegrationTest.java` (sibling of the Okta gating test, which is Okta-specific): user without the Seamless app does not see `Seamless_*` tools and `tools/call` is refused with "No MCP client can handle tool"; user with the app sees the cached tools; user with the app but no connection is told `Seamless.AI is not connected` with the `howto_connect_seamless` hint
- [X] T013 [US1] Verify US1 by running `mvn compile`, `mvn test`, then `mvn verify` against `pom.xml` (with `-Dmaven.gitcommitid.skip=true` in a worktree); Seamless tests in `T/application/SeamlessConnectionEntityTest.java`, `T/application/SeamlessMcpClientTest.java`, `T/api/SeamlessOAuthEndpointTest.java` and `T/api/SeamlessOktaAppGatingIntegrationTest.java` pass. Note: 9 pre-existing failures in `AuthEndpointIntegrationTest` (5) and `OAuthEndpointIntegrationTest` (4) also fail on a clean HEAD (expect "302" but the SDK message now reads "response content type is none/none"); not caused by this feature

**Checkpoint**: US1 works and is testable alone (MVP). Manual smoke test: quickstart.md steps 1–4.

---

## Phase 4: User Story 2 - Outreach: campaigns, templates, email, calls and tasks (Priority: P2)

**Goal**: Outreach tools work and reference data (`seamless://…` resources) is readable before writes; licence/enablement errors are clear.

**Independent Test**: With a licensed user, read `seamless://templates/variables` and `seamless://email-accounts` via `Seamless_read_resource`, then create a template, campaign, draft and call log; for an unlicensed user, the upstream licence error reaches the user unchanged.

### Tests for User Story 2

- [X] T014 [P] [US2] Extend `T/application/SeamlessMcpClientTest.java`: `Seamless_read_resource` rejects a `uri` not starting with `seamless://` with `isError=true` and no upstream call; `canHandle("Seamless_read_resource")` is true; the synthetic tool spec has `readOnlyHint=true` and an `inputSchema` requiring `uri`

### Implementation for User Story 2

- [X] T015 [US2] In `G/application/SeamlessMcpClient.java`, append the synthetic tool `Seamless_read_resource` (see contracts/seamless-tools.md) to `listTools` results with `ToolMeta` `readOnlyHint=true`; in `callTool` route it to `McpClient.readResource(uri)` after validating the `seamless://` prefix, returning the resource text (join contents) as the result
- [X] T016 [US2] In `G/application/SeamlessMcpClient.java`, make upstream failures surface the upstream message text in `ToolCallResult` (`isError=true`) rather than a generic error, so "MCP Server access is not enabled" and licence/credit errors reach the user (FR-013); keep the exception path returning a plain "Seamless.AI is temporarily unavailable" message for connection/timeouts
- [X] T017 [P] [US2] Add unit tests in `T/application/SeamlessMcpClientTest.java` for the error mapping in T016 (upstream error result text preserved; MCP protocol error such as "MCP Server access is not enabled" shown with its upstream message; timeout and connection failure map to "temporarily unavailable"; missing connection propagates), using the package-private `SeamlessMcpClient(tokenFetcher, clientFactory, mcpUrl, oktaAppId)` seam with a fake `McpClient`
- [X] T018 [US2] Verify with `mvn test` that `T/application/SeamlessMcpClientTest.java` passes

**Checkpoint**: US2 usable with US1; can be validated independently against a licensed test account (quickstart step 3–4 plus outreach prompts).

---

## Phase 5: User Story 4 - Safe, audited use with read/write/destructive controls (Priority: P2)

**Goal**: Permissions and audit behave for Seamless exactly as for other systems, with deletions identifiable.

**Independent Test**: Reader-only user can search but not send; writer can delete a test list and the deletion is identifiable in history.

### Tests for User Story 4

- [ ] T019 [P] [US4] Extend `T/api/AkkaMcpGatewayIntegrationTest.java` (or add `T/api/SeamlessRiskIntegrationTest.java`): seed registry `seamless` tool metadata with a read tool (`readOnlyHint=true`), a write tool (`false`) and a tool with no hint; assert reader-only user may call the first and is refused the other two; assert a writer may call all; assert each call is recorded in the interaction history
- [ ] T020 [P] [US4] Add to `T/application/SeamlessMcpClientTest.java`: `listTools` conversion passes `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, and `title` annotations through unchanged (assert via the annotation-mapping code path)

### Implementation for User Story 4

- [ ] T021 [US4] Read `G/api/AkkaMcpGateway.java` and `G/domain/McpInteraction*.java` to decide how deletions are made identifiable (FR-010). If the existing record already stores the tool name (e.g. `Seamless_delete_list`), document that in `specs/006-seamless-mcp-integration/research.md` (R4) and add an assertion in T019; if not, extend the interaction record minimally with a `destructive` flag derived from the forwarded `destructiveHint`, update `McpInteractionEntity`/views and their tests, and surface it in `src/main/resources/static-resources/interactions.html`. Ask the user which route before changing the interaction model
- [ ] T022 [US4] Verify with `mvn test` and `mvn verify` that `T/api/SeamlessRiskIntegrationTest.java` (or the extended `T/api/AkkaMcpGatewayIntegrationTest.java`) passes

**Checkpoint**: US4 complete; permission and audit behaviour proven for Seamless.

---

## Phase 6: User Story 3 - Reference workflows work end to end (Priority: P3)

**Goal**: The four documented workflows are available as ready-to-follow recipes, including the Slack fallback.

**Independent Test**: Call the how-to tool for Seamless and confirm all four workflows are described with the correct tool sequence; run one workflow end to end on a test account (quickstart "Workflows to try").

### Tests for User Story 3

- [ ] T023 [P] [US3] Extend `T/application/HowToMcpClientTest.java` (and `T/application/SeamlessMcpClientTest.java`) to assert the Seamless how-to markdown mentions the four workflows by name (prospect to meeting, bulk enrich and campaign, daily activity digest, job change trigger), the `Seamless_read_resource` resource-read steps, polling after `Seamless_research_*`, and the Slack-not-connected fallback

### Implementation for User Story 3

- [ ] T024 [US3] Re-read `https://docs.seamless.ai/mcp/workflows/prospect-to-meeting.md`, `bulk-enrich-and-campaign.md`, `daily-activity-digest.md` and `job-change-trigger.md`, then extend `howTo()` in `G/application/SeamlessMcpClient.java` with one section per workflow: ordered tool names (with `Seamless_` prefix), resources to read first (`seamless://credits`, `seamless://email-accounts`, `seamless://templates/variables`), polling instruction after research, resolving saved contact ids, and that digest posts to Slack only if Slack tools are available, else return the summary in the conversation
- [ ] T025 [US3] Verify with `mvn test` that `T/application/HowToMcpClientTest.java` and `T/application/SeamlessMcpClientTest.java` pass

**Checkpoint**: All four stories work independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T026 [P] Add a **Seamless.AI** section to `README.md` under "Supported systems" (`SEAMLESS_MCP_URL` optional default, `SEAMLESS_REDIRECT_URI`, `SEAMLESS_OKTA_APP_ID`), add Seamless.AI to the Okta application table in "Access and permissions", and add the system to the intro list if appropriate
- [ ] T027 [P] Search `src/main/resources/static-resources/*.html` (tools.html, permissions.html, how-to-use.html) for per-system lists (grep `hubspot`, `reo`) and add Seamless.AI where systems are enumerated
- [ ] T028 Run `mvn verify` from `pom.xml` for the full suite and confirm no test or coverage regression
- [ ] T029 Run the quickstart.md smoke test against a real Seamless account: confirm DCR sign-in works end to end (research R2 open check), tool list contains the documented domains, licence-filtered listing behaves per user (research risk on registry caching), and record findings in `research.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup (T001) → Foundational (T002–T003) → all stories
- US1 (T004–T013) is the MVP and is required by US2, US4 and US3 for a running system (they extend `SeamlessMcpClient`, gateway tests, and how-to)
- US2 (T014–T018) touches `SeamlessMcpClient.java`; run after US1
- US4 (T019–T022) can run in parallel with US2 (different files) except T021 if it touches interaction code
- US3 (T023–T025) extends `howTo()` in `SeamlessMcpClient.java`; run after US2 to avoid conflicts (T024 references `Seamless_read_resource`)
- Polish (T026–T029) after all desired stories

### Story completion order

US1 → US2 → US4 (parallel with US2) → US3

### Within Each Story

- Tests first (they fail until the implementation lands), then entity → client → endpoint → registration → UI
- Verify with `mvn compile`/`mvn test`/`mvn verify` at each checkpoint

### Parallel Opportunities

- US1: T004, T005, T006 together; T011 anytime after T009
- US2: T014 and T017 together
- US4: T019 and T020 together
- US3/Polish: T026 and T027 together

## Parallel Execution Examples

```text
US1 tests (start together):
  T004 SeamlessConnectionEntityTest
  T005 SeamlessMcpClientTest
  T006 DCR endpoint probe case

US2 + US4 (after US1):
  T014/T017 client tests   |   T019/T020 risk and audit tests

Polish:
  T026 README   |   T027 dashboard pages
```

## Implementation Strategy

### MVP First (User Story 1 only)

1. T001–T003 setup + domain
2. T004–T013 US1
3. **STOP and validate**: connect and run search/credits; confirm Okta gating

### Incremental Delivery

1. MVP → demo connect + search/research
2. US2 → resources and outreach clarity
3. US4 → permission/audit proof and deletion identification
4. US3 → workflow recipes
5. Polish → README, dashboard lists, real-account smoke test

## Notes

- Everything is additive except one registration line (T010), config, dashboard, and docs
- No new dependencies (constitution I, IV)
- Open decision needing user input: T021 (how to make deletions identifiable)
