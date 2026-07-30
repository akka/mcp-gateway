# Tasks: Salesforce MCP Agent

**Input**: Design documents from `/specs/001-salesforce-mcp-agent/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)
- Exact file paths are included in all task descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm project is ready for implementation

- [x] T001 Verify `pom.xml` uses `akka-javasdk-parent` 3.5+ and that `src/main/java/com/example/api/` and `src/main/java/com/example/application/` package directories exist — no changes needed if already correct

**Checkpoint**: Project structure confirmed — implementation can begin

---

## Phase 2: Foundational (Blocking Prerequisites)

> No entity, domain model, or shared infrastructure is required for this feature. All components are stateless. Proceed directly to user story implementation.

---

## Phase 3: User Story 1 — Query Salesforce via HTTP (Priority: P1) 🎯 MVP

**Goal**: `POST /salesforce/query` accepts a query, delegates to `SalesforceAgent` via `RemoteMcpTools`, and returns the Salesforce response.

**Independent Test**: Send `POST /salesforce/query` with `{"query": "..."}` and receive a populated `{"answer": "..."}` response.

### Implementation

- [x] T002 [P] [US1] Implement `SalesforceAgent` in `src/main/java/com/example/application/SalesforceAgent.java`:
  - `@Component(id = "salesforce-agent")`, extends `Agent`
  - Constructor reads `SALESFORCE_MCP_URL` and `SALESFORCE_MCP_TOKEN` from `System.getenv()`
  - Single command handler: `public Effect<String> query(String message)`
  - Uses `.mcpTools(RemoteMcpTools.fromServer(salesforceMcpUrl).addClientHeader(Authorization.oauth2(token)))`
  - Uses `.systemMessage("You are a Salesforce assistant...")` + `.userMessage(message)` + `.thenReply()`

- [x] T003 [P] [US1] Implement `SalesforceEndpoint` in `src/main/java/com/example/api/SalesforceEndpoint.java`:
  - `@HttpEndpoint("/salesforce")`, `@Acl(allow = @Acl.Matcher(principal = Acl.Principals.INTERNET))`
  - Inner records: `record QueryRequest(String query)` and `record QueryResponse(String answer)`
  - `@Post("/query")` method: accepts `QueryRequest`, calls agent via `componentClient.forAgent().inSession(UUID.randomUUID().toString()).method(SalesforceAgent::query).invoke(request.query())`, returns `QueryResponse`
  - Constructor-injects `ComponentClient`

- [x] T004 [US1] Run `mvn compile` and fix any compilation errors in T002 and T003

### Tests for User Story 1

- [x] T005 [US1] Implement `SalesforceAgentTest` in `src/test/java/com/example/application/SalesforceAgentTest.java`:
  - Extends `TestKitSupport`
  - Uses `TestModelProvider` registered in `testKitSettings()` with `.withModelProvider(SalesforceAgent.class, agentModel)`
  - Test: happy-path — `agentModel.fixedResponse("42 open opportunities")`, invoke `SalesforceAgent::query` with a sample question, assert returned string equals mock response
  - Test: verify the agent can be called in a session (`inSession("test-session-id")`)

- [x] T006 [US1] Run `mvn test` and confirm `SalesforceAgentTest` passes

**Checkpoint**: US1 complete — `SalesforceAgent` and `SalesforceEndpoint` implement the core query flow; agent test passes

---

## Phase 4: User Story 2 — Handle Salesforce Errors Gracefully (Priority: P2)

**Goal**: When the MCP server is unavailable or query is empty, callers receive a human-readable error with a non-2xx HTTP status.

**Independent Test**: Submit an empty query → receive `400 Bad Request`. Submit with MCP server down → receive a readable error message.

### Implementation

- [x] T007 [US2] Add `.onFailure(throwable -> "Failed to reach Salesforce: " + throwable.getMessage())` to the agent effect chain in `SalesforceAgent.java` (after `.thenReply()`)

- [x] T008 [US2] Add input validation to `SalesforceEndpoint.java`: check `request.query() == null || request.query().isBlank()` at the top of the handler and return `HttpResponses.badRequest("Query must not be empty")` before invoking the agent

### Tests for User Story 2

- [x] T009 [US2] Implement `SalesforceEndpointIntegrationTest` in `src/test/java/com/example/api/SalesforceEndpointIntegrationTest.java`:
  - Extends `TestKitSupport`
  - Test: empty query → `httpClient.POST("/salesforce/query").withRequestBody(new QueryRequest("")).invoke()` → assert `400` status
  - Test: null query → assert `400` status
  - Test: valid query with mocked model → assert `200` status and non-empty `answer` field

- [x] T010 [US2] Run `mvn verify` and confirm all tests (unit + integration) pass

**Checkpoint**: US2 complete — all error conditions produce human-readable responses with correct HTTP status codes

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T011 Update `README.md` with required environment variables (`SALESFORCE_MCP_URL`, `SALESFORCE_MCP_TOKEN`), startup instructions, and a `curl` example for `POST /salesforce/query`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **User Stories (Phase 3–4)**: Depend only on Phase 1 confirmation
  - US1 (Phase 3) and US2 (Phase 4) are sequential (US2 adds error handling to US1 components)
- **Polish (Phase 5)**: After both user stories complete

### Within Each User Story

- T002 and T003 are parallel (different files)
- T004 (compile check) depends on T002 + T003
- T005 (agent test) depends on T002
- T007 and T008 (error handling) depend on T004
- T009 (integration test) depends on T007 + T008
- T010 (mvn verify) depends on T009

### Parallel Opportunities

```bash
# US1 — run in parallel:
Task T002: SalesforceAgent.java
Task T003: SalesforceEndpoint.java

# US2 — run in parallel:
Task T007: Add .onFailure() to SalesforceAgent.java
Task T008: Add validation to SalesforceEndpoint.java
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1: Confirm project structure (T001)
2. Phase 3: Implement agent + endpoint + compile + agent test (T002–T006)
3. **STOP and VALIDATE**: `mvn test` passes; run service locally with `SALESFORCE_MCP_URL` set and test with curl

### Full Delivery

1. Complete MVP
2. Phase 4: Add error handling + integration tests (T007–T010)
3. Phase 5: README update (T011)

---

## Notes

- No `domain` package tasks — feature is stateless with no persistent entities
- `SalesforceAgent` must have exactly **one** command handler (`query`) per Akka Agent rules
- Session ID is a fresh `UUID.randomUUID().toString()` per request — do not reuse across calls
- `RemoteMcpTools` is from `akka.javasdk.agent.RemoteMcpTools` — no extra Maven dependency needed
- `Authorization` header helper is from `akka.http.javadsl.model.headers.Authorization`
