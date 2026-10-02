# Session / turn / execution 身份改造记录（2026-09-30）

工作目录：`/Users/ken/agentscope-3/agentscope-java`，分支：`harness-context-redesign`。直接修改当前工作目录，保留既有 staged/unstaged 改动，没有提交或部署。

## 已实施

- AgentEvent 保留强类型实时接口，SessionEvent 保留原生不可变事实；统一 ExecutionIdentity 关联，事件 ID/seq 不混用。
- RunControl 分配唯一 runId，AgentRun、事件 execution 和 SessionRecorder.executionRunId 共享它；prepareRun/prepareCall 在订阅前即可关联。
- 每次直接订阅分配身份；同逻辑 turn 的挂起/恢复使用新 handle、新 runId。正常返回的 handle COMPLETED 不代表业务成功。
- native turn/start 只在首次出现，恢复为 turn/resumed；结果使用 completed/suspended/failed/interrupted/cancelled。run/start/end 表示一次执行。只读取历史 turn/end。
- 公共 run.started/run.ended 来自原生执行事实，逻辑 turn 状态由 durable command inbox 发布；snapshot 分开 turns/runs，前端不再将旧 turn.ended 当作终态。
- 原生公共投影和 preview 直接暴露 data.run_id。模型用量用 model_call_id 和 model_calls 命名，保留历史 attempt_id 的读取。
- Service 旧工具结果续跑通过 pending request 找回原 turn，拒绝未知、已解决或跨 turn 请求。原控制面 fence 保持，持久保存显式 coordination 关联。
- 子事件保留子身份，子交互不写入父 pending inbox。
- native provider overflow 在执行内部压缩并重试模型，保持一个 writer/run；不从已结束的原生执行外部重新 call。
- 挂起恢复验证中发现的工具多态序列化缺失已修复：新写入显式保留 type，已有 action/end 结果小范围兼容读取。
- 修复 SessionLogException.causedBy 遇循环 cause 的死循环，避免压缩失败判断挂住。
- 中文/英文 Harness、Message/Event、Service Session Log、SSE 及协议文档同步。

## 保留的轻量兼容

只读历史 turn/end、usage.attempt_id 和旧 action result 类型缺省；保留 AgentRun.Status.COMPLETED 的 API 含义、控制面 fence wire 字段。没有恢复旧 JSONL 双写、SessionTree 或影子模式。

## 后续集中回归

- 多副本租约过期、进程崩溃与接管；不同 run 的迟到 cancel/heartbeat 不影响新执行。
- always_ask 在线等待与跨进程确认恢复、批量/部分外部工具结果、并行子 Agent 的真实 UI 联调。
- 长时间 SSE 断线、多消费者、导出 ACK 丢失、preview 与最终消息替换、保留期变化后的 snapshot/cursor。
- 大历史日志的恢复/扫描耗时，分布式 Filesystem 后端 CAS/fencing 与故障注入。
- 真 provider 的 context overflow、部分 chunk 后失败、compaction 中取消；自定义 onAgent 整体重试应重新创建执行 handle，模型内部重试保持 runId。

本轮采用定向测试和构建，不运行完整回归或真实模型集成测试。

## 本轮验证结果

- Java：47 项定向测试通过（Core 19、Harness 11、Service 17），Service Data Plane 及依赖模块构建通过。最后对压缩重试和 Service 语义终态补测通过。
- 前端：2 项 Agent API 终态测试通过，TypeScript 检查和 Vite 构建通过。
- 文档：452 页、504 个重定向检查通过；中英文 Java/TypeScript 示例编译通过，22 段 shell 和 10 个 JSON/SSE payload 语法通过。
- 本轮修改文件执行定向格式化，git diff --check 通过。未运行全量回归、未提交、未部署。
