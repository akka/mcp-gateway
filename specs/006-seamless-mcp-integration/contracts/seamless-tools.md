# Contract: Tools exposed through the gateway

All upstream tools are exposed with prefix `Seamless_` (e.g. `Seamless_search_contacts`). The list comes from Seamless at runtime; the table shows the documented catalog at time of writing (54 tools documented; ~50 named in the pages reviewed).

| Domain | Tools | Access (upstream licence) |
|---|---|---|
| Search | `search_contacts`, `search_companies` | All |
| Research | `research_contacts`, `research_companies`, `poll_contact_research`, `poll_company_research` | All |
| User | `get_credits`, `get_my_contacts`, `get_my_companies` | All |
| Lists | `get_lists`, `create_list`, `update_list`, `delete_list` | All |
| Saved searches | `list_saved_searches`, `create_saved_search`, `update_saved_search`, `delete_saved_search` | All |
| Campaigns | `list_campaigns`, `create_campaign`, `update_campaign`, `clone_campaign`, `delete_campaign`, `list_campaign_steps`, `create_campaign_step`, `list_campaign_contacts`, `add_contacts_to_campaign`, `remove_contacts_from_campaign` | Connect (full) |
| Templates | `list_templates`, `create_template`, `update_template`, `delete_template` | Connect |
| Email | `list_email_accounts`, `create_email_draft`, `get_email_draft`, `update_email_draft`, `send_email_draft`, `send_email`, `send_bulk_email`, `send_email_preview`, `list_email_footers` | Connect |
| Calls | `log_call`, `list_call_dispositions`, `list_call_sentiments` | Connect |
| Tasks | `list_tasks`, `create_task`, `update_task`, `delete_task`, `execute_task_action` | Connect (full) |
| Activity | `get_activity_feed` | Connect |

## Added by the gateway

### `Seamless_read_resource` (read)

Reads a Seamless read-only resource.

Input:
```json
{ "uri": "seamless://credits" }
```
`uri` MUST start with `seamless://`; anything else is rejected with an error result and no upstream call.

Supported URIs (documented): `seamless://me`, `seamless://credits`, `seamless://campaigns/{campaignId}`, `seamless://templates`, `seamless://templates/variables`, `seamless://email-accounts`, `seamless://connect/config`.

Output: resource text content (JSON) as tool result text; `isError=true` on upstream failure.

## Classification

- Upstream `readOnlyHint=true` → reader permission.
- `readOnlyHint=false` (write and destructive) → writer permission.
- No hint → write (fail-safe).
- Annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`, `title`) forwarded unchanged to clients.
