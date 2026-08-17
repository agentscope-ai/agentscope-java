# Human-in-the-Loop

Human-in-the-Loop lets you insert human review checkpoints during agent execution. When the agent is about to call tools, you can pause for user confirmation before proceeding.

## Two Pause Points

Agent execution has two phases: "reasoning" and "acting". You can pause at either point:

**Pause after reasoning**: After the model decides which tools to call, but before execution. You can see the tool names and parameters, letting users decide whether to allow execution.

**Pause after acting**: After tool execution completes, but before the next reasoning iteration. You can see the results, letting users decide whether to continue.

## Example: Confirming Sensitive Operations

This example shows how to require user confirmation before executing sensitive operations like deleting files or sending emails:

```java
// 1. Create confirmation hook
Hook confirmationHook = new Hook() {
    private static final List<String> SENSITIVE_TOOLS = List.of("delete_file", "send_email");

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            Msg reasoningMsg = e.getReasoningMessage();
            List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);

            // Pause if sensitive tools are involved
            boolean hasSensitive = toolCalls.stream()
                .anyMatch(t -> SENSITIVE_TOOLS.contains(t.getName()));

            if (hasSensitive) {
                e.stopAgent();
            }
        }
        return Mono.just(event);
    }
};

// 2. Create agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hook(confirmationHook)
    .build();
```

## Handling Pause and Resume

When the agent pauses, the returned message contains the pending tool information. Display it to the user and decide next steps based on their choice:

```java
Msg response = agent.call(userMsg).block();

// Check for pending tool calls
while (response.hasContentBlocks(ToolUseBlock.class)) {
    // Display pending tools
    List<ToolUseBlock> pending = response.getContentBlocks(ToolUseBlock.class);
    for (ToolUseBlock tool : pending) {
        System.out.println("Tool: " + tool.getName());
        System.out.println("Input: " + tool.getInput());
    }

    if (userConfirms()) {
        // User confirmed, continue execution
        response = agent.call().block();
    } else {
        // User declined, return cancellation
        Msg cancelResult = Msg.builder()
            .role(MsgRole.TOOL)
            .content(pending.stream()
                .map(t -> ToolResultBlock.of(t.getId(), t.getName(),
                    TextBlock.builder().text("Operation cancelled").build()))
                .toArray(ToolResultBlock[]::new))
            .build();
        response = agent.call(cancelResult).block();
    }
}

// Final response
System.out.println(response.getTextContent());
```

## Quick Reference

**Pause methods**:
- `PostReasoningEvent.stopAgent()` — Pause after reasoning
- `PostActingEvent.stopAgent()` — Pause after acting

**Resume methods**:
- `agent.call()` — Continue executing pending tools
- `agent.call(toolResultMsg)` — Provide custom tool result and continue

**Check pause reason**:
- `response.getGenerateReason()` returns `REASONING_STOP_REQUESTED` or `ACTING_STOP_REQUESTED`
