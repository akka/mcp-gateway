# HTTP Endpoint Contracts: OAuth Token Flow

**Feature**: 002-oauth-token-flow
**Date**: 2026-04-21

---

## OAuthEndpoint — `/` (admin UI)

### GET /

Returns the admin HTML page for managing the Salesforce connection.

**Response**: `200 OK` — `text/html` — static `index.html`

---

## OAuthEndpoint — `/oauth/connect`

### GET /oauth/connect

Initiates the Salesforce OAuth authorization code flow. Generates a random nonce, stores it in the `SalesforceConnectionEntity`, and issues a browser redirect to Salesforce.

**Response**: `302 Found`
- `Location: {SALESFORCE_OAUTH_BASE_URL}/services/oauth2/authorize?response_type=code&client_id={CLIENT_ID}&redirect_uri={REDIRECT_URI}&state={NONCE}&scope=api`

**Error response**: `500 Internal Server Error` if OAuth configuration (client ID, redirect URI) is missing.

---

## OAuthEndpoint — `/oauth/callback`

### GET /oauth/callback

Handles the Salesforce authorization callback. Validates the `state` parameter, exchanges the authorization code for a token, stores the token, and redirects back to the admin UI.

**Query Parameters**:
- `code` (string, required) — authorization code from Salesforce
- `state` (string, required) — must match the stored nonce

**Success Response**: `302 Found`
- `Location: /` — redirects to admin UI which will show "Connected"

**Error Responses**:

| Condition | Status | Body |
|---|---|---|
| Missing `code` or `state` | `400 Bad Request` | `"Missing required parameters"` |
| State mismatch or expired nonce | `400 Bad Request` | `"Invalid or expired OAuth state"` |
| Salesforce token exchange failure | `502 Bad Gateway` | `"Token exchange failed: {reason}"` |

---

## OAuthEndpoint — `/oauth/status`

### GET /oauth/status

Returns the current Salesforce connection status as JSON.

**Response**: `200 OK` — `application/json`

```json
{
  "connected": true,
  "tokenAcquiredAt": "2026-04-21T10:30:00Z"
}
```

```json
{
  "connected": false,
  "tokenAcquiredAt": null
}
```

---

## SalesforceEndpoint — `/salesforce/query` (modified)

### POST /salesforce/query

Unchanged interface. Modified behaviour: the bearer token is now sourced from `SalesforceConnectionEntity` instead of an environment variable.

**New error case**:

| Condition | Status | Body |
|---|---|---|
| No Salesforce token stored | `503 Service Unavailable` | `"Salesforce is not connected. Visit / to complete the OAuth setup."` |

---

## Environment Variables (updated)

| Variable | Required | Description |
|---|---|---|
| `SALESFORCE_MCP_URL` | Yes | Salesforce MCP server URL |
| `SALESFORCE_CLIENT_ID` | Yes | OAuth Consumer Key from Salesforce Connected App |
| `SALESFORCE_CLIENT_SECRET` | Yes | OAuth Consumer Secret |
| `SALESFORCE_REDIRECT_URI` | Yes | Callback URL (e.g. `https://your-service.akka.io/oauth/callback`) |
| `SALESFORCE_OAUTH_BASE_URL` | No | `https://login.salesforce.com` (default) or `https://test.salesforce.com` for sandboxes |
| `SALESFORCE_MCP_TOKEN` | **Removed** | Replaced by entity-stored token |
