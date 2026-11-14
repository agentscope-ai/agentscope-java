---
title: Release Notes
description: AgentScope Java 各版本变更记录
en_link: /v2/en/docs/others/release-notes
---

本页记录 AgentScope Java 2.0 各版本的具体变更。从 1.x 升级的整体迁移指南请见 [V1 迁移指南](/v2/zh/docs/change-log)。

---

## 2.0.5

> 状态：发布草稿（准备日期：2026-11-14）

AgentScope Java 2.0.5 是一个维护版本，将主线上的修复回合到 2.0.x。本次更新加强了状态持久化与取消、工具调用参数处理、上下文压缩、记忆持久化、技能与沙箱，并更新了 Anthropic 和 Gemini 的默认模型。本版本没有新增模块，也没有移除公开 API。

### 行为变更与升级说明

- **默认模型变更。** `AnthropicChatModel.builder()` 现在默认使用 `claude-sonnet-5-5`，`GeminiChatModel.builder()` 默认使用 `gemini-3.8-flash`。建议显式设置 `modelName(...)`，让模型选择、行为和计费不随库升级变化；这些默认值不会覆盖 Spring Boot 属性中显式设置的模型名。
- **文件系统队列格式。** 文件系统支持的 `WorkspaceMessageBus` 现在写入 `{id}.payload` 和 `{id}.ready` 领取标记，不再使用单个 `{id}.json` 文件（[#3281](https://github.com/agentscope-ai/agentscope-java/pull/3281)）。旧版消费实例只扫描 `.json`，看不到新条目。使用共享后端时，应先升级全部队列消费实例，再让新版生产实例发布消息；也可以在滚动升级期间暂停队列流量。历史 `.json` 条目仍可读取。
- **记忆读取的命名空间回退。** 对于带命名空间的调用方，`MEMORY.md` 和 `memory/*.md` 只会在该命名空间内回退，根目录副本不再提供给具名调用方。升级时请把现有根目录记忆文件移动到命名空间目录。没有命名空间或文件系统的上下文仍读取根目录（[工作区](/v2/zh/docs/harness/workspace)）。
- **结构化输出不再强制 strict 模式。** 框架不再为结构化输出生成的 JSON Schema 设置 `strict: true`（[#2608](https://github.com/agentscope-ai/agentscope-java/pull/2608)）。端点支持时，可通过 `OpenAIChatModel.builder().strictJsonSchema(true)` 按模型开启；已经声明 `strict` 的 Schema 保持不变。
- **更严格的工具 Schema。** `@Tool(strict = true)` 现在会递归地向生成 Schema 的每个对象节点注入 `additionalProperties: false`，因此任意模型提供商都会拒绝未声明的参数，同时满足 OpenAI strict 模式（[#1392](https://github.com/agentscope-ai/agentscope-java/pull/1392)）。手写 Schema（`SchemaOnlyTool` 或自定义 `AgentTool`）不受影响。
- **SQL 表结构变更。** `agentscope-extensions-jdbc` 在 PostgreSQL 和 H2 上新建表时使用 `item_key VARCHAR(2048)`（[#3464](https://github.com/agentscope-ai/agentscope-java/pull/3464)）；MySQL 新建表将关键列固定为 `utf8mb4_bin`（[#3288](https://github.com/agentscope-ai/agentscope-java/pull/3288)）。已有表不会自动变更，需要手工迁移，参见 [MySQL / JDBC](/v2/zh/integration/distributed/mysql) 和 [MySQL 状态存储](/v2/zh/integration/session/mysql)。MySQL 迁移后，关键列比较将变为大小写敏感。
- **Kubernetes 工作区创建失败即报错。** `mkdir` 返回非零退出码时，调用会以 `SandboxErrorCode.WORKSPACE_START_ERROR` 失败，而不是继续使用未创建成功的工作区（[#3951](https://github.com/agentscope-ai/agentscope-java/pull/3951)）。
- **可选的 Redis 集群 Key 布局。** `RedisAgentStateStore` 可通过 `keyLayoutVersion(KeyLayoutVersion.V1)` 把 `userId/sessionId` 段包进 Redis hash tag，使多 Key 操作落在同一个集群 slot（[#3129](https://github.com/agentscope-ai/agentscope-java/pull/3129)）。默认仍是 V0 布局；共享同一 Key 前缀的所有存储必须配置相同的版本。

### 修复

**核心 / 状态 / 取消**

- 将 `ConfirmResult` 规则持久化到会话权限上下文，并让声明的权限规则在每次调用时生效（[#3377](https://github.com/agentscope-ai/agentscope-java/pull/3377)，[#3361](https://github.com/agentscope-ai/agentscope-java/pull/3361)）。
- 在发布 HITL 授权请求前持久化暂停状态，并允许在暂停执行收尾过程中恢复（[#3699](https://github.com/agentscope-ai/agentscope-java/pull/3699)，[#3602](https://github.com/agentscope-ai/agentscope-java/pull/3602)）。
- 以调用方身份持久化 Agent 状态，支持并发状态写入和空工具调用名，并在被取消调用的状态写入落盘前保持同一会话门闩（[#3335](https://github.com/agentscope-ai/agentscope-java/pull/3335)，[#4048](https://github.com/agentscope-ai/agentscope-java/pull/4048)，[#3924](https://github.com/agentscope-ai/agentscope-java/pull/3924)）。
- 响应体停滞时完成流式取消，并重试没有 HTTP 状态码的流式传输错误（[#4021](https://github.com/agentscope-ai/agentscope-java/pull/4021)，[#3058](https://github.com/agentscope-ai/agentscope-java/pull/3058)）。
- 按值比较消息，对列表的每个元素计算哈希，在 JSON 解码时拒绝尾随内容，并让 `JacksonJsonCodec` 能读回它写出的所有字符串（[#3842](https://github.com/agentscope-ai/agentscope-java/pull/3842)，[#3697](https://github.com/agentscope-ai/agentscope-java/pull/3697)，[#3468](https://github.com/agentscope-ai/agentscope-java/pull/3468)）。
- 提取 `generate_response` 结果时保留 id、时间戳和用量，将 `callTool` 路由到共享工具基础设施，并保留 `SubAgentTool` 会话历史（[#3032](https://github.com/agentscope-ai/agentscope-java/pull/3032)，[#3130](https://github.com/agentscope-ai/agentscope-java/pull/3130)，[#3915](https://github.com/agentscope-ai/agentscope-java/pull/3915)）。
- 将子 Agent 等待与普通工具超时分离，并保留工具完成、hook 反馈和错误状态（[#3879](https://github.com/agentscope-ai/agentscope-java/pull/3879)，[#3803](https://github.com/agentscope-ai/agentscope-java/pull/3803)）。

**工具调用 / 结构化输出**

- 拒绝不完整和格式错误的工具调用参数，按解析后的参数执行 Schema 校验，并记录参数解析失败而不是静默吞掉（[#3739](https://github.com/agentscope-ai/agentscope-java/pull/3739)，[#3728](https://github.com/agentscope-ai/agentscope-java/pull/3728)，[#2869](https://github.com/agentscope-ai/agentscope-java/pull/2869)）。
- 避免空对象工具调用内容掩盖已正确解析的参数（[#3886](https://github.com/agentscope-ai/agentscope-java/pull/3886)）。
- 改进结构化输出的降级路径，并在混合结构化输出批次中保留业务工具历史（[#2697](https://github.com/agentscope-ai/agentscope-java/pull/2697)，[#4086](https://github.com/agentscope-ai/agentscope-java/pull/4086)）。
- 校验 `write_text_file` 的行范围以避免文件损坏，并从解析后的路径读取 `WriteFileTool` 片段（[#3208](https://github.com/agentscope-ai/agentscope-java/pull/3208)，[#3918](https://github.com/agentscope-ai/agentscope-java/pull/3918)）。

**Harness / 压缩 / 上下文**

- 持久化压缩后的状态，并避免溢出重试时重复输入（[#3661](https://github.com/agentscope-ai/agentscope-java/pull/3661)）。
- 在摘要中保留工具参数和续接上下文，并拒绝不完整或不收敛的摘要（[#3693](https://github.com/agentscope-ai/agentscope-java/pull/3693)，[#3855](https://github.com/agentscope-ai/agentscope-java/pull/3855)）。
- 在压缩时保留富工具结果、工具结果元数据与状态，并在截断参数时保留消息标识和工具状态（[#3845](https://github.com/agentscope-ai/agentscope-java/pull/3845)，[#3326](https://github.com/agentscope-ai/agentscope-java/pull/3326)，[#3649](https://github.com/agentscope-ai/agentscope-java/pull/3649)）。
- 按工具结果计数裁剪预算，并在安全截断点为 0 时返回轻量裁剪结果（[#3538](https://github.com/agentscope-ai/agentscope-java/pull/3538)，[#3571](https://github.com/agentscope-ai/agentscope-java/pull/3571)）。
- 在保留工作区指令内容的同时限制 `AGENTS.md` 大小，对 `read_file` 输出做 Unicode 安全分页，并限制 `session_search` / `session_history` 中模型提供的上限（[#3723](https://github.com/agentscope-ai/agentscope-java/pull/3723)，[#3821](https://github.com/agentscope-ai/agentscope-java/pull/3821)，[#3382](https://github.com/agentscope-ai/agentscope-java/pull/3382)）。
- 将附件和收件箱上下文传递到下一次请求（[#3738](https://github.com/agentscope-ai/agentscope-java/pull/3738)）。

**记忆 / 任务**

- 新增整理提交门禁：只有完整、纯文本、非空且不超过 token 目标的正文才会替换 `MEMORY.md`，并且只有在读回已写入正文后才推进水位（[记忆](/v2/zh/docs/harness/memory)）。
- 在后台刷新和整理时重放完成回合的 Reactor 上下文，保留上下文级 token 计量和链路追踪，并将记忆 IO 移出单次调用的沙箱（[#3113](https://github.com/agentscope-ai/agentscope-java/pull/3113)）。
- 按存储命名空间隔离子 Agent 任务句柄，恢复持久化 `SESSION` / `USER` 作用域中的孤儿任务，并修复嵌套任务路由合成（[#4043](https://github.com/agentscope-ai/agentscope-java/pull/4043)，[#3600](https://github.com/agentscope-ai/agentscope-java/pull/3600)）。
- 沙箱任务记录仍保存在宿主工作区根目录下，而记忆文件跟随调用方命名空间。

**技能**

- 将阻塞的技能重新加载移出响应式事件循环线程，并移除 `computeSignature` 中的 O(n²) 拷贝和无界文件锁表（[#3792](https://github.com/agentscope-ai/agentscope-java/pull/3792)，[#3795](https://github.com/agentscope-ai/agentscope-java/pull/3795)）。
- 让技能资源和执行指引与实际可用资源保持一致（[#3797](https://github.com/agentscope-ai/agentscope-java/pull/3797)，[#3779](https://github.com/agentscope-ai/agentscope-java/pull/3779)）。
- 在 `AgentSkillPromptProvider` 中按技能名匹配 `SkillFilter`，要求 frontmatter 结束分隔符独立成行，并支持 `SKILL.md` 的 UTF-8 BOM（[#3302](https://github.com/agentscope-ai/agentscope-java/pull/3302)，[#3655](https://github.com/agentscope-ai/agentscope-java/pull/3655)，[#3385](https://github.com/agentscope-ai/agentscope-java/pull/3385)）。

**工作区 / 沙箱 / 文件系统**

- 在 ROOTED 本地文件系统中对绝对路径执行命名空间边界检查，并遵循当前生效的沙箱工作区根目录（[#3292](https://github.com/agentscope-ai/agentscope-java/pull/3292)，[#3646](https://github.com/agentscope-ai/agentscope-java/pull/3646)）。
- 所有 Web 工具共享一个默认 `HttpClient`，不再每次构建都新建（[#3833](https://github.com/agentscope-ai/agentscope-java/pull/3833)）。
- 初始化失败时关闭索引连接，并按字面量匹配索引前缀而不是 `LIKE` 模式（[#3912](https://github.com/agentscope-ai/agentscope-java/pull/3912)，[#3875](https://github.com/agentscope-ai/agentscope-java/pull/3875)）。
- 工作区创建失败时关闭快照归档，并在沙箱释放后避免产生会话镜像（[#3597](https://github.com/agentscope-ai/agentscope-java/pull/3597)，[#3039](https://github.com/agentscope-ai/agentscope-java/pull/3039)）。
- 将 `DockerFilesystemSpec` 的 `additionalRunArgs` 改为追加语义，处理补丁模糊匹配阶梯中的 CRLF，在创建沙箱写入父目录时避免命令替换，并修复沙箱 `edit` 生成包含字面量序列的程序的问题（[#3545](https://github.com/agentscope-ai/agentscope-java/pull/3545)，[#3416](https://github.com/agentscope-ai/agentscope-java/pull/3416)，[#3871](https://github.com/agentscope-ai/agentscope-java/pull/3871)，[#3226](https://github.com/agentscope-ai/agentscope-java/pull/3226)）。
- 原子领取工作区队列条目（[#3281](https://github.com/agentscope-ai/agentscope-java/pull/3281)）；按订阅隔离网关 turn 租约，并淘汰空闲 turn 门闩（[#3550](https://github.com/agentscope-ai/agentscope-java/pull/3550)，[#3533](https://github.com/agentscope-ai/agentscope-java/pull/3533)）。
- 在 `HarnessAgent.Builder` 上公开并继承 `ConflictPolicy`（[#3263](https://github.com/agentscope-ai/agentscope-java/pull/3263)），并在延迟仓库没有暂存任何资源时返回 `NONE`（[#3374](https://github.com/agentscope-ai/agentscope-java/pull/3374)）。

**沙箱后端**

- Kubernetes：强制执行运行时执行超时契约，将命令预算应用到响应头，并限制完整 HTTP 交换（[#3951](https://github.com/agentscope-ai/agentscope-java/pull/3951)，[#4006](https://github.com/agentscope-ai/agentscope-java/pull/4006)，[#3884](https://github.com/agentscope-ai/agentscope-java/pull/3884)）。
- E2B：遵循 `WorkspaceSpec` 根目录并重新绑定远程快照，通过服务端 kill 实现可靠的执行超时，快照无法恢复时让调用失败，用单个沙箱恢复 `NATIVE_SNAPSHOT` 会话，并加固 envd 流式响应解析（[#4081](https://github.com/agentscope-ai/agentscope-java/pull/4081)，[#2976](https://github.com/agentscope-ai/agentscope-java/pull/2976)，[#3609](https://github.com/agentscope-ai/agentscope-java/pull/3609)，[#3562](https://github.com/agentscope-ai/agentscope-java/pull/3562)，[#3341](https://github.com/agentscope-ai/agentscope-java/pull/3341)）。

**安全**

- 配置非空白名单时，使用展开、管道、重定向、命令分隔符、分组或不完整引号/转义的命令都需要审批；审批回调接收原始完整命令，只有显式 `true` 才允许执行（[#3286](https://github.com/agentscope-ai/agentscope-java/pull/3286)）。
- 阻止技能物化和暂存过程中的路径穿越（[#3787](https://github.com/agentscope-ai/agentscope-java/pull/3787)，[#3314](https://github.com/agentscope-ai/agentscope-java/pull/3314)）。

**协议 / 集成**

- A2A 客户端：调用取消时停止远程流和任务，未完成任务不再挂起，保留已完成状态消息，优先使用已完成产物而不是状态消息，Agent Card 无法解析时快速失败，每次执行只解析一次 Agent Card，并关闭已取消调用的客户端（[#4097](https://github.com/agentscope-ai/agentscope-java/pull/4097)，[#3992](https://github.com/agentscope-ai/agentscope-java/pull/3992)，[#3784](https://github.com/agentscope-ai/agentscope-java/pull/3784)，[#3775](https://github.com/agentscope-ai/agentscope-java/pull/3775)，[#3867](https://github.com/agentscope-ai/agentscope-java/pull/3867)，[#3767](https://github.com/agentscope-ai/agentscope-java/pull/3767)，[#3663](https://github.com/agentscope-ai/agentscope-java/pull/3663)）。
- AG-UI：发出符合 Schema 的事件、消息和补丁载荷；保留错误工具结果内容；将前端工具错误报告为 `ERROR` 工具结果；SSE 断开后保持 MVC 运行；净化文档拒绝并映射转换错误（[#3747](https://github.com/agentscope-ai/agentscope-java/pull/3747)，[#3932](https://github.com/agentscope-ai/agentscope-java/pull/3932)，[#3324](https://github.com/agentscope-ai/agentscope-java/pull/3324)，[#3745](https://github.com/agentscope-ai/agentscope-java/pull/3745)，[#3397](https://github.com/agentscope-ai/agentscope-java/pull/3397)）。
- 在协议模块中串行化并发任务提交（[#3643](https://github.com/agentscope-ai/agentscope-java/pull/3643)）。
- Nacos：保留 A2A 能力扩展，并仅在启用 Nacos 提示词时构建提示词 `ReActAgent`（[#3801](https://github.com/agentscope-ai/agentscope-java/pull/3801)，[#3994](https://github.com/agentscope-ai/agentscope-java/pull/3994)）。
- XXL-Job：每次触发只计一次执行（[#3507](https://github.com/agentscope-ai/agentscope-java/pull/3507)）。

**模型提供商**

- Anthropic：保留并行工具调用批次，将非流式工具参数序列化为 JSON，将响应的 stop reason 作为结束原因，并容忍响应中缺少 message id（[#4019](https://github.com/agentscope-ai/agentscope-java/pull/4019)，[#4031](https://github.com/agentscope-ai/agentscope-java/pull/4031)，[#3852](https://github.com/agentscope-ai/agentscope-java/pull/3852)，[#3394](https://github.com/agentscope-ai/agentscope-java/pull/3394)）。
- Gemini：保证生成的函数调用 ID 不重复，遵循默认工具选择，报告结束原因代码，并修复媒体 URL 扩展名解析（[#3982](https://github.com/agentscope-ai/agentscope-java/pull/3982)，[#3858](https://github.com/agentscope-ai/agentscope-java/pull/3858)，[#3701](https://github.com/agentscope-ai/agentscope-java/pull/3701)，[#1646](https://github.com/agentscope-ai/agentscope-java/pull/1646)）。
- OpenAI：在 reasoning 回放中省略流式索引，并在 MiniMax formatter 中遵循 `ToolChoice.None`（[#4000](https://github.com/agentscope-ai/agentscope-java/pull/4000)，[#3430](https://github.com/agentscope-ai/agentscope-java/pull/3430)）。
- Ollama：保留每次调用的执行选项，并为结构化 Agent 输出使用原生 Schema（[#3859](https://github.com/agentscope-ai/agentscope-java/pull/3859)，[#3455](https://github.com/agentscope-ai/agentscope-java/pull/3455)）。
- DeepSeek：在所有 assistant 回合中回传 `reasoning_content`，并在上下文窗口表中加入规范的 `deepseek-flash` id（[#3272](https://github.com/agentscope-ai/agentscope-java/pull/3272)，[#3380](https://github.com/agentscope-ai/agentscope-java/pull/3380)）。
- `registerFactory` 时淘汰 `ModelRegistry` 缓存并共享并发首次创建；将 Responses 用量明细作为可选 JSON 字段读取；不再为 DashScope 参数片段臆造工具调用 ID（[#3791](https://github.com/agentscope-ai/agentscope-java/pull/3791)，[#3580](https://github.com/agentscope-ai/agentscope-java/pull/3580)，[#3474](https://github.com/agentscope-ai/agentscope-java/pull/3474)）。

**存储 / RAG / 依赖**

- 注册向量类型前先创建 pgvector 扩展，解析 `QdrantStore` 中不带 scheme 的 IP 地址，并接受 Dify 检索响应 query 字段的裸字符串（[#3802](https://github.com/agentscope-ai/agentscope-java/pull/3802)，[#3804](https://github.com/agentscope-ai/agentscope-java/pull/3804)，[#3504](https://github.com/agentscope-ai/agentscope-java/pull/3504)）。
- 每次 `TikaReader` 读取使用独立的内容处理器，并复用和关闭 OpenAI embedding 客户端（[#3196](https://github.com/agentscope-ai/agentscope-java/pull/3196)，[#3730](https://github.com/agentscope-ai/agentscope-java/pull/3730)）。
- 将 `com.aliyun:tea` 和 `aliyun-java-sdk-core` 固定到明确版本，移除版本范围解析（[#3257](https://github.com/agentscope-ai/agentscope-java/pull/3257)）。
- 接受 Jev 概率和在 API 四位小数舍入误差范围内的结果（[#3366](https://github.com/agentscope-ai/agentscope-java/pull/3366)）。

### 文档

- 修复已核实的文档与代码不一致问题（MCP 示例、HITL、默认值、工具名），中英文同步（[#3293](https://github.com/agentscope-ai/agentscope-java/pull/3293)）。
- 说明技能刷新按调用发生而不是按推理步骤（[#3471](https://github.com/agentscope-ai/agentscope-java/pull/3471)），并重新挂接 `generateReason` 的 javadoc（[#3327](https://github.com/agentscope-ai/agentscope-java/pull/3327)）。
- 示例支持工作区 `SKILL.md` frontmatter 中的 UTF-8 BOM（[#3434](https://github.com/agentscope-ai/agentscope-java/pull/3434)）。

**完整更新记录:** [v2.0.4...v2.0.5](https://github.com/agentscope-ai/agentscope-java/compare/v2.0.4...v2.0.5)

---

## 2.0.4

> 状态：发布草稿（准备日期：2026-10-08）

AgentScope Java 2.0.4 新增 OpenAI Responses API、重新设计的 AgentScope Service，以及 Channel 和存储集成，并加强并发 Agent 执行、工具和技能的隔离。

### 新增

**核心 / 模型**

- 新增 OpenAI Responses API 模块（[#3078](https://github.com/agentscope-ai/agentscope-java/pull/3078)）。
- 支持 `returnDirect` 工具结果，直接返回并结束推理循环（[#2891](https://github.com/agentscope-ai/agentscope-java/pull/2891)）。
- 公开详细的聊天 token 用量指标，以及模型回退的 failover 监听器（[#3215](https://github.com/agentscope-ai/agentscope-java/pull/3215)，[#3145](https://github.com/agentscope-ai/agentscope-java/pull/3145)）。
- 支持 Anthropic Bearer Token 鉴权（[#3038](https://github.com/agentscope-ai/agentscope-java/pull/3038)）。
- 新增类型安全的 Jev System One 客户端、Spring Boot Starter 和中间件示例（[#3240](https://github.com/agentscope-ai/agentscope-java/pull/3240)）。

**Harness / 工具 / 存储**

- 新增 `AgentRun` / `RunControl` 单次执行控制，并显式传播运行时上下文（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)）。
- 新增请求级工具配置，隔离工具可见性、流式回调和会话技能资源（[#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)）。
- 文件系统配置新增可选的 `sharedLocalWorkspace`（[#3247](https://github.com/agentscope-ai/agentscope-java/pull/3247)）。
- 支持禁用内置 Web 工具，以及为其注入 HTTP 客户端（[#3075](https://github.com/agentscope-ai/agentscope-java/pull/3075)，[#3103](https://github.com/agentscope-ai/agentscope-java/pull/3103)）。
- 支持按 MCP server 和工具配置元数据传播（[#3255](https://github.com/agentscope-ai/agentscope-java/pull/3255)）。
- 新增 MongoDB 存储扩展（[#2698](https://github.com/agentscope-ai/agentscope-java/pull/2698)）。
- 支持按沙箱自动清理旧的 E2B 原生快照（[#2555](https://github.com/agentscope-ai/agentscope-java/pull/2555)）。

**Service / Channel**

- 引入重新设计的 AgentScope Service 实现（[#3080](https://github.com/agentscope-ai/agentscope-java/pull/3080)）。
- 新增个人微信 iLink Channel 集成（[#3184](https://github.com/agentscope-ai/agentscope-java/pull/3184)）。
- 钉钉新增 HTTP 回调接收模式，与 Stream 模式并存（[#3177](https://github.com/agentscope-ai/agentscope-java/pull/3177)）。
- 新增可插拔的 Channel access-token 存储和入站事件去重机制，并为 bot-loop guard 增加空闲淘汰（[#3182](https://github.com/agentscope-ai/agentscope-java/pull/3182)，[#3175](https://github.com/agentscope-ai/agentscope-java/pull/3175)）。
- 将 Channel 入站媒体消息表示为通用元数据（[#3180](https://github.com/agentscope-ai/agentscope-java/pull/3180)）。

### 变更

- 移除消息中未使用的 role 参数（[#3045](https://github.com/agentscope-ai/agentscope-java/pull/3045)）。
- Gemini 工具参数 schema 直接使用原生 JSON Schema（[#3051](https://github.com/agentscope-ai/agentscope-java/pull/3051)）。
- `Version.VERSION` 改为从 Maven 项目版本生成（[#3087](https://github.com/agentscope-ai/agentscope-java/pull/3087)）。
- 文档站迁移到 Mintlify，保留旧链接并恢复托管站点的语言切换（[#3081](https://github.com/agentscope-ai/agentscope-java/pull/3081)，[#3082](https://github.com/agentscope-ai/agentscope-java/pull/3082)，[#3276](https://github.com/agentscope-ai/agentscope-java/pull/3276)）。

### 修复

**核心 / 状态 / 并发**

- 隔离并发调用的上下文，支持独立取消运行中或排队中的执行；防止工具和技能跨会话相互干扰（[#3279](https://github.com/agentscope-ai/agentscope-java/pull/3279)，[#3283](https://github.com/agentscope-ai/agentscope-java/pull/3283)）。
- 修复版本为 0 的迁移状态产生虚假 CAS 冲突的问题，原子返回 JDBC 无条件写入版本，并在无条件写入后缓存版本（[#3165](https://github.com/agentscope-ai/agentscope-java/pull/3165)，[#3220](https://github.com/agentscope-ai/agentscope-java/pull/3220)，[#3236](https://github.com/agentscope-ai/agentscope-java/pull/3236)）。
- 状态缓存淘汰时同步清理 slot 版本，避免反序列化 `State` 标记接口，并在 Agent 返回空结果后持久化状态（[#3074](https://github.com/agentscope-ai/agentscope-java/pull/3074)，[#3048](https://github.com/agentscope-ai/agentscope-java/pull/3048)，[#3049](https://github.com/agentscope-ai/agentscope-java/pull/3049)）。
- 保留摘要失败的终止状态，并处理模型回退中的同步异常（[#2757](https://github.com/agentscope-ai/agentscope-java/pull/2757)，[#3149](https://github.com/agentscope-ai/agentscope-java/pull/3149)）。
- 为用户拒绝的 HITL 工具调用发出工具结果事件，并支持自定义拒绝原因（[#3104](https://github.com/agentscope-ai/agentscope-java/pull/3104)，[#2546](https://github.com/agentscope-ai/agentscope-java/pull/2546)）。
- 修复 JSON Schema 生成的线程安全问题（[#2796](https://github.com/agentscope-ai/agentscope-java/pull/2796)）。

**模型提供商**

- 明文 HTTP 请求默认使用 HTTP/1.1，避免 h2c upgrade 导致 vLLM / uvicorn 丢失 POST 请求体（[#3214](https://github.com/agentscope-ai/agentscope-java/pull/3214)）。
- 保留所有流式 chunk 的 reasoning details，以及 JSON 持久化往返中的 thought signatures；移除冗余的 OpenAI 工具元数据（[#3128](https://github.com/agentscope-ai/agentscope-java/pull/3128)，[#2910](https://github.com/agentscope-ai/agentscope-java/pull/2910)，[#2914](https://github.com/agentscope-ai/agentscope-java/pull/2914)）。
- 将 cache control 移至内容块（[#2878](https://github.com/agentscope-ai/agentscope-java/pull/2878)）。
- 解耦 DashScope 流式输出与 thinking 模式，支持自定义多模态端点识别，并将阻塞的 embedding SDK 调用移出当前执行线程（[#3137](https://github.com/agentscope-ai/agentscope-java/pull/3137)，[#3163](https://github.com/agentscope-ai/agentscope-java/pull/3163)，[#3265](https://github.com/agentscope-ai/agentscope-java/pull/3265)）。
- 修正 Gemini token 用量统计（[#3034](https://github.com/agentscope-ai/agentscope-java/pull/3034)）。
- Ollama 工具结果名称序列化为 `tool_name`，并将 thinking 输出表示为 `ThinkingBlock`（[#3093](https://github.com/agentscope-ai/agentscope-java/pull/3093)，[#3146](https://github.com/agentscope-ai/agentscope-java/pull/3146)）。

**Harness / 沙箱 / 集成**

- 保留非 UTF-8 文件上传内容，并在提示词中公开会话 workspace 路径（[#2456](https://github.com/agentscope-ai/agentscope-java/pull/2456)，[#3020](https://github.com/agentscope-ai/agentscope-java/pull/3020)）。
- 恢复持久化状态时重新绑定 Kubernetes 远程快照，并保留命令包裹的换行符（[#3025](https://github.com/agentscope-ai/agentscope-java/pull/3025)，[#3204](https://github.com/agentscope-ai/agentscope-java/pull/3204)）。
- 改进 Docker 沙箱原生文件传输，并拒绝截断的下载结果（[#2923](https://github.com/agentscope-ai/agentscope-java/pull/2923)）。
- 对于调用方不拥有的 AgentRun 沙箱保持 MCP 连接打开；保留 MCP 工具结果中的图片 URL（[#2302](https://github.com/agentscope-ai/agentscope-java/pull/2302)，[#3053](https://github.com/agentscope-ai/agentscope-java/pull/3053)）。
- 允许 JDBC 的沙箱形态 slot ID 包含路径分隔符（[#3233](https://github.com/agentscope-ai/agentscope-java/pull/3233)）。
- 修复 AG-UI 权限恢复后工具结果丢失，为不连续的文本段生成唯一消息 ID，并在传播终止信号前清理 active-run 标记（[#3100](https://github.com/agentscope-ai/agentscope-java/pull/3100)，[#3010](https://github.com/agentscope-ai/agentscope-java/pull/3010)，[#3109](https://github.com/agentscope-ai/agentscope-java/pull/3109)）。
- 将企业微信发送拒绝传播为流式错误（[#3173](https://github.com/agentscope-ai/agentscope-java/pull/3173)）。
- Spring Boot Starter 改为使用模块化的 core 和 AG-UI 依赖（[#3156](https://github.com/agentscope-ai/agentscope-java/pull/3156)）。
- 归一化 Milvus L2 检索分数，并转义 WordReader Markdown 表格单元格（[#3070](https://github.com/agentscope-ai/agentscope-java/pull/3070)，[#3169](https://github.com/agentscope-ai/agentscope-java/pull/3169)）。

### 文档

- 新增中英文 Agent Harness 构建指南，重建 AgentScope Service 产品使用指南（[#3139](https://github.com/agentscope-ai/agentscope-java/pull/3139)，[#3136](https://github.com/agentscope-ai/agentscope-java/pull/3136)）。
- 补充 Jev 结构化决策集成文档，并修正文档中的 `CompactionConfig.keepTokens` 默认值（[#3274](https://github.com/agentscope-ai/agentscope-java/pull/3274)，[#3144](https://github.com/agentscope-ai/agentscope-java/pull/3144)）。

**完整更新记录:** [v2.0.3...v2.0.4](https://github.com/agentscope-ai/agentscope-java/compare/v2.0.3...v2.0.4)

---

## 2.0.1

> 发布日期：2026-08-05

AgentScope Java 2.0.1 是 2.0.0 GA 之后的首个维护版本，重点补齐模型提供商生态、完善 Harness 子 agent / HITL / 权限与稳定性，并修复一批生产场景中的关键问题。

**快速链接：** [快速开始](/v2/zh/docs/quickstart) | [V1 迁移指南](/v2/zh/docs/change-log) | [上线指南](/v2/zh/docs/others/going-to-production)

### 新增

**核心 / Agent**

- Middleware 支持执行优先级排序：`MiddlewareBase.order()`，数值越高越外层；`ReActAgent.Builder.build()` 会在注册完成后稳定降序排序 ([#2532](https://github.com/agentscope-ai/agentscope-java/pull/2532), [#2449](https://github.com/agentscope-ai/agentscope-java/issues/2449))
- 新增 Session 上下文清理 API：`ReActAgent` / `HarnessAgent` 支持在不换 session 的情况下清空模型可见对话上下文 ([#2499](https://github.com/agentscope-ai/agentscope-java/pull/2499), [#2496](https://github.com/agentscope-ai/agentscope-java/issues/2496))
- 暴露 `ReActAgent` 状态缓存清理 API，便于长生命周期实例主动释放缓存 ([#2572](https://github.com/agentscope-ai/agentscope-java/pull/2572))
- HITL 恢复权限确认时发射 `UserConfirmResultEvent`，可与前置 `RequireUserConfirmEvent` 通过 `replyId` 关联 ([#2511](https://github.com/agentscope-ai/agentscope-java/pull/2511))
- Anthropic 支持配置 `disable_parallel_tool_use` ([#2257](https://github.com/agentscope-ai/agentscope-java/pull/2257))

**模型提供商**

- 新增 OpenAI 兼容扩展包，为三方兼容厂商提供统一适配基础 ([#2208](https://github.com/agentscope-ai/agentscope-java/pull/2208))
- 新增 DeepSeek 一等公民模型提供商（`deepseek:<model>`、`DEEPSEEK_API_KEY`）([#2307](https://github.com/agentscope-ai/agentscope-java/pull/2307), [#2211](https://github.com/agentscope-ai/agentscope-java/issues/2211))
- 新增 GLM（智谱 AI）提供商与专用 formatter ([#2316](https://github.com/agentscope-ai/agentscope-java/pull/2316))
- 新增 Kimi（Moonshot AI）提供商与专用 formatter ([#2320](https://github.com/agentscope-ai/agentscope-java/pull/2320), [#2213](https://github.com/agentscope-ai/agentscope-java/issues/2213))
- 新增 MiniMax OpenAI 兼容提供商 ([#2299](https://github.com/agentscope-ai/agentscope-java/pull/2299))

**Harness / 工具**

- 远程子 agent 支持事件流转发与 HITL resume ([#2559](https://github.com/agentscope-ai/agentscope-java/pull/2559))
- 异步工具结果等待支持按 `taskId` 精确等待 ([#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- 支持通过 `AGENTSCOPE_WORKSPACE` 环境变量配置默认工作区，便于镜像打包 ([#2310](https://github.com/agentscope-ai/agentscope-java/pull/2310))

**AG-UI**

- 升级 AG-UI 模块事件机制 ([#2306](https://github.com/agentscope-ai/agentscope-java/pull/2306), [#2202](https://github.com/agentscope-ai/agentscope-java/issues/2202))
- 引入类型化 `MessageContent` / `InputContent`，支持多模态 AG-UI 消息 ([#2518](https://github.com/agentscope-ai/agentscope-java/pull/2518), [#551](https://github.com/agentscope-ai/agentscope-java/issues/551))

**Spring Boot Starters**

- 新增 Ollama Spring Boot Starter ([#2176](https://github.com/agentscope-ai/agentscope-java/pull/2176), [#2172](https://github.com/agentscope-ai/agentscope-java/issues/2172))

### 重构

- Toolkit 默认执行模式改为并行，并完善相关文档 ([#2558](https://github.com/agentscope-ai/agentscope-java/pull/2558), follow-up of [#2529](https://github.com/agentscope-ai/agentscope-java/pull/2529))
- 抽象 Session metadata 存储，解耦 builder 与具体存储实现 ([#2258](https://github.com/agentscope-ai/agentscope-java/pull/2258), [#2068](https://github.com/agentscope-ai/agentscope-java/issues/2068))
- Kubernetes 沙箱存储迁移至 [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) CRD / 控制器模型，由集群侧负责沙箱生命周期与预热池 ([#2308](https://github.com/agentscope-ai/agentscope-java/pull/2308))

### 修复

**核心 / Agent**

- 防止 pending recovery 误消耗 HITL 审批结果 ([#2109](https://github.com/agentscope-ai/agentscope-java/pull/2109), [#2534](https://github.com/agentscope-ai/agentscope-java/issues/2534))
- `onModelCall` middleware 对文本 delta 的变换会正确应用到最终消息，修复原生结构化输出读到陈旧文本的问题 ([#2469](https://github.com/agentscope-ai/agentscope-java/pull/2469), [#2385](https://github.com/agentscope-ai/agentscope-java/issues/2385))
- 流式工具参数为 null 时，可从完整原始 JSON 修复补全 ([#2451](https://github.com/agentscope-ai/agentscope-java/pull/2451), [#768](https://github.com/agentscope-ai/agentscope-java/issues/768))
- `ReActAgent.close()` 解绑 state-saver，避免优雅停机注册表堆积导致 OOM ([#2322](https://github.com/agentscope-ai/agentscope-java/pull/2322), [#2321](https://github.com/agentscope-ai/agentscope-java/issues/2321))
- `ReActAgent.close()` 解绑 `ShutdownStateSaver`，修复内存泄漏 ([#2384](https://github.com/agentscope-ai/agentscope-java/pull/2384))
- 用户中断正确标记为 interrupted reason ([#2260](https://github.com/agentscope-ai/agentscope-java/pull/2260))
- 写入 agent 状态文件时容错畸形 Unicode，避免 `UnmappableCharacterException` ([#2255](https://github.com/agentscope-ai/agentscope-java/pull/2255), [#2204](https://github.com/agentscope-ai/agentscope-java/issues/2204))
- 转发 reasoning middleware 事件（如 `InboxMiddleware` 的 `HintBlockEvent`）到 `streamEvents()` ([#2179](https://github.com/agentscope-ai/agentscope-java/pull/2179), [#2160](https://github.com/agentscope-ai/agentscope-java/issues/2160))
- `ToolResultBlock.error` 标记为结构化错误 ([#2174](https://github.com/agentscope-ai/agentscope-java/pull/2174), [#2157](https://github.com/agentscope-ai/agentscope-java/issues/2157), [#2111](https://github.com/agentscope-ai/agentscope-java/issues/2111))

**模型提供商**

- DashScope：将 `qwen3.8-max` 路由到多模态端点 ([#2553](https://github.com/agentscope-ai/agentscope-java/pull/2553))
- DashScope：保留 SSE 错误响应体，便于读取 `request_id` ([#2278](https://github.com/agentscope-ai/agentscope-java/pull/2278), [#2197](https://github.com/agentscope-ai/agentscope-java/issues/2197))
- OpenAI：流式分支用 `Flux.defer` 包裹，使重试能重新发起 HTTP 请求 ([#2079](https://github.com/agentscope-ai/agentscope-java/pull/2079))
- OpenAI：遇到 `[DONE]` 哨兵正确终止流 ([#2104](https://github.com/agentscope-ai/agentscope-java/pull/2104))
- OpenAI：丢弃非 chunk 的 summary 事件消息，避免内容重复 ([#2367](https://github.com/agentscope-ai/agentscope-java/pull/2367))
- OpenAI：`OpenAIMessageConverter` 规范化 `name` 字段 ([#2346](https://github.com/agentscope-ai/agentscope-java/pull/2346))
- OpenAI AutoConfiguration：api-key 改为可选 ([#2175](https://github.com/agentscope-ai/agentscope-java/pull/2175))
- DeepSeek formatter 保留 `system` role ([#2189](https://github.com/agentscope-ai/agentscope-java/pull/2189), [#2168](https://github.com/agentscope-ai/agentscope-java/issues/2168))
- Ollama：正确遵循 `stream` 标志 ([#2415](https://github.com/agentscope-ai/agentscope-java/pull/2415))
- Anthropic：`ToolChoice.None` 正确映射为禁止工具调用（此前误映射为强制调用）([#2232](https://github.com/agentscope-ai/agentscope-java/pull/2232), [#2221](https://github.com/agentscope-ai/agentscope-java/issues/2221))
- 模型提供商优化与兼容性调整 ([#2474](https://github.com/agentscope-ai/agentscope-java/pull/2474))

**Harness / 工具 / 沙箱**

- 远程子 agent 转发事件时正确打上 `taskId` ([#2575](https://github.com/agentscope-ai/agentscope-java/pull/2575))
- Memory prompt guidance 受 disable 标志门控 ([#2565](https://github.com/agentscope-ai/agentscope-java/pull/2565))
- 子 agent 结束事件在父 agent 完成前发出，避免事件丢失 ([#2544](https://github.com/agentscope-ai/agentscope-java/pull/2544))
- 父 agent 取消时正确关闭子 agent 事件流 ([#2481](https://github.com/agentscope-ai/agentscope-java/pull/2481), [#2480](https://github.com/agentscope-ai/agentscope-java/issues/2480))
- 派生子 agent 强制继承父级 DENY 权限规则 ([#2477](https://github.com/agentscope-ai/agentscope-java/pull/2477))
- Skill promotion 过程中保留 `RuntimeContext` ([#2465](https://github.com/agentscope-ai/agentscope-java/pull/2465))
- 子 agent 强制执行 Plan Mode ([#2377](https://github.com/agentscope-ai/agentscope-java/pull/2377))
- 拒绝 workspace 路径穿越（如 `../`）([#2358](https://github.com/agentscope-ai/agentscope-java/pull/2358))
- Windows 下本地 shell 执行兼容（工作目录命令与字符集解码）([#2304](https://github.com/agentscope-ai/agentscope-java/pull/2304), [#2268](https://github.com/agentscope-ai/agentscope-java/issues/2268))
- 静态子 agent 注册表按 runtime context 隔离，避免多租户串扰 ([#2371](https://github.com/agentscope-ai/agentscope-java/pull/2371), [#2328](https://github.com/agentscope-ai/agentscope-java/issues/2328))
- 链式 compaction 保留先前摘要，避免丢失用户意图 ([#2360](https://github.com/agentscope-ai/agentscope-java/pull/2360))
- 保留 skill 隔离与工具结果历史 ([#2319](https://github.com/agentscope-ai/agentscope-java/pull/2319))
- `RemoteFilesystem` 递归 glob 能匹配搜索根目录下的文件 ([#2343](https://github.com/agentscope-ai/agentscope-java/pull/2343))
- FilesystemTool 可选参数标记为 `required=false` ([#2227](https://github.com/agentscope-ai/agentscope-java/pull/2227))
- 优化 shell-execute 的 `working_directory` 参数与工具使用提示 ([#2107](https://github.com/agentscope-ai/agentscope-java/pull/2107))
- 声明式子 agent 继承父级 `modelExecutionConfig` / `toolExecutionConfig` ([#2252](https://github.com/agentscope-ai/agentscope-java/pull/2252))
- 修正 `sessionId` 参数描述 ([#2195](https://github.com/agentscope-ai/agentscope-java/pull/2195))

**存储 / 传输**

- PostgreSQL BaseStore 支持 schema ([#2273](https://github.com/agentscope-ai/agentscope-java/pull/2273), [#2192](https://github.com/agentscope-ai/agentscope-java/issues/2192))
- 修复 PostgreSQL upsert SQL 语法错误 ([#2167](https://github.com/agentscope-ai/agentscope-java/pull/2167), [#2166](https://github.com/agentscope-ai/agentscope-java/issues/2166))
- 修复 `JdkHttpTransport` SSE 长连接被绝对超时切断的问题 ([#1322](https://github.com/agentscope-ai/agentscope-java/pull/1322), [#1302](https://github.com/agentscope-ai/agentscope-java/issues/1302))

**Spring Boot / Examples**

- 修复 Spring Boot starter 包名错误 ([#2264](https://github.com/agentscope-ai/agentscope-java/pull/2264))
- 示例改用原始 DashScope 模型名（去掉无效 `dashscope:` 前缀）([#2318](https://github.com/agentscope-ai/agentscope-java/pull/2318))
- 修正 `RuntimeContextExample` 中的 DashScope 模型名 ([#2228](https://github.com/agentscope-ai/agentscope-java/pull/2228), [#2229](https://github.com/agentscope-ai/agentscope-java/issues/2229))
- 修正 skill 示例资源路径 ([#2250](https://github.com/agentscope-ai/agentscope-java/pull/2250))
- 改进文档与示例 ([#2508](https://github.com/agentscope-ai/agentscope-java/pull/2508))

### 文档

- README 补充 Agent Evolution 到 Java 2.0 特性列表 ([#2494](https://github.com/agentscope-ai/agentscope-java/pull/2494))
- Quickstart 澄清 all-in-one 依赖已包含模型提供商 ([#2425](https://github.com/agentscope-ai/agentscope-java/pull/2425), [#840](https://github.com/agentscope-ai/agentscope-java/issues/840))
- 文档链接重定向修复 ([#2203](https://github.com/agentscope-ai/agentscope-java/pull/2203), [#2198](https://github.com/agentscope-ai/agentscope-java/issues/2198))
- 生成按版本拆分的 `llms.txt` 产物（`/v1`、`/v2`）([#2188](https://github.com/agentscope-ai/agentscope-java/pull/2188), [#2185](https://github.com/agentscope-ai/agentscope-java/issues/2185))
- 补充 model builder customizer 文档 ([#2092](https://github.com/agentscope-ai/agentscope-java/pull/2092))
- 模型文档更新 ([#2100](https://github.com/agentscope-ai/agentscope-java/pull/2100))
- 修正 README 文档链接与 Release Notes URL ([#2099](https://github.com/agentscope-ai/agentscope-java/pull/2099))
- 更新 AG-UI 文档 ([#2274](https://github.com/agentscope-ai/agentscope-java/pull/2274))

---

## 2.0.0 (GA)

> 发布日期：2026-07-10

AgentScope Java 2.0.0 正式发布（General Availability）。这是从 1.x 到 2.0 的首个正式版本，标志着 AgentScope Java 从"透明开发"迈向"系统工程"的里程碑。

**快速链接：** [快速开始](/v2/zh/docs/quickstart) | [V1 迁移指南](/v2/zh/docs/change-log) | [上线指南](/v2/zh/docs/others/going-to-production)

### 2.0 版本核心设计概要

AgentScope Java 2.0 围绕"让智能体稳定完成任务"这一目标进行了系统性升级，核心设计如下：

**双层 Agent 架构**

- **ReActAgent**：无状态的推理核心，提供"推理 → 工具调用 → 回复"的 ReAct 循环。2.0 中 Agent 实例完全无状态，所有 per-call 可变状态通过 Reactor Context 透传，同一实例可安全并发服务多个 `(userId, sessionId)` 组合
- **HarnessAgent**：在 ReActAgent 之上通过 Middleware 与 Toolkit 两个扩展通道，叠加工作区、记忆、沙箱、子 agent、技能与计划模式等工程化基础设施——核心推理循环原样保留，只叠加不替换

**消息与事件流**

统一的 ContentBlock 消息模型（TextBlock / DataBlock / ToolUseBlock / ToolResultBlock / HintBlock 等），配合 `streamEvents()` 返回的 28 种类型化 AgentEvent，让 Agent 的执行过程可展示、可交互、可干预。前端 UI 可实时跟随文本增量、工具调用、用户确认等全生命周期事件

**权限系统**

全新的 PermissionEngine 为工具调用建立"允许 / 用户审批 / 拒绝"三态决策机制。根据静态规则、工具类型和输入内容综合判断，敏感操作自动进入 HITL 审批流程

**Middleware 扩展机制**

五阶段洋葱 + 管道混合模型（`onAgent` / `onReasoning` / `onActing` / `onModelCall` / `onSystemPrompt`），在保持核心框架稳定的同时，为日志追踪、安全检查、业务策略、上下文注入等提供灵活的扩展点

**上下文工程**

结构化压缩保留任务目标、当前状态、关键发现与下一步计划；超大工具结果自动落盘，上下文仅保留占位符；文件读写内置缓存并强制"先读后改"策略

**Workspace 执行环境抽象**

将"Agent 做什么"与"在哪里执行"解耦。本地文件系统、Docker 容器、Kubernetes、E2B 云沙箱等执行后端统一到同一套接口。内置预热池机制，适配 RL rollout 等并行场景

**模型容错**

统一的 Credential + ModelRegistry 抽象，覆盖 Qwen / OpenAI / Anthropic / Gemini / DeepSeek / Ollama 等主流模型。可配置最大重试与备用模型，主模型不可用时自动切换

**企业级分布式部署**

`DistributedBackend` 一键配置（Redis / OSS / MySQL / PostgreSQL / COS），`AgentStateStore` 按 `(userId, sessionId)` 自动分桶持久化。Session 跨副本恢复、沙箱状态快照、子 agent 跨副本路由

**协议互通**

内置 A2A（Agent-to-Agent）与 MCP（Model Context Protocol）协议支持，以及 AG-UI 协议适配，覆盖智能体间通信与前端展示的标准化需求

**多智能体编排**

声明式子 agent 规格定义（YAML / Markdown），运行时按需 `agent_spawn` / `agent_send`，支持同步阻塞与后台委派两种模式。子 agent 事件流可实时转发到父 agent 的 `streamEvents()`

**技能系统**

四层 Skill 合成（Classpath / FileSystem / Nacos / Marketplace）+ SkillFilter 细粒度过滤 + 自学习闭环（propose → curate → promote）

---

### 自 RC5 以来的变更

以下为 2.0.0-RC5（2026-07-07）至 GA 版本之间的增量变更。

#### 新增

- 当 HITL 拒绝所有工具调用时触发 `AllToolsDeniedEvent` hook，方便应用层监听和处理全拒绝场景 ([#2083](https://github.com/agentscope-ai/agentscope-java/pull/2083))
- `wait_async_results` 增加防护机制，防止反复长时间阻塞等待 ([#2093](https://github.com/agentscope-ai/agentscope-java/pull/2093))
- 新增 `PostgresDistributedStore`，支持 PostgreSQL 作为 HarnessAgent 的分布式后端 ([#2054](https://github.com/agentscope-ai/agentscope-java/pull/2054))
- Spring Boot Starter 新增 OpenAI、DashScope、Anthropic 模型的 builder customizer，简化自动配置 ([#2045](https://github.com/agentscope-ai/agentscope-java/pull/2045))

#### 修复

**核心 / Agent**

- `seedSystemMsg` 改为 reactive 实现，避免在 NIO 线程上调用 `block()` ([#2086](https://github.com/agentscope-ai/agentscope-java/pull/2086))
- PERMISSION_ASKING 状态的结果消息中正确包含 ASKING 状态的 ToolUseBlock ([#2082](https://github.com/agentscope-ai/agentscope-java/pull/2082))
- 通过 `activateOnSkill` 字段正确激活 SkillToolGroup ([#2057](https://github.com/agentscope-ai/agentscope-java/pull/2057))
- 用户中断时保存 agent 状态，防止会话丢失 ([#1970](https://github.com/agentscope-ai/agentscope-java/pull/1970))

**模型提供商**

- Anthropic：将并行 tool calls 拆分为交替排列的消息，符合 API 要求 ([#2090](https://github.com/agentscope-ai/agentscope-java/pull/2090))
- OpenAI：`nativeStructuredOutput` 改为可配置 ([#2069](https://github.com/agentscope-ai/agentscope-java/pull/2069))

**Harness / 工具 / 沙箱**

- 外部工具执行现在正确产生 suspended 结果 ([#2071](https://github.com/agentscope-ai/agentscope-java/pull/2071))
- Plan Mode 下允许 SkillLoadTool，通过将 `isReadOnly` 提升到 AgentTool 接口实现 ([#2067](https://github.com/agentscope-ai/agentscope-java/pull/2067))
- 中断孤儿子 agent：当 AgentSpawnTool 的父订阅取消时正确中断子 agent ([#2064](https://github.com/agentscope-ai/agentscope-java/pull/2064))
- MemoryFlushMiddleware 移除不必要的 ReActAgent 类型限制 ([#2078](https://github.com/agentscope-ai/agentscope-java/pull/2078))
- ROOTED 模式下将以 `/` 开头的路径解析为相对于 workspace ([#2049](https://github.com/agentscope-ai/agentscope-java/pull/2049))
- workspace projection 前预先部署 marketplace 技能 ([#2059](https://github.com/agentscope-ai/agentscope-java/pull/2059))
- Kubernetes `hydrateWithArchive` 中 null exit code 视为成功 ([#1915](https://github.com/agentscope-ai/agentscope-java/pull/1915))
- 恢复持久化状态时使用更新后的 WorkspaceSpec ([#1928](https://github.com/agentscope-ai/agentscope-java/pull/1928))
- AgentRun MCP 响应支持嵌套 JSON 和 banner 前缀 ([#1930](https://github.com/agentscope-ai/agentscope-java/pull/1930))
- Docker workspaceRoot 使用解析后的 workingDir ([#2033](https://github.com/agentscope-ai/agentscope-java/pull/2033))

**Channel**

- OutboundAddress 中包含 PeerKind，修复群组消息路由 ([#2060](https://github.com/agentscope-ai/agentscope-java/pull/2060))

**A2A**

- 合并流式文本 chunk，避免碎片化 ([#2058](https://github.com/agentscope-ai/agentscope-java/pull/2058))

---

## 2.0.0-RC5

> 发布日期：2026-07-07

### 重大变更

- **模型提供商模块化** —— OpenAI、Gemini、Anthropic、DashScope、Ollama 模型提供商从 `agentscope-core` 拆分为独立的 `agentscope-extensions-model-*` 扩展模块。应用需添加对应扩展依赖 ([#1890](https://github.com/agentscope-ai/agentscope-java/pull/1890), [#1916](https://github.com/agentscope-ai/agentscope-java/pull/1916), [#1947](https://github.com/agentscope-ai/agentscope-java/pull/1947), [#1972](https://github.com/agentscope-ai/agentscope-java/pull/1972))

### 新增

- 所有模型提供商（OpenAI、DashScope、Gemini、Anthropic）统一支持 `DataBlock` 多模态内容，覆盖单 agent、多 agent 和工具结果路径 ([#1933](https://github.com/agentscope-ai/agentscope-java/pull/1933))
- 原生结构化输出（Structured Output）与工具调用协同工作 —— 支持模型原生 JSON Schema 约束 ([#1904](https://github.com/agentscope-ai/agentscope-java/pull/1904))
- DashScope 模型支持原生结构化输出 ([#1935](https://github.com/agentscope-ai/agentscope-java/pull/1935))
- `McpClientBuilder` 新增 `httpRequestCustomizer`，支持动态 token 注入（如 OAuth 刷新）([#1992](https://github.com/agentscope-ai/agentscope-java/pull/1992))
- `AguiEvent` 对齐 AG-UI 协议规范，补全缺失事件类型 ([#1862](https://github.com/agentscope-ai/agentscope-java/pull/1862))
- 子 agent 可选技能白名单过滤 ([#1873](https://github.com/agentscope-ai/agentscope-java/pull/1873))
- `NacosSkillRepository` 支持 `knownSkillNames` ([#1853](https://github.com/agentscope-ai/agentscope-java/pull/1853))
- 新增 `CosAgentStateStore`、`CosBaseStore`、`CosDistributedStore`，支持腾讯云 COS 状态持久化 ([#1857](https://github.com/agentscope-ai/agentscope-java/pull/1857))
- `ChatUsage` 暴露 cached prompt tokens ([#1868](https://github.com/agentscope-ai/agentscope-java/pull/1868))

### 修复

**核心 / Agent**

- 用户中断恢复时持久化 agent 状态 ([#2008](https://github.com/agentscope-ai/agentscope-java/pull/2008))
- 正确连线 fallback model 到 `ReActAgent` ([#1851](https://github.com/agentscope-ai/agentscope-java/pull/1851))
- 修复 `ReActAgent` 流式事件 block end 顺序 ([#1829](https://github.com/agentscope-ai/agentscope-java/pull/1829))
- 在加入 agent 上下文前更新 `ToolResultBlock` 状态 ([#1886](https://github.com/agentscope-ai/agentscope-java/pull/1886))
- 复用 classpath skill JAR 文件系统，避免资源泄漏 ([#1981](https://github.com/agentscope-ai/agentscope-java/pull/1981))
- 修复 `serializeOnKey` 在 `Flux.create` 回调中的 gate 泄漏 ([#1796](https://github.com/agentscope-ai/agentscope-java/pull/1796))

**模型提供商**

- 将 `thinkingBudget` 映射到 OpenAI 兼容 API 请求 ([#2028](https://github.com/agentscope-ai/agentscope-java/pull/2028))
- 修复 Anthropic 流式 thinking event 处理 ([#1943](https://github.com/agentscope-ai/agentscope-java/pull/1943))
- 保留 `OllamaOptions` `fromOptions`/`toBuilder` 中的 `executionConfig` ([#2011](https://github.com/agentscope-ai/agentscope-java/pull/2011))
- DashScope thinking 模式下降级强制 tool choice ([#1882](https://github.com/agentscope-ai/agentscope-java/pull/1882))

**Harness / 沙箱**

- 修复远程快照状态反序列化 —— Jackson 往返后重新注入 `RemoteSnapshotClient` ([#2013](https://github.com/agentscope-ai/agentscope-java/pull/2013))
- 修复 THROTTLED 记忆保存模式在每次请求新建实例时失效 ([#1788](https://github.com/agentscope-ai/agentscope-java/pull/1788))
- 通过 wakeup dispatch 传播 `userId` ([#2001](https://github.com/agentscope-ai/agentscope-java/pull/2001))
- message bus 心跳改用 `boundedElastic` 调度器 ([#1974](https://github.com/agentscope-ai/agentscope-java/pull/1974))
- 避免 `fromAgent` 重复注册 `GracefulShutdownMiddleware` ([#1952](https://github.com/agentscope-ai/agentscope-java/pull/1952))
- 转义 `ShellPathPolicy` 返回路径中的空格 ([#2031](https://github.com/agentscope-ai/agentscope-java/pull/2031))
- YAML 解析失败时回退到简单键值提取 ([#2027](https://github.com/agentscope-ai/agentscope-java/pull/2027))
- `ls` 命令报告沙箱文件大小 ([#1838](https://github.com/agentscope-ai/agentscope-java/pull/1838))
- 归一化 Windows `list_files` 路径 ([#1892](https://github.com/agentscope-ai/agentscope-java/pull/1892))
- `LocalFilesystem.edit()` 将 `\r\n` 归一化为 `\n` ([#2020](https://github.com/agentscope-ai/agentscope-java/pull/2020))
- `CompositeFilesystem` 将 `"."` 视为根路径 ([#1830](https://github.com/agentscope-ai/agentscope-java/pull/1830))
- 校验 `working_directory` 防止命名空间逃逸 ([#1834](https://github.com/agentscope-ai/agentscope-java/pull/1834))
- 未配置分布式 `AgentStateStore` 时回退到 `LocalFilesystemSpec` ([#1841](https://github.com/agentscope-ai/agentscope-java/pull/1841))
- 修复 Kubernetes `hydrateWithArchive` WebSocket 竞态导致 `exit=null` ([#1903](https://github.com/agentscope-ai/agentscope-java/pull/1903))
- 容忍 wrapped sandbox base64 下载 ([#1866](https://github.com/agentscope-ai/agentscope-java/pull/1866))
- 移除 `AgentRun` sandbox API 版本前缀 ([#1891](https://github.com/agentscope-ai/agentscope-java/pull/1891))
- E2B sandbox 新增 connect JSON codec 支持 ([#1844](https://github.com/agentscope-ai/agentscope-java/pull/1844))

**链路追踪 / 可观测性**

- 从 Reactor `ContextView` 读取父 OTel Context，修复 `OtelTracingMiddleware` 孤立 span ([#1940](https://github.com/agentscope-ai/agentscope-java/pull/1940))
- 修复 `OtelTracingMiddleware` 子 span 看不到正确父 span ([#1909](https://github.com/agentscope-ai/agentscope-java/pull/1909))
- 将 Reactor context 传播到 chunk event hooks ([#1923](https://github.com/agentscope-ai/agentscope-java/pull/1923))

**子 Agent**

- 将父 `RuntimeContext` 传播到子 agent ([#1833](https://github.com/agentscope-ai/agentscope-java/pull/1833))
- 将父 middleware 传播到子 agent ([#1843](https://github.com/agentscope-ai/agentscope-java/pull/1843))

**A2A**

- 处理流式背压 ([#1734](https://github.com/agentscope-ai/agentscope-java/pull/1734))
- A2A 转换保留 AgentScope 消息角色 ([#1995](https://github.com/agentscope-ai/agentscope-java/pull/1995))

**AG-UI**

- 传播 run input 和 frontend tools ([#1895](https://github.com/agentscope-ai/agentscope-java/pull/1895))

**其他**

- Middleware `doFlush` 包装 `Mono.defer` 防止提前求值 ([#1880](https://github.com/agentscope-ai/agentscope-java/pull/1880))
- Nacos auto-configurations 改为 opt-in（`matchIfMissing=false`）并修复 A2A server-addr 覆盖 ([#1709](https://github.com/agentscope-ai/agentscope-java/pull/1709))
- DataAgent 补充 `ObjectMapper` bean ([#1993](https://github.com/agentscope-ai/agentscope-java/pull/1993))

### 文档

- 明确流式事件 `blockId` 语义 ([#2016](https://github.com/agentscope-ai/agentscope-java/pull/2016))
- 改进模型提供商文档 ([#1986](https://github.com/agentscope-ai/agentscope-java/pull/1986))
- 移除无效的 `ChatResponse.isLast` 引用 ([#1921](https://github.com/agentscope-ai/agentscope-java/pull/1921))
- 修复多副本 Redis 示例 —— 声明 jedis 依赖并补充 `stateStore` ([#1869](https://github.com/agentscope-ai/agentscope-java/pull/1869))
- 修复 `MemoryCompactionExample` 展示 memory 文件并触发 compaction ([#1978](https://github.com/agentscope-ai/agentscope-java/pull/1978))

---

## 2.0.0-RC4

> 发布日期：2026-06-18

### 新增

- Agent harness 支持异步工具执行和通知，包括 message bus、async tool registry 和 scheduled wakeup dispatching ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- Agent 调用新增 String/Message 便捷重载；所有 formatter 支持 `HintBlock` ([#1802](https://github.com/agentscope-ai/agentscope-java/pull/1802))
- 持久化 spawn registry 支持子 agent 跨副本路由和 session 恢复 ([#1817](https://github.com/agentscope-ai/agentscope-java/pull/1817))
- `DynamicSkillMiddleware` 实现 `ToolkitAware`，支持动态接收解析后的 toolkit ([#1828](https://github.com/agentscope-ai/agentscope-java/pull/1828))
- Kubernetes sandbox 支持向 Pod 注入环境变量 ([#1789](https://github.com/agentscope-ai/agentscope-java/pull/1789))

### 修复

- 修复 Kubernetes 文件上传中的 SIGKILL 竞态条件，使用两阶段 archive 策略 ([#1826](https://github.com/agentscope-ai/agentscope-java/pull/1826))
- 修复超时子 agent 未在重试时中断导致的资源泄漏 ([#1784](https://github.com/agentscope-ai/agentscope-java/pull/1784))
- 修复复制 `RuntimeContext` 时丢失 typed attributes ([#1813](https://github.com/agentscope-ai/agentscope-java/pull/1813))
- 修复 MySQL utf8mb4 字符集下 `JdbcStore` 表初始化失败 ([#1781](https://github.com/agentscope-ai/agentscope-java/pull/1781))
- Session JSONL offload 改为幂等，防止重复写入 ([#1774](https://github.com/agentscope-ai/agentscope-java/pull/1774))
- 修复 `TelemetryTracer` 中 OpenTelemetry context 传播 ([#1799](https://github.com/agentscope-ai/agentscope-java/pull/1799))
- 修复 `OllamaChatModel` 获取 tool choice 时 options 为 null 导致 NPE ([#1803](https://github.com/agentscope-ai/agentscope-java/pull/1803))
- 补充 `LocalSandboxSnapshot` 缺失的 Jackson 注解 ([#1825](https://github.com/agentscope-ai/agentscope-java/pull/1825))
- 修复 sandbox glob 不支持 `**/` 递归模式 ([#1684](https://github.com/agentscope-ai/agentscope-java/pull/1684))
- 修复 `SkillFilter` 使用 composite ID 而非 skill name 匹配 ([#1771](https://github.com/agentscope-ai/agentscope-java/pull/1771))
- 允许 `MultiModalTool` 使用自定义默认视觉模型 ([#1701](https://github.com/agentscope-ai/agentscope-java/pull/1701))

### 文档

- 修复 middleware 文档中错误的 hook 签名 ([#1835](https://github.com/agentscope-ai/agentscope-java/pull/1835))
- 修复文档示例中引用不存在的 `.sandboxContext()` ([#1792](https://github.com/agentscope-ai/agentscope-java/pull/1792))
- 修复 v2 文档中 `getToolName()` → `getToolCallName()` ([#1760](https://github.com/agentscope-ai/agentscope-java/pull/1760))
- 文档站点新增 AI 上下文菜单

---

## 2.0.0-RC3

> 发布日期：2026-06-11

### 新增

- **`AgentResultEvent`** —— 新增事件类型，在 agent 调用完成后、`AgentEndEvent` 之前发出，携带最终 `Msg` 结果。`streamEvents()` 的消费方可直接从事件流中获取最终结果，无需额外订阅 `Mono<Msg>` 返回值
- **`CustomEvent`** —— 通用可扩展事件，用于中间件向前端推送应用级通知（状态变更、团队变更等），无需为每种业务场景新增 `AgentEventType`。内置 well-known name：`state_updated`、`team_updated`
- **`HintBlockEvent`** —— 一次性 hint block 事件，用于传递团队消息、后台工具结果、用户中断等完整内容，区别于需要流式拼接的 text/thinking block
- **`WorkspacePathNormalizer`** —— 文件路径归一化工具，将绝对路径转换为 workspace 相对路径。根据当前文件系统模式（本地 / 沙箱）注册前缀，避免跨模式误匹配
- **工具事件携带 `toolCallName`** —— `ToolCallDeltaEvent`、`ToolCallEndEvent`、`ToolResultDataDeltaEvent`、`ToolResultEndEvent`、`ToolResultTextDeltaEvent` 均新增 `toolCallName` 字段，消费端不再需要缓存 start 事件的名称映射

### 变更

- **`call()` 与 `streamEvents()` 共享执行核心** —— 新增内部 `buildAgentStream` 方法作为 `call()` 和 `streamEvents()` 的统一实现，确保 `onAgent` middleware 链在所有调用路径上一致触发。`call()` 从事件流中提取 `AgentResultEvent` 获得结果，移除了旧的独立 `agentImpl` 逻辑
- **分布式部署下 session 状态始终从 store 加载** —— `activateSlotForContext` 在配置了 `AgentStateStore` 时，每次调用开头从 store 重新加载状态和权限引擎，避免分布式环境中同一 sessionId 漂移到不同机器时读到过期本地缓存
- **`ToolResultEvictionMiddleware` 时机修正** —— 从 `onActing`（此时状态尚未写入，导致空操作）迁移到 `onReasoning`，确保工具结果已持久化后再执行淘汰
- **`LocalFilesystem` 路径解析简化** —— 重构路径解析逻辑，减少冗余代码

### 修复

- 修复 `RuntimeContext` 在测试中未设置 `userId` 导致用户隔离不准确的问题

---

## 2.0.0-RC2

> 发布日期：2026-06-09

### 新增

- **`projectWritable` 模式**（`LocalFilesystemSpec`）—— 开启后，agent 的文件写入按路径自动路由：工作区元数据（`MEMORY.md`、`agents/`、`skills/` 等）写到 workspace，其余文件（代码、配置等）直接落到项目目录。适合代码生成类 agent。详见 [文件系统 · 项目可写模式](/v2/zh/docs/harness/filesystem#项目可写模式projectwritable)
- **Permission 系统运行时切换** —— 新增 `HarnessAgent.setPermissionMode()` / `getPermissionMode()`，支持在运行时按 session 动态调整权限模式
- **子 agent 事件流转发** —— `streamEvents()` 现在实时转发子 agent 的中间事件（`TextBlockDelta`、`ToolCallStart` 等），每个事件携带 `source` 路径标识来源
- **`AgentEvent.source` 来源标识** —— 所有 `AgentEvent` 新增 `source` 字段，在同一事件流中区分 main agent 事件（`source = null`）和 sub agent 事件（`source = "main/researcher"` 等路径格式），消费端无需额外状态即可分流处理
- **Compaction / Memory 定制 prompt 和 model** —— `CompactionConfig` 和 `MemoryConfig` 新增 `.model()` 和 `.prompt()` builder 方法，允许为上下文压缩和记忆提取指定独立的轻量模型和自定义 prompt，不再强制使用 agent 主模型
- **Qwen 3.7 模型支持** —— `ModelRegistry` 新增 `dashscope:qwen3.7-plus` 等 Qwen 3.7 系列模型的解析支持
- **直接与子 agent 对话** —— 支持通过 `agent_send` 直接向已声明的子 agent 发送消息并获取响应，无需经过父 agent 的推理循环
- **Channel 模块** —— 新增 `agentscope-extensions-channel` 系列模块，实现 IM 平台接入（钉钉、飞书、企业微信、GitHub、GitLab），内置 ChatUI 提供开箱即用的对话界面
- **`DistributedBackend` 统一接口** —— 新增 `DistributedBackend` 抽象，收敛分布式部署所需的所有存储组件（`AgentStateStore`、`BaseStore`、`SandboxSnapshotSpec`）为一键配置。内置 `RedisDistributedBackend`、`OssDistributedBackend`、`MysqlDistributedBackend` 等实现，通过 `HarnessAgent.builder().distributedBackend(backend)` 一行完成分布式后端接入，不再需要分别配置 stateStore、baseStore、snapshotSpec

### 变更

- **Agent 完全无状态改造** —— `ReActAgent` 不再持有任何可变的 per-session 状态，所有可变状态封装在内部 `CallExecution` 中通过 Reactor Context 透传。同一 agent 实例可安全并发服务多个 `(userId, sessionId)` 组合
- **Session 接口全面改为 `AgentStateStore`** —— 移除 `SessionManager`、`StatePersistence` 等旧接口，统一使用 `AgentStateStore`（内置 `InMemoryAgentStateStore`、`JsonFileAgentStateStore`、`RedisAgentStateStore`、`MysqlAgentStateStore`），按 `(userId, sessionId)` 二元组自动分桶持久化
- **`BaseStore` 接口包名迁移** —— `BaseStore` 及相关接口从旧包迁移到新包路径，使用旧 import 的代码需要更新
- **Extension 模块坐标整合** —— 部分扩展包 Maven 坐标调整，按职能重新归类。例如 `agentscope-extensions-session-redis` 合并为 `agentscope-extensions-redis`（同时包含 `RedisAgentStateStore`、`RedisStore`、`RedisSnapshotSpec` 等）。使用旧坐标的 pom 需要更新 `<artifactId>`
- **Sandbox 实现从 harness 内核拆出** —— Docker、Kubernetes、E2B、Daytona、AgentRun 等沙箱后端的实现从 `agentscope-harness` 内核移至独立扩展包（`agentscope-extensions-sandbox-*`）。harness 内核仅保留 `SandboxFilesystemSpec` 等抽象接口，不再传递具体实现依赖。如需使用沙箱需额外引入对应扩展包，例如 Docker 沙箱需添加 `agentscope-extensions-sandbox-docker`
- **Plan Mode 优化增强** —— 改进计划文件持久化与恢复机制，优化 `plan_enter` / `plan_write` / `plan_exit` 工具链的交互体验，增强 HITL 审批流程的稳定性
- **Skill 自进化增强** —— 优化技能提案（`ProposeSkillTool`）、审批（`SkillCurator`）与晋升（`SkillPromoter`）闭环，改进技能匹配精度与跨会话复用效果
- `DashScopeHttpClient` 请求超时与重试策略调整
- `ModelRegistry` 模型解析逻辑优化
- `AgentState` 序列化格式更新

### 修复

- 修复 `PermissionContextState` 在跨 session 恢复时的状态丢失问题
- 修复 `agentscope-all` 中缺失 4 个 sandbox 扩展模块（`sandbox-kubernetes`、`sandbox-agentrun`、`sandbox-daytona`、`sandbox-e2b`）的问题

---

## 2.0.0-RC1

> 发布日期：2025-05-28

首个 2.0 Release Candidate。包含从 1.x 的全部架构升级：

- Harness 工程化（workspace、记忆、技能、子 agent、Plan Mode、上下文压缩）
- 企业级分布式部署（多租户隔离、沙箱执行、权限管控、会话恢复）
- 底层框架重构（事件流、消息模型、Middleware、HITL）

完整的 1.x → 2.0 变更列表请见 [V1 迁移指南](/v2/zh/docs/change-log)。
