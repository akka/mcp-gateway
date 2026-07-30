# Contract: Salesforce Endpoint

**Feature**: 001-salesforce-mcp-agent  
**Component**: `SalesforceEndpoint`  
**Base path**: `/salesforce`

## POST /salesforce/query

Forward a query to Salesforce via the MCP agent and return the result.

### Request

```
POST /salesforce/query
Content-Type: application/json
```

**Body**:
```json
{
  "query": "How many open opportunities are in the pipeline?"
}
```

| Field   | Type   | Required | Description                          |
|---------|--------|----------|--------------------------------------|
| `query` | string | Yes      | The question to forward to Salesforce |

### Response — 200 OK

```json
{
  "answer": "There are 42 open opportunities in the pipeline with a total value of $3.2M."
}
```

| Field    | Type   | Description                      |
|----------|--------|----------------------------------|
| `answer` | string | The Salesforce agent's response  |

### Response — 400 Bad Request

Returned when `query` is missing or blank.

```json
"Query must not be empty"
```

### Response — 500 Internal Server Error

Returned when the Salesforce MCP server is unreachable or returns an error.

```json
"Failed to reach Salesforce: <error detail>"
```

## Access Control

- `@Acl(allow = @Acl.Matcher(principal = Acl.Principals.INTERNET))` — publicly accessible.
- Adjust to `service = *` to restrict to internal service calls only.
