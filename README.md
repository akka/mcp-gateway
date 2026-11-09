# MCP Gateway

One place to connect your AI assistant to the tools Akka runs on.

## What it is

The MCP Gateway is a single, company-managed connection point between AI assistants — Claude Code, Claude Desktop, and other MCP clients — and the business systems we use every day, such as Zoho Desk, Salesforce, Google Workspace, Reo, and Groundcover.

Instead of setting up and maintaining a separate connection for every system, you connect your assistant to the gateway once. From then on, the assistant can work with whichever systems you have access to, and you can ask questions in plain language: *"What support tickets came in today?"*, *"Find the contract we signed with this customer."*

## Why it exists

- **One connection instead of many.** Add the gateway once and every connected system becomes available to your assistant.
- **You sign in as yourself.** Access goes through your Okta account, so the assistant only ever sees what you are already allowed to see.
- **Nothing to install or configure.** No API keys to copy around, no credentials stored on your laptop (a token connector is the exception: you paste a token the system issues to you).
- **Everything is recorded.** Each action an assistant takes through the gateway is logged, so there is always a clear record of what was accessed and when.

## Getting started

### 1. Add the gateway to your assistant

If you use Claude Code, run:

```
claude mcp add akka-mcp-gateway --transport http https://<gateway-url>/mcp
```

When running the gateway on your own machine, use `http://localhost:9000/mcp` instead.

### 2. Sign in

The first time your assistant contacts the gateway, a browser window opens and asks you to sign in with your Okta account. Approve the request, and the assistant is connected. You will not need to do this again until your session expires.

If you are already signed in to Okta in that browser, the gateway signs you in without asking. **Sign out** on the dashboard ends only your gateway session and leaves you signed in to Okta, so your other Okta apps keep working. It also means the next visit, or **Sign in with Okta** on the signed-out page, signs that browser straight back in as the same Okta user. To switch to another account, or before leaving a shared computer, sign out of Okta as well.

### 3. Start asking

Your assistant now has access to the connected systems. Simply ask for what you need — the gateway takes care of reaching the right system and returning the answer.

## Connecting a system

Some systems need to be linked to your account before they can be used. Open the gateway in your browser, sign in, and you will see a dashboard listing every available system along with its current status. Choose **Connect** next to any system you want to use, sign in to that system once (for a token connector, paste a token instead), and it stays connected from then on.

## Supported systems

Each system below can be connected to the gateway. The environment variables are only relevant to whoever operates the gateway — as an everyday user you never need to set them.

Two variables are shared by every system and must always be set: `ANTHROPIC_API_KEY`, and the Okta sign-in settings (`OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, `OKTA_ISSUER_URL`, `OKTA_REDIRECT_URI`, `MCP_BASE_URL`).

Access control is also configured through the environment — none of these have built-in defaults, so the gateway fails closed until you set them:
- `OKTA_ALLOWED_EMAIL_DOMAIN` — the email domain users must sign in with (e.g. `example.com`). Leave unset to allow any Okta-authenticated email.
- `OKTA_GROUP_READER`, `OKTA_GROUP_WRITER`, `OKTA_GROUP_ADMIN`, `OKTA_GROUP_ESCALATER` — the Okta group names that grant each gateway role (see [Access and permissions](#access-and-permissions)). A role with no group set is granted to nobody.
- `MCP_OAUTH_REDIRECT_HOST_ALLOWLIST` — comma-separated hostnames allowed for `https://` `redirect_uris` when an MCP client dynamically registers itself. Defaults to `claude.ai,claude.com`, so Claude's hosted clients work out of the box; setting it replaces the default (list every host you want, including those two if you still need them). Loopback redirect URIs (`127.0.0.1`, `localhost`, `[::1]`) are always allowed, per RFC 8252. Set it to an empty value to reject every https redirect host, so only loopback clients can register.

Each system is also gated by an Okta application, identified by its app instance id in `<SYSTEM>_OKTA_APP_ID` (listed per system below). Leaving one unset disables app-gating for that system (it appears accessible to everyone who can otherwise reach it), except for a token connector, which stays off without it.

Optional support-contact settings surface in the "request an integration" help text: `SUPPORT_EMAIL` and `SUPPORT_SLACK_CHANNEL`. When unset, users are pointed at "your gateway administrator" generically.

**Zoho Desk** — support tickets and customer conversations.
- `ZOHO_MCP_URL`
- `ZOHO_REDIRECT_URI`
- `ZOHO_OKTA_APP_ID`

**Salesforce** — accounts, opportunities, and CRM records.
- `SALESFORCE_MCP_URL`
- `SALESFORCE_CLIENT_ID`
- `SALESFORCE_CLIENT_SECRET`
- `SALESFORCE_REDIRECT_URI`
- `SALESFORCE_OAUTH_LOGIN_URL` — your Salesforce org login host (e.g. `https://your-org.my.salesforce.com`); the OAuth token/authorize endpoints derive from it.
- `SALESFORCE_OKTA_APP_ID`
- `AKKA_SALESFORCE_MCP_URL` *(optional — our own MCP server complementing Salesforce with additional tools, e.g. downloading Opportunity attachments; reuses the Salesforce connection above)*
- `AKKA_SALESFORCE_OKTA_APP_ID` *(optional — defaults to `SALESFORCE_OKTA_APP_ID`)*

**Google Workspace** — Drive, Docs, Sheets, Gmail, and Calendar behind one OAuth grant.

One OAuth client covers all five Google products, so files created via Drive are reachable
from Docs and vice-versa (the `drive.file` allowlist is keyed to the OAuth client-id — see
[Google's Workspace MCP guide](https://developers.google.com/workspace/guides/configure-mcp-servers)).
Configure a single OAuth application in Google Cloud with the union of Drive/Docs/Sheets/Gmail/Calendar
scopes, enable each product's MCP server in the Cloud project as that guide describes, and point every
user at the "Google Workspace" card on the dashboard.

- `GOOGLE_WORKSPACE_CLIENT_ID`
- `GOOGLE_WORKSPACE_CLIENT_SECRET`
- `GOOGLE_WORKSPACE_REDIRECT_URI`
- `GOOGLE_WORKSPACE_OKTA_APP_ID` *(optional — defaults to `GOOGLE_DRIVE_OKTA_APP_ID`)*
- `GOOGLE_WORKSPACE_DRIVE_MCP_URL` *(optional — defaults to `https://drivemcp.googleapis.com/mcp/v1`)*
- `GOOGLE_WORKSPACE_DOCS_MCP_URL` *(optional — defaults to `https://docsmcp.googleapis.com/mcp/v1`)*
- `GOOGLE_WORKSPACE_SHEETS_MCP_URL` *(optional — defaults to `https://sheetsmcp.googleapis.com/mcp/v1`)*
- `GOOGLE_WORKSPACE_GMAIL_MCP_URL` *(optional — defaults to `https://gmailmcp.googleapis.com/mcp/v1`)*
- `GOOGLE_WORKSPACE_CALENDAR_MCP_URL` *(optional — defaults to `https://calendarmcp.googleapis.com/mcp/v1`)*

The deprecated Drive, Gmail, and Calendar connectors refuse new connections (`/connect` returns 409);
existing connections keep working until disconnected. Google Workspace likewise refuses to connect
while any of them is still connected, so each user ends up with a single Google connection.

Docs and Sheets use the `documents` and `spreadsheets` scopes, which reach every document and spreadsheet
the user can edit, not only files the gateway created. With writes enabled for Google Workspace (see
[Systems decide whether writes are possible](#systems-decide-whether-writes-are-possible)), a user in the
writer group lets their assistant change any of them. Drive is narrower: it reads any file but writes only
files the gateway created or the user opened with it.

Sheets needs the `spreadsheets` scope, which Google Workspace connections made before Sheets support
were not granted. Sheets tools are listed for those users, but Google refuses their calls until they
disconnect and connect Google Workspace again; their other Google tools keep working meanwhile. Once the
gateway has recorded the scopes of such a connection, at its next token refresh, Sheets is reported as not
connected for that user.

**Google Drive** *(deprecated — use Google Workspace)* — retained so existing operators keep
working; new deployments should not set these.
- `GOOGLE_DRIVE_MCP_URL`
- `GOOGLE_DRIVE_CLIENT_ID`
- `GOOGLE_DRIVE_CLIENT_SECRET`
- `GOOGLE_DRIVE_REDIRECT_URI`
- `GOOGLE_DRIVE_OKTA_APP_ID` — the shared Google app instance id (Drive, Gmail, Calendar all use it).

**Gmail** *(deprecated — use Google Workspace)* — retained for existing operators.
- `GMAIL_CLIENT_ID`
- `GMAIL_CLIENT_SECRET`
- `GMAIL_REDIRECT_URI`
- `GMAIL_MCP_URL` *(optional — defaults to Google's hosted server)*
- `GMAIL_OKTA_APP_ID` *(optional — defaults to `GOOGLE_DRIVE_OKTA_APP_ID`)*

**Google Calendar** *(deprecated — use Google Workspace)* — retained for existing operators.
- `GOOGLE_CALENDAR_CLIENT_ID`
- `GOOGLE_CALENDAR_CLIENT_SECRET`
- `GOOGLE_CALENDAR_REDIRECT_URI`
- `GOOGLE_CALENDAR_MCP_URL` *(optional — defaults to Google's hosted server)*
- `GOOGLE_CALENDAR_OKTA_APP_ID` *(optional — defaults to `GOOGLE_DRIVE_OKTA_APP_ID`)*

**Slack** — channels and messages. Posts start with 🤖 so readers can tell an assistant wrote them, unless the user explicitly asks the assistant to leave it off. Channels shared outside the organisation (Slack Connect, or named `external-...`) are never posted to, with or without the marker: the assistant saves the text as a draft in the user's Slack for a person to review and send, and when the gateway cannot tell whether a channel is shared, nothing is posted.

The assistant can also edit a message the user posted (`slack_update_message`), for example to fix formatting. Slack only allows editing the user's own messages. The new text replaces the whole message and starts with 🤖 again under the same rule as a post. Messages in channels shared outside the organisation are never edited; the assistant gives the user the new text to edit the message themselves. Editing uses the same write gate and `chat:write` scope as posting, so anyone who can post can also edit without reconnecting.
- `SLACK_MCP_URL`
- `SLACK_CLIENT_ID`
- `SLACK_CLIENT_SECRET`
- `SLACK_REDIRECT_URI`
- `SLACK_OKTA_APP_ID`

Saving a Slack draft (`slack_draft_message`) uses Slack's hosted MCP server, so the Slack app needs **Slack Model Context Protocol (MCP) Server** turned on under *Agents* in its settings.

**HubSpot** — marketing and CRM data.
- `HUBSPOT_CLIENT_ID`
- `HUBSPOT_CLIENT_SECRET`
- `HUBSPOT_REDIRECT_URI`
- `HUBSPOT_MCP_URL` *(optional — defaults to HubSpot's hosted server)*
- `HUBSPOT_OKTA_APP_ID`

**Reo** — product and visitor analytics.
- `REO_MCP_URL`
- `REO_REDIRECT_URI`
- `REO_OKTA_APP_ID`

**Groundcover** — logs, traces, and monitoring.
- `GROUNDCOVER_MCP_URL`
- `GROUNDCOVER_REDIRECT_URI`
- `GROUNDCOVER_OKTA_APP_ID`

**Seamless.AI** — contact and company search, enrichment, and outreach. Access to this is enabled via OKTA.
- `SEAMLESS_API_KEY`
- `SEAMLESS_MCP_URL` *(optional — defaults to Seamless's hosted server)*
- `SEAMLESS_OKTA_APP_ID`

**Token connectors**: any MCP server whose users paste a token it issues to them instead of signing in. The operator defines them in `TOKEN_MCPS`; see [Token connectors](#token-connectors).
- `TOKEN_MCPS` *(a JSON list, one entry per connector; defaults to none)*

The Okta admin lookups (account-status page) use their own system: `OKTA_ADMIN_MCP_URL` and `OKTA_ADMIN_OKTA_APP_ID`.

`OKTA_JWKS_URI` is optional and overrides where Okta sign-in keys are fetched from.

`MCP_PROXY_OKTA_API_TOKEN` is an Okta API token that the gateway uses to look up each user's assigned applications and groups, and for the Okta account-status page. Treat it as required: without it no user is seen as having any application, so every app-gated system is unavailable. The admin role behind the token must be able to read users, groups and applications. If Okta refuses the groups or applications lookup, the gateway logs an error that names this token.

## Seeing what happened

The gateway keeps a full history of the actions taken on your behalf. From the dashboard you can review which systems were used, what was requested, and when. If something looks wrong, you can flag it there. The exception to "what was requested" is a token connector that does not keep call content (the default): its entries show the tool and the time but not the arguments or the results.

## Access and permissions

Your access is decided by two separate things in Okta: the **groups** you belong to, and the **applications** assigned to you. Groups control *what kind of actions* you may take; applications control *which systems* you are meant to reach. Both are managed by IT — sign in and open the permissions page to see where you currently stand.

### Groups decide what you can do

The group names below are the conventional ones; the operator maps them to Okta groups via `OKTA_GROUP_READER`, `OKTA_GROUP_WRITER`, and `OKTA_GROUP_ADMIN` (see [Supported systems](#supported-systems)), so a deployment can use whatever group names it likes.

| Group | What it grants |
|---|---|
| `mcp-gateway-reader` | Run tools that only read data — searching tickets, looking up records, reading files. |
| `mcp-gateway-writer` | Run tools that change data (posting a message, creating a calendar event, drafting an email) on the systems where writes are enabled (see [Systems decide whether writes are possible](#systems-decide-whether-writes-are-possible)). |
| `mcp-gateway-admin` | View the full interaction log for every user, flag entries for escalation, and open the Okta account-status page. |

Reader and writer are independent, not tiered. If you only hold `mcp-gateway-reader`, a request to create or update something is refused. Holding neither means you can sign in and see your own permissions page, but every tool call is refused.

One important detail: if the gateway cannot tell whether a tool reads or writes, it treats that tool as a **write**. This is deliberate — it fails safe. In practice it means a reader may occasionally be refused a tool that looks harmless, usually right after the gateway restarts and before it has re-learned the tool list.

Your groups are re-read from Okta every time your assistant renews its connection, which is roughly hourly, so a role IT removes stops working without you having to do anything. If that check, or the applications check below, cannot complete (Okta is down, rate limiting us, or refusing our token), you are treated as holding no groups at all. This too fails safe: a revoked role cannot stay alive through an outage. Your tool calls are refused until a renewal reaches Okta again, and the gateway asks your assistant to renew within about a minute, so this usually clears on its own. Your assistant stays connected the whole time and its tool list does not change.

### Applications decide which systems you see

Every system is tied to an Okta application (a token connector to the one in its `okta-app-id`), and the tools page lists only the systems whose application you have. Being assigned that application is what puts the system in the **accessible** list on your dashboard; without it the system appears as unavailable and you should ask IT to assign it to you.

| System | Okta application |
|---|---|
| Zoho Desk | Zoho Desk |
| Salesforce | Salesforce |
| Google Workspace (Drive, Docs, Sheets, Gmail, Calendar) | a single shared Google application |
| Slack | Slack |
| HubSpot | HubSpot |
| Reo | Reo |
| Groundcover | Groundcover |
| Seamless.AI | Seamless.AI |
| Okta admin lookups | Okta MCP Admin |

An application counts as assigned when it is active and assigned to you, whether or not its icon is shown on your Okta dashboard. That lets an application exist only to give the gateway access to a system.

Application assignments are re-read on renewal too. If that check cannot complete, you keep the applications you had, so the tool list stays complete, but you hold no groups until a renewal succeeds, so every tool call is refused in the meantime.

Being assigned the Google application enables the whole Google Workspace card at once — Drive, Docs, Sheets, Gmail, and Calendar cannot currently be granted separately.

### Systems decide whether writes are possible

Being in the writer group is necessary but not sufficient. A system must also have writes enabled; every other system is read-only for everyone, writers included. A write tool on a read-only system is refused with a message that says so, but it still appears in the tool list (see [Every tool is always listed](#every-tool-is-always-listed)). The dashboard marks each system **Read-only** or **Read** and **Write** for you specifically, so a reader never sees a **Write** badge.

Admins choose the enabled systems on the **Write access** page (admin menu on the dashboard). A change is applied by the gateway immediately: disabling a system stops writes on it for everyone, and enabling one lets users in the writer group use it straight away. It is recorded in the Interactions log as a `policy-change` entry with who changed what, so it can be reviewed and flagged like any other entry. Nothing is enabled until an admin saves a selection, so a new deployment is read-only everywhere. Writes enabled on a system that is configured but off are kept when the page is saved, though the page does not show that system, and they apply again when it is switched on. If the saved selection cannot be read, the gateway refuses all writes.

Enabling a system is a deliberate decision: review the OAuth scopes users grant and the tools the downstream server exposes first. Every system asks each user for the same permissions whatever their role, so enabling one needs no reconnect, and the write gate is what stops a reader writing. Turning writes off does not revoke tokens people already hold, it only stops the gateway using them.

### Every tool is always listed

The tool list does not depend on your role, on which systems have writes enabled, or, once anyone has connected a system, on whether you have. A tool you cannot use yet is still listed, and calling it returns an error saying what is wrong: not connected, read-only through the gateway, or write access not permitted (or, for a tool your assistant listed before the operator turned the system off, not set up on this gateway).

This is deliberate. Assistants fetch the tool list once, when they connect, and the main ones ignore "tool list changed" notifications, so a list that varied would stay stale until the assistant was restarted. What shapes it is your Okta application assignments, read at sign-in and on each renewal (a renewal that cannot reach Okta leaves them as they were), and whether the operator has set up a token connector on this gateway. For a system nobody has connected yet there is nothing to list, so its tools appear after the first connection (restart the assistant, or have it call `howto_refresh_tools`).

`AkkaMcpGatewayWriteGateIntegrationTest` pins this behaviour, and `OktaAppsRefreshIntegrationTest` pins it across a renewal that cannot reach Okta.

### Connecting is still per-person

Group membership and app assignment establish that you are *allowed* to use a system. They do not connect it. Each user still signs in to each system once through the dashboard, and the gateway acts strictly as that person, never with a shared or elevated account. So a colleague who can reach Salesforce sees only the Salesforce records their own Salesforce login permits. A token connector connects with a token you paste instead of a sign-in, and it is still your own: the system that issued it decides what that token may see.

### Token connectors

A token connector fronts an MCP server whose users each have a token the server issues to them. Each connector is one entry in `TOKEN_MCPS`, a JSON list. It holds operator-specific URLs and Okta application ids, so set it from a secret:

```json
[{"id": "billing", "name": "Billing", "tool-prefix": "Billing_",
  "mcp-url": "https://billing.example.com", "okta-app-id": "0oa...",
  "connect-hint": "Open your Billing profile and create an access key."}]
```

| Setting | Meaning |
|---|---|
| `id` | Up to 40 lowercase letters, digits and hyphens, starting with a letter; not `proxy`, `howto` or starting with `google-workspace`. Names the connector, its dashboard card and its how-to tool (`howto_connect_<id>` with hyphens turned into underscores). |
| `name` | Shown to users: 1 to 60 characters, no control characters, not starting with `Google Workspace`, and not the name of another connector or a built-in system (compared ignoring case). |
| `tool-prefix` | Up to 31 letters and digits ending in `_`, starting with a letter and not with `howto`. The gateway lists the server's tools under it. It cannot repeat another connector's prefix, and a prefix that a built-in system already handles is refused. A prefix that only starts a built-in's longer prefix is accepted, but a tool name that a built-in system answers to is never listed by a connector. |
| `mcp-url` | The server's base URL, to which the gateway adds `/mcp` (trailing slashes are dropped): `https`, or `http` for loopback only, with no credentials, query or fragment. Blank leaves the connector off, as does anything else that does not fit. |
| `okta-app-id` | The Okta application that gates access. Blank leaves the connector off. |
| `record-tool-content` | Optional. `true` keeps tool arguments and results in the history. Defaults to `false`, so the history shows only who called which tool, when and whether it succeeded. |
| `connect-hint` | Optional, 1 to 300 characters. One line telling users how to get a token; without it they are told to get an access token from the service. Every signed-in user can read it, so keep it free of anything sensitive. |

`id`, `name`, `tool-prefix`, `mcp-url` and `okta-app-id` are required keys (the last two may be blank); any other key is refused.

Routes, all for a signed-in user (without a session `status` and `test` redirect to sign in, and the others answer `401`): `GET /token-mcp/connectors` lists the connectors that are set up; `GET /token-mcp/{id}/status`, `POST /token-mcp/{id}/connect` (a JSON body with `token`), `POST /token-mcp/{id}/disconnect` and `GET /token-mcp/{id}/test` act on one. An unknown id is `404`. `connect` answers `400` for a token that is malformed, refused or lists no tools, `502` when the server cannot be reached or fails, `503` when the connector is off or the token cannot be saved, and `403` to a user without its Okta application or from another origin; `test` answers `503` and `403` likewise. `status` answers `connected: false` for a user without the application and `503` when the connection cannot be checked, and `disconnect` succeeds for any known id. Connect and test are not rate limited.

- **Your token, your access.** The gateway checks a pasted token with the server before keeping it (printable ASCII with no spaces, up to 4,096 characters, and a leading `Bearer ` is dropped) and forwards it as `Authorization: Bearer`. The server decides what that person may see on every call, on top of the gateway's Okta application, role and write checks. The gateway cannot tell whose token a person pasted, so the server attributes calls to the token's owner.
- **A refused token.** Only a `401` from the server is read as a refused token: on a tool call it disconnects that user, who connects again with a new token, and on connect it answers `400`. Any other failure, a `403` included, reads as the server being unavailable or the token not being checkable.
- **Invalid entries.** An entry that is not valid, or whose id, name or tool prefix is already used by another connector or a built-in system, is skipped and logged. A `TOKEN_MCPS` value that is not valid JSON, has text after the list or repeats a key in an entry sets up no connector at all.
- **Browser requests.** Connect and disconnect refuse a browser request whose `Origin` is not the gateway's own address (`MCP_BASE_URL`).
- **One tool list.** A server can list different tools for different tokens, so the gateway only adds to its cached tool list for a connector (up to 500 tools) and lists that whole cache to everyone with its Okta application. The cache grows when someone connects, tests the connection, or lists tools while connected and nothing is cached; a tool the server removes stays listed. A tool is left out if its name with the connector's prefix is not 1 to 128 letters, digits, `_`, `.` or `-`, or if its definition is over 8,000 characters, and a listing is cut at 500 tools. A tool that does not say it only reads is treated as a write, and a tool once seen as a write stays one. Do not front a server whose tool listings a user can influence or that carries per-user values, because the cache is shared.
- **Changing a connector.** The cache and the stored tokens are kept per `id`. To point a connector at a different server or change its `tool-prefix`, give it a new `id` and a new `tool-prefix`, so that tokens issued by the old server are never sent to the new one and the old cached tools never match it. Tokens are kept in the connection entity until the user disconnects or the server refuses them; removing a connector from `TOKEN_MCPS` leaves them stored, so revoke them at the server.

## Development

### Code style

Each Maven project (the root gateway and every `mcp-servers/*` sub-project) is linted with Checkstyle, using the shared ruleset at `checkstyle/checkstyle.xml`. It's bound to the `validate` phase, so `mvn compile` or `mvn test` runs it automatically and fails the build on a violation; run `mvn checkstyle:check` on its own to just lint. CI runs it for all four projects on every PR.

The ruleset matches this codebase's existing conventions rather than a stock preset — it skips checks like mandatory braces or `ConstantName` that would fight the deliberate single-line getters/validation and lowercase static-final service handles (`log`, `okta`, etc.) already in use. It only covers `src/main/java`; test sources are excluded because this repo's test method names use the widely-accepted `methodUnderTest_condition_expectedResult` underscore convention, which Checkstyle's naming checks can't express.

## Questions or problems

If a system will not connect, an answer looks wrong, or you think you should have access to something you do not, reach out to the team that maintains the gateway. Mentioning what you asked for and roughly when helps them find the matching entry in the log.
