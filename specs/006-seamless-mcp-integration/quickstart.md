# Quickstart: Seamless.AI MCP Integration

## Operator setup

1. In Seamless (Settings > Public API Connections) make sure MCP access is enabled for the organisation and users are licensed (engagement tools need Connect).
2. Create/assign an Okta application for Seamless.AI and note its app instance id.
3. Set:
   - `SEAMLESS_REDIRECT_URI` — gateway callback, e.g. `http://localhost:9000/seamless/oauth/callback`
   - `SEAMLESS_OKTA_APP_ID` — Okta app instance id
   - `SEAMLESS_MCP_URL` — optional, defaults to `https://mcp.seamless.ai/mcp`
4. Run `mvn compile` then start the gateway (`mvn compile exec:java`).

## Smoke test

1. Open the dashboard, sign in with Okta, click **Connect** on the Seamless.AI card, sign in to Seamless.
2. Connect the gateway to an MCP client and run `tools/list` — expect `Seamless_*` tools (including `Seamless_read_resource`).
3. Ask: "How many Seamless credits do I have?" → `Seamless_get_credits` or `Seamless_read_resource seamless://credits`.
4. Ask: "Find 5 VPs of Engineering at fintech companies" → `Seamless_search_contacts`.
5. As a reader-only user, ask to create a list → refused. As a writer → succeeds.
6. Check the interactions page shows each call.

## Workflows to try

- **Prospect to meeting**: "Find <name> at <company>, research them, and draft an intro email."
- **Bulk enrich and campaign**: "Research these 10 contacts and put them in a new campaign with one email step."
- **Daily activity digest**: "Summarize yesterday's Seamless activity and post it to #sales." (needs Slack connected)
- **Job change trigger**: "Check whether <contact> changed jobs; if so research the new role and prepare outreach."

## Tests

- `mvn test` — entity and client unit tests.
- `mvn verify` — gateway integration tests (gating, risk mapping).
