---
hide-toc: true
---

# AgentScope Service 技术解读：控制面、数据面与可恢复的 Agent 运行时

如果把发布通告看作「AgentScope Service 能做什么」，这篇更关注「它是怎么做成的」。我们将沿着产品资源模型、平面边界、Turn 生命周期、Brain / Hands 拆分、Session 事件契约，以及多框架接入路径，把平台背后的系统设计讲清楚。

产品概述与能力说明见姊妹篇：[AgentScope Service 正式发布](./agentscope-service-release.md)。本文默认读者已了解 AgentScope 2.0 / Harness 的基本概念，并关心如何把单个可运行 Agent 扩展成可运营平台。

## 什么是 AgentScope Service（实现视角）

从实现上看，AgentScope Service 不是单一进程，而是一组边界清晰的组件：

| 组件 | 角色 |
| --- | --- |
| `service-gateway` | 对外入口：认证、路由、公共 API |
| `aistiod` | Go 控制面：产品资源、舰队注册、Session / Team 运行时状态、控制台后端 |
| `service-dataplane` | Java 数据面：Managed Session Brain，基于 AgentScope Harness 执行 Turn |
| `service-scheduler` | Channel、Cron、出站任务、Self-hosted Hands Worker |
| PostgreSQL | 按 schema 分割的权威状态：`cp` / `rt` / `dp` |
| Web Console | Dashboard · Managed Agents · Agent Teams |

它同时服务两类 workload：

1. **Managed Agents**：控制面持有版本化 Snapshot，数据面按 Snapshot 构建 `HarnessAgent` 并跑事件化 Turn；
2. **BYO Agents**：已有 AgentScope / LangChain / Claude 等运行时通过扩展、`instrument()` 或 Sidecar 接入，进入同一舰队与 Session 观测模型。

控制面管理期望状态与运行状态，但**不执行模型 Turn**；推理循环留在数据面或接入方自己的 Runtime。这个边界贯穿整套架构：一旦控制面开始「顺便跑模型」，平面职责、扩缩容和故障域都会纠缠在一起。

对部署形态而言，本地开发可关闭 Kubernetes Reconciler，走 Hosted Product 路径；生产也可启用 Aistio 的 CRD / Workload 能力，把声明式 Agent 与舰队治理接到集群。产品品牌仍是 Agent Service，底层控制组件是 `aistiod`。

## 为什么需要这样一套平台

单看 agent loop，现在的框架已经足够写 Demo。难的是把「能跑」变成「能运维」：

1. **本机脚本 / CLI**：状态在本地目录，适合个人，不适合多副本与审计。
2. **业务服务内嵌 SDK**：每个应用各自实现 SessionStore、HITL、租约、事件回放与权限，成本重复且标准不一。
3. **低代码编排**：把 Harness 工程细节外泄给业务配置者，平台难统一升级。
4. **单一托管运行时**：效果好，但跨框架舰队、客户 VPC Hands、以及团队协作状态往往另起炉灶。

另一类隐藏成本是「状态归属不清」。很多人把 Java/Python 进程里的 agent 对象、前端 SSE 流、数据库里的聊天记录混为一谈。对象可丢、流可断，只有带序号的持久化事件与可恢复状态存储，才能支撑多副本、故障切换和审计回放。AgentScope Service 把这件事做成默认契约，而不是留给每个业务自选。

因此平台的解法可以概括为三句：

- **治理问题**收敛到控制面与数据面契约；
- **推理内核**收敛到 AgentScope Harness；
- **工具执行边界**收敛到 Environment（Hands）。

业务侧尽量只定义 Agent 差异（prompt、工具、Skills、权限策略）；压缩、恢复、事件、租约、审批则作为平台能力持续演进。平台升级 Harness 后，Managed Agent 应能共同受益，而不必逐个改流程图。

## 整体架构

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                              Agent Service                                 │
│                                                                            │
│  Web Console ──► Gateway :8080 ──┬──► aistiod :8081 （CP / RT）            │
│                                  └──► dataplane :8082 （DP Brain）         │
│                                              │                             │
│                                              ▼                             │
│                                     PostgreSQL                             │
│                              cp · rt · dp schemas                          │
│                                              ▲                             │
│                                              │                             │
│                                     scheduler :8083                        │
│                              Channel / Cron / Hands Worker                 │
└────────────────────────────────────────────────────────────────────────────┘
```

### 平面职责

| 平面 | 负责 | 明确不负责 |
| --- | --- | --- |
| Gateway | JWT / 公共路由 | 业务状态、模型调用 |
| Control（`aistiod`） | 用户、Agent 版本、Environment、Session 绑定、Team、舰队实例、运行时命令 | 模型 Turn |
| Dataplane | Turn Lease、事件落库、SSE、HITL、按 Snapshot 构建 Harness、Work Queue | 直读 `cp` 表作为 Catalog 回退 |
| Scheduler | Channel、Cron、出站 Hands Worker | 推理循环 |

### 数据归属

各平面可共用同一个 PostgreSQL Server，但**不共享表**：

| Schema | Owner | 数据 |
| --- | --- | --- |
| `cp` | `aistiod` 产品 API | 用户、Agent、版本、Environment、Session、Vault、Memory、Deployment |
| `rt` | Aistio Runtime Store | 舰队实例、运行时 Session、Context、Team、Task、Message |
| `dp` | Java Dataplane | Session Event、协调状态、HITL、Work Item、数据面投影 |

Dataplane 通过控制面内部 API 解析 Managed Session，并只使用返回的 Agent Snapshot 构建运行时。数据面副本可以水平扩展，但产品 Catalog 仍以控制面为准，避免双写和缓存漂移。本地 Catalog 回退看起来省事，长期往往制造「实例 A 已更新、实例 B 还在跑旧定义」的幽灵 bug。

### 一次 Turn 的完整路径

1. 客户端向已有 Session 追加 `user.message`。
2. Dataplane 获取 Turn Lease，并把 Session 标记为 `running`。
3. 控制面解析已固定版本的 Agent Snapshot、Environment、Workspace、Memory 与 Vault。
4. `SessionTurnRunner` 执行 `HarnessAgent.streamEvents`。
5. `agent.message`、`agent.tool_use`、`span.model_request_*` 等权威事件写入 PostgreSQL；可选 Preview Delta 仅用于打字机效果，不落库。
6. Session 回到 `idle`、因 HITL / Tool Result 暂停，或以类型化错误终止。

客户端以持久化事件序列恢复，并通过：

```http
GET /api/sessions/{id}/events/stream?after={seq}
```

做增量续传。进程内 Agent 对象和 Preview Stream 都不是权威数据源——这是可恢复 Session 的前提。

Turn Lease 的意义在于并发控制：同一时刻应只有一个执行者推进该 Session 的 Turn，避免双写事件序号、重复扣费调用模型，或在 HITL 等待期间被另一副本误续跑。Lease 丢失/过期后的接管策略，是数据面多副本可用性的关键细节。

### Brain 与 Hands

| Environment | 执行方式 |
| --- | --- |
| `local` | 在 Dataplane 宿主机跑文件系统与 Shell，仅建议开发 |
| `sandbox` | 托管 E2B 等云沙箱 |
| `remote` | 远程 / 分布式文件系统，不提供本地 Shell |
| `self_hosted` | Brain 只暴露 Tool Schema；客户侧出站 Worker poll / ack / heartbeat / 回传结果 |

`self_hosted` 适合私有网络：无需给 Brain 开入站到客户内网，由 Worker 主动出站拉取工具调用。协议语义是稳定的 `tool_use` / `tool_result` 事件闭环——Hands 换地方，Brain 的推理循环不必重写。

安全与合规团队因此可以分开审批三个问题：模型上下文可见范围、工具可达网络与文件系统、结果回传 Brain 的数据最小化策略。这比把「整个 Agent 容器」当成一个黑盒权限对象更容易落地。

## 核心能力（实现层）

> UI 截图请见产品版文章；此处聚焦机制。

### Dashboard：舰队与运行时观测

Dashboard 的数据主要来自控制面 Runtime Store 与数据面事件投影，而不是前端临时聚合：

- Agent / Instance 健康与在线清单
- Session phase（如 `active` / `idle` / `compressing` / `archived` / `terminated`）与 Turn 时长
- Context pressure、Token delta、错误计数
- Team 成员状态、任务进度、生命周期事件

这里有两个容易混淆的概念需要分开：

- **Session**：可恢复的对话线程；`phase` 描述线程运营态。
- **Turn**：一次用户请求到回复的执行单元；时长统计应落在 Turn，而不是把 Session 存活墙钟时间误当成「活跃耗时」。

对 BYO Agent，Level-1 Session Snapshot 由适配器周期上报；控制面据此做舰队统计与运营操作（压缩、终止等）。字段语义必须跨框架一致，否则 Dashboard 会退化成各适配器各自为政的「指标墙」。Token 类指标也应优先使用 delta 聚合，而不是简单累加绝对值快照。

### Managed Agents：版本化定义 + 事件化 Session

产品资源模型大致如下：

| 资源 | 作用 |
| --- | --- |
| Agent | 版本化 System Prompt、模型、工具、MCP、Skill、协作配置 |
| Environment | 工具执行边界与 Sandbox / Worker 配置 |
| Session | Agent 版本、Environment、Memory、Vault 与事件流的有状态绑定 |
| Memory / Vault | 跨 Session 文档与加密凭据 |
| Deployment / Channel | Cron、Webhook、手动触发与消息通道 |
| Team | Lead / Member、Message、Task、Plan、生命周期 |

几个关键设计选择：

1. **Session 创建是静态绑定**  
   创建时只记录资源关系，不跑 Agent；第一条 `user.message` 才启动 Turn。

2. **Event-native**  
   入站事件驱动工作，出站事件描述进度与结果。每个持久化事件有 Session 内单调递增序号，客户端可断点续传。

3. **HITL 作为一等公民**  
   Ask Policy 工具会暂停 Turn，发出确认请求；`user.tool_confirmation` 继续或拒绝，同时保留完整历史。

4. **权威事件 vs Preview**  
   SSE 可推送 `event_start` / `event_delta` 获得即时体验，但最终以落库事件为准。UI 刷新、多端恢复、审计回放都依赖同一真相源。

5. **版本 pin**  
   Session 绑定 Agent 的具体版本 Snapshot，避免运行中途定义被热更新导致不可复现轨迹。需要升级时，显式创建新 Session 或走产品定义的升级路径。

Managed Agent 在 Java Dataplane 中根据控制面 Snapshot 构建。底层直接复用 AgentScope 2.0 `HarnessAgent`：上下文压缩、工具结果淘汰、状态恢复、Skills / 子任务等工程默认项，不必在产品层再造一套 agent loop。产品层补的是租户资源、ACL、事件契约、Turn Lease、HITL ticket、Environment 与 Worker 队列——这些才是「框架」上升为「平台」的成本所在。

### Agent Teams：跨 Session 的协作状态机

Agent Teams 把多 Agent 协作做成控制面资源，而不是某个进程内存里的临时聊天：

- Lead / Member 拓扑与动态成员（数量 / 白名单约束）
- 单播与广播消息
- 共享任务、Claim / Assign、Plan Approval
- 成员 Wakeup、优雅关闭、生命周期时限、故障恢复
- 消息与任务跨进程、跨 Session 保留

对于 Managed 成员，Wakeup 可以进一步落到数据面 Session / Turn；对于 BYO 成员，则通过控制面命令与适配器能力协同。团队状态主要落在 `rt` schema，避免与单 Session 的 `dp` 事件日志混淆——Session 负责一次对话轨迹，Team 负责跨成员协作的持久单元。

一个务实约束是：Teams 不假设所有成员同源。Lead 可以是 Managed Harness Agent，Member 可以是接入的 Coding Agent 或 LangChain 服务。控制面负责拓扑、任务与生命周期，成员侧只需满足协作与观测契约。异构组队比「先统一框架再谈协作」更贴近企业现状。

## 如何接入

### AgentScope（原生）

Java 侧通过 `agentscope-extensions-aistio` 接入。扩展负责把 Runtime 注册到控制面，上报 Session / Context / 健康信息，并承接运营命令。对已有 AgentScope 应用，这是侵入性最低、契约最完整的路径：与 Managed Agent 共用 Dashboard 与 Session 观测模型。

因为双方共享同一套 AgentScope 事件与状态语义，Level-1 / Context / 压缩等能力通常最先对齐。若你已经在用 `HarnessAgent`，接入的边际成本主要是依赖、注册配置与运行时标识，而不是重写业务 Prompt。

### LangChain

Python SDK 提供 `aistio.instrument()`。对 LangChain / LangGraph，适配器挂在 Callback / Checkpointer 等拦截点：

```python
import aistio

aistio.instrument(
    app_or_client,
    control_plane="aistiod.aistio-system:9090",
    agent_name="my-langchain-agent",
    namespace="default",
    enable_events=False,  # Level 2 事件默认关，可按需打开
)
```

设计原则是旁路上报：主路径成功优先，上报失败静默降级，避免控制面抖动拖垮业务推理。Level-1 默认打开以支撑舰队视图；更细粒度事件（Level-2）可按成本与合规要求打开。Context 上报采用 hash 变更防抖，减少无效全量推送。

### Claude SDK 与 Sidecar

Claude Agent SDK 同样走 `instrument()`，通过装饰 SessionStore 等路径获得 Level-1 快照与压缩 / 终止能力。

对于 Claude Code、Qoder 等不便嵌入 SDK 的 Coding Agent，采用 **Sidecar**：

- 主容器继续跑原有 CLI / Agent；
- Sidecar 观察本地 Session 目录（如 `~/.claude/`）与运行状态；
- 向控制面上报舰队与 Session 信息，并转发可支持的运营命令；
- 必要时把 Session 文件态同步到外部存储，以支持跨节点恢复。

Sidecar 不是「再实现一个 agent loop」，而是在无法改二进制时，补齐控制面所需的最小可观测与可运营面。它也提醒我们：很多 Coding Agent 的真相在文件系统，而不在数据库——控制面必须能理解这种状态形态，而不是强迫所有 Runtime 先改存 PostgreSQL。

### 本地启动与验收

```bash
export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
BUILDER_REBUILD=1 scripts/dev-up.sh
# Console: http://localhost:8080
scripts/smoke.sh
```

建议至少验收三类路径：

1. Managed Session：投递 `user.message`，从事件流恢复，刷新页面后序号续传正确；
2. HITL：触发 Ask Policy，确认后续跑，历史完整；
3. `self_hosted`：Worker poll / ack / heartbeat / 回传 `tool_result`，Turn 正确恢复。

详见 [`docs/guide/14-validation.md`](../../../agentscope-service/docs/guide/14-validation.md) 与架构说明 [`docs/guide/02-architecture.md`](../../../agentscope-service/docs/guide/02-architecture.md)。

## 几个值得提前避开的实现误区

1. **把 Preview SSE 当权威日志**  
   打字机效果可以丢、可以重连重建；审计、复盘、计费应对齐落库事件序号。

2. **让数据面本地缓存产品 Catalog 并在控制面不可达时静默回退**  
   短期看起来高可用，长期会出现「跑了未知版本」的最坏故障。宁可失败可感知，也不要静默用旧定义。

3. **把 Session phase 与 Turn 时长混用**  
   `active` / `idle` 描述线程态；耗时应落到 Turn。否则 Dashboard「谁最忙」会被长挂起会话误导。

4. **在 BYO 适配器里发明平行指标**  
   舰队 KPI 必须共用语义。新框架适配优先对齐契约，再谈特化字段。

5. **把 Team 消息塞进某个成员的 Session 事件流冒充协作状态**  
   Session 轨迹与 Team 状态机生命周期不同；混存会导致恢复、清理和权限边界全部出错。

这些点在单体 demo 里不明显，一旦多副本、多框架、多团队同时出现，就会变成线上事故的温床。

## Roadmap（工程视角）

1. **适配器覆盖面**  
   深化 LangChain、ADK、Claude、Qoder、OpenAI Agents 等 Level-1 / Level-2 / Context 能力对齐，减少框架特化字段，保证 Dashboard KPI 同义。

2. **生产级多租户与治理**  
   ACL、配额、审计、灰度发布、密钥轮换与更严格的 Environment 隔离策略；Vault / Memory 的生命周期与访问边界也会继续打磨。

3. **Automation**  
   Deployment / Cron / Webhook / Channel 从「能触发」演进到「可编排、可回放、可补偿」。自动 Turn 一旦失败，要有类型化错误、重试策略与人工接管入口。

4. **事件驱动入口**  
   GitHub / GitLab、钉钉、企微等：把外部事件稳定映射为 Session Turn 或 Team Task，并保留幂等与鉴权边界。外部系统重试是常态，平台侧必须防重复开工。

5. **Teams 与恢复**  
   动态成员、计划审批、成员失联恢复、跨 Session Restart，以及 Managed / BYO 混合组队的一致性。协作状态机比单 Session 更难，因为失败域跨多个 Runtime。

## 和「只嵌入 Harness」差在哪里

如果业务服务里直接嵌入 `HarnessAgent`，你已经拥有不错的长任务与压缩能力。但一旦面对多租户、多副本、多团队，你还要补齐：

- 版本化 Agent 定义与 Session pin；
- append-only 事件与游标续传；
- Turn Lease 与 HITL ticket；
- Environment 切换与 Self-hosted Work Queue；
- 舰队注册、上下文压力、压缩 / 终止命令；
- Team 任务板与跨 Session 协作状态。

AgentScope Service 并不是「在 Harness 外面加一层 UI」，而是把上述分布式职责做成稳定产品资源与内部契约。Harness 让平台不必重写 agent loop；平台层仍要对状态归属、故障域和治理边界负责。

## 结语

AgentScope Service 的技术内核可以概括成三句话：

1. **控制面管期望与运行状态，数据面跑 Turn，Hands 决定工具落点**；
2. **持久化事件序列才是 Session 真相，进程内对象只是可丢弃的缓存**；
3. **Managed 与 BYO 共用舰队契约，框架差异收敛在适配器，而不是散落在 Console**。

如果你正在从「单个 Harness Agent」走向「可运营的 Agent 舰队」，这套分层会减少大量重复基础设施。欢迎直接阅读 [`agentscope-service/README_zh.md`](../../../agentscope-service/README_zh.md)；产品能力与接入故事则可回到[发布版文章](./agentscope-service-release.md)。
