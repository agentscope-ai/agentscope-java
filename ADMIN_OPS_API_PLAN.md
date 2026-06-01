# AgentScope-Java 后端运维 OpenAPI 规划

> 灵感源：opencode 的 slash 运维命令（详见 [`OPENCODE_OPS_COMMANDS_REFERENCE.md`](./OPENCODE_OPS_COMMANDS_REFERENCE.md)）。
> 本文目的：给企业级、Web 后端进程部署形态的 AgentScope-Java，规划一套**面向运维**的 OpenAPI。
> 创建日期：2026-05-31

---

## 1. 结论先行

**做。但是要分两层做，不要直接抄 CLI 的 slash 命令到 REST。**

- ✅ **思路本身合适**：AgentScope-Java 已经具备所有底层能力（`AgentState` / `Session` / `Agent.interrupt()` / `GracefulShutdownManager` / `PermissionEngine` / `Toolkit`），把这些能力以 OpenAPI 暴露是企业级框架本就该有的"控制面"。
- ⚠️ **但 CLI 的命令模型不能 1:1 映射**：CLI 把「业务请求」（如 `/compact` 我自己这通会话）和「运维动作」（如 `/status` 看进程健康）合并在一个面板里；后端必须拆成两层 ——
  - **Data plane（数据面）**：调用方对**自己拥有的某个会话**做的运行期操作（compact、undo、fork、abort）。
  - **Control plane（控制面 / Admin）**：运维/SRE 对**整个进程或所有会话**做的全局动作（drain、shutdown、reload config、dump state、list tools、permission audit）。
- ✅ **直接复用 Spring Boot 生态**：用 `@Endpoint`/`@WebEndpoint`（Actuator）暴露控制面，独占 `management.server.port`，免费拿到鉴权 / `/actuator` 命名空间 / metrics 集成；数据面用普通 `@RestController` 走 `/v1/sessions/...`。

---

## 2. 当前 agentscope-java 的可用底座（盘点）

| 现状能力 | 文件 / 类 | 与运维命令的对应 |
|---|---|---|
| 单会话状态对象 | `core/state/AgentState.java`（含 `summary` / `context` / `replyId` / `curIter` / `permissionContext` / `toolContext` / `tasksContext` / `planModeContext`） | `/compact`、`/undo`、`/fork`、`/state-dump` 的数据基础 |
| 会话存储抽象 | `core/session/Session.java`（`save/get/delete/listSessionKeys`） | `/sessions list / delete / export` |
| 中断 | `Agent.interrupt()`、`Agent.interrupt(Msg)` | `/abort` |
| 优雅关闭 + 活跃请求追踪 | `core/shutdown/GracefulShutdownManager.java`（单例，`registerRequest` / `getActiveRequestCount` / `performGracefulShutdown` / `bindStateSaver`） | `/status`、`/drain`、`/shutdown` |
| 权限引擎 | `core/permission/PermissionEngine.java` + `PermissionMode` + `PermissionRule` + `PermissionDecision` | `/permissions list / set` |
| 工具集 | `core/tool/Toolkit` | `/tools list / enable / disable` |
| Hook / Middleware | `core/middleware/*`、legacy hook | 运维插桩（操作审计、限流） |
| 已有 REST 入口 | `chat-completions-web-starter`、`a2a-spring-boot-starter`、`agui-spring-boot-starter`、`agent-protocol` | 数据面已经在 starter 里有先例，控制面缺位 |

**关键观察**：项目已经有多个 `@RestController` starter，但它们都是**业务对外协议适配器**（OpenAI 兼容、A2A、AG-UI、AgentProtocol），尚未有**通用的运维管理接口**。这正是要补的位置。

---

## 3. 设计原则

1. **两层分离**：数据面（per-session）与控制面（global/admin）走不同 URL prefix、不同端口、不同鉴权策略。
2. **沿用 Actuator 协议**：控制面落到 `/actuator/agentscope/...`，吃掉 Spring Boot Actuator 已有的 `management.server.port`、`management.endpoint.<name>.access`、`spring.security` 等基础设施，不重造轮子。
3. **能力即资源**：每个运维动作都是 RESTful 资源（`POST /v1/sessions/{id}:compact` 而不是 RPC 风格 `/compact`）。这样能直接由 OpenAPI / Swagger 自动生成、curl 可达、有审计 trail。
4. **响应式贯穿**：Mono/Flux 返回，结合 SSE 暴露流式状态变更（agent 事件流、压缩进度、shutdown 进度），与 SKILL.md 的"NEVER block"原则一致。
5. **Idempotent + 版本化**：`If-Match` ETag 防并发冲突；URL 用 `/v1/`；Action 用 `:` 子动词形式（Google AIP-136），如 `:compact` `:fork` `:abort`。
6. **可被禁用**：每个 starter 用 `agentscope.admin.enabled=true` 显式开启，**默认关闭**——避免库使用者把运维口意外暴露到公网。
7. **审计强制**：所有写操作走统一 `AdminAuditFilter`，落 SLF4J + 可选 Nacos / Loki，记录 (operator, action, target, before-state-hash, after-state-hash, ts)。
8. **对齐 SKILL.md**：实现里**不允许 `.block()`**；ThreadLocal 改 Reactor Context；返回 `Mono`/`Flux`。

---

## 4. 命令 → REST 端点映射表

### 4.1 数据面（Data plane） —— `agentscope-admin-spring-boot-starter` 的 `/v1/sessions/...`

> 由会话所有者或受信调用者发起；JWT/OAuth2 鉴权；按 `session_id` 隔离。

| OpenCode 命令 | REST 端点 | 主要操作 | 实现要点 |
|---|---|---|---|
| `/compact`、`/summarize` | `POST /v1/sessions/{id}:compact` | 调模型把 `AgentState.context` 摘要进 `summary`，截断 context | 复用 `AgentState.setSummary` + 配置压缩策略；返回压缩前后 token 估算 |
| `/new`、`/clear` | `POST /v1/sessions/{id}:reset`（或 `DELETE` 后重建） | 清空 context，保留 sessionId 与 agent meta | 注意 `replyId` 重新生成 |
| `/sessions` | `GET /v1/sessions?owner=...` | 列出会话 | 复用 `Session.listSessionKeys()` |
| `/undo` | `POST /v1/sessions/{id}:undo` | 回滚到上一条用户消息前的快照 | **依赖**：扩展 `AgentState` 支持快照栈（见 §6） |
| `/redo` | `POST /v1/sessions/{id}:redo` | 复原被 undo 的那一步 | 同上 |
| `/fork` | `POST /v1/sessions/{id}:fork` | 从某 `messageId` / `curIter` 处分叉新会话 | `AgentState` 深拷贝 + 新 sessionId |
| `/timeline` | `GET /v1/sessions/{id}/messages` | 列消息 | 直接读 `AgentState.context` |
| `/copy` `/export` | `GET /v1/sessions/{id}:export?format=md` | 导出 transcript | Markdown / JSON 两种格式 |
| `/share` `/unshare` | `POST /v1/sessions/{id}:share` / `:unshare` | 生成可访问 URL | 可选，有需要再做 |
| `/abort`（隐含） | `POST /v1/sessions/{id}:abort` | 中断当前推理 | 复用 `agent.interrupt(msg)`；返回 202 Accepted |
| `/state-dump`（新） | `GET /v1/sessions/{id}/state` | 导出完整 `AgentState` JSON | 用 Jackson 现有注解 |
| `/state-load`（新） | `PUT /v1/sessions/{id}/state` | 灌入 `AgentState`（运维场景） | 受限：要 admin 角色 |

### 4.2 控制面（Control plane / Admin） —— Actuator endpoints

> 由 SRE / 运维平台发起；走 `management.server.port`；`@WebEndpoint(id = "agentscope")`；建议在生产环境用单独的 mTLS 端口。

| OpenCode 命令 | Actuator 路径 | 实现 | 备注 |
|---|---|---|---|
| `/status` | `GET /actuator/agentscope/status` | 聚合：activeRequestCount、shutdown state、registered agents、provider 连接、token 计费汇总 | 复用 `GracefulShutdownManager.getActiveRequestCount()` |
| `/help` | `GET /actuator/agentscope/commands` | 列出本进程所有运维命令（自动从注册表生成） | 即 OpenCode 的 `/help` 等价物 |
| `/cost`（claude-code 风格） | `GET /actuator/agentscope/usage` | 累计 token / 调用次数 / 费用估算 | 需要 hook 收集 token 计数 |
| `/models` | `GET /actuator/agentscope/models` | 列已注册 `Model` bean 及连通性 | 健康检查复用 |
| `/agents` | `GET /actuator/agentscope/agents` | 列所有 `Agent` bean（id/name/desc/maxIters/toolkit）| 通过 Spring `ApplicationContext` 扫 |
| `/mcps` | `GET /actuator/agentscope/mcps` + `POST :enable/:disable` | 列 MCP server 与运行状态，启停 | 复用 `McpClientWrapper` |
| `/tools` | `GET /actuator/agentscope/tools?agent=xx` | 列 toolkit 的 `getToolNames()` | 可选 enable/disable |
| `/permissions` | `GET /actuator/agentscope/permissions` + `POST :rule` | 查/改 `PermissionRule` | 写动作要更高权限 |
| `/connect` | `POST /actuator/agentscope/providers` | 录入/轮换 API key | **重要**：经 `Credential` 抽象，密钥不落明文日志 |
| `/themes` `/editor` | 不实现 | 这些是 CLI/UI 性质 | — |
| `/init` | `POST /actuator/agentscope/agents-md:generate` | 调模型生成项目 AGENTS.md | 可选 |
| `/review` | `POST /actuator/agentscope/review`（或不做） | 通常作为业务 endpoint 而非运维 | 可选 |
| `/hooks` | `GET /actuator/agentscope/hooks` | 列已注册 Hook + priority + 启停 | 复用 hook 系统 |
| `/skills` | `GET /actuator/agentscope/skills` | 列 SkillFilter 注册表 | — |
| `/doctor`（claude-code 风格，**强烈建议**） | `GET /actuator/agentscope/doctor` | 自检：JVM、provider 连通、Session 存储可写、MCP 可达、内存阈值 | 与 Spring Boot 自带 `/health` 互补 |
| `/drain`（新，运维必备） | `POST /actuator/agentscope/drain` | 标记不再接收新请求，等已有请求结束 | 复用 `performGracefulShutdown` 但不退出 JVM |
| `/shutdown`（新，运维必备） | `POST /actuator/agentscope/shutdown` | 触发 JVM 优雅退出 | 与 Spring Boot 自带 shutdown 端点对齐 |
| `/reload-config` | `POST /actuator/agentscope/config:reload` | 热加载 prompt / model 配置 | 配合 Nacos 扩展 |

---

## 5. 模块 / 包结构建议

新增一个 starter（不要改动 core）：

```
agentscope-extensions/
└── agentscope-spring-boot-starters/
    └── agentscope-admin-spring-boot-starter/        ← 新增
        ├── pom.xml
        └── src/main/java/io/agentscope/spring/boot/admin/
            ├── AgentscopeAdminAutoConfiguration.java
            ├── properties/
            │   └── AdminProperties.java          (admin.enabled, basePath, requireAuth, ...)
            ├── audit/
            │   ├── AdminAuditEvent.java
            │   └── AdminAuditFilter.java         (WebFilter, 记录所有写操作)
            ├── controller/                        ← 数据面
            │   ├── SessionAdminController.java   (/v1/sessions/...)
            │   └── AgentAdminController.java
            ├── endpoint/                          ← Actuator 控制面
            │   ├── AgentscopeStatusEndpoint.java
            │   ├── AgentscopeAgentsEndpoint.java
            │   ├── AgentscopeToolsEndpoint.java
            │   ├── AgentscopeMcpEndpoint.java
            │   ├── AgentscopePermissionsEndpoint.java
            │   ├── AgentscopeUsageEndpoint.java
            │   ├── AgentscopeDoctorEndpoint.java
            │   ├── AgentscopeDrainEndpoint.java
            │   └── AgentscopeShutdownEndpoint.java
            ├── command/                           ← 命令注册表（仿 opencode）
            │   ├── AdminCommand.java
            │   ├── AdminCommandRegistry.java
            │   └── BuiltinCommands.java
            └── service/
                ├── SessionOperations.java        (compact/undo/fork/export 的核心实现)
                └── AgentInventory.java
```

并在 `agentscope-core` 中补少量底层能力（见下一节）。

---

## 6. 需要 core 补齐的底层能力

| 能力 | 现状 | 改造点 |
|---|---|---|
| **`AgentState` 快照栈** | `AgentState` 是单实例 mutable 对象 | 增加 `List<AgentSnapshot> history`（或对接外部 Session 做版本化 save），让 undo/redo/fork 有源可寻。配合 [[project_java-agentstate-refactor]] 一起做 |
| **`Session.delete(SessionKey, String)`** | 默认 no-op | `JsonSession` / `InMemorySession` / mysql / redis 各 starter 必须实现，否则 `/sessions:delete` 不工作 |
| **`AgentRegistry`** | 现在是各处 `@Bean` 散落 | 补一个 `AgentRegistry` 接口（List/get），让 `/agents` 端点能枚举 |
| **`AgentMetrics`** | 没有统一的 token / 调用次数收集 | 加 `MetricsHook` 收集到 Micrometer registry，`/usage` 端点直接读 |
| **`SummarizationStrategy`** | 没有 first-class 摘要器 | 抽 `interface SummarizationStrategy { Mono<AgentState> compact(AgentState s); }`，默认实现：调 model 摘要+截断 |
| **`PermissionRule` 动态变更通知** | 静态 | 让 PermissionEngine 监听 rule 变更（Reactor `Sinks`），便于 admin API 热更新 |

这些都是与现有架构**正交的小幅增量**，不需要破坏式改造。

---

## 7. 命令注册表设计（关键，仿 opencode 命令模型）

为了让"列出本进程所有运维能力"这个动作（`/help` 等价物）天然可用，建议把每个 admin 端点都注册到一个 `AdminCommandRegistry`：

```java
public interface AdminCommand {
    String id();                                    // "session.compact"
    String title();                                 // "Compact session"
    String category();                              // "Session" / "Agent" / "System"
    Plane plane();                                  // DATA | CONTROL
    Set<String> roles();                            // 可执行此命令所需角色
    String httpMethod();                            // POST / GET / DELETE
    String httpPath();                              // "/v1/sessions/{id}:compact"
    boolean idempotent();
    boolean writes();                               // 是否变更状态（影响审计/CSRF）
    Class<?> requestSchema();                       // 请求体 schema（生成 OpenAPI）
    Class<?> responseSchema();
}
```

收益：
1. `GET /actuator/agentscope/commands` 自动生成命令清单（CLI/IDE 客户端能用）。
2. 自动生成 OpenAPI（`springdoc-openapi` 集成），运维平台前端无需维护命令字典。
3. 一处声明，鉴权 + 审计 + 限流统一拦截。
4. 第三方扩展（MCP、Skill、Tool）也走同一注册表注册自己的命令，与 opencode 的 `source: command|mcp|skill` 对齐。

---

## 8. 鉴权 / 安全建议

1. **分级**：
   - `agentscope.admin.role.read` 看；`agentscope.admin.role.write` 改；`agentscope.admin.role.dangerous` 改 permission/drain/shutdown。
2. **生产默认关写**：`agentscope.admin.write.enabled=false` 时仅暴露只读端点。
3. **数据面 vs 控制面解耦端口**：
   ```yaml
   server.port: 8080            # 数据面 + 业务 starter
   management.server.port: 9090 # Actuator 控制面（建议仅内网 / mTLS）
   ```
4. **审计**：每个写操作落事件流（推荐打到 `agentscope-extensions-rocketmq` 或 SLF4J），含 traceId（与 OpenTelemetry 集成 `tracing/telemetry/`）。
5. **危险命令二次确认**：`/drain` `/shutdown` `/state-load` 要求 `Confirm: yes` header 或两步式 token，防误操作。
6. **CSRF / origin**：写操作要求自定义 header（如 `X-Agentscope-Admin-Token`）—— 防止浏览器跨域带 cookie 触发。
7. **Provider 密钥**：`/connect` 写入后不允许任何 GET 端点回读，按 [[user_credential-handling]] 类敏感信息处理。

---

## 9. 与 opencode 的差异点（解释为什么不能直接照搬）

| 维度 | opencode (CLI) | agentscope-java (Web 后端) |
|---|---|---|
| 主体 | 单用户、单进程、单会话焦点 | 多租户、多会话、多并发 |
| `/exit` | 退出当前 TUI | 无意义（HTTP 无状态） → 改 `/drain` + `/shutdown` |
| `/themes` `/editor` | UX 偏好 | 不实现 |
| `/help` | 显示对话框 | 改成 `GET /actuator/agentscope/commands` |
| `/sessions` | 切换"我"的会话 | 列**所有**会话需要 admin，列**某 owner**的会话是数据面 |
| `/compact` | 压我自己的 | 同左（数据面）；额外多一个 admin 强制压所有空闲会话的"批量 compact" |
| `/undo` | 即时回滚 + git 恢复文件 | 需要快照栈（不依赖 git） |
| `/share` | 一键托管 | 默认不做，企业场景多走 SSO + 内部知识库 |
| 命令面板 | TUI 内 ctrl+p | 控制面前端可由 `/actuator/agentscope/commands` 自动渲染 |

---

## 10. 落地路线 — 进展跟踪

> 模块路径：`agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter/`
> 最新状态更新：2026-05-31

### ✅ Phase 1（最小可用）— 已交付
1. ✅ 新建 `agentscope-admin-spring-boot-starter`，含 `AdminProperties` + AutoConfiguration（opt-in）。
2. ✅ 只读控制面 Actuator：`agentscope-status` / `agentscope-agents` / `agentscope-tools` / `agentscope-models` / `agentscope-doctor` / `agentscope-commands`。
3. ✅ 核心数据面 REST：`POST :compact`、`:abort`、`GET /messages`、`/state`、`:export`、`GET /sessions`。
4. ✅ 审计骨架（`AdminAuditLogger` + `AdminAuditEvent`），写操作受 `WriteGuard`/`agentscope.admin.write-enabled` + 可选 token 保护。
5. ✅ 内置 12 条 `AdminCommand`，统一命令注册表 + `/actuator/agentscope-commands` 自描述。

### ✅ Phase 2a — 已交付（不动 core）
6. ✅ `/actuator/agentscope-drain`（`@WriteOperation`）— 走 `GracefulShutdownManager.performGracefulShutdown()`。
7. ✅ `/actuator/agentscope-shutdown`（`@WriteOperation`）— drain + `ConfigurableApplicationContext.close()`，JVM 退出留给容器。
8. ✅ `MetricsHook`（`AgentBase.addSystemHook`）+ `MetricsRecorder`（per-agent / per-model / global，`LongAdder` 无锁）+ `/actuator/agentscope-usage`。
9. ✅ `/actuator/agentscope-permissions`（只读，扫所有 ReActAgent 的 `PermissionEngine`）。

### ✅ Phase 2b — 已交付（不动 core）
10. ✅ `SnapshotStore`（in-memory、per-session undo+redo 双栈、bounded capacity）。
11. ✅ `:compact` 执行前自动 snapshot；`:undo` `:redo` REST endpoints + 内置命令。
12. ✅ `AgentStateRestorer` 在 `AgentState` 不可替换 instance 的约束下，原地恢复 summary/replyId/curIter/shutdownInterrupted/context。

> Phase 2b 当前覆盖：summary + context 维度，正是 `:compact` 的反向操作。
> 限制：permission/tool/task/plan 四个子 context 不在 undo 范围（缺 setter）；`:fork` 尚未交付 — 需要 core 支持把"快照变成新 sessionId 的初始状态 + agent 拉起"。

### ✅ Phase 3 — 部分交付

#### Phase 3a — 已交付（springdoc）
- ✅ 在 pom 加 `springdoc-openapi-starter-webmvc-ui` 为 `optional`，consumer 加它就启用。
- ✅ `AdminOpenApiConfiguration` 用 `@ConditionalOnClass({GroupedOpenApi, OpenAPI})` 提供：
  - `GroupedOpenApi` 把所有 admin 路由分到独立 "agentscope-admin" group。
  - 默认 `OpenAPI` 信息块 + 以 `AdminCommand.category` 自动派生的 Tag 列表。

#### Phase 3b/c/d — 已交付（plan / agent tasks / subagent tasks）
详见 [`PLAN_SUBAGENT_ADMIN_GAP.md`](./PLAN_SUBAGENT_ADMIN_GAP.md)。新增端点：
- 数据面：`GET /sessions/{id}/plan`、`POST :enter-plan-mode` `:exit-plan-mode`、`GET /sessions/{id}/tasks`、`GET/DELETE /sessions/{id}/subagent-tasks[/{id}]`
- 控制面：`GET /actuator/agentscope-subagents`
- 新 SPI：`SubagentInventory`（默认 `SpringSubagentInventory` 扫 `SubagentEntry`/`SubagentDeclaration` beans）

### ⏳ Phase 3 剩余 / Phase 4 backlog

- `:fork` 数据面：snapshot 落到新 sessionId 的 Session，但 agent 实例化由业务侧控制，需要约定 `AgentFactory` SPI 或推 core 改。
- `:reset`（基于 `SessionOperations` 写空 state）。
- `/permissions` 动态修改（need core 让 `PermissionEngine` 支持热更新规则）。
- `/connect` provider 密钥录入 / 轮换（与 credential 抽象联动）。
- 配置热重载（与 Nacos 扩展联动）。
- SSE 流式 `/v1/admin/sessions/{id}/events`（暴露 Hook / `streamEvents` / `SubagentEventBus` 事件）。
- Spring Security starter 范例：把 `/actuator/agentscope-*` 写操作真正绑到 mTLS / OAuth2 而不仅仅依赖 confirm+token。
- 集群一致性：默认 `InMemoryAgentRegistry` / `SnapshotStore` / `InMemoryAgentRegistry` 仅单机，提供 Redis / Nacos backed 替换实现的 starter。
- Plan 蓝图文件读写端点（与 `PlanModeManager` 联动）。
- 业务向 admin 暴露 Task 写操作的设计 — 需要锁 / 与 model-driven 写竞争问题。

### 当前模块测试

```
mvn -pl agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter test -Dspotless.check.skip=true -Dmaven.javadoc.skip=true
# Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
```

文件清单（截至 Phase 3）：
- 主代码：49 个 Java class
- 测试：8 个测试类（AutoConfigurationTest / AdminCommandRegistryTest / WriteGuardTest / SessionOperationsTest / MetricsRecorderTest / SnapshotStoreTest / AgentStateRestorerTest / SpringSubagentInventoryTest / SubagentTaskOperationsTest）
- 内置命令：26 条（15 数据面 + 11 控制面），可通过 `GET /actuator/agentscope-commands` 自检

---

## 11. 客户端形态（顺带规划）

控制面有了 OpenAPI 之后可以白送三件 client：

1. **Web 控制台**：用 `@agentscope-extensions-studio` 扩展现有 Studio，加"运维"分页。
2. **CLI**：单独发一个 `agentscope-cli`，本质就是把 admin OpenAPI 包一层 cobra-style 命令——这时 opencode 的 slash 命令模型反而又有用了，只不过它跑在客户端。
3. **kubectl-style 插件**：`kubectl agentscope status` / `kubectl agentscope sessions list`，与 K8s Operator 配合。

---

## 12. 最小示例：把 `/compact` 落到 OpenAPI

```java
@RestController
@RequestMapping("/v1/sessions")
@ConditionalOnProperty(prefix = "agentscope.admin", name = "enabled", havingValue = "true")
public class SessionAdminController {

    private final SessionOperations ops;
    private final AdminAuditFilter audit;

    @PostMapping("/{sessionId}:compact")
    public Mono<CompactResponse> compact(
            @PathVariable String sessionId,
            @RequestBody(required = false) CompactRequest req,
            @RequestHeader(value = "If-Match", required = false) String etag,
            ServerWebExchange exchange) {

        return ops.compact(sessionId, req, etag)
            .doOnSuccess(r -> audit.write(exchange, "session.compact", sessionId, r.beforeHash, r.afterHash))
            .onErrorResume(e -> Mono.error(toProblemDetails(e)));
    }
}
```

```java
@Component
public class SessionOperations {
    public Mono<CompactResponse> compact(String sessionId, CompactRequest req, String etag) {
        return sessionStore.load(sessionId)
            .flatMap(state -> summarizer.compact(state, req.strategy()))
            .flatMap(newState -> sessionStore.save(sessionId, newState, etag))
            .map(saved -> new CompactResponse(saved.beforeHash(), saved.afterHash(),
                                              saved.tokensBefore(), saved.tokensAfter()));
    }
}
```

注意：**全程 Reactor，无 `.block()`**，符合 SKILL.md 强约束。

---

## 13. 给本规划的快速回答

> Q：把 opencode 的运维命令做成后端 admin OpenAPI，合适吗？

**合适**。理由：
1. AgentScope-Java 已经具备所有底层能力，缺的只是统一的"控制面 / 数据面"REST 暴露层。
2. Spring Boot Actuator 提供成熟容器，可直接挂载控制面端点，免费拿到鉴权 / 端口隔离 / metrics。
3. 已有 4 个 starter（chat-completions、a2a、agui、agent-protocol）证明这种 starter+controller 模式在本项目里是 idiomatic 的。

> Q：有什么补充建议？

1. **不要把所有命令一锅端到 `/`**，按数据面 / 控制面分两层。
2. **以 `AdminCommand` 注册表为核心**而不是散乱 controller —— 可生成 OpenAPI、可被 CLI/Studio/K8s 复用、可统一审计。
3. **优先补 `AgentSnapshot` 快照栈**，否则 undo/redo/fork 这些有价值的命令做不出来。
4. **`/doctor` 与 `/drain` 是企业场景比 CLI `/compact` 更刚需**的，先做。
5. **生产默认关闭写操作**，把"开放运维口"作为有意识的部署决定而不是默认值。
6. **配合 [[project_java-agentstate-refactor]]**：admin API 是把"AgentState 成为唯一状态容器"这一改造**对外可视化**的最佳载体，建议两件事一起做。

---
