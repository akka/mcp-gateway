# Feature Specification: Seamless.AI MCP Integration

**Feature Branch**: `006-seamless-mcp-integration`
**Created**: 2026-09-25
**Status**: Draft
**Input**: User description: "Add a component/class that connects to seamless mcp. It should support all tools and workflows mentioned in https://docs.seamless.ai/mcp-docs"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Use search, research and account tools via Seamless.AI (Priority: P1)

Any gateway user (there is no application-assignment gate for this system) sees Seamless.AI listed as available on the dashboard — no personal sign-in step required, since the gateway reaches Seamless.AI with a single API key an administrator configures once for the whole organisation. From their AI assistant they can then ask things like "Find VP of Engineering contacts at mid-size fintech companies", "Research this contact", or "How many Seamless credits do I have left?" and get answers through the same gateway they already use for other systems.

**Why this priority**: Without a working connection and the core prospecting tools (search, research, credits, my contacts/companies, lists, saved searches), nothing else is reachable. This is the smallest slice that delivers value.

**Independent Test**: As any authenticated test user, ask the assistant to search for contacts and report the credit balance. Verify results match what the operator's Seamless.AI account sees.

**Acceptance Scenarios**:

1. **Given** any authenticated gateway user, **When** they view the dashboard, **Then** Seamless.AI shows as available with no connect step needed.
2. **Given** an authenticated user, **When** they ask their assistant to search contacts or companies, **Then** results are returned and the action is attributed to that user in the gateway's own interaction history, even though Seamless.AI itself sees the organisation's single connection.
3. **Given** an authenticated user, **When** they request contact or company research (which consumes credits), **Then** the assistant can start the research, wait for it to finish, and return the enriched result.
4. **Given** an authenticated user, **When** they ask for the credit balance, saved contacts, saved companies, lists or saved searches, **Then** the current data is returned.
5. **Given** the operator has not yet configured the Seamless.AI API key, **When** any user tries to use a Seamless.AI tool, **Then** the request is refused with a message that the system isn't connected, not a personal-access message.

---

### User Story 2 - Outreach: campaigns, templates, email, calls and tasks (Priority: P2)

A sales user asks the assistant to build and run outreach: create a campaign with steps, add or remove contacts, draft and send emails (single or bulk) using saved templates and connected sender accounts, log calls with disposition and sentiment, and manage follow-up tasks.

**Why this priority**: These are the tools that turn prospecting into pipeline, but they depend on P1 (the working connection and saved contacts) and on the organisation's Seamless.AI engagement ("Connect") licence.

**Independent Test**: As a user with the engagement licence, create a template, create a campaign with one email step, add a saved contact, draft an email and log a call, then verify each item appears in Seamless.AI.

**Acceptance Scenarios**:

1. **Given** a licensed user, **When** they ask to create, update, clone or list campaigns and their steps and contacts, **Then** the change is made in Seamless.AI and confirmed.
2. **Given** a licensed user, **When** they ask to create, edit, list or delete email templates, **Then** the operation succeeds and personalization merge tags are available to use.
3. **Given** a licensed user with a connected sender account, **When** they ask to draft, revise, preview, send, or bulk-send email, **Then** the action is performed and the outcome (sent, failed, previewed) is reported.
4. **Given** a licensed user, **When** they log a call with a disposition and sentiment, or create, update, list, run actions on, or delete tasks, **Then** the record is saved and visible in Seamless.AI.
5. **Given** a user whose organisation lacks the engagement licence, **When** they use the assistant, **Then** those tools are not offered or are refused with a clear licence message.

---

### User Story 3 - Reference workflows work end to end (Priority: P3)

A user can hand the assistant any of the four documented Seamless.AI workflows and it completes them by chaining the available tools:

- **Prospect to meeting** — search, research, resolve the saved contact, then email the prospect.
- **Bulk enrich and campaign** — research many contacts, resolve their saved records, create a campaign with an email step.
- **Daily activity digest** — pull the activity feed, summarise it, and post the summary to Slack.
- **Job change trigger** — detect a job change, research the new role, then send outbound.

**Why this priority**: Workflows are compositions of the tools in P1 and P2; they add convenience and prove the tools work together, but need no new capability beyond them.

**Independent Test**: Run each workflow prompt against a test account and verify the expected end state (email sent, campaign created, digest posted, outbound sent).

**Acceptance Scenarios**:

1. **Given** a user whose organisation is licensed for Connect, **When** they run "prospect to meeting" for a named person, **Then** the person is found, researched, resolved to a saved contact, and an email is sent or drafted for approval.
2. **Given** a list of contacts, **When** they run "bulk enrich and campaign", **Then** all researched contacts are added to a newly created campaign with an email step.
3. **Given** the user has also connected Slack, **When** they run "daily activity digest", **Then** a summary of recent activity is posted to the chosen Slack channel.
4. **Given** the user has not connected Slack, **When** they run the digest workflow, **Then** the summary is returned in the conversation and the user is told Slack is not connected.
5. **Given** a contact has changed jobs, **When** the user runs "job change trigger", **Then** the new role is researched and outbound is prepared or sent for that contact.

---

### User Story 4 - Safe, audited use with read/write/destructive controls (Priority: P2)

An administrator wants confidence that Seamless.AI actions follow the same rules as every other system: readers can only read, writers can change data, and every action is logged. Deleting campaigns, lists, saved searches, templates or tasks is treated as high risk.

**Why this priority**: Outreach tools send real email and delete real data; they must not ship without permission enforcement and audit.

**Independent Test**: As a reader-only user, attempt a search (allowed) and an email send (refused). As a writer, delete a test list and confirm it appears in the interaction log.

**Acceptance Scenarios**:

1. **Given** a user with only read permission, **When** they call a tool that changes data, **Then** it is refused and nothing changes in Seamless.AI.
2. **Given** a user with write permission, **When** they call a create/update/send tool, **Then** it succeeds and is recorded in the interaction history.
3. **Given** any user, **When** a delete tool is called, **Then** it requires write permission and is clearly recorded as a deletion in the history.
4. **Given** a tool Seamless.AI adds in future, **When** its risk level is not known, **Then** it is treated as a write.

---

### Edge Cases

- The operator has not yet configured the Seamless.AI API key: all users see the system as unavailable/not connected, not a generic error.
- The configured Seamless.AI API key is revoked or expires: all users are told the system isn't connected until an operator updates it; other systems are unaffected.
- Seamless.AI has not enabled MCP access for the organisation: the user sees Seamless.AI's own "not enabled" message passed through clearly, not a generic failure.
- Organisation lacks the engagement licence: engagement tools are absent or refused with a licence message; search/research still work.
- Research consumes credits and the balance is insufficient: the user is told before or when it fails; no partial silent failure.
- Research is asynchronous and slow or never completes: the assistant reports a timeout with a way to check again later rather than hanging.
- Seamless.AI is unreachable or slow: a clear "temporarily unavailable" message is returned; other systems keep working.
- Seamless.AI adds or removes tools: the gateway reflects the current tool list without a redeploy.
- Rate limits from Seamless.AI: the user is told to retry later.
- A bulk send partially fails: the result lists which recipients succeeded and which failed.
- Two different gateway users both use Seamless.AI at the same time: both act through the same shared Seamless.AI identity, but the gateway's own interaction history still records which gateway user made each request.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The gateway MUST list Seamless.AI as a system on the dashboard, showing whether it is available (configured and reachable) to the current user.
- **FR-002**: Seamless.AI MUST authenticate with a single API key configured by an operator; the gateway MUST NOT require or offer a per-user Seamless.AI sign-in step.
- **FR-003**: Every Seamless.AI action MUST be attributed in the gateway's own interaction history to the individual gateway user who requested it, even though Seamless.AI itself sees one shared connection rather than one per user. This is a deliberate exception to the general per-person-connection pattern used by other systems, following the same shared-credential model already used for Okta admin lookups.
- **FR-004**: The gateway MUST make available every tool Seamless.AI exposes, including all documented domains: search, research and polling, user (credits, my contacts, my companies), lists, saved searches, campaigns and campaign steps/contacts, email templates, email accounts, drafts, send and bulk send, call logging with dispositions and sentiments, tasks, activity feed, and email footers/preview.
- **FR-005**: The gateway MUST make available the read-only reference data Seamless.AI provides (profile, credits, campaign details, templates, template variables, email accounts, engagement configuration) so the assistant can consult it before acting.
- **FR-006**: The four documented workflows (prospect to meeting, bulk enrich and campaign, daily activity digest, job change trigger) MUST be completable end to end using the available tools.
- **FR-007**: The daily activity digest MUST deliver to Slack when the user has Slack connected, and otherwise return the summary in the conversation.
- **FR-008**: Seamless.AI access MUST NOT be gated by application assignment; every authenticated gateway user MUST be able to see and use Seamless.AI tools, subject only to the reader/writer permission check in FR-009. (This is a deliberate exception, made explicitly by the project owner, to the application-gating pattern most other systems use.)
- **FR-009**: Tools that only read MUST require read permission; tools that create, modify, send or delete MUST require write permission; tools of unknown risk MUST be treated as writes.
- **FR-010**: Deletion tools MUST be distinguishable in the interaction history from ordinary writes.
- **FR-011**: Every Seamless.AI action MUST appear in the interaction history with the user, tool, request and time, consistent with other systems.
- **FR-012**: Asynchronous research MUST be handled so a user gets the finished result, or a clear pending/timeout message, without needing to manage polling themselves.
- **FR-013**: Licence and enablement errors from Seamless.AI (MCP not enabled, engagement licence missing, insufficient credits) MUST be shown to the user in plain language.
- **FR-014**: Failure or unavailability of Seamless.AI MUST NOT affect other connected systems.
- **FR-015**: The set of available tools MUST follow what Seamless.AI currently offers, without requiring code changes for new or removed tools.
- **FR-016**: Operators MUST be able to configure the Seamless.AI connection (server address, API key, application id) through the same environment-based settings pattern as other systems, and the README MUST document them.
- **FR-017**: Users MUST be told clearly, and told to contact an administrator, when the Seamless.AI API key is missing, invalid, or revoked.

### Key Entities

- **Seamless.AI connection**: The gateway's single, operator-configured link to Seamless.AI (an API key plus server address); shared by every gateway user, not one per person, and not gated by application assignment.
- **Contact / Company**: Prospect records found via search and enriched via research; may be "saved" to the user's account, at which point they have a stable saved identifier.
- **List / Saved search**: User-owned groupings of contacts and reusable search definitions.
- **Campaign / Campaign step**: An outreach sequence with ordered steps (e.g. email) and enrolled contacts.
- **Email template / Draft / Sender account**: Reusable message content with merge tags, unsent messages, and connected sending addresses.
- **Call log / Task**: Records of calls (with disposition and sentiment) and follow-up to-dos.
- **Activity feed item**: A recent engagement event used by the digest workflow.
- **Interaction record**: The gateway's audit entry for each Seamless.AI action.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Once an operator configures the Seamless.AI API key, any signed-in user can run their first search immediately — no per-user setup or application-assignment step.
- **SC-002**: 100% of tools and reference data documented by Seamless.AI for an account's licence level are usable through the gateway.
- **SC-003**: Each of the four reference workflows completes successfully on a test account in a single assistant conversation, without the user manually calling individual tools.
- **SC-004**: 100% of Seamless.AI actions taken through the gateway appear in the interaction history.
- **SC-005**: 0 write or delete actions succeed for users lacking write permission. (There is no application-assignment gate for Seamless.AI to measure separately — see FR-008.)
- **SC-006**: When Seamless.AI is unavailable, users receive an explanatory message within 30 seconds and requests to other systems are unaffected.
- **SC-007**: Enriched research results are returned to the user in under 2 minutes for a single contact, or a clear "still processing" message is given.

## Assumptions

- Seamless.AI is added as another system alongside the existing ones (Zoho Desk, Salesforce, Slack, etc.), reusing the gateway's existing permission and audit behaviour, but — by two explicit decisions made by the project owner — deviating from two patterns other systems follow: (1) it authenticates with a single operator-configured API key rather than a per-user sign-in flow, and (2) it has no Okta application-gate at all, unlike every other system (including the Okta admin lookups integration it otherwise mirrors), so it is available to any authenticated gateway user. Seamless.AI itself sees one organisation-wide connection; the gateway's own interaction history is what ties each action back to the individual user, and reader/writer permission is still enforced normally.
- Seamless.AI's read/write/destructive labels are the source of risk classification: read maps to reader permission; write and destructive both map to writer permission.
- Destructive tools require writer permission only; extra human confirmation is left to the user's assistant client, as Seamless.AI recommends.
- Engagement (Connect) licensing is enforced by Seamless.AI; the gateway passes through what the account may use and does not duplicate licence checks.
- Seamless.AI must have MCP access enabled for the organisation and the gateway registered as an authorised connection there; this is an operator prerequisite.
- The documented total is 54 tools across 11 domains plus 7 resources; the gateway exposes whatever Seamless.AI currently offers rather than a fixed hard-coded list.
- Workflows are compositions of tools driven by the user's assistant, not separately scheduled or unattended jobs.
- Slack posting in the digest workflow relies on the user's existing Slack connection in the gateway.
