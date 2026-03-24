# Supervisor（监督者）

在 **Supervisor** 模式中，一个中心**监督者**（主智能体）通过**工具**调用专职智能体进行协调。监督者接收用户请求，决定调用哪些专家（如日历、邮件），并将结果综合成一条回复。专职智能体从用户视角看是**无状态**的；监督者维护对话，将一次性任务委托给专家。在 AgentScope 中通过 **Agent as Tool**（[agent-as-tool.md](../task/agent-as-tool.md)）实现：`Toolkit.registration().subAgent()`。

## 概述

核心特点：

- **集中控制**：所有请求先到监督者；通过工具调用分发给专家。
- **每专家一个工具**：每个专职智能体注册为一个工具（如 `schedule_event`、`manage_email`），名称与描述清晰，便于监督者决定何时调用。
- **专家不直接面向用户**：专家在限定上下文（instruction + 请求字符串）中运行，将结果返回监督者；仅监督者的回复呈现给用户。
- **专家无状态**：每次调用新建子智能体实例（或使用全新上下文）；对话状态由监督者持有。

当存在明确领域（如日历、邮件、CRM）、专家无需直接与用户多轮对话、且希望单一入口并委托给合适智能体时，适合使用 Supervisor 模式。

## 架构

```
用户 → Supervisor（带 subAgent 工具的 ReActAgent）
                │
                ├→ schedule_event(instruction) → 日历智能体（ReActAgent）→ 桩工具
                └→ manage_email(instruction)   → 邮件智能体（ReActAgent）→ 桩工具
```

- **Supervisor**：一个 ReActAgent，其 Toolkit 通过 **Toolkit.registration().subAgent(provider)** 注册各专家。工具以 `schedule_event`、`manage_email` 出现，输入为单字符串（该领域的用户请求）。
- **日历智能体**：ReActAgent，带日历相关提示与工具（如 `get_available_time_slots`、`create_calendar_event`）。以工具 **schedule_event** 暴露。
- **邮件智能体**：ReActAgent，带邮件相关提示与工具（如 `send_email`）。以工具 **manage_email** 暴露。

示例中日历与邮件「API」为**模拟实现**（`CalendarStubTools`、`EmailStubTools`）；生产环境可替换为真实集成。

## 实现

1. **模型**：所有智能体共用同一 **DashScopeChatModel**（或其它 AgentScope `Model`）；API Key 来自 `spring.ai.dashscope.api-key` 或 `AI_DASHSCOPE_API_KEY`。
2. **专家智能体**：为每个领域构建一个 ReActAgent（日历、邮件），各有 sysPrompt 与 Toolkit（桩或真实工具）。在监督者的 Toolkit 中注册：`toolkit.registration().subAgent(() -> calendarAgent).apply()`，邮件同理。可选使用 **SubAgentConfig** 设置 `toolName` 与 `description`。
3. **监督者**：用同一模型与包含 subAgent 注册的 Toolkit 构建 ReActAgent。监督者系统提示中应说明何时使用 `schedule_event` 与 `manage_email`。
4. **调用**：注入监督者 ReActAgent，调用 `supervisorAgent.call(userMsg).block()`。对于跨领域请求（如「安排会议并发邮件」），监督者会调用多个工具并合并结果。

## 示例项目

- **路径**：`agentscope-examples/multiagent-patterns/supervisor/`
- **主要类**：`SupervisorConfig`（model、calendarAgent、emailAgent、supervisorAgent），`CalendarStubTools`，`EmailStubTools`，`SupervisorRunner`（可选演示）。
- **演示**：设置 `supervisor.run-examples=true` 可在启动时运行两个场景：(1) 单领域：「明天上午 9 点安排团队站会」；(2) 多领域：「下周二下午 2 点与设计团队开 1 小时会，并给他们发邮件提醒查看新原型」。

**构建与运行**（在仓库根目录）：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor spring-boot:run
```

**在代码中使用**：注入监督者 ReActAgent（如 `@Qualifier("supervisorAgent")`），传入用户 `Msg` 调用；用 `getTextContent()` 获取回复文本。

## 相关文档

- [Agent as Tool](../task/agent-as-tool.md) - 子智能体注册与 SubAgentConfig
- [Subagents](./subagent.md) - 使用 Task/TaskOutput 与 Markdown/API 子智能体的编排
- [Pipeline](./pipeline.md) - 顺序与并行智能体组合
