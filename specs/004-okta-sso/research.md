# Research: Okta SSO Authentication

**Feature**: 004-okta-sso
**Date**: 2026-04-27

## Decision 1: Session Storage

**Decision**: Key Value Entity (`UserSessionEntity`) keyed by an opaque session token UUID.

**Rationale**: Aligns with Akka SDK First principle. No new dependencies. Server-side storage means sessions can be invalidated centrally. The entity ID is the session token sent to the browser as an `HttpOnly` cookie. Lookup by token is O(1) via entity routing.

**Alternatives considered**:
- In-memory map: lost on restart, not distributed-safe.
- JWT cookie: no server-side revocation, requires a JWT signature library.
- External store (Redis, DB): extra dependency, constitution violation.

---

## Decision 2: OIDC Token Validation Strategy

**Decision**: After the token exchange, call Okta's `/v1/userinfo` endpoint with the returned access token to fetch `email` and `name`. This implicitly validates the access token server-to-server.

**Rationale**: Avoids importing a JWT verification library. No JWKS key rotation to manage. The userinfo call is a standard OIDC step and Okta enforces all token validity there. Already using `java.net.http.HttpClient` and Jackson in this codebase — no new dependencies.

**Alternatives considered**:
- Verify JWT signature locally (JWKS): requires a JWT library (e.g., `nimbus-jose-jwt`) — new dependency, more complexity.
- Trust ID token claims without signature check: insecure.

---

## Decision 3: Auth Guard Pattern

**Decision**: Introduce `AbstractProtectedEndpoint extends AbstractHttpEndpoint` with a `requireSession()` helper. All four existing endpoints and the new `AuthEndpoint` extend this base class. Each handler calls `requireSession()` at entry and returns a redirect to `/login` if it returns null.

**Rationale**: Akka SDK HTTP endpoints have no filter/interceptor mechanism. A shared abstract base class is the only DRY option. It keeps domain-irrelevant auth wiring out of each handler body. `AbstractHttpEndpoint.requestContext()` is the correct way to read the incoming `Cookie` header.

**Alternatives considered**:
- Duplicate auth check in every handler: error-prone, not maintainable.
- AOP/bytecode interceptor: not available in Akka SDK context.
- A separate `AuthFilter` component: no such primitive in Akka SDK.

---

## Decision 4: OIDC Discovery

**Decision**: Fetch Okta's OIDC discovery document (`{OKTA_ISSUER_URL}/.well-known/openid-configuration`) once at startup in `AuthEndpoint` to obtain `authorization_endpoint`, `token_endpoint`, and `userinfo_endpoint`. Cache the result as instance fields.

**Rationale**: Standard and issuer-agnostic. Works regardless of whether the customer uses Okta's default or a custom authorization server. Fetching once at startup avoids per-request overhead.

**Alternatives considered**:
- Hardcode Okta URL patterns (`{issuer}/v1/authorize` etc.): brittle, breaks for custom auth servers.
- Fetch on every request: unnecessary latency, not idiomatic.

---

## Decision 5: Cookie Handling

**Decision**: Session token sent as `Set-Cookie: SESSION=<token>; HttpOnly; SameSite=Lax; Path=/` on login completion. Read back via the `Cookie` request header in `requestContext()`. Cleared on logout via `Set-Cookie: SESSION=; Max-Age=0; HttpOnly; Path=/`.

**Rationale**: `HttpOnly` prevents JavaScript access (XSS mitigation). `SameSite=Lax` prevents CSRF on cross-site navigations while allowing top-level GET redirects (the Okta callback). `Secure` flag added when not in dev mode. No `Secure` enforced in local dev to avoid TLS setup friction.

**Alternatives considered**:
- `SameSite=Strict`: would break the Okta redirect-back callback (a cross-site top-level GET), causing the cookie to be dropped.
- `SameSite=None`: requires `Secure`, too permissive.

---

## Decision 6: Login Page UX

**Decision**: A dedicated `login.html` static resource served at `GET /login`. Contains an email input field. Client-side JavaScript validates the `@example.com` domain with an inline error before submitting. On submit the form POSTs to `POST /auth/initiate` which creates the pending state and issues the Okta redirect server-side.

**Rationale**: Client-side domain validation satisfies SC-003 (no network request to Okta for invalid domains). Server-side re-validation of the domain in `POST /auth/initiate` ensures the constraint cannot be bypassed by direct API calls.

**Alternatives considered**:
- Inline login form on the root page: mixes concerns; the root page already carries significant UI logic.
- Single-page redirect (no email form): loses `login_hint` capability and removes the domain gate.

---

## Environment Variables Required

| Variable | Purpose |
|---|---|
| `OKTA_CLIENT_ID` | OAuth2 client identifier registered in Okta |
| `OKTA_CLIENT_SECRET` | OAuth2 client secret for the token exchange |
| `OKTA_ISSUER_URL` | Base URL of the Okta authorization server (e.g. `https://dev-123.okta.com/oauth2/default`) |
| `OKTA_REDIRECT_URI` | Callback URL registered in Okta (e.g. `https://app.example.com/auth/callback`) |
