# Implementation Plan: Seamless.AI MCP Integration

**Branch**: `feature/add-seamless-mcp` (spec dir `006-seamless-mcp-integration`) | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/006-seamless-mcp-integration/spec.md`

## Summary

Add Seamless.AI as another proxied upstream MCP system in the gateway, exactly like Reo: a per-user connection stored in a Key Value Entity, an OAuth endpoint that reuses the existing dynamic-client-registration (DCR) base class, and a `RemoteMcpClient` implementation that lists/calls the upstream's tools with the user's own token. Tool discovery is dynamic, so all 54 tools appear without hard-coding; risk classification comes from the upstream `readOnlyHint`, which feeds the existing reader/writer enforcement and audit. The one real gap is **resources** (`seamless://credits`, templates, etc.): the gateway only proxies `tools/*`, so the client exposes one synthetic read-only tool that reads a `seamless://` resource. The four workflows need no code; they are documented in the how-to content the gateway already generates per system.

## Technical Context

**Language/Version**: Java 21, Akka SDK (version per existing `pom.xml`)
**Primary Dependencies**: Existing only — Akka SDK, `langchain4j-mcp` 1.15.0-beta25 (already used by every client). No new dependencies.
**Storage**: One Key Value Entity per user (keyed by email), same as other connections
**Testing**: JUnit 5 + `KeyValueEntityTestKit` (entity), `TestKitSupport` integration tests (gateway gating/risk), existing DCR probe test pattern
**Target Platform**: Akka service (existing gateway)
**Project Type**: web-service (HTTP endpoints + static dashboard)
**Performance Goals**: `tools/list` fetch stays inside the existing 20 s per-client budget; single-contact research result visible within 2 min via assistant polling (SC-007)
**Constraints**: Seamless outage must not affect other systems (existing parallel/bounded fetch already guarantees this); no shared credentials
**Scale/Scope**: Same as other systems; 54 tools + 1 synthetic resource tool

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Akka SDK First | PASS | Entity + HTTP endpoint on the SDK; no new dependencies |
| II. Design principles | PASS | Domain record has no Akka deps; endpoint uses its own types; one focused class per role; domain-aligned names (`Seamless*`) |
| III. Test coverage | PASS (planned) | Entity unit test, domain-record behaviour via entity test, client unit tests, gateway integration tests for gating and risk mapping |
| IV. Simplicity | PASS | Reuses `AbstractDcrOAuthEndpoint` and the Reo pattern; no composite/polling component; workflows are documentation only |

Post-design re-check: still PASS. No violations, so Complexity Tracking is empty.

## Component Architecture

`akka-context/sdk/components/index.html.md` is not present in this worktree, so choices follow the repo's established patterns for the identical Reo/HubSpot integrations rather than the decision guide. Verify against the guide if it becomes available.

| Domain concept / process / query | Component | Reason | Rejected alternative |
|---|---|---|---|
| Per-user Seamless connection (token, refresh token, pending OAuth state) | Key Value Entity `SeamlessConnectionEntity` (id = user email) | Current state only; no audit need for token history (interactions are audited separately); matches all other `*ConnectionEntity` | Event Sourced Entity: token history has no business value and would retain secrets in a journal |
| Connection state and rules (valid pending state, expiry) | Plain record `SeamlessConnection` in `domain` | Logic independent of Akka | Logic in entity: violates domain independence |
| Token refresh on read | Inside `SeamlessConnectionEntity.getAccessToken` | Same as Reo/HubSpot; single writer per user avoids refresh races | Timed Action pre-refresh: extra component, refresh-on-use is sufficient |
| Connect / status / disconnect / test flow | HTTP Endpoint `SeamlessOAuthEndpoint` extends `AbstractDcrOAuthEndpoint` | Seamless supports DCR + PKCE; base class already implements the flow | `AbstractStaticOAuthEndpoint`: would need an operator-registered client id/secret, which Seamless does not require |
| Proxying tools/list and tools/call | Plain class `SeamlessMcpClient implements RemoteMcpClient`, registered in `AkkaMcpGateway` | Gateway's existing extension point; not an Akka component | New Agent/Workflow: the assistant already orchestrates tools |
| Resource access (`seamless://…`) | Synthetic tool `Seamless_read_resource` inside `SeamlessMcpClient` | Gateway proxies tools only; keeps FR-005 without changing the gateway protocol layer | Add `resources/list`/`read` to the gateway: larger cross-cutting change, YAGNI for one system |
| Async research polling | None (assistant chains `research_*` and `poll_*` tools) | Seamless already exposes poll tools; assistant loop satisfies FR-012 | Workflow that polls: duplicates the assistant, adds a component with no requirement forcing it |
| Four reference workflows | Markdown in `howTo()` content (consumed by existing `HowToMcpClient`) | They are prompts over existing tools | Workflow component: unattended execution is out of scope per spec |
| Read/write permission + audit | Existing gateway logic via `ToolMeta` + `McpInteraction*` | No change; provide accurate `readOnlyHint` | New audit path: unnecessary |
| Okta app gating | Existing check via `getRequiredOktaAppId()` | Same as other systems | — |
| Dashboard card | New `data-mcp-id="seamless"` block in `index.html` | Same as other systems | — |

## Project Structure

### Documentation (this feature)

```text
specs/006-seamless-mcp-integration/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── seamless-oauth-endpoint.md
│   └── seamless-tools.md
├── checklists/requirements.md
└── tasks.md             # /akka:tasks
```

### Source Code (repository root)

```text
src/main/java/io/akka/mcp/gateway/
├── domain/SeamlessConnection.java                 # new
├── application/SeamlessConnectionEntity.java      # new
├── application/SeamlessMcpClient.java             # new
├── api/SeamlessOAuthEndpoint.java                 # new
└── api/AkkaMcpGateway.java                        # register client
src/main/resources/
├── application.conf                               # seamless { mcp-url, redirect-uri, okta-app-id }
└── static-resources/index.html                    # dashboard card
src/test/java/io/akka/mcp/gateway/
├── application/SeamlessConnectionEntityTest.java
├── application/SeamlessMcpClientTest.java
└── api/AkkaMcpGatewayIntegrationTest.java (extend) / SeamlessOAuthEndpoint probe
README.md                                          # Seamless section + env vars
```

**Structure Decision**: Single existing Maven project, existing `domain`/`application`/`api` packages; purely additive apart from one registration line and config/doc edits.

## Complexity Tracking

No violations.
