# MCP

`tool/mcp` connects Model Context Protocol servers to AgentScope Go tools.

## What It Provides

- Stdio MCP client.
- HTTP MCP client with SSE or streamable HTTP transport selection.
- In-process MCP client for local examples and tests.
- Stateful and stateless connection modes.
- Enable and disable tool filters.
- MCP tool wrapping into AgentScope `tool.Tool`.
- MCP content conversion to `message.TextBlock` and `message.DataBlock`.

## Tool Naming

MCP tools are exposed with the same naming scheme as the Python implementation:

```text
mcp__<server>__<tool>
```

For example, a raw MCP tool named `lookup_profile` from a client named `people` becomes:

```text
mcp__people__lookup_profile
```

## In-process Example

```go
client, err := mcp.NewInProcessClient("people", server)
if err != nil {
	panic(err)
}
if err := client.Connect(ctx); err != nil {
	panic(err)
}
defer client.Close()

tools, err := client.ListTools(ctx)
if err != nil {
	panic(err)
}

kit, err := tool.NewToolkit(tools...)
```

## Permission Behavior

If an MCP tool declares `readOnlyHint=true`, AgentScope Go allows it by default. Other MCP tools ask by default.
