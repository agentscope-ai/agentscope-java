# AgentScope AG-UI extension

This module converts AgentScope `AgentEvent` streams to AG-UI events. Text output disposition and
authoritative final-message calibration are opt-in so existing AG-UI event sequences and message
IDs remain unchanged by default.

## Enable text output disposition

```java
AguiAdapterConfig config = AguiAdapterConfig.builder()
        .textOutputDispositionEnabled(true)
        .build();

AguiAgentAdapter adapter = new AguiAgentAdapter(agent, config);
Flux<AguiEvent> events = adapter.run(input);
```

When enabled, the adapter derives `TextOutputDispositionEvent` values from the agent stream and
emits an AG-UI `CUSTOM` event named `agentscope.text_output.disposition`. Its value contains:

- `replyId`: the AgentScope reply whose text lifecycle changed;
- `messageIds`: all AG-UI text segment IDs associated with that reply, using the stream's own
  `replyId-blockId` message IDs (for example `reply-1-text-1`);
- `disposition`: `INTERMEDIATE` or `TERMINAL`;
- `generateReason`: the generation reason when one is available.

`INTERMEDIATE` identifies text that should be presented as progress or commentary. `TERMINAL`
closes a streamed text lifecycle, but **does not mean that the text is the final answer**. The
authoritative invocation result remains `AgentResultEvent`.

For completed top-level results, the opt-in adapter emits a standard AG-UI `MESSAGES_SNAPSHOT`
containing the authoritative result and available conversation state. Consumers should use that
snapshot to reconcile or replace streamed text. No snapshot is emitted for a pending interrupt or
for generation reasons that do not represent an ordinary completed answer.

Leaving `textOutputDispositionEnabled` unset (or setting it to `false`) preserves the existing
`replyId-blockId` message ID and event sequence unchanged, and emits neither disposition `CUSTOM`
events nor final `MESSAGES_SNAPSHOT` calibration events. Enabling the flag only adds the
disposition `CUSTOM` events and snapshot calibration on top of those same message IDs.
