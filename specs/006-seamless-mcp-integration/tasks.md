---

description: "Task list for Seamless.AI MCP Integration"
---

# Tasks: Seamless.AI MCP Integration

**Input**: Design documents from `/specs/006-seamless-mcp-integration/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The constitution (Principle III) requires tests with every behavioural change.

**Organization**: Grouped by user story. Stories are ordered by priority: US1 (P1), US2 (P2), US4 (P2), US3 (P3).

**Revised 2026-09-30 (twice)**: first renumbered from the original draft after an explicit user decision to switch Seamless.AI authentication from per-user OAuth 2.1 DCR to a single, operator-configured API key (the `Token` header Seamless documents as its non-OAuth method), mirroring the existing `OktaMcpClient` shared-credential pattern — removing the connection entity, OAuth endpoint, and Foundational phase. Then, by a **second, separate** explicit user decision, Okta application gating was dropped entirely: Seamless.AI now has no `oktaAppId`/app-assignment check at all, unlike `OktaMcpClient` and every other system — any authenticated gateway user can use it, subject only to the normal reader/writer permission check. Task IDs below do not match earlier session transcripts. See `research.md` R2 (auth) and R8 (access gating) for both superseded decisions.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4 from spec.md
- All paths are relative to the repository root. `G` = `src/main/java/io/akka/mcp/gateway`, `T` = `src/test/java/io/akka/mcp/gateway`.
- Reference analog for the client: `OktaMcpClient` (shared org-wide credential, no per-user entity, no `ComponentClient`) — but note `SeamlessMcpClient`'s constructor is `(mcpUrl, apiKey)`, with no `oktaAppId` parameter at all, since Seamless.AI has no application gate.
- Per CLAUDE.md, implementation proceeds one component at a time with user approval between steps.

---

## Phase 1: Setup

**Purpose**: Configuration surface shared by all stories

- [X] T001 Add a `seamless { mcp-url, api-key }` block to `src/main/resources/application.conf` (defaults: `mcp-url = "https://mcp.seamless.ai/mcp"` overridable by `SEAMLESS_MCP_URL`; `api-key` (empty default) overridable by `SEAMLESS_API_KEY`) — no `okta-app-id` key, unlike every other system

**Checkpoint**: Config reads cleanly; no domain model needed (no per-user state), so no separate Foundational phase.

---

## Phase 2: User Story 1 - Use search, research and account tools via Seamless.AI (Priority: P1) 🎯 MVP

**Goal**: Any authenticated gateway user can list and call all Seamless tools through the gateway's single shared API key, with no personal sign-in step and no application-assignment check.

**Independent Test**: As any authenticated test user, run `tools/list` (expect `Seamless_*` tools), call `Seamless_search_contacts` and `Seamless_get_credits`; a user with no application assignments at all still sees and can call them once the API key is configured.

### Tests for User Story 1

- [X] T002 [P] [US1] Create `T/application/SeamlessMcpClientTest.java` covering: `getMcpId()` is `seamless`; `getMcpName()` is `Seamless.AI`; `canHandle` true only for names starting `Seamless_`; `getRequiredOktaAppId` returns `""` (no app gate); `isConnected` is `false` when `mcp-url` is blank, `false` when `api-key` is blank, `true` when both are set (no upstream call); `howTo` mentions Seamless.AI, the dashboard URL, and "no separate connect step"

### Implementation for User Story 1

- [X] T003 [US1] Create `G/application/SeamlessMcpClient.java` implementing `RemoteMcpClient` (MCP_ID `seamless`, name `Seamless.AI`, tool prefix `Seamless_`), constructor `(String mcpUrl, String apiKey)` — no `ComponentClient`, no `oktaAppId`: `listTools` prefixes names and forwards annotations plus `readOnlyHint`/`hasBodyParam` into `McpConfig.ToolMeta`; `callTool` strips the prefix and executes upstream via `StreamableHttpMcpTransport` with header `Token: <apiKey>` (Seamless's documented non-OAuth method — NOT `Authorization: Bearer`), init timeout 15 s, tool timeout 30 s; `isConnected` returns `!mcpUrl.isBlank() && !apiKey.isBlank()`; `getRequiredOktaAppId()` hard-returns `""`; `howTo` returns lookup instructions styled like `OktaMcpClient.howTo` but with no Okta-app prerequisite (no connect steps either; workflow recipes added in US3); run T002 with `mvn test`
- [X] T004 [US1] Register `new SeamlessMcpClient(config.getString("seamless.mcp-url"), config.getString("seamless.api-key"))` in the `serviceClients` list in `G/api/AkkaMcpGateway.java` (after `HubspotMcpClient`)
- [X] T005 [P] [US1] Add a Seamless.AI card (`data-mcp-id="seamless"`) to `src/main/resources/static-resources/index.html`, styled like the `okta-admin` card: a single status line ("Available — search, research, campaigns and more; no connection needed"), no connect/test/token-box UI; wire `STATUS_FETCHERS['seamless']` to `setDot('seamless', 'connected')` (no status fetch, since there is nothing per-user to check)
- [X] T006 [US1] Create `T/api/SeamlessAccessIntegrationTest.java`: a session with no application assignments at all still sees `Seamless_*` tools in `tools/list` and can call them (proving there is no app gate); when `seamless.api-key` is unset (default test config), even that same unprivileged session is told "Seamless.AI is not connected" (an operator-configuration issue, not a personal-access one)
- [X] T007 [US1] Verify US1 by running `mvn compile`, `mvn test`, then `mvn verify` against `pom.xml` (with `-Dmaven.gitcommitid.skip=true` in a worktree); Seamless tests in `T/application/SeamlessMcpClientTest.java` and `T/api/SeamlessAccessIntegrationTest.java` pass. Note: 9 pre-existing failures in `AuthEndpointIntegrationTest` (5) and `OAuthEndpointIntegrationTest` (4) also fail on a clean HEAD (expect "302" but the SDK message now reads "response content type is none/none"); not caused by this feature

**Checkpoint**: US1 works and is testable alone (MVP). Manual smoke test: quickstart.md operator setup + smoke test steps 1–4.

---

## Phase 3: User Story 2 - Outreach: campaigns, templates, email, calls and tasks (Priority: P2)

**Goal**: Outreach tools work and reference data (`seamless://…` resources) is readable before writes; licence/enablement errors are clear.

**Independent Test**: With the shared API key licensed for Connect, read `seamless://templates/variables` and `seamless://email-accounts` via `Seamless_read_resource`, then create a template, campaign, draft and call log; for an unlicensed key, the upstream licence error reaches the user unchanged.

### Tests for User Story 2

- [X] T008 [P] [US2] Extend `T/application/SeamlessMcpClientTest.java`: `Seamless_read_resource` rejects a `uri` not starting with `seamless://` with `isError=true` and no upstream call; `canHandle("Seamless_read_resource")` is true; the synthetic tool spec has `readOnlyHint=true` and an `inputSchema` requiring `uri`

### Implementation for User Story 2

- [X] T009 [US2] In `G/application/SeamlessMcpClient.java`, append the synthetic tool `Seamless_read_resource` (see contracts/seamless-tools.md) to `listTools` results with `ToolMeta` `readOnlyHint=true`; in `callTool` route it to `McpClient.readResource(uri)` after validating the `seamless://` prefix, returning the resource text (join contents) as the result
- [X] T010 [US2] In `G/application/SeamlessMcpClient.java`, make upstream failures surface the upstream message text in `ToolCallResult` (`isError=true`) rather than a generic error, so "MCP Server access is not enabled" and licence/credit errors reach the user (FR-013); map I/O and timeout failures to "Seamless.AI is temporarily unavailable. Please try again shortly."
- [X] T011 [P] [US2] Add unit tests in `T/application/SeamlessMcpClientTest.java` for the error mapping in T010 (upstream error result text preserved; MCP protocol error such as "MCP Server access is not enabled" shown with its upstream message; timeout and connection failure map to "temporarily unavailable"), using the package-private `SeamlessMcpClient(Supplier<McpClient> clientFactory, mcpUrl, apiKey)` seam with a fake `McpClient` (a `java.lang.reflect.Proxy`)
- [X] T012 [US2] Verify with `mvn test` that `T/application/SeamlessMcpClientTest.java` passes

**Checkpoint**: US2 usable with US1; can be validated independently against a licensed Seamless account (quickstart step 3–4 plus outreach prompts).

---

## Phase 4: User Story 4 - Safe, audited use with read/write/destructive controls (Priority: P2)

**Goal**: Permissions and audit behave for Seamless exactly as for other systems, with deletions identifiable, and every call is still attributed to the requesting gateway user even though Seamless itself sees one shared identity.

**Independent Test**: Reader-only user can search but not send; writer can delete a test list and the deletion is identifiable in history, tagged with the requesting user.

### Tests for User Story 4

- [ ] T013 [P] [US4] Extend `T/api/AkkaMcpGatewayIntegrationTest.java` (or add `T/api/SeamlessRiskIntegrationTest.java`): seed registry `seamless` tool metadata with a read tool (`readOnlyHint=true`), a write tool (`false`) and a tool with no hint; assert reader-only user may call the first and is refused the other two; assert a writer may call all; assert each call is recorded in the interaction history against the calling gateway user's identity (FR-003)
- [ ] T014 [P] [US4] Add to `T/application/SeamlessMcpClientTest.java`: `listTools` conversion passes `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, and `title` annotations through unchanged (assert via the annotation-mapping code path)

### Implementation for User Story 4

- [ ] T015 [US4] Read `G/api/AkkaMcpGateway.java` and `G/domain/McpInteraction*.java` to decide how deletions are made identifiable (FR-010). If the existing record already stores the tool name (e.g. `Seamless_delete_list`), document that in `specs/006-seamless-mcp-integration/research.md` (R4) and add an assertion in T013; if not, extend the interaction record minimally with a `destructive` flag derived from the forwarded `destructiveHint`, update `McpInteractionEntity`/views and their tests, and surface it in `src/main/resources/static-resources/interactions.html`. Ask the user which route before changing the interaction model
- [ ] T016 [US4] Verify with `mvn test` and `mvn verify` that `T/api/SeamlessRiskIntegrationTest.java` (or the extended `T/api/AkkaMcpGatewayIntegrationTest.java`) passes

**Checkpoint**: US4 complete; permission and audit behaviour proven for Seamless.

---

## Phase 5: User Story 3 - Reference workflows work end to end (Priority: P3)

**Goal**: The four documented workflows are available as ready-to-follow recipes, including the Slack fallback.

**Independent Test**: Call the how-to tool for Seamless and confirm all four workflows are described with the correct tool sequence; run one workflow end to end (quickstart "Workflows to try").

### Tests for User Story 3

- [ ] T017 [P] [US3] Extend `T/application/HowToMcpClientTest.java` (and `T/application/SeamlessMcpClientTest.java`) to assert the Seamless how-to markdown mentions the four workflows by name (prospect to meeting, bulk enrich and campaign, daily activity digest, job change trigger), the `Seamless_read_resource` resource-read steps, polling after `Seamless_research_*`, and the Slack-not-connected fallback

### Implementation for User Story 3

- [ ] T018 [US3] Re-read `https://docs.seamless.ai/mcp/workflows/prospect-to-meeting.md`, `bulk-enrich-and-campaign.md`, `daily-activity-digest.md` and `job-change-trigger.md`, then extend `howTo()` in `G/application/SeamlessMcpClient.java` with one section per workflow: ordered tool names (with `Seamless_` prefix), resources to read first (`seamless://credits`, `seamless://email-accounts`, `seamless://templates/variables`), polling instruction after research, resolving saved contact ids, and that digest posts to Slack only if Slack tools are available, else return the summary in the conversation
- [ ] T019 [US3] Verify with `mvn test` that `T/application/HowToMcpClientTest.java` and `T/application/SeamlessMcpClientTest.java` pass

**Checkpoint**: All four stories work independently.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T020 [P] Add a **Seamless.AI** section to `README.md` under "Supported systems" (`SEAMLESS_MCP_URL` optional default, `SEAMLESS_API_KEY`; note it uses a single shared API key, not per-user sign-in, and — unlike every other listed system — has no Okta application, so it does NOT belong in the "Applications decide which systems you see" table in "Access and permissions"); add the system to the intro list if appropriate
- [ ] T021 [P] Search `src/main/resources/static-resources/*.html` (tools.html, permissions.html, how-to-use.html) for per-system lists (grep `hubspot`, `okta-admin`) and add Seamless.AI where systems are enumerated
- [ ] T022 Run `mvn verify` from `pom.xml` for the full suite and confirm no test or coverage regression
- [ ] T023 Run the quickstart.md smoke test against a real Seamless account: confirm the API key authenticates (research R2), tool list contains the documented domains, and record findings in `research.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup (T001) → all stories
- US1 (T002–T007) is the MVP and is required by US2, US4 and US3 (they extend `SeamlessMcpClient`, gateway tests, and how-to)
- US2 (T008–T012) touches `SeamlessMcpClient.java`; run after US1
- US4 (T013–T016) can run in parallel with US2 (different files) except T015 if it touches interaction code
- US3 (T017–T019) extends `howTo()` in `SeamlessMcpClient.java`; run after US2 to avoid conflicts (T018 references `Seamless_read_resource`)
- Polish (T020–T023) after all desired stories

### Story completion order

US1 → US2 → US4 (parallel with US2) → US3

### Within Each Story

- Tests first (they fail until the implementation lands), then client → registration → UI
- Verify with `mvn compile`/`mvn test`/`mvn verify` at each checkpoint

### Parallel Opportunities

- US1: T005 anytime after T003/T004
- US2: T008 and T011 together
- US4: T013 and T014 together
- Polish: T020 and T021 together

## Parallel Execution Examples

```text
US2 + US4 (after US1):
  T008/T011 client tests   |   T013/T014 risk and audit tests

Polish:
  T020 README   |   T021 dashboard pages
```

## Implementation Strategy

### MVP First (User Story 1 only)

1. T001 setup
2. T002–T007 US1
3. **STOP and validate**: configure the API key, run search/credits, confirm there is no app-assignment gate

### Incremental Delivery

1. MVP → demo search/research via the shared API key
2. US2 → resources and outreach clarity
3. US4 → permission/audit proof and deletion identification
4. US3 → workflow recipes
5. Polish → README, dashboard lists, real-account smoke test

## Notes

- Everything is additive except one registration line (T004), config, dashboard, and docs
- No new dependencies (constitution I, IV)
- No per-user entity, no OAuth endpoint, and no Okta application gate exist for Seamless.AI — two separate, explicit user decisions (see plan.md Component Architecture and research.md R2, R8)
- Open decision needing user input: T015 (how to make deletions identifiable)
