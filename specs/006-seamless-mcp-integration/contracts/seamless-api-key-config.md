# Contract: Seamless.AI shared API-key configuration

**Revised 2026-09-30**: replaces the earlier `seamless-oauth-endpoint.md` contract. Seamless.AI has no OAuth endpoint and no per-user connect/disconnect route — there is nothing for an individual user to authorize. **Revised again 2026-09-30**: no Okta application id either, by a second explicit user decision — Seamless.AI has no access gate beyond the normal reader/writer permission check.

## Configuration (`application.conf`)

```hocon
seamless {
  mcp-url = "https://mcp.seamless.ai/mcp"
  mcp-url = ${?SEAMLESS_MCP_URL}
  api-key = ""
  api-key = ${?SEAMLESS_API_KEY}
}
```

## Obtaining the API key

In the Seamless.AI app: **Settings > Public API Connections > API Key** → **+ Create New Connection** → check the **MCP** scope → choose the **Group** with access → **Save Connection** → copy the key into `SEAMLESS_API_KEY`.

## Request shape

Every upstream call sends the key in a `Token` header (not `Authorization: Bearer`):

```
POST https://mcp.seamless.ai/mcp
Content-Type: application/json
Token: <SEAMLESS_API_KEY>
```

## Availability

`SeamlessMcpClient.isConnected(userId)` ignores `userId` and returns `true` only when both `mcp-url` and `api-key` are non-blank — availability is an operator-level property, not a per-user one. `getRequiredOktaAppId()` hard-returns `""`: there is no Okta application gate at all. Access is limited only by the normal reader/writer permission check (FR-009) — any authenticated gateway user can see and call Seamless.AI tools once the operator has configured the API key.

## Dashboard

The Seamless.AI card shows a single status line ("Available — … ; no connection needed") for every signed-in user — mirroring the Okta admin card, but without even that card's application-assignment check. There is no connect button, test button, or token box, since there is no per-user credential to manage.
