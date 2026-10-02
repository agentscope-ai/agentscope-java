# Agent API 能力核对（2026-10-01）

核对范围：`/Users/ken/agentscope-3/agentscope-java`，`harness-context-redesign` 当前工作树。本文面向维护者；业务接入文档为 [Agent API](../../docs/v2/zh/service/session-event-log.md) 与 [SSE](../../docs/v2/zh/service/sse-events.md)。

结论：托管推理的核心闭环已有实现，不能据此宣称完整 Agent-as-a-Service 产品能力或已经达到其他厂商的协议覆盖。完整原生 Session Log、SDK AgentSession、Service 命令调度、公共资源投影是不同层。底层有事实，不代表公共 API 已有稳定资源、控制操作和展示语义。

本次按代码核对并更新用户文档，没有增加运行时能力、修改协议或进行部署。多副本故障、数据库迁移与高负载回归仍应按既有回归清单执行，不能由文档或少量单元测试替代。

> 本文是改造前的核对快照；下述缺口已在后续实施中处理。当前实现、限制与验证见 [能力补齐实施记录](agent-api-completion-20261001.md)，用户应按更新后的 Agent API / SSE 文档接入。

## 已有实现与证据

| 能力 | 当前实现 | 主要证据 |
| --- | --- | --- |
| Session lifecycle | Gateway 把 v1 创建、查询、更新、删除和归档路由到既有控制面；执行接口到 Data Plane | `service-gateway/src/main/resources/application.yml`；Aistio `internal/product/handlers_sessions.go` |
| 持久提交与幂等 | message 字符串、按 user/session/key 幂等、事务接收命令和 accepted 事件 | `AgentSessionTurnsController.CreateTurn`；`SessionTurnInbox.accept` |
| 后台任务与顺序调度 | 持久命令表、每 session 顺序派发、worker lease、断连独立 | `SessionTurnInbox.dispatch`；`SessionTurnRunner.runDurableTurnAsync` |
| 执行跟踪与恢复 | 共享原生日志、checkpoint、结果未知工具核对；worker 丢失后修复结果或 interrupted | `SessionNativeLogService`；`SessionTurnInbox.recoverOutcome`；`AgentSessionTraceController` |
| 公共历史与 SSE | 不透明 session cursor、事件分页、snapshot、历史补放、跨副本持久事实读取 | `AgentSessionEventsController`；`SessionEventCursor`；`SessionEventLog.subscribe` |
| HITL | confirmation/external_execution 答复、幂等 actions、accepted/resolved；在线票据投递失败有 rejected | `SessionTurnInbox.answer/deliverConfirmations` |
| cancel / resume | 显式停止请求与状态核对；恢复保留 turn、新建 run | `SessionTurnInbox.cancel/resume/finish`；`SessionTurnRunner` |
| 产物和用量 | HTTPS 引用发布/撤销、当前 session 去重 token 统计 | `AgentSessionArtifactsController`；`AgentSessionEventsController.snapshot` |
| 前端基础 | fetch SSE parser、游标重连、按目标 turn 等待、Execution 页 | `frontend/src/api/agentSessions.ts`；`SessionExecution.tsx` |

Java 文件除特别说明外位于 `agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/`；公共日志和 cursor 位于 service-common。表中表示存在代码路径，不代表已完成所有生产验收。

## 官方设计参考与取舍

核对了 [OpenAI Agents API Events and items](https://developers.openai.com/api/docs/guides/agents-api/sessions/events) 以及 [Sessions](https://developers.openai.com/api/docs/guides/agents-api/sessions)。值得保留的是事件、保存的 item 和逻辑 turn 的分层；用 item/content 身份更新内容，以根任务明确结果判定完成。OpenAI 的断流流程先开流并缓冲，再读取保存的资源；不能直接假定它与 AgentScope 的持久 cursor 补放协议相同。运行时追加消息的语义也不能照搬成我们 POST turns 的行为。

核对了 [Claude Managed Agents Session event stream](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)。它区分输入事件与处理进度，也区分持久完整事件与临时预览；预览丢失应由完整记录收敛，不能当作可靠审计数据。其交互事件、thread 和状态名称属于另一套协议。AgentScope 可借鉴这几个分层原则，但必须独立说明自己的任务状态和断线恢复方式。

AgentScope 当前采用 snapshot + as_of 后补放持久事件的方式，因服务端提供持久游标，可以避免要求客户端先建立并缓冲实时流。输入接收、运行时应用、任务完成要分别解释；SSE 连接和执行寿命要解耦；增量预览要从权威结果中分离。这些原则已体现在用户指南中。

## 尚未覆盖或需要完善的能力

### 优先补齐公共资源和状态的一致性

1. **运行中引导与上下文注入尚无 HTTP API。** SDK AgentSession 的 steer/inject 不能通过 Agent API 使用。CreateTurn 只收 `String message`，所有新提交均创建任务排队。应设计显式操作和 accepted/applied 反馈，不能悄悄改变 POST turns 的含义。
2. **公共部分内容不可恢复。** `CommittedSessionEventProjector` 不投影 model/chunk、tool/chunk；snapshot 只折叠 item.completed。原生日志虽记录片段，公共刷新无法恢复正在生成消息的前缀、参数与进度。要支持这类体验，需要稳定 item/block/tool 身份及对应的进行中资源视图，或明确保留目前“临时预览 + 完整替换”的产品边界。
3. **预览关联粒度与跨副本边界。** SessionTurnRunner 使用 `turn-output-<turn>`，一个 turn 多个模型输出复用 preview_id。content_block_id 和 run_id 可分组，但 item.completed 的替换标记仍是 turn 粒度；预览总线仅在进程内。不能承诺任意副本上无缝 token 续传。下一步应校准完整 item 与预览 block 的关联以及延迟预览的丢弃规则。
4. **命令状态与快照状态不完全同源。** dispatch 把行状态改为 running，但未追加 turn.running；actions 后重新排队也未发布 turn.queued。snapshot.turns 只折叠公共事件，可能仍为 accepted/requires_action，而 GET turns 已是 running/queued。前端 waitForAgentTurn 会直接返回快照中的旧 requires_action，不能在答复后立即重用它等待恢复执行。当前文档要求用 run.started 展示开始、继续订阅并查询 GET turns；后续应补全事务内状态事件与投影一致性。
5. **待办答复失败视图。** required_action.accepted 会从 snapshot 删除待办，后续 required_action.rejected 只含 command_id 和 turn_id，snapshot 不恢复失败动作。用户页面重开后无法仅凭待办列表识别投递失败。需要 action command 的可查询状态或明确的失败动作投影；目前不能把 accepted 当成 resolved。
6. **工具公共视图不完整。** 普通 requested/dispatched 只提供调用 ID/可选名称；完整消息投影过滤 tool_use，tool_result 只保留状态，未提供原始输出。没有独立工具资源或进行中工具快照。现阶段可通过事件历史重建状态卡，不能声称可还原全部工具输入/输出。

### 后续产品能力

- turns 的结构化/多模态输入、输入资源引用与明确 schema。
- 子 Agent 的公共列表、独立 item/turn/history 视图和可选聚合用量；当前只有父侧关联事件和管理员 child trace。
- Session webhook 通知；当前 Agent API 没有对应注册/投递接口，不能套用其他产品入口的 webhook。
- 文件上传、受控下载和不可变产物资源；现有 artifacts 是 HTTPS 引用发布，不等同于托管文件服务。
- session fork、任意 checkpoint 回滚等公共能力；SDK 离线原语不能当作 REST 功能已交付。
- 服务用量、费用、预算限制和配额策略；usage 目前只是当前 session 的已报告模型 token，不是账单或预算执法。
- 完整机器可读契约：现有 OpenAPI 只覆盖执行/读接口，未覆盖全部 lifecycle；public-event schema 只有通用 envelope，尚无按事件类型约束的 payload 联合体与独立 preview schema。
- 长历史按需资源分页与压缩索引：当前 snapshot 扫描全部公共事件，events limit 限制响应而不是数据库读取量。需验证规模再承诺高容量会话。

## 后续实施顺序建议

先补状态与动作失败的一致性，再确定工具/进行中 item 的公共展示边界，然后补 steer/inject 与输入契约。独立推进文件、子 Agent、webhook 和用量产品能力。每一步同时维护公共协议、服务端投影、前端状态 reducer 与示例，避免仅在 native 日志里增加事件就宣称 HTTP 功能完成。

后续测试重点：多副本断线补放与预览缺失；accept/apply/finish 崩溃边界；动作 accepted 后 rejected 的页面恢复；多次助手输出与工具循环后的刷新；取消完成竞态；模型/工具副作用未知；长历史分页与投影水位。已有集中清单见 [session-event-log-regression-backlog.md](session-event-log-regression-backlog.md)。

## 本次验证

- `npm run check --prefix docs`：456 个页面、504 条重定向的导航、语法、链接、锚点和资源检查通过。
- `npm test --prefix agentscope-service/frontend -- src/api/agentSessions.test.ts`：现有 2 项逻辑 turn 结果判断测试通过；不代表已测试网络断流或多副本故障。
- 中英文指南中 25 段 shell 示例通过 `bash -n`，9 个 JSON/SSE JSON 示例解析通过，4 段 TypeScript 示例组合后针对实际 Console 客户端完成类型检查。示例未向运行中的服务发请求。
- `git diff --check` 通过。本轮只修改文档和导航，没有进行服务构建或部署。
