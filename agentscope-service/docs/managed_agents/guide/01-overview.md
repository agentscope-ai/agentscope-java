# Overview · 产品能力

[回目录](README.md) · [下一页：Architecture →](02-architecture.md)

---

AgentScope Service 提供与 Claude Managed Agents **同构**的产品面：**Agent / Environment / Session / Events**。你定义 Agent 与执行环境，通过 Session 发用户事件，由服务端托管 agent loop、工具编排、事件落库与 SSE；运行时基于 **AgentScope Harness**。

## 你不必自建的部分

| 自建常见工作 | AgentScope Service 托管 |
|---|---|
| 模型调用循环与 tool 调度 | `SessionTurnRunner` + HarnessAgent |
| 会话历史与可重放轨迹 | `SessionEventLog`（JPA） |
| 流式 UI / CLI 消费 | SSE + 可选 `event_deltas` |
| 人机确认（HITL） | `always_ask` → `requires_action` → `user.tool_confirmation` |
| 沙箱 / 私有 Hands | Environment 类型 + Worker 队列 |

## 能力速览

| 能力 | 说明 |
|---|---|
| **版本化 Agent** | 创建/更新产生版本快照；Session 可 pin 版本 |
| **四种 Environment** | `local` / `sandbox`（E2B）/ `remote` / `self_hosted`（出站 Worker） |
| **持久化 Session 事件** | `{domain}.{action}` 命名；历史可 `GET …/events` |
| **HITL** | 工具策略 `always_ask` 暂停会话直至确认 |
| **Hands Worker** | `self_hosted`：poll / ack / heartbeat / stop / pending-tools / tool-results |
| **Memory / Vault 挂载** | 创建 Session 时绑定 store / vault id |
| **Deployments** | Cron / webhook / 手动触发并跑 turn |
| **统一错误体** | HTTP 与 `session.error` 共用类型化 `error` |

## 典型用法

1. 定义 Agent（system + tools + 可选 skills / MCP）。
2. 创建 Environment（本地开发用 `local` 即可；云隔离用 `sandbox`；客户机用 `self_hosted`）。
3. 创建 Session，绑定 Agent 与 Environment。
4. 打开事件流，投递 `user.message`，消费出站事件直至 `session.status_idle`。

下一步：[Quickstart](03-quickstart.md) 跑通第一条会话 → [产品验证清单](14-validation.md) 做 E2B / Worker / HITL 验收；原理见 [Architecture](02-architecture.md)。

## 相关文档

- 部署与验收：[13-operations.md](13-operations.md)、[14-validation.md](14-validation.md)
- 路由与缺口：[MANAGED_AGENTS_API.md](../WIP/MANAGED_AGENTS_API.md)
- 事件契约：[events/README.md](../events/README.md)
