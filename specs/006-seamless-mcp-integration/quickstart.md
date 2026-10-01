# Quickstart: Seamless.AI MCP Integration

## Operator setup

1. In Seamless (Settings > Public API Connections) make sure MCP access is enabled for the organisation and the account is licensed for what you want exposed (engagement tools need Connect).
2. Create an API key: **Settings > Public API Connections > API Key** → **+ Create New Connection** → check the **MCP** scope → choose the **Group** with access → **Save Connection** → copy the key.
3. Set:
   - `SEAMLESS_API_KEY` — the API key copied above
   - `SEAMLESS_MCP_URL` — optional, defaults to `https://mcp.seamless.ai/mcp`
4. Run `mvn compile` then start the gateway (`mvn compile exec:java`).

There is no per-user connect step, and no Okta application to assign — once the API key is set, Seamless.AI tools are immediately available to every signed-in gateway user.

## Smoke test

1. Open the dashboard, sign in with Okta as any user (no particular application assignment needed). The Seamless.AI card should show "Available" with no connect button.
2. Connect the gateway to an MCP client and run `tools/list` — expect `Seamless_*` tools (including `Seamless_read_resource`).
3. Ask: "How many Seamless credits do I have?" → `Seamless_get_credits` or `Seamless_read_resource seamless://credits`.
4. Ask: "Find 5 VPs of Engineering at fintech companies" → `Seamless_search_contacts`.
5. As a reader-only user, ask to create a list → refused. As a writer → succeeds.
6. Check the interactions page shows each call attributed to the requesting user.
7. Unset `SEAMLESS_API_KEY` and confirm any user sees "Seamless.AI is not connected" rather than a raw error.

## Workflows to try

- **Prospect to meeting**: "Find <name> at <company>, research them, and draft an intro email."
- **Bulk enrich and campaign**: "Research these 10 contacts and put them in a new campaign with one email step."
- **Daily activity digest**: "Summarize yesterday's Seamless activity and post it to #sales." (needs Slack connected)
- **Job change trigger**: "Check whether <contact> changed jobs; if so research the new role and prepare outreach."

## Tests

- `mvn test` — client unit tests.
- `mvn verify` — gateway integration tests (no-app-gate access, risk mapping).
