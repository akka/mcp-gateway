# Implementation Plan: Seamless.AI MCP Integration

**Branch**: `feature/add-seamless-mcp` (spec dir `006-seamless-mcp-integration`) | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/006-seamless-mcp-integration/spec.md`

## Summary

Add Seamless.AI as another proxied upstream MCP system in the gateway. **Revised 2026-09-30 per explicit user decisions**: authenticate with a single, operator-configured API key against `https://mcp.seamless.ai/mcp` (the `Token` header Seamless documents as its non-OAuth method) rather than per-user OAuth 2.1 DCR, and — a second, separate decision — drop Okta application gating entirely, so every authenticated gateway user (not just those assigned a "Seamless.AI" Okta app) can use it. This mirrors `OktaMcpClient`'s shared-credential authentication but goes further than it: `OktaMcpClient` still gates by Okta app assignment; Seamless.AI gates by nothing beyond the normal reader/writer permission check. No connection entity, no OAuth endpoint, no per-user connect/disconnect step, no `oktaAppId`. `SeamlessMcpClient` (a plain `RemoteMcpClient`, not an Akka component) lists/calls the upstream's tools with the shared key. Tool discovery is dynamic, so all 54 tools appear without hard-coding; risk classification comes from the upstream `readOnlyHint`, which feeds the existing reader/writer enforcement and audit — each call is still attributed to the requesting gateway user in the local interaction history even though Seamless itself sees one connection. The one real gap is **resources** (`seamless://credits`, templates, etc.): the gateway only proxies `tools/*`, so the client exposes one synthetic read-only tool that reads a `seamless://` resource. The four workflows need no code; they are documented in the how-to content the gateway already generates per system.

## Technical Context

**Language/Version**: Java 21, Akka SDK (version per existing `pom.xml`)
**Primary Dependencies**: Existing only — Akka SDK, `langchain4j-mcp` 1.15.0-beta25 (already used by every client). No new dependencies.
**Storage**: None — no per-user state; the API key is a config value, not persisted by the gateway
**Testing**: JUnit 5 unit tests for the client (fake `McpClient` seam), `TestKitSupport` integration tests (gateway gating/risk)
**Target Platform**: Akka service (existing gateway)
**Project Type**: web-service (HTTP endpoints + static dashboard)
**Performance Goals**: `tools/list` fetch stays inside the existing 20 s per-client budget; single-contact research result visible within 2 min via assistant polling (SC-007)
**Constraints**: Seamless outage must not affect other systems (existing parallel/bounded fetch already guarantees this); the shared API key must never leak into logs or client-visible output
**Scale/Scope**: Same as other systems; 54 tools + 1 synthetic resource tool

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Akka SDK First | PASS | No new dependencies; the client is a plain class behind the SDK-free `RemoteMcpClient` extension point, same as `OktaMcpClient` |
| II. Design principles | PASS | One focused class; domain-aligned name (`SeamlessMcpClient`); no entity or endpoint needed since there is no per-user state |
| III. Test coverage | PASS | Client unit tests (fake `McpClient` seam) for listing, resource reads, error mapping; gateway integration tests for gating and the not-configured path |
| IV. Simplicity | PASS | No connection entity, no OAuth endpoint, no dashboard connect flow — simpler than the originally planned per-user design; workflows are documentation only |

Post-design re-check: still PASS. No violations, so Complexity Tracking is empty.

## Component Architecture

`akka-context/sdk/components/index.html.md` is not present in this worktree, so choices follow the repo's established patterns (specifically `OktaMcpClient`, the existing shared-credential integration) rather than the decision guide. Verify against the guide if it becomes available.

| Domain concept / process / query | Component | Reason | Rejected alternative |
|---|---|---|---|
| Seamless API key + server URL | Plain config values (`seamless.mcp-url`, `seamless.api-key`), no persisted state | Single organisation-wide credential set once by an operator; nothing per-user to store | Key Value Entity per user (the original DCR-based plan): rejected by explicit user decision — Seamless.AI now authenticates with one shared API key, not per-user OAuth, so there is no per-user token/expiry/pending-state to persist |
| Sign-in / connect flow | None — no HTTP endpoint | There is nothing for an individual user to authorize | `AbstractDcrOAuthEndpoint`/`AbstractStaticOAuthEndpoint` (the original plan): rejected for the same reason — no per-user OAuth handshake exists to host |
| Proxying tools/list and tools/call | Plain class `SeamlessMcpClient implements RemoteMcpClient`, registered in `AkkaMcpGateway` | Gateway's existing extension point; not an Akka component; constructor `(mcpUrl, apiKey)` only — no `oktaAppId`, no `ComponentClient` | `OktaMcpClient`'s exact constructor shape (`mcpUrl`, credential, `oktaAppId`): rejected by explicit user decision — Seamless.AI has no application gate at all |
| Resource access (`seamless://…`) | Synthetic tool `Seamless_read_resource` inside `SeamlessMcpClient` | Gateway proxies tools only; keeps FR-005 without changing the gateway protocol layer | Add `resources/list`/`read` to the gateway: larger cross-cutting change, YAGNI for one system |
| Async research polling | None (assistant chains `research_*` and `poll_*` tools) | Seamless already exposes poll tools; assistant loop satisfies FR-012 | Workflow that polls: duplicates the assistant, adds a component with no requirement forcing it |
| Four reference workflows | Markdown in `howTo()` content (consumed by existing `HowToMcpClient`) | They are prompts over existing tools | Workflow component: unattended execution is out of scope per spec |
| Read/write permission + audit | Existing gateway logic via `ToolMeta` + `McpInteraction*` | No change; provide accurate `readOnlyHint`; audit still attributes each call to the requesting gateway user regardless of the shared upstream credential | New audit path: unnecessary |
| Access gating | `getRequiredOktaAppId()` returns `""` (no gate) | Explicit user decision: every authenticated gateway user may use Seamless.AI, subject only to the read/write permission check | Okta app gating like every other system (and the original plan): rejected by the user |
| Dashboard card | New `data-mcp-id="seamless"` block in `index.html`, styled like the Okta admin card (status only, no connect/disconnect/token UI) | Matches the shared-credential precedent | The originally planned connect/token-box card: no per-user token to show |

## Project Structure

### Documentation (this feature)

```text
specs/006-seamless-mcp-integration/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── seamless-api-key-config.md
│   └── seamless-tools.md
├── checklists/requirements.md
└── tasks.md             # /akka:tasks
```

### Source Code (repository root)

```text
src/main/java/io/akka/mcp/gateway/
├── application/SeamlessMcpClient.java             # new
└── api/AkkaMcpGateway.java                        # register client
src/main/resources/
├── application.conf                               # seamless { mcp-url, api-key }
└── static-resources/index.html                    # dashboard card (status only)
src/test/java/io/akka/mcp/gateway/
├── application/SeamlessMcpClientTest.java
└── api/SeamlessAccessIntegrationTest.java
README.md                                          # Seamless section + env vars
```

**Structure Decision**: Single existing Maven project, existing `domain`/`application`/`api` packages; purely additive apart from one registration line and config/doc edits.

## Complexity Tracking

No violations.
