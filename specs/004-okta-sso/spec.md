# Feature Specification: Okta SSO Authentication

**Feature Branch**: `004-okta-sso`
**Created**: 2026-04-27
**Status**: Draft
**Input**: User description: "now I want to build users into the system. users must be authorized and authenticated by okta sso only. build a login screen where I can put my email which must end with @example.com. it must redirect okta and process the OIDC protocol properly. I have the client id / secret as env. show a status of login in the root page where the mcps are listed"

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Login via Okta SSO (Priority: P1)

A user navigates to the application. Because they are not authenticated, they are redirected to a login page. They enter their `@example.com` email address and click **Continue**. The system redirects them to Okta. After authenticating at Okta, they are returned to the application and land on the root MCP connections page, now shown as logged in.

**Why this priority**: Without this story no protected functionality is accessible. It is the foundation of the entire auth system.

**Independent Test**: Can be fully tested by visiting `/`, being redirected to login, completing the Okta flow, and verifying arrival at the MCP connections page with a session established.

**Acceptance Scenarios**:

1. **Given** an unauthenticated user visits any page, **When** the page loads, **Then** they are redirected to the login screen
2. **Given** the login screen, **When** the user enters a valid `@example.com` email and submits, **Then** they are redirected to Okta for authentication
3. **Given** the user enters an email that does not end in `@example.com`, **When** they submit, **Then** an inline error is shown and no redirect occurs
4. **Given** the user successfully authenticates at Okta, **When** Okta redirects back to the callback URL, **Then** a session is created and the user lands on the MCP connections root page
5. **Given** the user is already authenticated in the current session, **When** they visit any page, **Then** they see the page without being redirected to login

---

### User Story 2 — OIDC callback handling (Priority: P1)

After Okta redirects back to the application with an authorization code, the application exchanges the code for tokens, validates the ID token, extracts the user's identity, and establishes a server-side session. The user is not exposed to any of this — they simply arrive at the application logged in.

**Why this priority**: This is the server-side completion of the login flow. Without it, SSO cannot complete.

**Independent Test**: Can be tested by simulating an Okta callback with a valid code and verifying a session is created; and by verifying that invalid or tampered callbacks are rejected.

**Acceptance Scenarios**:

1. **Given** Okta redirects with a valid authorization code and matching state, **When** the callback is processed, **Then** the user's identity is extracted and a session is established
2. **Given** Okta redirects with a mismatched or missing state parameter, **When** the callback is processed, **Then** the request is rejected and the user sees an error
3. **Given** Okta redirects with an expired or invalid code, **When** the callback is processed, **Then** the user sees a meaningful error and is directed back to login

---

### User Story 3 — Login status on root page (Priority: P2)

An authenticated user on the root MCP connections page can see who they are logged in as (their name and/or email) and a logout button.

**Why this priority**: Users need confirmation of their identity and a way to end their session. Builds trust and supports multi-user scenarios.

**Independent Test**: Can be tested by logging in and verifying that the root page displays the authenticated user's identity and a working logout button.

**Acceptance Scenarios**:

1. **Given** an authenticated user is on the root page, **When** the page loads, **Then** their name or email address is displayed in the page header
2. **Given** an authenticated user clicks **Logout**, **When** the action completes, **Then** their session is cleared and they are redirected to the login screen
3. **Given** a logged-out user visits the root page, **When** the page loads, **Then** no user identity is shown and they are redirected to login instead

---

### Edge Cases

- What happens when the user's Okta session is already active? The OIDC flow completes without re-entering credentials and the user is logged in automatically.
- What happens when Okta is unreachable? The user sees a clear error message and is not left on a blank or broken page.
- What happens when a session expires mid-use? The next page load redirects them to login; any in-progress query is abandoned gracefully.
- What happens when a non-`@example.com` email is entered? The form rejects it with an inline error before any redirect to Okta occurs.
- What happens when the OIDC state nonce is missing or mismatched on callback? The request is rejected and the user is shown an error page.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST redirect all unauthenticated requests to the login screen before serving any content
- **FR-002**: The login screen MUST accept an email address and validate that it ends with `@example.com` before initiating any redirect
- **FR-003**: System MUST initiate an OIDC authorization code flow directed at Okta, using client credentials supplied via environment variables
- **FR-004**: System MUST include a `state` nonce in the Okta authorization redirect and validate it on the callback to prevent CSRF
- **FR-005**: System MUST exchange the authorization code for tokens at Okta's token endpoint and validate the returned ID token
- **FR-006**: System MUST establish a server-side session after successful authentication, recording the user's email and display name
- **FR-007**: System MUST display the authenticated user's identity (name or email) on the root MCP connections page
- **FR-008**: System MUST provide a logout action that clears the server-side session and redirects to the login screen
- **FR-009**: System MUST reject OIDC callbacks where the `state` parameter does not match the pending login record
- **FR-010**: Okta client ID, client secret, issuer URL, and redirect URI MUST be read from environment variables and never hardcoded
- **FR-011**: Sessions MUST expire after 8 hours, requiring re-authentication on the next request

### Key Entities

- **UserSession**: Represents an authenticated browser session. Attributes: opaque session token, user email, user display name, creation time, expiry time. Stored server-side; the browser holds only the opaque token via a secure cookie.
- **OidcPendingLogin**: Short-lived record of an in-progress login attempt. Attributes: state nonce, expiry timestamp. Consumed on callback and discarded afterwards.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every request from an unauthenticated user results in a redirect to the login screen — no protected content is ever served without a valid session
- **SC-002**: A user with an active Okta session completes the full login flow and lands on the root page in under 5 seconds (excluding Okta's own authentication page)
- **SC-003**: Emails not ending in `@example.com` are rejected with an inline error before any network request to Okta is made
- **SC-004**: A user can log out and verify their session is cleared within one click and one page load
- **SC-005**: Sessions automatically expire after 8 hours, and the next request after expiry redirects to login rather than serving content

## Assumptions

- Okta is already configured by the operator with a matching application, allowed redirect URI, and OIDC settings. This feature only covers the application side.
- Required environment variables: `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, `OKTA_ISSUER_URL`, `OKTA_REDIRECT_URI`.
- The email entered on the login screen is passed to Okta as a `login_hint` to pre-fill the Okta login form; Okta remains the authoritative authenticator.
- No role-based access control is needed at this stage — any authenticated `@example.com` user has full access to all pages.
- The existing Salesforce and Zoho OAuth callback endpoints are also protected once this feature is in place.
- Session cookies are `HttpOnly` and `Secure`; sessions are never stored in the browser's local storage.
