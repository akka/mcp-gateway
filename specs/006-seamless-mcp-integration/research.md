# Research: Seamless.AI MCP Integration

Sources: https://docs.seamless.ai/mcp-docs and its linked MCP pages (authentication, risk tiers, access control, resources, tools, workflows), plus the existing gateway code (Reo/HubSpot clients, `AbstractDcrOAuthEndpoint`).

## R1. Upstream server and transport
- **Decision**: Default `SEAMLESS_MCP_URL` to `https://mcp.seamless.ai/mcp` (streamable HTTP), overridable by env like other systems.
- **Rationale**: Documented server URL; same transport as the existing `StreamableHttpMcpTransport` clients.
- **Alternatives**: SSE transport — not documented for this server.

## R2. Authentication
- **Decision**: OAuth 2.1 with DCR/CIMD and PKCE S256, scope `mcp.all`, via `AbstractDcrOAuthEndpoint` (as Reo). Seamless also documents an API key option; not used.
- **Rationale**: Per-user identity is a core requirement (FR-003); DCR needs no operator-held client secret. Seamless's own docs mark OAuth as recommended.
- **Alternatives**: Org-wide API key — rejected (breaks per-user access, requires secret storage). Static client id via Seamless Settings — possible fallback, not needed.
- **Open check for implementation**: confirm `AbstractDcrOAuthEndpoint` discovers metadata at `/.well-known/oauth-authorization-server` and handles Seamless's `/mcp/register`, `/mcp/authorize`, `/mcp/token` and requests scope `mcp.all`. The existing `AbstractDcrOAuthEndpointProbeTest` shows how to probe this. Redirect URI must be registered per DCR at connect time (env `SEAMLESS_REDIRECT_URI`).

## R3. Tool coverage
- **Decision**: Dynamic discovery via `listTools()`, prefix `Seamless_`, no hard-coded list.
- **Rationale**: FR-004/FR-015. Docs list ~50 named tools (search, research/poll, user, lists, saved searches, campaigns/steps/contacts, templates, email accounts/drafts/send/bulk/preview/footers, calls/dispositions/sentiments, tasks, activity feed) against a documented total of 54; dynamic discovery covers any not enumerated in the pages read.
- **Alternatives**: Static allow-list — drifts from upstream.

## R4. Risk classification
- **Decision**: Carry upstream annotations through; `readOnlyHint` drives `ToolMeta.isWrite`. Destructive tools are writes (hint `readOnlyHint=false`).
- **Rationale**: Seamless docs state every tool is annotated `read`/`write`/`destructive` and exposes `readOnlyHint`/`destructiveHint`. Missing hint falls back to body-param check then write (fail-safe, FR-009).
- **Alternatives**: Name-based heuristics — fragile.
- **Gap**: The interaction log does not currently distinguish destructive from write (FR-010). Plan: the audit records the tool name, and the client forwards `destructiveHint` in annotations; distinguishing in the log is limited to that unless the interaction record is extended. Treat as a small follow-up decision at task time (see Risks).

## R5. Resources
- **Decision**: Add synthetic read tool `Seamless_read_resource` (input `uri` restricted to `seamless://`), implemented with `McpClient.readResource`; `readOnlyHint=true`.
- **Rationale**: Gateway serves only `tools/list` and `tools/call`. `langchain4j-mcp` 1.15.0-beta25 provides `readResource` (verified with `javap`). Seamless says agents should read `seamless://credits`, templates, email accounts before writes, so this is required for the workflows.
- **Alternatives**: Implement `resources/*` in the gateway — broader change; expose only via equivalent tools (`get_credits`, `list_templates`, `list_email_accounts`) — misses `template-variables` and `connect-config`.

## R6. Async research
- **Decision**: No gateway-side polling. Assistant calls `research_*`, then `poll_*`.
- **Rationale**: Docs model research as start + poll tools; assistant loop is the intended usage (FR-012, SC-007). how-to text tells the assistant to poll.
- **Alternatives**: Blocking composite tool — long calls risk the 30 s tool timeout used by clients.

## R7. Timeouts
- **Decision**: Reuse Reo/HubSpot values (init 15 s, tool 30 s). Bulk send may take longer; keep default and revisit if tests against a real account show timeouts.

## R8. Access gating and config
- **Decision**: New config block `seamless { mcp-url, redirect-uri, okta-app-id }` with env `SEAMLESS_MCP_URL`, `SEAMLESS_REDIRECT_URI`, `SEAMLESS_OKTA_APP_ID`. Unset app id disables gating (existing behaviour). Empty `mcp-url` → not connected.
- **Rationale**: Identical to Reo; README already documents this pattern per system.

## R9. Workflows
- **Decision**: Encode the four workflows (prospect-to-meeting, bulk enrich and campaign, daily activity digest, job-change trigger) in `howTo()` markdown as ordered tool sequences with the resource-read steps. Digest's Slack step relies on the user's existing Slack connection; without it the assistant returns the summary inline (spec FR-007).
- **Rationale**: They are assistant-driven recipes upstream too. Detailed step lists were read from the workflow index only at summary level; refine wording against the four workflow pages when writing `howTo()`.

## R10. Slack tool naming for the digest
- **Decision**: No coupling; the assistant discovers Slack tools itself.

## Risks
- DCR compatibility with Seamless is unverified until run against a real account (mitigation: quickstart smoke test, probe test).
- FR-010 (deletions distinguishable in history) may need a small extension to interaction recording; decide during tasks.
- Engagement-licence tool filtering is done by Seamless per account at `tools/list`; the gateway caches tool metadata per system, so users with different licences could see a cached union in the registry. Confirm registry behaviour with existing per-user listing during implementation.
