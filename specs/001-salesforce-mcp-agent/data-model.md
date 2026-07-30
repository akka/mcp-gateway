# Data Model: Salesforce MCP Agent

**Date**: 2026-04-20  
**Feature**: 001-salesforce-mcp-agent

## Overview

This feature is stateless — there are no entities, events, or persistent state. The data model covers only the request/response types that cross the API boundary.

## API Types (defined in `api/SalesforceEndpoint.java`)

### QueryRequest

| Field   | Type   | Constraints         | Description                                      |
|---------|--------|---------------------|--------------------------------------------------|
| `query` | String | Required, non-blank | The question or query to forward to Salesforce   |

### QueryResponse

| Field    | Type   | Description                                      |
|----------|--------|--------------------------------------------------|
| `answer` | String | The response returned by the Salesforce MCP agent |

## Component State

None. Both `SalesforceAgent` and `SalesforceEndpoint` are stateless. No entities, views, or persistent storage are involved.

## Configuration (not data model, but referenced)

| Config Key             | Source          | Description                              |
|------------------------|-----------------|------------------------------------------|
| `SALESFORCE_MCP_URL`   | Environment var | Base URL of the Salesforce MCP server    |
| `SALESFORCE_MCP_TOKEN` | Environment var | OAuth2 bearer token for MCP server auth  |
