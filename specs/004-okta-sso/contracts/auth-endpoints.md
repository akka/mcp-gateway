# API Contracts: Auth Endpoints

**Feature**: 004-okta-sso
**Date**: 2026-04-27

## Auth Endpoints (`/login`, `/auth/*`)

These endpoints are **not** protected by the session guard. All other endpoints require a valid `SESSION` cookie.

---

### GET /login

Serves the login page HTML.

**Response**: `200 OK` — `text/html` — login page with email form

---

### POST /auth/initiate

Validates the submitted email and starts the OIDC flow.

**Request body** (form or JSON):

| Field | Type | Required | Validation |
|---|---|---|---|
| `email` | `String` | Yes | Must end with `@example.com` |

**Responses**:

| Status | Condition |
|---|---|
| `302 Found` → Okta authorize URL | Email valid; `Location` header set to Okta with `state`, `login_hint`, `scope=openid profile email`, `response_type=code` |
| `400 Bad Request` | Email missing or does not end with `@example.com` |

**Side effect**: Creates `OidcPendingLoginEntity` keyed on the generated `state` nonce (TTL 10 min).

---

### GET /auth/callback

OIDC redirect target — called by Okta after the user authenticates.

**Query parameters**:

| Parameter | Type | Required |
|---|---|---|
| `code` | `String` | Yes — authorization code |
| `state` | `String` | Yes — must match a live `OidcPendingLoginEntity` |

**Responses**:

| Status | Condition |
|---|---|
| `302 Found` → `/` | Authentication succeeded; `Set-Cookie: SESSION=<uuid>; HttpOnly; SameSite=Lax; Path=/` |
| `400 Bad Request` | Missing `code` or `state` |
| `400 Bad Request` | `state` not found or expired |
| `502 Bad Gateway` | Token exchange with Okta failed |
| `502 Bad Gateway` | Userinfo call to Okta failed |

**Side effects**:
- Deletes `OidcPendingLoginEntity` (one-time use)
- Creates `UserSessionEntity` (8-hour TTL)

---

### GET /auth/logout

Clears the current session.

**Request**: Requires `SESSION` cookie (if absent, redirects to `/login` immediately).

**Responses**:

| Status | Condition |
|---|---|
| `302 Found` → `/login` | Session invalidated; `Set-Cookie: SESSION=; Max-Age=0; HttpOnly; Path=/` |

**Side effect**: Calls `UserSessionEntity.Invalidate()`.

---

## Session Cookie Protocol

| Attribute | Value |
|---|---|
| Name | `SESSION` |
| Value | Opaque UUID (entity ID of `UserSessionEntity`) |
| `HttpOnly` | Yes |
| `SameSite` | `Lax` |
| `Path` | `/` |
| `Secure` | Yes (omitted in local dev) |
| Max-Age | Not set (session cookie); server-side expiry enforced via entity |

---

## Protected Endpoint Auth Contract

Every endpoint **other than** `/login`, `/auth/initiate`, `/auth/callback` enforces the following:

1. Read `SESSION` cookie from the `Cookie` request header.
2. Look up `UserSessionEntity` by the cookie value.
3. If not found or `session.isExpired()` → respond `302 Found` → `/login`.
4. Otherwise, proceed with the request.

The authenticated user's `email` and `displayName` are available to any handler that needs them (e.g., for display on the root page).
