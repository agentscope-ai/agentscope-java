# Managed Agents 会话能力后续改造

## 0. 为什么单独规划

已决策:**不将 Managed Agents 纳入 Operate 统一管理**。两者在会话详情这件事上的分歧,不是历史包袱而是结构性的——

| 维度 | Managed(Build) | BYO(Operate) |
|---|---|---|
| 控制台后端 | 生命周期在 aistiod `product`,**事件与交互直连 Java 数据面** | 全部经 aistiod,再经 prober/registry 触达数据面 |
| 会话身份 | 单一 `sessionId` | `(agentName, namespace, sessionId)` 三元组 |
| 鉴权租户 | JWT userId + `AgentAccessGuard.require(userId, agentId, Tier.RUN)` | 按 agent/namespace |
| 事件存储 | `dp.builder_session_event`,信封 + `payload_json` LOB | runtime store `session_events`,展开列 |
| 事件来源 | 数据面写自己的 schema | poller 探测 / ASDP 上报后复制 |
| 事件时间线 | SSE(服务端 500ms 轮询) | 客户端 5s 轮询,默认为空 |
| 历史消息 | 从持久化事件重建 | 实时转发活实例,受 `message-query` 门控 |
| 能力协商 | 无,假定全能力 | contract level + capabilities |
| 实例解析 | 无(共享实例) | `InstanceRef` 亲和 + hardBound |
| 交互写入 | 发消息、工具确认 | 仅运维命令,经 sessionops router |

其中"控制台后端归属"是最深的一条:`/api/sessions/*` 这个前缀被**两个进程分别实现了一部分**——生命周期 CRUD 在 Go 的 `product.Server`(`registerSessions`),事件与交互在 Java 的 `DataSessionApiController`(`@RequestMapping("/api/sessions")`),靠 ingress 按子路径拆分。Managed 控制台的会话运行时 API 是绕过控制面直连数据面的。

BYO 线的改造见 [session-byo-plan.md](./session-byo-plan.md)。

## 1. 事件完整性与流式(部分已落地)

> 事件/SSE **不经 aistio 产品控制面**,由 gateway 直达 `service-dataplane`(`/api/sessions/*/events/**`)。下列改造仅影响 Managed 数据面与 Build Chat,不触及 BYO `/api/v1/sessions` 或 harness `TranscriptStore`。

### 1.1 工具 input/output 落库 — **已修**

`SessionEventMapper` 现按 `toolCallId` 累积 `ToolCallDelta` / `ToolResult*Delta`,在 End 时落完整 `agent.tool_use.input` 与 `agent.tool_result.output`(超 64KB 截断并标 `truncated`)。Start 不再写空壳 tool_use。

### 1.2 流式 delta 不持久化 — 有意设计(保持)

Token / 参数 delta 仍只走 PreviewBus;`event_start` / `event_delta` 的 `event_id` 现与落库 `evt_*` 对齐,供打字机和解耦权威记录。

### 1.3 `builder_session_event` 无时间保留策略 — **仍待排期**

只有 `deleteBySessionId`(会话删除时 CP best-effort 调用)。热尾保留策略未做。

### 1.4 / 1.5 打字机 + 重连幂等 — **已修(Build Chat)**

- Chat 订阅 SSE 时传 `event_deltas=agent.message,agent.thinking,agent.tool_use`
- 先 `listEvents` 种 `seenEventIds` / `lastSeq`,再 `stream?after=`
- 按 event id 去重;delta 写入同一气泡,落库 `agent.message` 覆盖为权威文本

### 1.6 其它

- `GET …/events?types=` 重复参数过滤已支持(Claude `types[]` 等价)
- `event_deltas` 非法值返回 400
- Thread 级 stream、`user.define_outcome` 完整语义、SSE 退役改轮询 — **仍未做**
- `DataPlaneSelfRegistration` 默认关闭(`BUILDER_DATAPLANE_REGISTER=false`):数据面托管 Managed runs,不应伪装成 Operate Agent;需要可见性时显式打开

## 2. 会话管理能力(已落地)

Managed Agents 在 Build 侧自建会话能力(独立于 Operate)。本阶段**不**切换历史读路径到 BYO `TranscriptStore`,Chat / Transcript 仍走 `builder_session_event`。

| 能力 | 落地要点 |
|---|---|
| **会话发起入口** | Build 顶级 **Sessions**(`/sessions`):创建=`POST /api/sessions` 静态绑定(初始 `status=idle`);首条 `user.message` 才启动 turn。Agent 页不再嵌套 Chat/Sessions |
| **Session 管理** | 列表 Active / Archived(按 `archived_at`);`POST /api/sessions/:id/restore`;详情 Details Tab 可 Archive / Restore / Delete |
| **组件绑定可见可改** | 创建表单、Details Mounts 面板、Chat 顶栏摘要;`PATCH /api/sessions/:id` 支持 `environmentId` / `vaultIds` / `memoryStoreIds` |
| **API 配套** | `GET /api/sessions?status=active\|archived\|all`;删除会话后 CP best-effort 调数据面 `DELETE /api/sessions/:id/events` 清理事件 |

关键代码:

- 控制面:`internal/product/handlers_sessions.go`
- 数据面:`DataSessionApiController#deleteEvents`
- 前端:`SessionsHubPage`、`SessionCreatePage`、`SessionDetailPage`、`NewManagedSessionForm.tsx`、`SessionTranscript.tsx`、`ChatPanel.tsx`、`api/managedSessions.ts`

## 2b. AgentTeams 人机入口（已落地）

Teams **不**新开聊天通道。Managed 成员建队时走 `POST /api/internal/sessions/find-or-create`，`sessionId` 即成员会话；Console Teams 详情深链 `/sessions/{managedSessionId}` 复用 Build Chat（events + SSE）。`GET /api/internal/sessions/{id}/resolve` 可返回 `teamContext`，数据面 `HarnessAgentBuildService` 挂 `TeamsMiddleware`。

BYO 成员经 `team_join` 入队；Console 标注「只观测」，对话由其自有应用负责。

相关：`internal/team/activator.go`、前端 `features/teams/*`、样例 `aistio/examples/agentteam/`。

## 3. 可从 BYO 线复用的部分

BYO 线的 `TranscriptStore` 抽象(接口、分段 key 布局、JSONL 行 schema、大 payload 外置)位于 harness 层。Managed 的数据面同样嵌入 harness,因此:

- **可直接复用**:transcript 的写入路径与存储实现(harness 默认已写;本阶段 Build UI **不读**)
- **不可复用**:`builder_session_event` 相关的事件级读路径,那是 Managed 独有的
- **已决策(本阶段)**:历史继续走 `builder_session_event` 重建;是否改读 transcript 留待与 §1.1 一并评估
