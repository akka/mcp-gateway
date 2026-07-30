# Research: OAuth Token Flow for Salesforce MCP

**Feature**: 002-oauth-token-flow
**Date**: 2026-04-21

---

## Decision 1: OAuth Flow Type

**Decision**: OAuth 2.0 Authorization Code Flow (web server flow)

**Rationale**: The service acts on behalf of a single Salesforce org — not individual end users. The authorization code flow is the standard, secure choice for server-side apps: the client secret never leaves the server, and a short-lived code is exchanged out-of-band for the token.

**Alternatives considered**: Client Credentials flow (requires IP ranges or API-only access, not suitable for user-delegated access); Device Authorization flow (for devices without browsers — not applicable).

---

## Decision 2: Salesforce OAuth Endpoints

**Decision**: Use `login.salesforce.com` for production/developer orgs, `test.salesforce.com` for sandboxes. Configurable via `SALESFORCE_OAUTH_BASE_URL` env var.

**Authorization endpoint**: `{SALESFORCE_OAUTH_BASE_URL}/services/oauth2/authorize`
**Token endpoint**: `{SALESFORCE_OAUTH_BASE_URL}/services/oauth2/token`

**Required authorization redirect params**:
- `response_type=code`
- `client_id` (Consumer Key from Salesforce Connected App)
- `redirect_uri` (must match registered callback URL)
- `state` (random nonce for CSRF protection)
- `scope=api` (grants access to Salesforce APIs)

**Token exchange POST (form-urlencoded)**:
- `grant_type=authorization_code`
- `code` (authorization code from callback)
- `client_id`
- `client_secret` (Consumer Secret from Salesforce Connected App)
- `redirect_uri` (must match authorization request)

**Token response fields used**:
- `access_token` — bearer token stored in entity
- `instance_url` — Salesforce instance base URL (stored alongside token for future use)
- `token_type` — always `Bearer`

**Alternatives considered**: None — Salesforce endpoint paths are fixed by the platform.

---

## Decision 3: State/Nonce Storage (Replay Protection)

**Decision**: Store the pending OAuth nonce directly in the `SalesforceConnectionEntity` (Key Value Entity) with a 10-minute expiry timestamp.

**Rationale**: This is a single-admin flow; only one OAuth exchange is in progress at any time. A dedicated `OAuthStateEntity` would add unnecessary complexity. The nonce is stored as fields on the connection entity and cleared when the callback is processed.

**Alternatives considered**: Separate `OAuthStateEntity` keyed by nonce (over-engineered for single-admin use); In-memory map (lost on restart, defeats durability requirement).

---

## Decision 4: Authorization Code Exchange — HTTP Client

**Decision**: Use `java.net.http.HttpClient` (Java standard library, since Java 11) for the token exchange HTTP POST.

**Rationale**: The token exchange is a single, infrequent HTTP call. The Java standard library `HttpClient` requires no additional dependency and is straightforward for a blocking form POST. The Akka HTTP endpoint runs on a thread pool, making blocking acceptable for this low-frequency admin operation.

**Alternatives considered**: Akka HTTP client (transitive dep, more complex API for simple one-off call); Apache HttpClient (external dependency, not justified per constitution).

---

## Decision 5: Token Delivery to Agent

**Decision**: `SalesforceEndpoint` reads the token from `SalesforceConnectionEntity` before invoking the agent, and passes it via a `QueryRequest` record that includes both `message` and `bearerToken`. The agent no longer reads environment variables.

**Rationale**: Agents cannot inject `ComponentClient`, so they cannot fetch the token themselves. The endpoint is the right place to resolve external state before delegating to the agent. This also keeps the agent stateless and independently testable.

**Alternatives considered**: Agent reads token from env var (status quo — breaks when env var is removed); Agent uses a `@FunctionTool` to fetch token (circular dependency, tool invoked by LLM not by infra).

---

## Decision 6: Static UI Resources Location

**Decision**: Admin UI HTML file served from `src/main/resources/static-resources/index.html` using `HttpResponses.staticResource("index.html")`.

**Rationale**: Akka SDK's static resource serving uses the `static-resources/` classpath directory. This requires no extra dependencies and is built into the SDK.

---

## Configuration (new env vars)

| Variable | Purpose | Default |
|---|---|---|
| `SALESFORCE_CLIENT_ID` | OAuth Consumer Key | (required) |
| `SALESFORCE_CLIENT_SECRET` | OAuth Consumer Secret | (required) |
| `SALESFORCE_OAUTH_BASE_URL` | Salesforce login base URL | `https://login.salesforce.com` |
| `SALESFORCE_REDIRECT_URI` | OAuth callback URL registered with Salesforce | (required) |

`SALESFORCE_MCP_TOKEN` env var is removed — replaced by the stored entity token.
