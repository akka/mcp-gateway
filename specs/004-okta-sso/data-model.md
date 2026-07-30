# Data Model: Okta SSO Authentication

**Feature**: 004-okta-sso
**Date**: 2026-04-27

## Entities

### UserSession (Key Value Entity)

**Entity ID**: opaque UUID session token (also the cookie value)
**Package**: `com.example.domain.UserSession` (state record), `com.example.application.UserSessionEntity`

| Field | Type | Notes |
|---|---|---|
| `email` | `String` | Authenticated user's email (from Okta userinfo) |
| `displayName` | `String` | Full name from Okta userinfo `name` claim |
| `createdAt` | `Instant` | When the session was established |
| `expiresAt` | `Instant` | `createdAt + 8h` |

**Domain methods**:
- `isExpired()` → `boolean` — true when `Instant.now().isAfter(expiresAt)`
- `empty()` → static factory for the null/absent state

**Commands**:
- `CreateCommand(email, displayName, expiresAt)` → `Effect<String>` (returns session token / entity ID)
- `GetSession()` (no params, read-only) → `ReadOnlyEffect<UserSession>`
- `Invalidate()` (no params) → `Effect<Done>` (deletes entity state)

---

### OidcPendingLogin (Key Value Entity)

**Entity ID**: state nonce UUID (sent as `state` parameter to Okta)
**Package**: `com.example.domain.OidcPendingLogin` (state record), `com.example.application.OidcPendingLoginEntity`

| Field | Type | Notes |
|---|---|---|
| `loginHint` | `String` | Email entered on login screen (for context / audit) |
| `expiresAt` | `Instant` | `Instant.now() + 10 minutes` — matches FR-004 short-lived nonce |

**Domain methods**:
- `isExpired()` → `boolean`
- `empty()` → static factory

**Commands**:
- `Create(loginHint, expiresAt)` → `Effect<Done>`
- `Consume()` (no params, read-only validate + delete) → `ReadOnlyEffect<OidcPendingLogin>` — caller deletes after validating
- `Delete()` → `Effect<Done>` — called after successful callback to prevent replay

---

## Relationships

```
Browser Cookie (SESSION=<uuid>)
        │
        ▼
UserSessionEntity[<uuid>]
        │ email, displayName, expiresAt
        │
        └──► Protected endpoints check this before serving content

POST /auth/initiate
        │ creates nonce UUID
        ▼
OidcPendingLoginEntity[<nonce>]
        │
        └──► GET /auth/callback validates & deletes this
```

---

## Session Lifecycle

```
[Unauthenticated] ──► GET /login ──► POST /auth/initiate
                                             │
                                    Create OidcPendingLogin
                                    Redirect to Okta
                                             │
                                    Okta authenticates user
                                             │
                                    GET /auth/callback
                                    Validate state nonce
                                    Exchange code for tokens
                                    Call Okta userinfo
                                    Create UserSession
                                    Set SESSION cookie
                                             │
                                    [Authenticated] ──► GET /
                                             │
                              ... (8-hour session) ...
                                             │
                               Session.isExpired() == true
                                             │
                              Next request ──► redirect to /login
                                    (or user clicks Logout)
                                    Invalidate UserSession
                                    Clear SESSION cookie
                                             │
                                    [Unauthenticated]
```

---

## Auth Guard Flow (per request)

```
Incoming HTTP request
        │
        ├── Is path /login, /auth/initiate, /auth/callback, or static asset?
        │       └── YES → pass through (no auth check)
        │
        └── NO → read SESSION cookie from Cookie header
                       │
                       ├── Cookie missing → redirect 302 /login
                       │
                       └── Cookie present → look up UserSessionEntity
                                   │
                                   ├── Not found or expired → redirect 302 /login
                                   │
                                   └── Valid session → proceed, inject session into handler
```
