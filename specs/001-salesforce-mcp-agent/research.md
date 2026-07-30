# Research: Salesforce MCP Agent

**Date**: 2026-04-20  
**Feature**: 001-salesforce-mcp-agent

## Decision Log

### 1. How to connect to an external MCP server from an Akka Agent

**Decision**: Use `RemoteMcpTools.fromServer(url)` from the Akka SDK's agent effects API.

**Rationale**: Akka SDK 3.4+ supports `RemoteMcpTools.fromServer(String url)` directly in `.mcpTools(...)`. This is the canonical SDK-first approach for connecting to third-party MCP servers (non-Akka services). No extra dependencies needed.

**Alternatives considered**:
- Custom HTTP client to Salesforce API — rejected; violates Akka SDK First principle and requires manual tool mapping.
- `RemoteMcpTools.fromService(name)` — only applicable for MCP endpoints hosted in other Akka services, not external Salesforce MCP servers.

**Key API**:
```java
effects()
  .systemMessage("...")
  .mcpTools(
    RemoteMcpTools.fromServer(salesforceMcpUrl)
      .addClientHeader(Authorization.oauth2(System.getenv("SALESFORCE_MCP_TOKEN")))
  )
  .userMessage(query)
  .thenReply();
```

---

### 2. Salesforce MCP server URL and auth configuration

**Decision**: Read from environment variable `SALESFORCE_MCP_URL` and `SALESFORCE_MCP_TOKEN`, injected into the agent constructor.

**Rationale**: Credentials and external URLs must never be hardcoded. Environment variable injection is the standard Akka deployment pattern and works locally and in production.

**Alternatives considered**:
- `application.conf` — acceptable but env vars are more portable for secrets.
- Constructor-injected config bean — unnecessary complexity for two values.

---

### 3. Session ID strategy

**Decision**: Generate a fresh `UUID` per HTTP request as the agent session ID.

**Rationale**: Each Salesforce query is independent — there is no conversational history to preserve across requests. A fresh UUID per call avoids session memory accumulation.

**Alternatives considered**:
- Caller-supplied session ID — not required by the spec; adds unnecessary complexity.
- Fixed session ID — would cause session memory to grow unboundedly.

---

### 4. Response type

**Decision**: Agent returns `String`; endpoint wraps it in a `QueryResponse` record.

**Rationale**: Salesforce MCP tools return unstructured text. The agent does not parse the result — it passes the LLM-summarized answer through as a string. The endpoint adds the API contract wrapper.

**Alternatives considered**:
- Structured `responseConformsTo(Class)` — requires knowing the Salesforce schema in advance, which varies by query. Not appropriate for a general-purpose query forwarding feature.

---

### 5. Error handling

**Decision**: Use `.onFailure(throwable -> fallbackMessage)` in the agent effect chain; return `HttpResponses.badRequest(message)` from the endpoint for validation errors.

**Rationale**: Agent failures (MCP unreachable, tool error) are surfaced as a fallback string. The endpoint maps this to an appropriate HTTP error code. This keeps error handling at the boundary.
