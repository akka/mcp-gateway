# AI coding assistant guidelines

This file provides guidance to AI coding assistant when working with code in this repository.

## Project overview

See @README.md for a project overview, and how to build, test and run the application.

## Coding guidelines

Use the detailed instructions in @AGENTS.md when writing Akka code.

Use the guidelines in @akka-context/sdk/ai-coding-assistant-guidelines.html.md when writing code in this project.

## Code style

- Write no comment unless the code is not obvious. Prefer a regression test, or naming and structure that show what the code does.
- A comment must be terse and describe the code as it is now. Never narrate how it got there, what it used to do, or why a past decision beat an alternative. That belongs in git history and commit messages. No references to "this PR", "this fix", a prior bug, an issue number or an earlier version.
- Extract a small, single-responsibility method whose name documents intent, within reason.
- Do not use `null` or an empty collection as an implicit sentinel for a meaningful special case ("no filter", "use the default", "lookup failed"). Use a named constant, enum or small result type so the case cannot be confused with "absent".
- Do not use em dashes in code, comments, docs, commit messages or PR text.
- Never reach for `synchronized` or `java.util.concurrent.locks.*` first. State belongs in an Akka component. A lock-free workaround is a narrow exception for plain adapter code that wraps a live non-Akka object.

## Reviewing a change

Check every PR against these, and say so explicitly when one does not apply.

**Constitution** (`.akka/constitution/akka-sdk-constitution.md`)
- Akka SDK first: new components and inter-service calls use SDK primitives. A new dependency, or a raw HTTP client where the SDK offers one, needs a stated reason.
- Single responsibility and domain independence: do not grow an endpoint into a second client for an external system. Put it beside the existing client for that system and share its timeouts, retries and error handling.
- Test coverage: every behavioral change has tests named for the behavior, including the failure and "not configured" branches.
- Simplicity: no abstraction for a hypothetical future need.

**Tool list invariant**
- `tools/list` returns every tool, whatever the caller's role, connection state or the write policy, because MCP clients cache it at connect time and ignore `list_changed`. Only Okta app assignments may shape it.
- A change that can alter apps on token issue or refresh, including a failed Okta lookup, can shrink the list for the rest of a client session. It needs a test that `tools/list` stays complete.
- Group and policy changes must be enforced in `tools/call` and never in `tools/list`.

**Security**
- Fail closed, and keep a failure distinguishable from "none". Check what a failure costs the user and how long it lasts.
- Never log tokens or secrets. Never send an Okta or upstream token to a host other than the configured one, and do not follow redirects with credentials.
- Every outbound call has a connect and request timeout. Do the lookups before revoking or rotating a credential, so a slow or failed lookup cannot strand the client.
- Refresh and token endpoints must not widen access on failure.

**Public repo**
- This repo is public. Issues, PRs and comments must not contain customer names or details, or content quoted from private repos.

**Verification**
- CI runs the integration tests with `mvn verify`. While developing, run a single class with `mvn verify -Dit.test=<Class>`.
- Run `mvn checkstyle:check`.

## Akka documentation

You find the reference documentation of Akka in the akka-context directory and sub-directories.
Read this documentation to answer questions about Akka.

## Incremental Generation Workflow

**Work step-by-step with user approval between major phases. Keep explanations brief - developers prefer concise communication.**

**CRITICAL: After completing each step below, you MUST STOP and WAIT for explicit user approval before proceeding to the next step. When you ask "Ready for X?", you are NOT allowed to continue until the user responds. NEVER create code for the next step until the user says "yes", "proceed", or similar.**

Create one component and a corresponding test at a time, with user feedback in between. If there are several components involved in the task some steps below should be repeated for each component. For example: create an entity, create unit test for the entity, create view, create test for the view, create endpoint for the entity and view, create integration test for the endpoint.

1. **Always wait** for user approval between major steps - this is MANDATORY
2. **If user says "proceed"/"yes","y"**, continue to next step
3. **If user provides feedback**, adjust and re-present
4. **If user says "skip X"**, skip that step
5. **Keep focused** - don't jump ahead, don't create multiple steps at once
6. **Brief explanations** - what you did, not verbose details

This approach enables early validation, catches issues before coding, and allows mid-course adjustments.

### Step 0: Documentation Check (complex components only)

For first-time or complex components:
1. Read relevant `akka-context/sdk/*.html.md`
2. Extract code examples, verify imports/patterns
3. Brief note: "📚 Reviewed {doc}"

### Step 1: Design & Planning

Present concise design:
```markdown
## Proposed Design

### Components
- Entity: CreditCardEntity (Event Sourced - need audit trail)
- View: CreditCardsByCardholderView
- Endpoint: CreditCardEndpoint

### Domain Model
**CreditCard (state):** cardId, cardNumber, cardholderName, creditLimit, currentBalance, active

**Events:** CardActivated, CardCharged, PaymentMade, CardBlocked

**Commands:** activate, charge, makePayment, block

### Integration
- View consumes: CardActivated, CardCharged, PaymentMade, CardBlocked
- Endpoint exposes: POST /cards/{id}/activate, POST /cards/{id}/charge, GET /cards/by-cardholder/{name}

Does this design look good? Should I proceed?
```

**STOP and wait for user approval**

### Step 2: Domain Layer

Create domain classes. Verify with `mvn compile`.

Report briefly:
```markdown
Created domain layer:
- domain/CreditCard.java (4 events)
- domain/CreditCardEvent.java

Ready for Entity?
```

**STOP and wait for user approval**

### Step 3: Application Layer

Create one component at a time, with user feedback in between.

Create application components. Verify with `mvn compile`.

Report briefly:
```markdown
Created application layer entity:
- application/CreditCardEntity.java (5 command handlers)

Please review.

Ready for tests?
```

**STOP and wait for user approval**

### Step 4: Tests

Create one test at a time, with user feedback in between.

Create tests for the component in the previous step. Verify with `mvn test` or with `mvn verify` for IntegrationTest.

Report briefly:
```markdown
Created tests:
- CreditCardEntityTest.java (8 test cases)

Please review.

Ready for API layer?
```

**STOP and wait for user approval**

### Step 5: API Layer

Create/update endpoint. Verify with `mvn compile`.

Report briefly:
```markdown
Updated API layer:
- api/CreditCardEndpoint.java
  - POST /cards/{id}/activate
  - POST /cards/{id}/charge
  - GET /cards/by-cardholder/{name}

Please review.

Ready for integration tests?
```

**STOP and wait for user approval**

### Step 6: API Layer integration tests

Create tests for the endpoint in the previous step. Verify with `mvn verify`.

Report briefly:
```markdown
Created tests:
- CreditCardEndpointIntegrationTest.java (6 test cases)

Please review.

Do you also want me to update the readme and include example curl commands of the endpoint?
```

**STOP and wait for user approval**

### Step 7: Documentation

```markdown
Updated README.md with curl examples.

Done! Created 2 domain classes, 1 entity, 1 view, 3 tests, 1 endpoint.
```
