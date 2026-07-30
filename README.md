# MCP Gateway

One place to connect your AI assistant to the tools Akka runs on.

## What it is

The MCP Gateway is a single, company-managed connection point between AI assistants — Claude Code, Claude Desktop, and other MCP clients — and the business systems we use every day, such as Zoho Desk, Salesforce, Google Drive, Reo, and Groundcover.

Instead of setting up and maintaining a separate connection for every system, you connect your assistant to the gateway once. From then on, the assistant can work with whichever systems you have access to, and you can ask questions in plain language: *"What support tickets came in today?"*, *"Find the contract we signed with this customer."*

## Why it exists

- **One connection instead of many.** Add the gateway once and every connected system becomes available to your assistant.
- **You sign in as yourself.** Access goes through your Okta account, so the assistant only ever sees what you are already allowed to see.
- **Nothing to install or configure.** No API keys to copy around, no credentials stored on your laptop.
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

### 3. Start asking

Your assistant now has access to the connected systems. Simply ask for what you need — the gateway takes care of reaching the right system and returning the answer.

## Connecting a system

Some systems need to be linked to your account before they can be used. Open the gateway in your browser, sign in, and you will see a dashboard listing every available system along with its current status. Choose **Connect** next to any system you want to use, sign in to that system once, and it stays connected from then on.

## Supported systems

Each system below can be connected to the gateway. The environment variables are only relevant to whoever operates the gateway — as an everyday user you never need to set them.

Two variables are shared by every system and must always be set: `ANTHROPIC_API_KEY`, and the Okta sign-in settings (`OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET`, `OKTA_ISSUER_URL`, `OKTA_REDIRECT_URI`, `MCP_BASE_URL`).

Access control is also configured through the environment — none of these have built-in defaults, so the gateway fails closed until you set them:
- `OKTA_ALLOWED_EMAIL_DOMAIN` — the email domain users must sign in with (e.g. `example.com`). Leave unset to allow any Okta-authenticated email.
- `OKTA_GROUP_READER`, `OKTA_GROUP_WRITER`, `OKTA_GROUP_ADMIN`, `OKTA_GROUP_ESCALATER` — the Okta group names that grant each gateway role (see [Access and permissions](#access-and-permissions)). A role with no group set is granted to nobody.

Each system is also gated by an Okta application, identified by its app instance id in `<SYSTEM>_OKTA_APP_ID` (listed per system below). Leaving one unset disables app-gating for that system (it appears accessible to everyone who can otherwise reach it).

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

**Google Drive** — documents and files.
- `GOOGLE_DRIVE_MCP_URL`
- `GOOGLE_DRIVE_CLIENT_ID`
- `GOOGLE_DRIVE_CLIENT_SECRET`
- `GOOGLE_DRIVE_REDIRECT_URI`
- `GOOGLE_DRIVE_OKTA_APP_ID` — the shared Google app instance id (Drive, Gmail, and Calendar all use it).

**Gmail** — mail search and threads.
- `GMAIL_CLIENT_ID`
- `GMAIL_CLIENT_SECRET`
- `GMAIL_REDIRECT_URI`
- `GMAIL_MCP_URL` *(optional — defaults to Google's hosted server)*
- `GMAIL_OKTA_APP_ID` *(optional — defaults to `GOOGLE_DRIVE_OKTA_APP_ID`)*

**Google Calendar** — events and schedules.
- `GOOGLE_CALENDAR_CLIENT_ID`
- `GOOGLE_CALENDAR_CLIENT_SECRET`
- `GOOGLE_CALENDAR_REDIRECT_URI`
- `GOOGLE_CALENDAR_MCP_URL` *(optional — defaults to Google's hosted server)*
- `GOOGLE_CALENDAR_OKTA_APP_ID` *(optional — defaults to `GOOGLE_DRIVE_OKTA_APP_ID`)*

**Slack** — channels and messages.
- `SLACK_MCP_URL`
- `SLACK_CLIENT_ID`
- `SLACK_CLIENT_SECRET`
- `SLACK_REDIRECT_URI`
- `SLACK_OKTA_APP_ID`

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

The Okta admin lookups (account-status page) use their own system: `OKTA_ADMIN_MCP_URL` and `OKTA_ADMIN_OKTA_APP_ID`.

A few settings are optional across the board: `OKTA_JWKS_URI` overrides where Okta sign-in keys are fetched from, and `MCP_PROXY_OKTA_API_TOKEN` enables the Okta account-status page.

## Seeing what happened

The gateway keeps a full history of the actions taken on your behalf. From the dashboard you can review which systems were used, what was requested, and when. If something looks wrong, you can flag it there.

## Access and permissions

Your access is decided by two separate things in Okta: the **groups** you belong to, and the **applications** assigned to you. Groups control *what kind of actions* you may take; applications control *which systems* you are meant to reach. Both are managed by IT — sign in and open the permissions page to see where you currently stand.

### Groups decide what you can do

The group names below are the conventional ones; the operator maps them to Okta groups via `OKTA_GROUP_READER`, `OKTA_GROUP_WRITER`, and `OKTA_GROUP_ADMIN` (see [Supported systems](#supported-systems)), so a deployment can use whatever group names it likes.

| Group | What it grants |
|---|---|
| `mcp-gateway-reader` | Run tools that only read data — searching tickets, looking up records, reading files. |
| `mcp-gateway-writer` | Run tools that change data — creating a ticket, sending a reply, updating a record. |
| `mcp-gateway-admin` | View the full interaction log for every user, flag entries for escalation, and open the Okta account-status page. |

Reader and writer are independent, not tiered. If you only hold `mcp-gateway-reader`, a request to create or update something is refused. Holding neither means you can sign in and see your own permissions page, but every tool call is refused.

One important detail: if the gateway cannot tell whether a tool reads or writes, it treats that tool as a **write**. This is deliberate — it fails safe. In practice it means a reader may occasionally be refused a tool that looks harmless, usually right after the gateway restarts and before it has re-learned the tool list.

### Applications decide which systems you see

Every system is tied to an Okta application. Being assigned that application is what puts the system in the **accessible** list on your dashboard; without it the system appears as unavailable and you should ask IT to assign it to you.

| System | Okta application |
|---|---|
| Zoho Desk | Zoho Desk |
| Salesforce | Salesforce |
| Google Drive, Gmail, Google Calendar | a single shared Google application |
| Slack | Slack |
| HubSpot | HubSpot |
| Reo | Reo |
| Groundcover | Groundcover |
| Okta admin lookups | Okta MCP Admin |

Because Drive, Gmail, and Calendar share one Google application, being assigned it makes all three appear at once — they cannot currently be granted separately.

### Connecting is still per-person

Group membership and app assignment establish that you are *allowed* to use a system. They do not connect it. Each user still signs in to each system once through the dashboard, and the gateway acts strictly as that person — never with a shared or elevated account. So a colleague who can reach Salesforce sees only the Salesforce records their own Salesforce login permits.

### A gap worth knowing about

Today the Okta application assignment is enforced on the dashboard, but **not** on the MCP path itself. A user who has `mcp-gateway-reader` or `mcp-gateway-writer`, and who has personally connected a system, can call that system's tools through their assistant even if the matching Okta application was never assigned to them. The system's own permissions still apply, and every call is logged, so this is a matter of the gateway not enforcing an intended boundary rather than data being exposed to someone the underlying system would refuse. If you rely on app assignment as a hard boundary, treat it as advisory until this is closed.

## Questions or problems

If a system will not connect, an answer looks wrong, or you think you should have access to something you do not, reach out to the team that maintains the gateway. Mentioning what you asked for and roughly when helps them find the matching entry in the log.
