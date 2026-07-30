# Feature Specification: OAuth Token Flow for Salesforce MCP

**Feature Branch**: `002-oauth-token-flow`
**Created**: 2026-04-21
**Status**: Draft
**Input**: User description: "add oauth - the bearer token that is used in the agent needs to be received via an oauth flow. Add this flow via ui, store the token in an entity and then use that token to call the mcp."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect Salesforce via OAuth (Priority: P1)

An administrator opens the service's web UI and initiates an OAuth authorization flow to connect their Salesforce organisation. They are redirected to Salesforce, grant access, and are returned to the UI. The service stores the resulting access token and confirms the connection is active.

**Why this priority**: Without a valid token the service cannot query Salesforce at all. This is the foundational setup step for the entire feature.

**Independent Test**: Navigate to the UI, click "Connect to Salesforce", complete the Salesforce login, verify the UI shows "Connected" and that a subsequent Salesforce query succeeds without providing a token manually.

**Acceptance Scenarios**:

1. **Given** no Salesforce connection exists, **When** the administrator opens the UI and clicks "Connect to Salesforce", **Then** they are redirected to the Salesforce authorization page.
2. **Given** the administrator is on the Salesforce authorization page, **When** they grant access, **Then** they are redirected back to the service UI with a "Connected" confirmation.
3. **Given** the administrator successfully completes the OAuth flow, **When** the UI confirmation is shown, **Then** the access token is durably stored and the connection status reflects "Connected".
4. **Given** the administrator denies access on the Salesforce authorization page, **When** they are redirected back, **Then** the UI shows a clear error message and the connection status remains "Not connected".

---

### User Story 2 - Query Salesforce Using the Stored Token (Priority: P2)

A user submits a natural language query to the Salesforce endpoint. The service automatically retrieves the previously stored access token and uses it to authenticate against the Salesforce MCP server — no manual token input required.

**Why this priority**: This is the primary runtime use case. Once connected, every query must transparently use the stored token.

**Independent Test**: With a valid stored token, send a query to `POST /salesforce/query` and verify a meaningful Salesforce answer is returned without supplying any Authorization header.

**Acceptance Scenarios**:

1. **Given** a valid access token is stored, **When** a user submits a query, **Then** the service uses the stored token to call the MCP server and returns the answer.
2. **Given** no access token is stored, **When** a user submits a query, **Then** the service returns a clear error indicating that Salesforce is not connected and instructs the user to complete the OAuth setup.

---

### User Story 3 - View Connection Status and Reconnect (Priority: P3)

The administrator can check the current connection status at any time from the UI and, if the connection is lost or the token has expired, re-initiate the OAuth flow to reconnect.

**Why this priority**: Tokens expire; providing a way to reconnect without redeploying the service is essential for day-to-day operations.

**Independent Test**: With an expired or missing token, open the UI, confirm the status shows "Not connected" or "Token expired", click "Reconnect", complete the OAuth flow, and confirm the status updates to "Connected".

**Acceptance Scenarios**:

1. **Given** a valid stored token, **When** the administrator opens the UI, **Then** the connection status shows "Connected" along with the time the token was last obtained.
2. **Given** the token is absent or expired, **When** the administrator opens the UI, **Then** the connection status shows "Not connected" with an option to connect.
3. **Given** the administrator clicks "Connect" or "Reconnect", **When** they complete the OAuth flow successfully, **Then** the old token is replaced with the new one and the status updates to "Connected".

---

### Edge Cases

- What happens when the OAuth callback is received with an error parameter (e.g. `error=access_denied`)?
- How does the system behave if the stored token is revoked mid-session by a Salesforce admin?
- What happens if the OAuth callback arrives after the state/nonce has expired (replay protection)?
- How does the UI behave if the user navigates away during the OAuth redirect before completing the flow?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST provide a web UI page that displays the current Salesforce connection status.
- **FR-002**: The UI MUST provide a button to initiate the Salesforce OAuth authorization flow.
- **FR-003**: The service MUST redirect the user to the Salesforce authorization endpoint when the connect button is clicked.
- **FR-004**: The service MUST handle the OAuth callback, extract the authorization code, and exchange it for an access token.
- **FR-005**: The service MUST durably store the obtained access token so it survives service restarts.
- **FR-006**: The service MUST use the stored access token automatically when calling the Salesforce MCP server; no manual token input by users is required at query time.
- **FR-007**: The service MUST return a clear, actionable error to users who submit queries when no valid token is stored.
- **FR-008**: The service MUST allow the administrator to re-initiate the OAuth flow at any time to replace an existing or expired token.
- **FR-009**: The service MUST protect the OAuth callback against replay attacks using a short-lived state parameter.
- **FR-010**: The UI MUST display when the current token was last obtained.

### Assumptions

- A single shared access token is used for the entire service (single Salesforce org, admin-level setup). Multi-user per-identity tokens are out of scope.
- The OAuth provider (Salesforce) is pre-configured outside this feature (client ID, client secret, allowed redirect URIs registered in Salesforce). Configuration is supplied via environment variables.
- Automatic token refresh is out of scope for this feature; the administrator reconnects manually when a token expires.

### Key Entities

- **SalesforceConnection**: Represents the current Salesforce integration state. Key attributes: connection identifier (singleton), access token (sensitive), token acquisition timestamp, connection status (connected / not connected).
- **OAuthState**: Short-lived record used during the authorization code exchange to prevent replay attacks. Attributes: random nonce, expiry time.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An administrator can complete the full OAuth connection flow — from clicking "Connect" to seeing "Connected" — in under 2 minutes.
- **SC-002**: 100% of Salesforce queries issued after a successful OAuth connection use the stored token without requiring the caller to supply credentials.
- **SC-003**: The connection status shown in the UI accurately reflects the stored token state within 3 seconds of any change.
- **SC-004**: Attempting to query Salesforce without a stored token results in a clear error message 100% of the time, with no silent failures or unhandled exceptions.
- **SC-005**: The OAuth callback correctly rejects replayed or tampered state parameters in all tested cases.
