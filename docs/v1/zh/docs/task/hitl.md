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

## 向用户提问（模型主动发起）

HITL 还支持反向场景：**模型**在运行过程中主动向用户索取信息。工具的 `checkPermissions()` 返回 `PermissionDecision.askUser(...)` 时，运行会以 `GenerateReason.ASK_USER_ASKING` 暂停——在包括 `BYPASS` 在内的所有 `PermissionMode` 下都生效——并且该工具**永远不会被执行**。内建的 `ask_user` 工具正是这样实现的，在 harness builder 上开启即可：

```java
HarnessAgent agent = HarnessAgent.builder()
        .model(model)
        .enableAskUser()      // 注册内建 ask_user 工具（默认关闭，显式开启）
        .build();
```

模型以 `questions[]` 载荷调用 `ask_user`（每个问题可带 `options` 选项，且始终额外接受自由文本输入；用户也可以跳过某个问题）。运行会暂停而不是执行该工具：

```java
Msg response = agent.call(userMsg).block();

if (response.getGenerateReason() == GenerateReason.ASK_USER_ASKING) {
    // 返回的 Msg 携带 ask_user 的 ToolUseBlock（state=ASKING），其 input 中即模型要问的问题
    ToolUseBlock ask = response.getContentBlocks(ToolUseBlock.class).get(0);
    Map<String, Object> questions = ask.getInput();

    // 把问题展示给用户，按 question id 收集回答
    Map<String, Object> answers = collectAnswers(questions);

    // 携带回答恢复；框架会把回答格式化为 ask_user 的工具结果，智能体不执行该工具继续运行
    Msg resumeMsg = Msg.builder()
            .name("user")
            .role(MsgRole.USER)
            .metadata(Map.of(Msg.METADATA_ASK_USER_RESULTS,
                    List.of(new AskUserResult(ask.getId(), answers))))
            .build();
    response = agent.call(List.of(resumeMsg)).block();
}
```

流式调用方会在暂停时收到 `RequireUserAskEvent`、恢复时收到 `UserAskResultEvent`，通过 `replyId` 关联——与权限确认流程同构。
