# Feature Specification: Zoho Desk Agent

**Feature Branch**: `003-zoho-desk-agent`
**Created**: 2026-04-21
**Status**: Draft
**Input**: User description: "write a new agent that integrates with Zoho Desk MCP server for ticket operations"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query Ticket Information via Natural Language (Priority: P1)

A user submits a natural language question about support tickets — such as "How many open tickets do we have?" or "Show me all high-priority tickets assigned to me" — and the service returns a clear, accurate answer sourced from Zoho Desk.

**Why this priority**: The ability to query ticket data is the foundational capability that delivers immediate value. It is the simplest independently testable slice.

**Independent Test**: Send `POST /desk/query` with `{"query": "How many open tickets are there?"}` and verify a meaningful answer is returned.

**Acceptance Scenarios**:

1. **Given** the service is connected to Zoho Desk, **When** a user submits a natural language query about tickets, **Then** the service returns a factual, human-readable answer.
2. **Given** the service connection fails, **When** a user submits a query, **Then** the service returns a clear error message — no silent failure.
3. **Given** an empty query is submitted, **When** the request is received, **Then** the service returns a 400 error indicating the query must not be empty.

---

### User Story 2 - Perform Ticket Operations via Natural Language (Priority: P2)

A user asks the service to take an action on tickets — such as "Close ticket #12345", "Assign ticket #6789 to Alice", or "Reply to ticket #1111 with: Thank you, we are looking into it" — and the service executes that action in Zoho Desk and confirms success.

**Why this priority**: Read-only querying delivers limited value in a support workflow. Allowing operations through the same interface makes the agent genuinely useful for support teams.

**Independent Test**: Submit a query to update a known test ticket's status. Verify the ticket changes in Zoho Desk and the response confirms the action was taken.

**Acceptance Scenarios**:

1. **Given** a valid ticket exists, **When** a user submits a natural language instruction to update it, **Then** the service performs the update in Zoho Desk and returns a confirmation.
2. **Given** the requested ticket does not exist, **When** the user submits an operation instruction, **Then** the service returns a clear message stating the ticket was not found.
3. **Given** the user submits an ambiguous or unsupported operation, **When** the request is received, **Then** the service responds with a helpful message clarifying what it can and cannot do.

---

### User Story 3 - Retrieve Ticket Metrics and Summaries (Priority: P3)

A support manager asks for a summary or report — such as "Summarise the 5 oldest open tickets" or "What is the average resolution time this week?" — and the service returns a concise, structured answer.

**Why this priority**: Summaries compound on basic querying, enabling managers to make decisions without opening the Zoho Desk dashboard.

**Independent Test**: Send a query requesting a ticket summary. Verify the response contains a structured answer with multiple ticket details or an aggregated metric.

**Acceptance Scenarios**:

1. **Given** tickets exist in Zoho Desk, **When** a user requests a summary, **Then** the service returns a structured summary with relevant details.
2. **Given** no tickets match the criteria, **When** the query is received, **Then** the service responds with a clear message indicating no matching tickets were found.

---

### Edge Cases

- What happens if the Zoho Desk MCP server is temporarily unavailable?
- What if the query is valid but returns an empty result set?
- What if the natural language instruction is potentially destructive (e.g. "Delete all tickets")?
- What happens if the response from Zoho Desk is very large?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST expose an HTTP endpoint that accepts natural language queries about Zoho Desk tickets.
- **FR-002**: The service MUST return a clear, human-readable response for each valid query.
- **FR-003**: The service MUST reject empty or blank queries with an appropriate error response.
- **FR-004**: The service MUST support read operations — querying ticket status, assignments, priorities, and counts.
- **FR-005**: The service MUST support write operations — updating ticket status, assignments, and adding replies.
- **FR-006**: The service MUST return a clear error message when the Zoho Desk connection is unavailable or an operation fails.
- **FR-007**: The service MUST NOT silently swallow errors — every failure must produce a user-visible response.

### Assumptions

- The Zoho Desk MCP server URL and any required authentication credentials are supplied via environment variables at deployment time.
- The scope of supported operations is determined by what the Zoho Desk MCP server exposes. The agent passes natural language to the MCP server and returns its response — no direct Zoho Desk API calls are made.
- A single shared connection is used for the entire service (no per-user authentication).
- The agent follows the same pattern as the existing Salesforce agent in this service.

### Key Entities

- **DeskQuery**: A user-submitted natural language request. Attributes: query text.
- **DeskAnswer**: The natural language response returned to the user. Attributes: answer text.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can submit a natural language query and receive a meaningful Zoho Desk answer within 30 seconds for 95% of requests.
- **SC-002**: 100% of empty or malformed queries receive a clear 400 error — no silent failures.
- **SC-003**: 100% of connection failures produce a user-visible error message.
- **SC-004**: Both read and write ticket operations are supported on day one.
- **SC-005**: The endpoint behaviour is consistent with the existing query endpoint pattern in this service, requiring no additional learning for current users.
