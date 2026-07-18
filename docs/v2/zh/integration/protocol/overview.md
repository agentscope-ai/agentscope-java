# 智能体协议（Agent Protocol）

AgentScope 在 Java 侧提供了多种"让 Agent 与外部交互"的协议适配器。它们各自解决一个不同的问题：

| 扩展 | 协议 | 解决的问题 |
| --- | --- | --- |
| [A2A](a2a.md) | [Agent-to-Agent](https://a2aproject.github.io/A2A/) | Agent 之间互相调用、组成工作流 |
| [AG-UI](agui.md) | [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) | 把 Agent 的事件流标准化输出给前端 UI |
| [Agent Protocol](agent-protocol.md) | [Agent Protocol](https://agentprotocol.ai/) | HTTP 标准接口，方便其他系统提交"任务"给 Agent |

> 还有 [Chat Completions Web](../ecosystem/chat-completions-web.md)：把 Agent 包装成 OpenAI Chat Completions 兼容接口，让现有客户端可以直接接入。

## 怎么选

- **想被前端 UI 实时消费 Agent 事件流（含 ThinkingBlock）** → AG-UI
- **想让其他业务系统通过 REST 调度 Agent** → Agent Protocol
- **想让多个 Agent / Agent 与外部 Agent 系统互相调用** → A2A
