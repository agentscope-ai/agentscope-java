# Agent API 能力补齐实施记录

工作目录：`/Users/ken/agentscope-3/agentscope-java`；分支：`harness-context-redesign`。直接修改当前目录，保留已有暂存及未提交改动；未创建 worktree、提交或部署。

范围来自同日 [改造前能力核对](agent-api-capability-review-20261001.md)。原生记录、公共资源、执行控制和前端恢复共同实现；用户文档以业务接入场景组织。

## 实施结果

- [x] 持久 steer/inject：HTTP 输入进入原生 inbox，步骤边界应用；关闭接收与 steering 串行化。支持文本、结构化消息和多模态文件引用，保留幂等身份。
- [x] 消息、工具参数、工具进度和结果：从已提交 native chunk 导出持久公共事件，稳定 item/model/call 身份；快照包含正在生成的前缀，最终内容替换累计内容。
- [x] turn/action 一致性：派发/重新排队记录 running/queued；答复拒绝与命令状态事务提交，带具体 request 身份和待办信息；不重新显示已解决请求。
- [x] 子会话公共快照、资源和可恢复 SSE；父授权加原生关联验证；可选子树用量与逐会话水位。
- [x] 不可变托管文件：共享 BaseStore、条件创建、内容摘要、所有者下载，文件引用可用于输入/产物。
- [x] Session Webhook：HTTPS allowlist、HMAC 签名、共享租约、持久投递游标与尝试记录、退避/暂停/显式重试。
- [x] checkpoint list / restore / fork / public export：不透明句柄、writer 内核对、单批次恢复事实、幂等重试；fork 写入预创建的空目标 session。
- [x] 可扩展计价与预算：SessionUsagePricer Bean；模型调用前 CAS 预留、结束后核算、native facts 修复；子调用继承预算。未报告费用不当成零，token/费用在下一次调用前判断。
- [x] 数据库固定前缀分页；BaseStore 保存增量资源投影，避免每次 snapshot 重读所有事件；资源分页冻结视图 15 分钟。
- [x] OpenAPI 覆盖 60 个操作；公共 schema 描述 44 类现有事件，并允许客户端消费未知新增类型。资源累计视图与事件日志的类型分开。
- [x] Console Execution 使用资源 reducer，支持文件、steer/inject、子视图、多轮工具及刷新恢复；补齐中英文 API/SSE 指南和路由索引。
- [x] 手动 PostgreSQL DDL、部署配置与集中回归清单。

## 主要实现位置

- Core：SessionRecorder / SessionModels / SessionModelPolicy / SessionExecution / SessionRecovery / SessionProjection / SessionTurns。
- Data Plane：AgentSession*Controller；SessionTurnInbox / SessionTurnRunner / CommittedSessionEventProjector；AgentSessionView / AgentSessionViewStore；SessionInputService / SessionFileService / SessionCheckpointService / SessionWebhookService / SessionUsageService / SessionBudgetService。
- Common：SessionEventLog.page/subscribe、持久 action/source 实体、LegacySessionEventAdapter。
- Console：`frontend/src/api/agentSessions.ts`、`agentSessionView.ts`、`components/SessionExecution.tsx`。
- 用户指南：`docs/v2/{zh,en}/service/session-event-log.md`、`sse-events.md`、`api-reference.md`、`managed-agent-execution.md`。
- 维护者部署说明：`agentscope-service/docs/agent-api/session-event-log.md`；手动 DDL：`service-dataplane/src/main/resources/db/manual/agent-api-v1-postgresql.sql`。

## 设计边界

- 新 turn 继续显式排队；steer 不改写已经发送的模型请求；inject 不唤醒推理。
- 新增消息/工具增量持久补放；旧 preview 参数是无效果的兼容参数。已存在的公共历史保持不可变，不补造升级前没导出的片段。
- 普通 API 不返回原始 prompt、thinking 或完整 checkpoint。文件/工具业务内容按当前 session 权限公开；并非把私有 journal blob 直接公开。
- restore 保留历史并用于后续新 turn；fork 不克隆环境、托管文件、子日志、凭据或预算。不撤销外部副作用，未知结果必须核对。
- 预算是会话子树级。调用次数限制使用原子预留；token/费用依据已报告用量阻止下一次调用，不能硬截断并行/在途调用。价格由部署方配置，不充当供应商账单。
- Webhook 从注册水位开始，至少一次交付；接收方验签、校验时间、按事件 ID 去重。默认空 allowlist 不接受外发目的地。
- 首次投影、checkpoint 查询、子树发现及预算故障核对仍需扫描相关原生历史；没有无限规模/自动 GC 的性能承诺。

## 验证

- 47 项相关 Java 用例（按唯一用例计，分轮执行）：Core 模型身份/原生恢复、Harness AgentSession、Common 数据库事件并发与订阅、Data Plane 资源重连/分页、部分失败消息、文件作用域与幂等、预算并发/计价、Webhook 注册与验签、steer 终态接收、取消、checkpoint/fork、turn 准入和轻量旧事件适配。
- 5 项前端用例：目标 turn 结果判断；在消息/工具循环的每个前缀刷新后收敛；失败部分内容保持 incomplete；审批投递失败恢复。
- Maven Data Plane 及其依赖编译；改动范围 Spotless 格式检查。
- Console `npm run build`：TypeScript 和 Vite 生产构建通过。
- 文档检查：456 页面、504 重定向的导航、MDX、链接、锚点和资源检查通过。
- 契约检查：60 个操作的路径参数/本地引用、公共事件 schema、31 段 shell 语法、9 段 JSON/SSE 示例通过。
- `git diff --check` 通过。

未连接真实模型/业务工具，未做真实远程 Webhook 投递、生产多副本故障注入或全量回归；未执行数据库 DDL，未部署服务。完整集中回归见 [清单](session-event-log-regression-backlog.md)。
