# Implementation Plan: OAuth Token Flow for Salesforce MCP

**Branch**: `002-oauth-token-flow` | **Date**: 2026-04-21 | **Spec**: [spec.md](spec.md)

## Summary

Replace the static `SALESFORCE_MCP_TOKEN` environment variable with a fully managed OAuth 2.0 Authorization Code flow. An admin UI lets the Salesforce administrator authorize the service once; the resulting bearer token is durably stored in a Key Value Entity and automatically used for all subsequent MCP calls.

---

## Technical Context

**Language/Version**: Java 21 (Akka SDK 3.5.18)
**Primary Dependencies**: Akka SDK (KVE, HTTP Endpoint, Agent); `java.net.http.HttpClient` (stdlib) for token exchange
**Storage**: Key Value Entity (`SalesforceConnectionEntity`) — token + nonce persisted via Akka durable state
**Testing**: JUnit 5, AssertJ, Akka TestKit (`KeyValueEntityTestKit`, `TestKitSupport`)
**Target Platform**: Akka cloud service (Linux, JVM)
**Performance Goals**: Token exchange is infrequent (admin operation); no performance target
**Constraints**: No external HTTP client dependency added (stdlib only)
**Scale/Scope**: Single-admin, single Salesforce org

---

## Constitution Check

| Principle | Status | Notes |
|---|---|---|
| I. Akka SDK First | ✅ Pass | State via KVE; UI served via Akka HTTP Endpoint; token exchange via Java stdlib (no extra dep) |
| II. Domain independence | ✅ Pass | `SalesforceConnection` domain record has zero framework imports |
| II. API isolation | ✅ Pass | `OAuthEndpoint` and `SalesforceEndpoint` define their own request/response records |
| II. Single responsibility | ✅ Pass | OAuthEndpoint handles auth flow; SalesforceEndpoint handles queries; Agent handles LLM |
| III. Test coverage | ✅ Pass | Unit tests for entity + agent; integration tests for both endpoints |
| IV. Simplicity | ✅ Pass | Nonce stored in connection entity (no separate OAuthState entity); stdlib HTTP client |

No violations. No complexity justification required.

---

## Project Structure

### Documentation (this feature)

```text
specs/002-oauth-token-flow/
├── plan.md              ← this file
├── spec.md
├── research.md
├── data-model.md
├── contracts/
│   └── http-endpoints.md
├── checklists/
│   └── requirements.md
└── tasks.md             ← created by /akka.tasks
```

### Source Code Changes

```text
src/main/java/com/example/
├── domain/
│   └── SalesforceConnection.java          NEW — KVE state record
├── application/
│   ├── SalesforceConnectionEntity.java    NEW — Key Value Entity
│   └── SalesforceAgent.java               MODIFIED — accept QueryRequest(message, bearerToken)
└── api/
    ├── OAuthEndpoint.java                 NEW — UI + OAuth flow
    └── SalesforceEndpoint.java            MODIFIED — fetch token from entity

src/main/resources/
├── application.conf                       MODIFIED — add OAuth config block
└── static-resources/
    └── index.html                         NEW — admin connection UI

src/test/java/com/example/
├── application/
│   ├── SalesforceConnectionEntityTest.java  NEW — KVE unit tests
│   └── SalesforceAgentTest.java             MODIFIED — pass bearerToken in QueryRequest
└── api/
    ├── OAuthEndpointIntegrationTest.java    NEW — OAuth flow integration tests
    └── SalesforceEndpointIntegrationTest.java  MODIFIED — pre-seed token; test 503 case
```

---

## Implementation Phases

### Phase 1 — Domain Layer

**Files**: `SalesforceConnection.java`

Domain record with all fields and derived methods. No Akka imports.

```java
// com.example.domain
public record SalesforceConnection(
    String accessToken,
    String instanceUrl,
    Instant tokenAcquiredAt,
    String pendingOAuthState,
    Instant oauthStateExpiresAt
) {
    public static SalesforceConnection empty() { ... }
    public boolean isConnected() { ... }
    public boolean isValidOAuthState(String candidate) { ... }
    public SalesforceConnection withPendingState(String nonce, Instant expiresAt) { ... }
    public SalesforceConnection withToken(String token, String instanceUrl, Instant at) { ... }
    public SalesforceConnection disconnected() { ... }
}
```

**Verify**: `mvn compile`

---

### Phase 2 — Key Value Entity

**Files**: `SalesforceConnectionEntity.java`

```java
@Component(id = "salesforce-connection")
public class SalesforceConnectionEntity extends KeyValueEntity<SalesforceConnection> {

    public static final String ENTITY_ID = "default";

    @Override
    public SalesforceConnection emptyState() { return SalesforceConnection.empty(); }

    public ReadOnlyEffect<SalesforceConnection> getStatus() { ... }
    public Effect<Done> initiateOAuth(String nonce) { ... }
    public Effect<Done> storeToken(StoreTokenCommand cmd) { ... }
    public Effect<Done> disconnect() { ... }

    public record StoreTokenCommand(
        String accessToken, String instanceUrl, Instant acquiredAt, String oauthState) {}
}
```

**Verify**: `mvn compile`

---

### Phase 3 — KVE Unit Tests

**Files**: `SalesforceConnectionEntityTest.java`

Test cases:
- `initiateOAuth_storesNonce`
- `initiateOAuth_overwritesPreviousNonce`
- `storeToken_validState_storesTokenAndClearsNonce`
- `storeToken_invalidState_returnsError`
- `storeToken_expiredState_returnsError`
- `disconnect_clearsToken`
- `getStatus_whenEmpty_returnsDisconnected`
- `getStatus_whenConnected_returnsToken`

**Verify**: `mvn test`

---

### Phase 4 — Update SalesforceAgent

**Files**: `SalesforceAgent.java`

Change command handler from `query(String message)` to `query(QueryRequest request)`.

```java
public record QueryRequest(String message, String bearerToken) {}

public Effect<String> query(QueryRequest request) {
    // uses request.bearerToken() instead of env var
}
```

Remove env-var token reading from constructor.

**Verify**: `mvn compile`

---

### Phase 5 — Update SalesforceAgent Tests

**Files**: `SalesforceAgentTest.java`

Update invocations to pass `QueryRequest(message, "test-token")`.

**Verify**: `mvn test`

---

### Phase 6 — Update SalesforceEndpoint

**Files**: `SalesforceEndpoint.java`

Inject `ComponentClient`. On `POST /salesforce/query`:
1. Call `SalesforceConnectionEntity.getStatus()`
2. If not connected → return `503` with setup instructions
3. If connected → call `SalesforceAgent.query(new QueryRequest(userQuery, token))`

**Verify**: `mvn compile`

---

### Phase 7 — OAuthEndpoint + Admin UI

**Files**: `OAuthEndpoint.java`, `static-resources/index.html`

`OAuthEndpoint` (no `@Component`, has `@HttpEndpoint("/")`, `@Acl` INTERNET):
- `GET /` → `HttpResponses.staticResource("index.html")`
- `GET /oauth/connect` → generate UUID nonce, call entity `initiateOAuth`, 302 redirect to Salesforce
- `GET /oauth/callback` → validate state via entity, exchange code (Java `HttpClient`), call `storeToken`, 302 to `/`
- `GET /oauth/status` → call entity `getStatus`, return `ConnectionStatus` JSON

`index.html`: minimal page showing connection status (fetched via `/oauth/status`), "Connect to Salesforce" button pointing to `/oauth/connect`.

**Verify**: `mvn compile`

---

### Phase 8 — Integration Tests

**Files**: `OAuthEndpointIntegrationTest.java`, `SalesforceEndpointIntegrationTest.java` (updated)

`OAuthEndpointIntegrationTest`:
- `getStatus_whenNoToken_returnsDisconnected`
- `connect_redirectsToSalesforce` (verifies 302 Location header contains Salesforce URL)
- `callback_invalidState_returns400`
- `callback_missingCode_returns400`

`SalesforceEndpointIntegrationTest` (updated):
- Add: `query_whenNotConnected_returns503`
- Existing 400 tests remain unchanged
- Existing 200 test: pre-seed token in entity before invoking query

**Verify**: `mvn verify`

---

### Phase 9 — Configuration Update

**Files**: `application.conf`, `README.md`

Add to `application.conf`:
```hocon
salesforce.oauth {
  base-url = "https://login.salesforce.com"
  base-url = ${?SALESFORCE_OAUTH_BASE_URL}
  client-id = ${?SALESFORCE_CLIENT_ID}
  client-secret = ${?SALESFORCE_CLIENT_SECRET}
  redirect-uri = ${?SALESFORCE_REDIRECT_URI}
}
```

Update `README.md`: add new env vars table, remove `SALESFORCE_MCP_TOKEN`, add UI setup instructions.

---

## Key Design Decisions

1. **Nonce stored in connection entity** — not a separate entity. Single admin means only one OAuth exchange in flight at a time. Simpler.
2. **Token passed via `QueryRequest`** — endpoint fetches token, passes to agent. Keeps agent stateless and testable without entity.
3. **`java.net.http.HttpClient` for token exchange** — stdlib, no extra dependency, synchronous call acceptable for infrequent admin operation.
4. **Singleton entity ID `"default"`** — one connection per service deployment, consistent with single-org assumption.
