# Data Model: Seamless.AI MCP Integration

**Revised 2026-09-30**: Seamless.AI authenticates with a single, operator-configured API key (mirroring `OktaMcpClient`'s authentication), not per-user OAuth — and, by a further explicit decision, has no Okta application gate at all. There is no persisted state for this feature — no entity, no per-user connection record, no app-id config.

## Configuration (not persisted; read from `application.conf` / environment)

| Field | Config key | Env var | Notes |
|---|---|---|---|
| MCP server URL | `seamless.mcp-url` | `SEAMLESS_MCP_URL` | Defaults to `https://mcp.seamless.ai/mcp` |
| API key | `seamless.api-key` | `SEAMLESS_API_KEY` | Sent as the `Token` header on every request; blank means "not connected" |

There is no Okta application id for Seamless.AI — every authenticated gateway user can see and use it, subject only to the reader/writer permission check.

## Derived / transient (not persisted by this feature)

- **Tool metadata**: per-tool `ToolMeta` (name, schema, `readOnlyHint`) cached in the existing `McpRegistryEntity` under mcp id `seamless`, exactly as for every other system.
- **Interaction record**: existing `McpInteraction` events for every call, attributing it to the requesting gateway user (FR-003, FR-011) — this is the only place per-user attribution exists for Seamless.AI; Seamless itself sees one shared identity.
