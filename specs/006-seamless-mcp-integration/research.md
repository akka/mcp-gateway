# Research: Seamless.AI MCP Integration

Sources: https://docs.seamless.ai/mcp-docs and its linked MCP pages (authentication, risk tiers, access control, resources, tools, workflows), plus the existing gateway code (Reo/HubSpot clients, `AbstractDcrOAuthEndpoint`).

## R1. Upstream server and transport
- **Decision**: Default `SEAMLESS_MCP_URL` to `https://mcp.seamless.ai/mcp` (streamable HTTP), overridable by env like other systems.
- **Rationale**: Documented server URL; same transport as the existing `StreamableHttpMcpTransport` clients.
- **Alternatives**: SSE transport — not documented for this server.

## R2. Authentication — REVISED 2026-09-30
- **Decision (current)**: A single, operator-configured API key (`SEAMLESS_API_KEY`), sent as the `Token` header on every request — Seamless's documented non-OAuth auth method (Settings > Public API Connections > API Key, scope MCP). No per-user entity, no OAuth endpoint. Mirrors `OktaMcpClient`.
- **Rationale**: Explicit user decision, made after the OAuth-DCR design (below) was implemented and passing: simplicity over per-user attribution at the Seamless layer. The gateway's own interaction log still attributes each call to the requesting gateway user (FR-003), so auditability is preserved locally even though Seamless sees one shared identity.
- **Alternatives**: Per-user OAuth 2.1 DCR (the original decision, see superseded note below) — rejected by the user in favor of the simpler shared-key model.
- **Superseded original decision** (kept for history; the DCR probe findings remain accurate if this is ever revisited): OAuth 2.1 with DCR/CIMD and PKCE S256, scope `mcp.all`, via `AbstractDcrOAuthEndpoint` (as Reo), was implemented and verified live on 2026-09-25 — `mcp.seamless.ai` challenges both `initialize` and `tools/call` with a 401 carrying `resource_metadata` + `scope="mcp.all"`, and its authorization server metadata (`/.well-known/oauth-authorization-server`) advertises `registration_endpoint`, PKCE `S256`, and both `authorization_code` and `refresh_token` grants. That code (`SeamlessConnectionEntity`, `SeamlessOAuthEndpoint`, the `getScope()`/`getGrantTypes()` hooks added to `AbstractDcrOAuthEndpoint`) has been removed in favor of the API-key design above; the hooks were reverted since nothing else needs them.

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

## R8. Access gating and config — REVISED 2026-09-30, then REVISED again 2026-09-30
- **Decision**: Config block `seamless { mcp-url, api-key }` with env `SEAMLESS_MCP_URL`, `SEAMLESS_API_KEY`. No Okta app id at all: `getRequiredOktaAppId()` hard-returns `""`, so `appAssigned()` never gates Seamless.AI for anyone. Empty `mcp-url` or `api-key` → `isConnected()` false for everyone, independent of `userId` — the only "not available" state left is an operator-configuration one, not a per-user one.
- **Rationale**: First revision matched every other system's config pattern with `okta-app-id` as the one remaining per-user gate. A second, separate explicit user decision removed that gate too — Seamless.AI is meant to be usable by any signed-in gateway user, not just those an admin has additionally assigned an Okta application. Reader/writer permission (FR-009) is unaffected and still enforced normally.
- **Alternatives**: Keeping `okta-app-id` as a soft/optional gate (unset = open, set = gated) — rejected; the user asked to remove it outright, not make it optional.

## R9. Workflows
- **Decision**: Encode the four workflows (prospect-to-meeting, bulk enrich and campaign, daily activity digest, job-change trigger) in `howTo()` markdown as ordered tool sequences with the resource-read steps. Digest's Slack step relies on the user's existing Slack connection; without it the assistant returns the summary inline (spec FR-007).
- **Rationale**: They are assistant-driven recipes upstream too. Detailed step lists were read from the workflow index only at summary level; refine wording against the four workflow pages when writing `howTo()`.

## R10. Slack tool naming for the digest
- **Decision**: No coupling; the assistant discovers Slack tools itself.

## Risks
- The API key itself, and the account it belongs to, is unverified against a real Seamless account until the quickstart smoke test runs.
- FR-010 (deletions distinguishable in history) may need a small extension to interaction recording; decide during tasks.
- Engagement-licence tool filtering is done by Seamless for whichever account the shared API key belongs to; since there is only one Seamless identity now (not one per gateway user), all gateway users effectively share that account's licence — there is no cross-user licence variance to worry about for this design, but the single API key's Group/scopes must cover everything the organisation wants exposed.
- The API key must never be logged or returned to a client; `buildClient` puts it only in the `Token` transport header.
