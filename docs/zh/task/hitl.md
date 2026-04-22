# Human-in-the-Loop

Human-in-the-Loop 让你可以在智能体执行过程中插入人工审核环节。当智能体准备调用工具时，你可以先暂停让用户确认，再决定是否继续。

## 两个暂停时机

智能体的执行分为"推理"和"行动"两个阶段，你可以选择在不同时机暂停：

**推理后暂停**：模型决定要调用哪些工具后，在实际执行前暂停。此时你可以看到工具名称和参数，让用户决定是否允许执行。

**行动后暂停**：工具执行完毕后，在进入下一轮推理前暂停。此时你可以看到执行结果，让用户决定是否继续。

## 典型场景：敏感操作确认

以下示例展示如何在执行删除文件、发送邮件等敏感操作前，先让用户确认：

```java
// 1. 创建确认 Hook
Hook confirmationHook = new Hook() {
    private static final List<String> SENSITIVE_TOOLS = List.of("delete_file", "send_email");

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent e) {
            Msg reasoningMsg = e.getReasoningMessage();
            List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);

            // 如果包含敏感工具，暂停等待确认
            boolean hasSensitive = toolCalls.stream()
                .anyMatch(t -> SENSITIVE_TOOLS.contains(t.getName()));

            if (hasSensitive) {
                e.stopAgent();
            }
        }
        return Mono.just(event);
    }
};

// 2. 创建智能体
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .hook(confirmationHook)
    .build();
```

## 处理暂停和恢复

当智能体暂停时，返回的消息会包含待执行的工具信息。你需要展示给用户，并根据用户选择决定下一步：

```java
Msg response = agent.call(userMsg).block();

// 检查是否有待确认的工具调用
while (response.hasContentBlocks(ToolUseBlock.class)) {
    // 展示待执行的工具
    List<ToolUseBlock> pending = response.getContentBlocks(ToolUseBlock.class);
    for (ToolUseBlock tool : pending) {
        System.out.println("工具: " + tool.getName());
        System.out.println("参数: " + tool.getInput());
    }

    if (userConfirms()) {
        // 用户确认，继续执行
        response = agent.call().block();
    } else {
        // 用户拒绝，返回取消信息
        Msg cancelResult = Msg.builder()
            .role(MsgRole.TOOL)
            .content(pending.stream()
                .map(t -> ToolResultBlock.of(t.getId(), t.getName(),
                    TextBlock.builder().text("操作已取消").build()))
                .toArray(ToolResultBlock[]::new))
            .build();
        response = agent.call(cancelResult).block();
    }
}

// 最终响应
System.out.println(response.getTextContent());
```

## API 速查

**暂停方法**：
- `PostReasoningEvent.stopAgent()` — 推理后暂停
- `PostActingEvent.stopAgent()` — 行动后暂停

**恢复方法**：
- `agent.call()` — 继续执行待处理的工具
- `agent.call(toolResultMsg)` — 提供自定义的工具结果后继续

**判断暂停原因**：
- `response.getGenerateReason()` 返回 `REASONING_STOP_REQUESTED` 或 `ACTING_STOP_REQUESTED`
