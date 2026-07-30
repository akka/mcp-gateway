# Tasks: Okta SSO Authentication

**Input**: Design documents from `/specs/004-okta-sso/`
**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- **[Story]**: Which user story the task belongs to (US1/US2/US3)

---

## Phase 1: Setup

**Purpose**: Wire environment configuration so the service starts with Okta credentials present.

- [X] T001 Add Okta env var stubs (`OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, `OKTA_ISSUER_URL`, `OKTA_REDIRECT_URI`) to `src/main/resources/application.conf`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain records and the shared auth base class that every user story depends on. No user story work can begin until this phase is complete.

- [X] T002 [P] Create `src/main/java/com/example/domain/UserSession.java` — immutable record with `email`, `displayName`, `createdAt`, `expiresAt`; methods `isExpired()` and static `empty()`
- [X] T003 [P] Create `src/main/java/com/example/domain/OidcPendingLogin.java` — immutable record with `loginHint`, `expiresAt`; methods `isExpired()` and static `empty()`
- [X] T004 Create `src/main/java/com/example/api/AbstractProtectedEndpoint.java` — abstract class extending `AbstractHttpEndpoint`; constructor accepts `ComponentClient`; provides `requireSession()` (reads `SESSION` cookie via `requestContext()`, looks up `UserSessionEntity`, returns null if missing or expired) and `redirectToLogin()` helper
- [X] T005 [P] Create `src/main/resources/static-resources/login.html` — email input form styled to match `index.html`; client-side JS validates `@example.com` domain with inline error before allowing submit; form POSTs to `/auth/initiate`

**Checkpoint**: Domain records and shared auth infrastructure ready — user story implementation can begin.

---

## Phase 3: US1 + US2 — OIDC Login Flow (Priority: P1) 🎯 MVP

**Goal**: Users can log in via Okta SSO. Unauthenticated requests to any page redirect to `/login`. A successful Okta callback creates a server-side session.

**Independent Test**: Visit `/` without a session → redirected to `/login`. Enter a `@example.com` email → redirected to Okta. After Okta auth, land back on `/` with a `SESSION` cookie set. Enter a non-`@example.com` email → inline error, no Okta redirect. Tamper the `state` on the callback → 400 response.

- [X] T006 [US1] Create `src/main/java/com/example/application/OidcPendingLoginEntity.java` — Key Value Entity (state = `OidcPendingLogin`); commands: `create(Create)` → `Effect<Done>`, `get()` → `ReadOnlyEffect<OidcPendingLogin>`, `delete()` → `Effect<Done>`; `emptyState()` returns `OidcPendingLogin.empty()`
- [X] T007 [US1] Create `src/main/java/com/example/application/UserSessionEntity.java` — Key Value Entity (state = `UserSession`); commands: `create(CreateCommand)` → `Effect<Done>`, `getSession()` → `ReadOnlyEffect<UserSession>`, `invalidate()` → `Effect<Done>`; `emptyState()` returns `UserSession.empty()`
- [X] T008 [US1] Create `src/main/java/com/example/api/AuthEndpoint.java` — extends `AbstractProtectedEndpoint`, `@HttpEndpoint("/")`, `@Acl(INTERNET)`:
  - Constructor fetches `{OKTA_ISSUER_URL}/.well-known/openid-configuration` and caches `authorizationEndpoint`, `tokenEndpoint`, `userinfoEndpoint`
  - `GET /login` → serve `login.html` (no auth check)
  - `POST /auth/initiate` → validate email ends with `@example.com` (400 if not); generate `state` UUID; create `OidcPendingLoginEntity[state]` with `loginHint=email`, `expiresAt=now+10min`; redirect 302 to Okta authorize URL with `response_type=code`, `client_id`, `redirect_uri`, `scope=openid profile email`, `state`, `login_hint`
  - `GET /auth/callback` → validate `code`+`state` params (400 if missing); look up `OidcPendingLoginEntity[state]` (400 if absent or expired); delete pending entity; POST to token endpoint with `grant_type=authorization_code`, `code`, `client_id`, `client_secret`, `redirect_uri` (502 on failure); call userinfo endpoint with `Authorization: Bearer {access_token}` to get `email`+`name` (502 on failure); generate `sessionToken` UUID; create `UserSessionEntity[sessionToken]` with `expiresAt=now+8h`; respond 302 to `/` with `Set-Cookie: SESSION={sessionToken}; HttpOnly; SameSite=Lax; Path=/`
  - `GET /auth/logout` → read `SESSION` cookie; if present call `UserSessionEntity[token].invalidate()`; respond 302 to `/login` with `Set-Cookie: SESSION=; Max-Age=0; HttpOnly; Path=/`
- [X] T009 [P] [US1] Modify `src/main/java/com/example/api/OAuthEndpoint.java` — change `extends AbstractHttpEndpoint` to `extends AbstractProtectedEndpoint`; update constructor to call `super(componentClient)`; add `var session = requireSession(); if (session == null) return redirectToLogin();` at the top of every handler except `/login`, `/auth/*`
- [X] T010 [P] [US1] Modify `src/main/java/com/example/api/ZohoDeskEndpoint.java` — extend `AbstractProtectedEndpoint`; add `requireSession()` guard at the top of `query()`
- [X] T011 [P] [US1] Modify `src/main/java/com/example/api/SalesforceEndpoint.java` — extend `AbstractProtectedEndpoint`; add `requireSession()` guard at the top of each handler
- [X] T012 [P] [US1] Modify `src/main/java/com/example/api/GenericMcpEndpoint.java` — extend `AbstractProtectedEndpoint`; add `requireSession()` guard at the top of each handler

**Checkpoint**: End-to-end login flow works. All pages redirect to `/login` when unauthenticated. Okta callback creates a session. Invalid state rejected with 400.

---

## Phase 4: US3 — Login Status on Root Page (Priority: P2)

**Goal**: Authenticated users see their name/email and a Logout button on the root MCP connections page.

**Independent Test**: Log in via Okta → visit `/` → page header shows authenticated email or display name → click Logout → redirected to `/login` → root page no longer accessible without re-authenticating.

- [X] T013 [US3] Add `GET /auth/me` to `src/main/java/com/example/api/AuthEndpoint.java` — requires session; returns `record MeResponse(String email, String displayName)` with current session values
- [X] T014 [US3] Update `src/main/resources/static-resources/index.html` — on page load, fetch `/auth/me` and render email/displayName in a header bar; add a **Logout** button linking to `GET /auth/logout`; if `/auth/me` returns non-200 redirect to `/login`

**Checkpoint**: Root page shows the logged-in user identity and a working logout button.

---

## Final Phase: Polish & Cross-Cutting Concerns

- [X] T015 [P] Create `src/test/java/com/example/application/UserSessionEntityTest.java` — unit tests using `KeyValueEntityTestKit`: `create` stores email/displayName/expiry; `getSession` returns stored state; `invalidate` clears state; expired session (`isExpired()` returns true for past `expiresAt`)
- [X] T016 [P] Create `src/test/java/com/example/application/OidcPendingLoginEntityTest.java` — unit tests: `create` stores loginHint/expiry; `get` returns state; `delete` clears state; expired pending login detected via `isExpired()`
- [ ] T017 Update `README.md` — add Okta env vars table (`OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, `OKTA_ISSUER_URL`, `OKTA_REDIRECT_URI`), note that all endpoints are now protected, and describe the login URL

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — **blocks all user story phases**
- **Phase 3 (US1+US2)**: Depends on Phase 2 — T006 and T007 before T008; T008 before T009–T012
- **Phase 4 (US3)**: Depends on Phase 3 complete (needs `UserSessionEntity` and auth guard in place)
- **Final Phase**: Depends on all desired user story phases complete

### Within Phase 3

```
T006 (OidcPendingLoginEntity) ─┐
                                ├─► T008 (AuthEndpoint) ─► T009, T010, T011, T012 (parallel)
T007 (UserSessionEntity) ──────┘
```

### Parallel Opportunities

```bash
# Phase 2 — run in parallel:
Task T002: UserSession.java
Task T003: OidcPendingLogin.java
Task T005: login.html

# Phase 3 — after T008 completes, run in parallel:
Task T009: OAuthEndpoint auth guard
Task T010: ZohoDeskEndpoint auth guard
Task T011: SalesforceEndpoint auth guard
Task T012: GenericMcpEndpoint auth guard

# Final Phase — run in parallel:
Task T015: UserSessionEntityTest.java
Task T016: OidcPendingLoginEntityTest.java
```

---

## Implementation Strategy

### MVP (Phase 1 + 2 + 3 only — 12 tasks)

1. Phase 1: Configure env vars
2. Phase 2: Domain records + `AbstractProtectedEndpoint` + `login.html`
3. Phase 3: Both entities → `AuthEndpoint` → apply auth guards to all four existing endpoints
4. **Validate**: End-to-end login flow works, all pages protected

### Incremental Delivery

1. Phases 1–3 → **MVP**: SSO login protecting all pages
2. Phase 4 → user identity visible on root page + logout button
3. Final Phase → unit tests + README

### Total: 17 tasks across 5 phases
