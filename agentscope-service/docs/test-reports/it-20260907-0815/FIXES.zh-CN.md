# 集成测试问题修复记录

日期：2026-09-07。代码基线：`9c01530abe49c0202ca9ac3dc187381322045f50`。

本记录对应同目录 `REPORT.zh-CN.md` 中的 IT-01–IT-07。修复位于当前 worktree，原始集成测试报告和现场数据保持原样。本轮完成源码修复和自动化回归；没有把新版本部署到原运行集群，因此不能将原先失败的真实模型场景改写为已通过。

## 修复内容

|问题|最终行为|主要实现|
|---|---|---|
|IT-01 协调员结束后无人推进|当前成员已终止而子 Issue 未处理时，拒绝协调员直接完成任务；当前子 Issue 验收后可等待其余任务；最后一个决策回合必须完成/失败协调节点或明确请求人工。旧客户端绕过 MCP 守卫导致任务全部终止时，将未处理工作显示为 blocked。|`collaboration_mcp.go`、`collaboration/service.go`、`runtimebinding/resolver.go`|
|IT-02 摘要遮住实际结果|完成评论保留摘要和完整 result；协调上下文新增 `coordinatorChildren.outcomes`，包含任务身份、状态、结构化结果和错误。保留现有当前子 Issue/已终止兄弟的可见范围。|`collaboration/service.go`|
|IT-03 能力不足被当成成功|新工具目录要求明确 `outcome`。`failed`/`blocked` 进入现有持久失败流程，产生失败任务和协调员决策；成员与协调员指令明确禁止将缺少工具或证据的报告视为满意交付。旧 SDK 缺省 outcome 仍兼容。|`collaboration_mcp.go`、`runtimebinding/resolver.go`|
|IT-04 ACK 额外回投|显式 A→B→A 回复回到 Issue 原负责人后，其完成不再自动回投 B；独立 B→A 请求仍会收到 A 的自动回复。|`collaboration/service.go`|
|IT-05 工具失败诊断缺失|Java MCP 在传输 `_meta` 中携带调用 ID，不污染模型工具参数；控制面读取该 ID。Managed 本地校验/拒绝/中断错误投影到 Run 诊断，保留 session state，远程与本地上报按调用 ID 去重，沿用 Attempt fence。|`McpTool.java`、`collaboration_mcp.go`、`managed_session_event.go`|
|IT-06 Hosted Chat 诊断缺项|派发时建立轮次，终态关闭为 completed/failed/aborted；重复投影幂等，旧任务终态不能关闭新一轮。历史 Hosted Chat Attempt 通过完整 Agent、Binding、Session 身份解析原会话链接。|`hosted_conversation.go`、`orchestration_handler.go`、两个存储后端的 `turns.go`|
|IT-07 SSE 探测得到 HTML|MCP GET 明确返回 405 和 `Allow: POST`，继续使用无状态 POST 协议。|`server.go`|

## 验证

- `go test ./...`：通过，37 个含测试包。
- `mvn -pl agentscope-core -am verify`：通过，2,276 项测试，0 失败、0 错误、9 跳过；包含格式检查、打包及覆盖率报告。
- Java MCP 针对性测试：58 项通过，覆盖调用 ID 传输、原元数据不可变和工具参数隔离。
- PostgreSQL 契约测试：使用独立临时 `postgres:17` 容器，端口 55439，独立数据库 `agentscope_fix_test`，`AISTIO_TEST_POSTGRES_DSN=... go test ./internal/store/postgres -count=1` 通过。测试后容器已停止并自动移除，未连接原集群业务数据库。
- `go test -race ./internal/collaboration ./internal/httpapi ./internal/runtimebinding`：通过，无竞态报告。

新增回归直接覆盖：两个成功成员逐项验收后 Run succeeded；成员 blocked 后任务 failed、不能直接验收，协调员明确使 Run failed；摘要与结构化答案同时可读；三次 A→B→A 执行终止；独立请求正常收到回复；Managed 本地错误与远程错误重复上报只产生一个诊断；MCP `_meta` 调用 ID 进入持久事件；Hosted 连续两轮和旧事件重放；历史会话链接的 Binding 隔离；轮次成功、失败、取消和幂等。

最终成功日志保存在 `fix-evidence/`。团队回归使用真实业务服务、内存存储和调度协调器，不调用模型或 Hosted CLI；PostgreSQL 使用共享存储契约测试。

## 验证边界

- 原运行集群未重启、未部署新代码；原来两个 waiting Run 未被人工改写。还需部署新版本后重新执行真实 Managed/Hosted 模型场景，才能确认行为改善的实际效果。
- outcome 及角色指令减少错误成功交付，但平台无法仅凭自然语言自动判定任意研究报告是否满足事实要求；没有加入错误关键词猜测或扩大 Qoder 工具权限。
- 普通 Hosted Issue Task 目前不创建产品 Chat Session，其 Attempt 没有 SessionRef 属于现有模型边界。`/sessions/:id/tasks` 查询运行时待办列表，并非 AgentTask 列表；缺少 instanceRef 的运行时仍明确不支持该能力，本次未用另一类数据冒充。
- External Application 的真实运行集成覆盖仍缺少已注册在线实例。本次不声称补齐该外部条件。
- 工作树原有前端构建 assets 增删未修改、未纳入修复。
