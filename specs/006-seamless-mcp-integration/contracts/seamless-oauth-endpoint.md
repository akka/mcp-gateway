# Contract: Seamless OAuth endpoint

`SeamlessOAuthEndpoint` at `/seamless/oauth`, extends `AbstractDcrOAuthEndpoint`; routes are inherited, identical to `/reo/oauth`. See `AbstractDcrOAuthEndpoint` for exact paths and payloads (status, connect/authorize redirect, callback, disconnect, test). Not redefined here to avoid drift.

## Class-specific values

| Item | Value |
|---|---|
| Path | `/seamless/oauth` |
| Provider label | `Seamless.AI` |
| MCP URL | config `seamless.mcp-url` |
| Redirect URI | config `seamless.redirect-uri` |
| ACL | `INTERNET` (same as other OAuth endpoints; protected by gateway session) |

## Configuration (`application.conf`)

```hocon
seamless {
  mcp-url = "https://mcp.seamless.ai/mcp"
  mcp-url = ${?SEAMLESS_MCP_URL}
  redirect-uri = ""
  redirect-uri = ${?SEAMLESS_REDIRECT_URI}
  okta-app-id = ""
  okta-app-id = ${?SEAMLESS_OKTA_APP_ID}
}
```

## Entity commands (`seamless-connection`, key = email)

| Command | Input | Output |
|---|---|---|
| `getStatus` (read-only) | – | `SeamlessConnection` |
| `getAccessToken` | – | access token, refreshing if needed; error if not connected / cannot refresh |
| `initiatePkceOAuth` | `InitiateCommand(state, codeVerifier, clientId, tokenEndpoint)` | `Done` |
| `storeToken` | `StoreTokenCommand(accessToken, refreshToken, expiresAt, state)` | `Done`; error on invalid state |
| `disconnect` | – | `Done` |

Refresh: `grant_type=refresh_token` against the stored `tokenEndpoint` with the stored `clientId` (public client, no secret).
