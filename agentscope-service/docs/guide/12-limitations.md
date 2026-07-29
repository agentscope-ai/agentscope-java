# Limitations · 能力边界

[← Auth & Sharing](11-auth-sharing.md) · [回目录](README.md) · [下一页：部署运维 →](13-operations.md) · [产品验证 →](14-validation.md)

---

下面只列 **用户集成时容易撞到** 的限制。完整缺口表见 [MANAGED_AGENTS_API.md §4](../WIP/MANAGED_AGENTS_API.md)；生产债见 [FOLLOW_UP_PRODUCTION.md](../WIP/FOLLOW_UP_PRODUCTION.md)。

## 会直接影响集成的点

| 项 | 现状 |
|---|---|
| **一等 Files API** | Session `resources[]` 的 file 挂载多为占位；无 Claude 式 `/v1/files` |
| **Session 中途更新** | 无 idle 时改 tools/MCP 的 Session PATCH；改 Agent 需新 Session 或依赖现有挂载 |
| **Multiagent Threads 对外 API** | 内部 fan-out（如 `/api/multiagent/run`）存在；无公开 threads 资源与 thread 事件流 |
| **入站事件支持度** | `user.tool_result` / `user.custom_tool_result`：**已接线续跑**（self_hosted / 外化工具）。`user.define_outcome` 仍为落库骨架。详见 [events/README.md](../events/README.md) |
| **多副本 interrupt** | 同实例 interrupt 可靠；跨副本可能 409 / 待租约过期，见 FOLLOW_UP |
| **event_deltas** | 仅 turn-owner 进程 best-effort；权威以落库事件为准 |
| **E2B packages / networking** | `type=sandbox` 用 template 承载运行时；Claude 式 packages/limited 网络未强制，见 [SANDBOX_GAPS.md](../WIP/SANDBOX_GAPS.md) |
| **前端 Tools / Settings** | 已改为 Agent body（`system` / `tools` / `mcpServers` + `version`）；旧 `/tools/config`、`/tools/active` 已删除 |
| **无公共 SDK / API 版本头** | 示例以 curl 为准；无 `managed-agents-…` beta header 承诺 |

## 已落地（勿再当作缺口）

- Worker：`poll` / `ack` / `heartbeat` / `stop` / pending-tools / tool-results / skills  
- Environment key 鉴权  
- `user.tool_result` / `user.custom_tool_result` Brain 续跑  
- Managed `type=sandbox` → E2B（非本机 Docker）  
- 统一 HTTP 错误体与 `session.error`  
- 出站命名：`agent.thinking`、`span.model_request_*`；不再落库 `agent.tool_use_delta`

## 路线图阅读顺序

1. 本页（产品可见边界）  
2. [产品验证清单](14-validation.md)（实操验收）  
3. [SELF_HOSTED_GAPS.md](../WIP/SELF_HOSTED_GAPS.md) / [SANDBOX_GAPS.md](../WIP/SANDBOX_GAPS.md)  
4. [FOLLOW_UP_PRODUCTION.md](../WIP/FOLLOW_UP_PRODUCTION.md) 多副本债  
5. [MANAGED_AGENTS_API.md](../WIP/MANAGED_AGENTS_API.md) 路由对照
