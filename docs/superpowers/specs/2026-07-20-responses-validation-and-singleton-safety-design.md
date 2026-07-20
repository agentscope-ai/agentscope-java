# Responses Validation and Singleton Safety

## Scope

Close three correctness gaps in the Responses web starter and singleton `ReActAgent` support:

- Invalid background requests must fail synchronously instead of remaining queued.
- An auto-allocated conversation must not be persisted until all synchronous request validation succeeds.
- A singleton agent with a configured legacy `RuntimeContextAware` hook must not expose one call's context to another concurrent call.

## Design

### Request preflight

Split request handling into two phases. The synchronous preflight phase prepares and converts the input, converts tools and generation options, resolves the agent, and builds the invocation-local `RuntimeContext`. Only after that phase succeeds may the controller commit an auto conversation or store a queued background response.

The execution phase receives the already prepared agent call. For background requests, only the actual agent publisher is deferred and subscribed on `boundedElastic`; validation is never deferred. Existing foreground and streaming HTTP status mapping therefore remains authoritative for `ResponsesValidationException`.

### Legacy hook serialization

`RuntimeContextAware` is a deprecated mutable-field contract, so one hook instance cannot safely hold two contexts concurrently. Preserve compatibility by using an agent-wide serialization key only when configured hooks implement that interface. Agents without such hooks retain existing per-session concurrency.

Call-scoped Responses hooks and copied toolkits remain invocation-local and do not require serialization.

## Error Handling

- Invalid tools or `tool_choice` return the existing structured HTTP 400 response.
- No queued response or auto conversation is persisted when preflight fails.
- Runtime model failures after a background request is accepted continue to produce a stored terminal failed response.

## Tests

Add regression coverage that first fails against the current implementation:

1. An invalid background request returns HTTP 400 and creates no queued state.
2. Tool validation failure with `conversation=auto` does not call `commitConversation`.
3. Distinct-session calls on an agent with a configured `RuntimeContextAware` hook execute serially and each hook observation sees its own context.

Run the focused core, protocol, and Responses starter suites after implementation.
