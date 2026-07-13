---
title: "Message & Event"
description: "The core data abstractions for agent communication and streaming"
---

Message and event are the two fundamental data structures in AgentScope.

- **Message** — the primitive of agent-to-agent communication and persistence. Each `Msg` is a complete conversation turn, stored in the context and passed between agents.
- **Event** — the primitive of frontend interaction and streaming. Events carry incremental progress updates (text tokens, tool-call fragments, permission requests, …) and drive real-time UIs and human-in-the-loop flows.

The event sequence emitted by a single `call` always condenses into exactly one assistant `Msg`, ensuring the full message state can be reconstructed from the event stream alone.

## Message

`Msg` (`io.agentscope.core.message`) represents one turn of conversation — a user input, an agent reply, or a system instruction — with content modelled as an ordered list of typed `ContentBlock`s.

:::{tip}
A single assistant `Msg` corresponds to one full `call` cycle (multiple reasoning + acting iterations until the final reply).
:::

### Structure

The core fields on `Msg` (via getters):

| Method | Type | Description |
|--------|------|-------------|
| `getId()` | `String` | Unique message identifier |
| `getName()` | `String` | Sender name (nullable) |
| `getRole()` | `MsgRole` | `USER` / `ASSISTANT` / `SYSTEM` / `TOOL` |
| `getContent()` | `List<ContentBlock>` | Ordered list of content blocks (immutable) |
| `getMetadata()` | `Map<String, Object>` | Arbitrary key/value metadata |
| `getTimestamp()` | `String` | Creation time (`yyyy-MM-dd HH:mm:ss.SSS`) |
| `getUsage()` | `ChatUsage` | Token usage (assistant messages only) |
| `getGenerateReason()` | `GenerateReason` | Termination reason: `MODEL_STOP` / `TOOL_SUSPENDED` / `REASONING_STOP_REQUESTED` / `ACTING_STOP_REQUESTED` / `ALL_TOOLS_DENIED` / `INTERRUPTED` / `MAX_ITERATIONS` |

### Content blocks

Message content is composed of typed blocks, each representing one type of information. Block classes live in `io.agentscope.core.message`:

| Block | Description | Allowed in |
|-------|-------------|-----------|
| `TextBlock` | Plain text content | USER, ASSISTANT, SYSTEM |
| `DataBlock` | Binary data (image / audio / video) via base64 or URL — unifies the legacy ImageBlock / AudioBlock / VideoBlock | USER, ASSISTANT |
| `ImageBlock` / `AudioBlock` / `VideoBlock` | Legacy concrete media blocks (still supported; new code should prefer `DataBlock`) | USER |
| `ThinkingBlock` | Model reasoning / chain of thought | ASSISTANT |
| `ToolUseBlock` | A tool call: `id` / `name` / `input` / `state` (`ToolCallState`) | ASSISTANT |
| `ToolResultBlock` | A tool result with `state` (`ToolResultState`) | ASSISTANT |
| `HintBlock` | Instructions injected into the loop as user context | ASSISTANT |

:::{note}
Role constraints are enforced at construction: `USER` only allows text/data/image/audio/video blocks; `SYSTEM` only allows `TextBlock`; `ASSISTANT` allows all block types.
:::

### Creating a message

The role-pinned subclasses (`io.agentscope.core.message.UserMessage` / `AssistantMessage` / `SystemMessage` / `ToolResultMessage`) provide convenient constructors. When `content` is a plain string, it is wrapped in a `TextBlock` automatically.

```java
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;

// User message — text only
UserMessage userText = new UserMessage("user", "What's in this image?");

// Multi-modal user message
UserMessage userMulti =
        new UserMessage(
                "user",
                TextBlock.builder().text("Describe this image:").build(),
                DataBlock.builder()
                        .source(Base64Source.builder()
                                .data("...")
                                .mediaType("image/png")
                                .build())
                        .build());

// System message — text only
SystemMessage systemMsg = new SystemMessage("system", "You are a helpful assistant.");

// Assistant message — all block types allowed
AssistantMessage assistantMsg = new AssistantMessage("agent", "Here's the result...");
```

For more optional fields (`metadata`, `timestamp`, `usage`, `generateReason`), use each subclass's `builder()`:

```java
UserMessage msg =
        UserMessage.builder()
                .name("user")
                .textContent("Hello")
                .build();
```

### Accessing content

`Msg` provides helpers for extracting specific block types:

| Method | Returns |
|--------|---------|
| `getTextContent()` | All `TextBlock`s concatenated by `\n`; empty string when there are none |
| `getContentBlocks(Class<T>)` | List filtered by type |
| `getFirstContentBlock(Class<T>)` | The first matching block, or null |
| `hasContentBlocks(Class<T>)` | `true` if a block of the given type exists |

```java
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;

// All text content
String text = msg.getTextContent();

// All tool calls
List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);

// Whether there are tool results
if (msg.hasContentBlocks(ToolResultBlock.class)) {
    // ...
}
```

## Event

Events are the streaming counterpart of messages. While the agent runs, it emits a sequence of `AgentEvent`s (`io.agentscope.core.event`) representing incremental progress — text tokens arriving, tool calls being assembled, results streaming back. Each event is lightweight and self-contained.

### Event lifecycle

Every event carries `getReplyId()`, tying it to the message being assembled. Within a reply, `getBlockId()` or `getToolCallId()` acts as a correlation key for events that belong to the same content-block lifecycle. Events follow a **start → delta → end** pattern:

```{mermaid}
sequenceDiagram
    participant Client
    participant Agent

    Agent->>Client: AgentStartEvent

    rect rgba(100, 150, 255, 0.1)
        Note over Client,Agent: Reasoning phase
        Agent->>Client: ModelCallStartEvent
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: TextBlock (blockId)
            Agent->>Client: TextBlockStartEvent
            Agent->>Client: TextBlockDeltaEvent (×N)
            Agent->>Client: TextBlockEndEvent
        end
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: DataBlock (blockId)
            Agent->>Client: DataBlockStartEvent
            Agent->>Client: DataBlockDeltaEvent (×N)
            Agent->>Client: DataBlockEndEvent
        end
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: ToolUseBlock (toolCallId)
            Agent->>Client: ToolCallStartEvent
            Agent->>Client: ToolCallDeltaEvent (×N)
            Agent->>Client: ToolCallEndEvent
        end
        Agent->>Client: ModelCallEndEvent
    end

    rect rgba(100, 255, 150, 0.1)
        Note over Client,Agent: Acting phase
        rect rgba(200, 200, 100, 0.1)
            Note over Client,Agent: ToolResultBlock (toolCallId)
            Agent->>Client: ToolResultStartEvent
            Agent->>Client: ToolResultTextDeltaEvent (×N)
            Agent->>Client: ToolResultDataDeltaEvent (×N)
            Agent->>Client: ToolResultEndEvent
        end
    end

    Agent->>Client: AgentEndEvent
```

All events in one reply share the same `replyId`. Within a reply, `blockId` ties text/thinking/data block events together; `toolCallId` ties tool calls and tool results. A `blockId` is scoped to its `replyId` and does not have to be a globally unique generated ID. When a block type can have at most one lifecycle within a reply, an implementation may use a stable type key, such as a fixed key for the text block.

### Event types

All events extend `AgentEvent` (`io.agentscope.core.event`), which exposes the common methods:

| Method | Type | Description |
|--------|------|-------------|
| `getId()` | `String` | Unique event identifier |
| `getCreatedAt()` | `String` | ISO 8601 timestamp |
| `getType()` | `AgentEventType` | Event type enum |
| `getSource()` | `String` | Source path identifying the originating agent. `null` for top-level agent events; a slash-separated path (e.g. `"main/researcher"`) for events forwarded from a subagent |

Events are grouped below; unless noted otherwise, every event also carries `getReplyId()` linking it to the message being assembled.

  :::{dropdown} Lifecycle events
**AgentStartEvent** — agent begins a new reply.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getSessionId()` | `String` | Session ID |
    | `getName()` | `String` | Agent name |
    | `getRole()` | `String` | Agent role (default `"assistant"`) |

    **AgentEndEvent** — agent finishes a reply.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |

    **ExceedMaxItersEvent** — agent hit the max reasoning-acting iteration limit.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |

    **RequestStopEvent** — early-stop request raised by middleware or a tool.
:::

  :::{dropdown} Text streaming events
**TextBlockStartEvent** — a new text block begins.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getBlockId()` | `String` | Text-block correlation key within the current reply |

    **TextBlockDeltaEvent** — incremental text content arrives.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getBlockId()` | `String` | Text-block correlation key within the current reply |
    | `getDelta()` | `String` | Incremental text content |

    **TextBlockEndEvent** — text block completes.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getBlockId()` | `String` | Text-block correlation key within the current reply |
:::

  :::{dropdown} Thinking streaming events
**ThinkingBlockStartEvent / ThinkingBlockDeltaEvent / ThinkingBlockEndEvent** — same shape as the text streaming events; specific to the model's chain of thought. Its `blockId` has the same reply-scoped correlation-key semantics.
:::

  :::{dropdown} Data streaming events
**DataBlockStartEvent / DataBlockDeltaEvent / DataBlockEndEvent** — same shape as the text streaming events, carrying images / audio / video binary data:

    - `DataBlockStartEvent`: `getMediaType()` returns the MIME type (e.g. `"image/png"`).
    - `DataBlockDeltaEvent`: `getData()` returns incremental base64-encoded data.
:::

  :::{dropdown} Tool-call streaming events
**ToolCallStartEvent** — agent begins a tool call.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getToolCallId()` | `String` | Unique tool call ID |
    | `getToolCallName()` | `String` | The tool being called |

    **ToolCallDeltaEvent** — incremental tool-call arguments arrive; `getDelta()` returns a JSON fragment.

    **ToolCallEndEvent** — tool-call arguments complete.
:::

  :::{dropdown} Tool-result streaming events
**ToolResultStartEvent** — tool starts executing (carries `toolCallId`, `toolCallName`).

    **ToolResultTextDeltaEvent** — incremental text output from the tool; `getDelta()` returns a text fragment.

    **ToolResultDataDeltaEvent** — incremental binary output from the tool; similar to `DataBlockDeltaEvent` with `mediaType` / `data` / `url`.

    **ToolResultEndEvent** — tool completes.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getToolCallId()` | `String` | The matching tool call ID |
    | `getState()` | `ToolResultState` | Final state: `SUCCESS`, `ERROR`, `INTERRUPTED`, `DENIED`, `RUNNING` |
:::

  :::{dropdown} Model-call events
**ModelCallStartEvent** — model API call starts (carries `modelName`).

    **ModelCallEndEvent** — model API call completes (carries `inputTokens` / `outputTokens`).
:::

  :::{dropdown} Human-in-the-loop events
**RequireUserConfirmEvent** — agent pauses for user confirmation.

    | Method | Type | Description |
    |--------|------|-------------|
    | `getReplyId()` | `String` | Reply message ID |
    | `getToolCalls()` | `List<ToolUseBlock>` | Tool calls awaiting confirmation |

    **RequireExternalExecutionEvent** — agent pauses for external execution.

    **UserConfirmResultEvent** — user provides confirmation results (input event); carries `List<ConfirmResult>`.

    **ExternalExecutionResultEvent** — external system returns execution results (input event); carries `List<ToolResultBlock>`.

    **AllToolsDeniedEvent** — the user denied all tool calls from the most recent reasoning step via HITL confirmation. This event is emitted through the `onActing` middleware chain, allowing middlewares to emit a `RequestStopEvent` to stop the agent. If no middleware handles it, the agent continues to the next reasoning iteration (backward compatible).

    | Method | Type | Description |
    |--------|------|-------------|
    | `getDeniedToolCalls()` | `List<ToolUseBlock>` | The denied tool calls |
:::

  :::{dropdown} Subagent events
**SubagentExposedEvent** — a subagent spawned via `agent_spawn(expose_to_user=true)` has been exposed as a user-addressable entry point. SSE / streaming consumers can use this to render a new conversation entry in the UI.

| Method | Type | Description |
|--------|------|-------------|
| `getSubagentId()` | `String` | Unique identifier of the subagent |
| `getAgentId()` | `String` | Agent type ID of the subagent |
| `getSessionId()` | `String` | Session ID of the subagent |
| `getLabel()` | `String` | User-visible label (optional) |
:::

## Reconstructing messages from events

Events and messages are not separate worlds — they are two views of the same data. The event stream from `streamEvents` can be aggregated by `replyId` / `blockId` / `toolCallId` to reconstruct a complete `AssistantMessage`, ensuring the final message state is fully recoverable from events alone.

See `agentscope-core`'s `agent/StreamingHook.java` and `agentscope-examples/documentation/.../streaming/AgentEventStreamExample.java` for the standard pattern of grouping by block ID and accumulating content with Reactor operators.

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;

StringBuilder accumulated = new StringBuilder();

agent.streamEvents(userMsg)
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                accumulated.append(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("[tool] " + tc.getToolCallName());
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[tool result state=" + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[end] full text:\n" + accumulated);
            }
        })
        .blockLast();
```

:::{tip}
This decoupling makes deployments flexible: the backend pushes the event stream over SSE, and the frontend reconstructs the message client-side. Even if the connection drops, replaying events from any checkpoint restores the message state precisely.
:::

### Example: streaming UI

A typical streaming UI loop (a Spring WebFlux SSE form is shown in `streaming/StreamingWebExample.java`):

```java
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;

agent.streamEvents(new UserMessage("user", "Help me fix this bug"))
        .doOnNext(event -> {
            if (event instanceof AgentStartEvent start) {
                System.out.println("[start replyId=" + start.getReplyId() + "]");
            } else if (event instanceof TextBlockDeltaEvent delta) {
                System.out.print(delta.getDelta());
            } else if (event instanceof ToolCallStartEvent tc) {
                System.out.println("\n[calling " + tc.getToolCallName() + "...]");
            } else if (event instanceof ToolResultEndEvent end) {
                System.out.println("[tool finished: " + end.getState() + "]");
            } else if (event instanceof AgentEndEvent end) {
                System.out.println("\n[done]");
            }
        })
        .blockLast();
```

## Further reading

::::{grid} 2

:::{grid-item-card} Agent
:link: ./agent.html

How agents emit events and messages in the ReAct loop
:::
  :::{grid-item-card} Context
:link: context.html

How messages are stored and persisted
:::

::::
