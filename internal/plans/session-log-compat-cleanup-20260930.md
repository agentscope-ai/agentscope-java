# Session Log 兼容链路收缩

实施位置：`/Users/ken/agentscope-3/agentscope-java`，分支 `harness-context-redesign`。本轮基于已经落地的原生 Session Log / Agent API 继续收缩兼容路径；此前设计文档中的 SHADOW、重复 transcript 和隐式旧状态加载方案由本文取代。

## 实施范围

- 删除 SessionTree、SessionEntry（Harness 旧消息模型）、SessionTranscriptWriter、TranscriptMiddleware、TranscriptStore 及本地/对象存储实现。应用自身的 SessionEntry / SessionStore 仍承担路由、标签与父子关系，继续保留。
- 移除压缩前额外 offload、旧 sessions.json 索引和旧 JSONL 定期清理。压缩后的引用指向原生 session_history；未删除任何历史数据文件。
- session_history / session_search / session_list 统一读取原生日志。发现由 SessionLogStore.list 和 AtomicSessionStorage.listPaths 扩展，本地与分布式 Filesystem 均可接入；范围受当前 namespace 限制。
- EVENT_LOG 模式不再以旧 AgentStateStore 保存会话或 action 观察记录。保留 LEGACY 独立状态存储，以及 sandbox 基础设施独立元数据。旧 v1 数据需要显式导入，不再在启动时隐式读取。
- 删除 SHADOW 双写模式，旧 shadow header 明确拒绝加载。原生管理操作通过租约与版本检查提交 checkpoint；clearContext 保留完整历史，get→修改→save 保留轻量兼容，推荐 updateAgentState。
- Service 删除 SessionEventMapper 及 thinking/tool/model 累积写入；旧 Chat、Session 和消息格式在读取边界转换，保留原 ID/seq。命令接收与 Endpoint 关联元数据、平台调度/审批、外部 provider 及 Aistio ACK outbox 继续保留。
- 示例的历史页面、消息接口与会话工具改读原生日志；按需生成旧 JSONL 输出，不落盘。Paw 共享配置改为 claw.session-log.root。

## 保留的兼容边界

`Flux<Event>` / StreamOptions 仍服务既有 Core 和自定义 Agent 消费者；AgentEvent 保留实时展示。它们不构成另一套持久 session history。`disableTranscript()` 和 `disableSessionPersistence()` 保留废弃 no-op 入口。旧 DTO 的 sessionFilePath 字段仅作为逻辑历史引用，不再读取对应文件。

旧档案不会自动合并进原生日志。需要迁移时使用经过核对的完整 AgentState 和 SessionMigration.importBaseline；旧截断 JSONL 不代表完整 checkpoint。

## 本轮验证

Core、Harness、Service 各 Java 模块、Paw / DataAgent / CodingAgent、JEV 示例和相关扩展完成清洁构建。82 项 Java 定向测试通过（Core 36、Harness 26、Service 20）；新增的管理事件空 run/turn 身份测试在最后增量验证中通过。删除的旧类未残留于编译产物。

Service 前端构建、11 项会话 adapter 测试、3 项 Go Endpoint / Managed Session 检查通过。文档检查覆盖 452 页面与 504 重定向；会话指南的中英文 Java 和 TypeScript 示例编译、22 个 shell 示例及 10 个 JSON/SSE 载荷校验通过。

## 后续集中回归

本轮以编译、契约适配和有限定向测试为主，以下留待集中回归：

1. 所有生产后端的多副本 CAS、租约到期、故障注入、重启恢复、导出 ACK 重试。
2. SESSION / USER / 全局 namespace 下的权限隔离、跨会话发现、空用户路由、百万级事件分页与检索成本。
3. Web 前端刷新、SSE 断线重连、preview 替换、工具多结果、待审批续跑以及历史主/子会话路由。
4. Endpoint invocation 关联和最终输出先于完成状态、控制面 mirror 重试、外部 provider 与 Aistio outbox 重放。
5. 管理员更新与执行竞争、过期快照、shutdown/timeout 标记、pending interaction 阻止上下文替换、自定义路由属性透传、清空上下文后恢复、checkpoint fork 和显式旧状态迁移。
6. 包发布与部署升级时确保删除的旧类没有残留在历史构建产物中；清洁构建后再发布。

本轮不执行线上部署或全量回归，也不自动删除用户历史档案。
