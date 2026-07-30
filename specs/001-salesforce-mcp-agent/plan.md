# Implementation Plan: Salesforce MCP Agent

**Branch**: `001-salesforce-mcp-agent` | **Date**: 2026-04-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/001-salesforce-mcp-agent/spec.md`

## Summary

Expose an HTTP endpoint (`POST /salesforce/query`) that accepts a free-text query, delegates it to an Akka Agent that connects to an external Salesforce MCP server via Akka's `RemoteMcpTools` integration, and returns the Salesforce response to the caller.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Akka SDK 3.4+  
**Storage**: N/A — stateless feature; no persistent state  
**Testing**: JUnit 5, Akka TestKitSupport, TestModelProvider  
**Target Platform**: Akka-hosted JVM service  
**Project Type**: Web service (HTTP endpoint + Agent)  
**Performance Goals**: Responses within 10 seconds for typical Salesforce queries  
**Constraints**: Salesforce MCP server URL and auth token must be externally configurable (env vars / application.conf); no hardcoded credentials  
**Scale/Scope**: Single agent component + single endpoint; no state or persistence

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Akka SDK First | ✅ PASS | Agent uses `RemoteMcpTools` from Akka SDK; endpoint uses `@HttpEndpoint` + `ComponentClient` |
| II. Design Principles — Domain independence | ✅ PASS | No business domain state; thin passthrough layer |
| II. Design Principles — API isolation | ✅ PASS | Endpoint defines its own `QueryRequest` / `QueryResponse` records |
| II. Design Principles — Single responsibility | ✅ PASS | Agent handles MCP delegation; endpoint handles HTTP contract |
| II. Design Principles — Descriptive naming | ✅ PASS | `SalesforceAgent`, `SalesforceEndpoint` are domain-aligned |
| III. Test Coverage | ✅ PASS | Plan includes unit test for agent (TestModelProvider) + integration test for endpoint |
| IV. Simplicity | ✅ PASS | No persistence, no workflow, no entities — only what is needed |

No violations. No complexity justification required.

## Project Structure

### Documentation (this feature)

```text
specs/001-salesforce-mcp-agent/
├── plan.md              ← this file
├── research.md          ← Phase 0 output
├── data-model.md        ← Phase 1 output
├── contracts/           ← Phase 1 output
│   └── salesforce-endpoint.md
└── tasks.md             ← /akka.tasks output (not created here)
```

### Source Code

```text
src/main/java/com/example/
├── api/
│   └── SalesforceEndpoint.java        # HTTP endpoint: POST /salesforce/query
└── application/
    └── SalesforceAgent.java           # Agent: delegates query to Salesforce MCP server

src/test/java/com/example/
├── application/
│   └── SalesforceAgentTest.java       # Unit test using TestModelProvider
└── api/
    └── SalesforceEndpointIntegrationTest.java  # Integration test using httpClient
```

**Structure Decision**: Single project, standard Akka SDK package layout (`api` / `application`). No `domain` package needed — this feature has no domain state or business rules.
