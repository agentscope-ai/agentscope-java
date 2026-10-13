---
title: Web Tools
description: The built-in web_fetch and web_search tools, choosing a search provider, and how
  failures degrade
zh_link: /v2/zh/docs/harness/web-tools
---

## What they do

HarnessAgent registers two web tools by default:

- **`web_fetch`** — retrieves a URL and returns its text, truncated to a caller-controlled `max_chars`.
- **`web_search`** — searches the web and returns titles, URLs and snippets.

Both are read-only, so [Plan Mode](/v2/en/docs/harness/plan-mode) and the permission system allow
them during a read-only phase.

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("researcher")
        .model(model)
        .workspace(workspace)
        .build();
```

`disableWebTools()` suppresses both tools. The setting propagates to local subagents, so a child
is never handed a search tool the parent turned off.

## Search providers

`web_search` is backed by [Tavily](https://tavily.com/) by default and reads `TAVILY_API_KEY` from
the environment at call time.

`.parallelWebSearch()` swaps in [Parallel's Search MCP](https://docs.parallel.ai/integrations/mcp/search-mcp)
instead. Harness connects to `https://search.parallel.ai/mcp` over Streamable HTTP during `build()`
and registers the server's `web_search` under the same model-facing name:

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("researcher")
        .model(model)
        .workspace(workspace)
        .parallelWebSearch()
        .build();
```

Queries go to Parallel's **anonymous, rate-limited** endpoint — no account or API key is required,
and no credentials are sent. Requests carry an `agentscope-java/<version>` `User-Agent` for
aggregate usage measurement and nothing that identifies a user. Search inputs and any supplied
metadata reach Parallel; see their [terms](https://parallel.ai/customer-terms) and
[privacy policy](https://parallel.ai/privacy-policy).

The two providers take different arguments. Parallel's schema requires both `objective` and
`search_queries`, where Tavily takes `query` and an optional `max_results`:

```json
{
  "objective": "Find the official AgentScope Java documentation",
  "search_queries": ["AgentScope Java documentation"]
}
```

`web_fetch` is unaffected by the choice.

## Failure behaviour on the Parallel path

The option **fails open**. An outage, a 429, or a provider-side rename degrades search rather than
breaking the agent, which matters most in `agentscope-distribution`, where an agent is
re-materialised per session and a failed connection would otherwise surface as session-start
failures.

Two things can go wrong, and both end the same way — a warning in the log and the built-in Tavily
`web_search` registered instead, so `build()` always returns an agent that has a search tool:

| Failure | What Harness does |
| --- | --- |
| `initialize` / `tools/list` fails (outage, rate limit) | Logs a warning, falls back to Tavily |
| Handshake succeeds but no `web_search` is advertised | Logs a warning, falls back to Tavily |

Note that the fallback needs `TAVILY_API_KEY` to actually run a search, so an agent that opted into
Parallel precisely because it is keyless should treat the warning as actionable.

Both round trips are bounded by explicit timeouts instead of the MCP client defaults (30s
initialization / 120s request), so an unresponsive endpoint cannot stall `build()` for tens of
seconds:

| Timeout | Value |
| --- | --- |
| Initialization | 10s |
| Request | 30s |

## Read-only semantics

An MCP tool normally inherits its read-only flag from the server's `annotations.readOnlyHint`. For
this built-in registration Harness **pins** `web_search` read-only regardless of what the endpoint
reports, so it behaves exactly like the Tavily tool it replaces: Plan Mode keeps allowing search
during the read-only phase, and the permission engine does not start prompting for it if Parallel
ever drops the annotation.

## Custom HTTP client

`webHttpClient(...)` injects a `java.net.http.HttpClient` — useful for a proxy, custom TLS, or
forcing HTTP/1.1 when a server fails under HTTP/2 negotiation.

It applies to `web_fetch` and to the **Tavily** `web_search` only. The Parallel path goes through
the MCP transport, which owns its own client, so `webHttpClient(...)` does not affect it.

## Subagents

Local subagents inherit the selected provider. On the Parallel path they also **reuse the parent's
connection**: the parent registers the tool once during its own `build()`, and children register
that same tool instead of repeating the handshake. A fan-out of several subagents in one turn
therefore costs one `initialize` / `tools/list` pair in total, not one per spawn, which keeps both
spawn latency and quota pressure flat.

A child never owns the connection, so closing a subagent cannot disconnect the parent.

If the parent fell back to Tavily, children use the fallback too rather than re-attempting a
connection the parent already found unavailable.
