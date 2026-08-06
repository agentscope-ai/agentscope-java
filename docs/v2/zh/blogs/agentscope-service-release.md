---
hide-toc: true
---

# AgentScope Service 正式发布：企业级 Agent 管控与治理中心

Agent 正在从「能对话的 Demo」走向「能持续完成任务的系统」。真正进入生产环境后，团队面对的不再只是 prompt 工程，而是一整套运营问题：Agent 如何定义与发布？Session 如何恢复与审计？工具调用如何审批？多 Agent 如何协同？不同框架写就的智能体，又如何进入同一张运营视图？

今天，我们正式发布 **AgentScope Service**——基于 AgentScope Harness 构建的托管 Agent 平台。它为企业提供统一的控制面与治理中心：用 Dashboard 运营整支 Agent 舰队，用 Managed Agents 完成从定义到会话的托管生命周期，用 Agent Teams 组织多智能体协作；同时也兼容 AgentScope、LangChain、ADK、Claude / Qoder 等主流运行时与 Coding Agent 的接入。

如果你把 AgentScope 2.0 理解为「如何把单个智能体做稳」，那么 AgentScope Service 回答的是下一问：**如何把很多个智能体管起来，并长期跑下去。**

## 什么是 AgentScope Service

AgentScope Service 是一个 Agent 智能体控制面与治理中心。产品围绕三项核心能力组织：

1. **Dashboard**：全局运营入口。统计在线 Agent 与健康实例，观察 Session 时间线、上下文压力、Token 用量与错误；同时支持 Managed Agent 与自带运行时（BYO）Agent 进入同一套运营模型。主流 Agent Framework（AgentScope、LangChain、ADK）以及 Claude、Qoder 等均可接入展示。
2. **Managed Agents**：托管 Agent Harness 运行时。支持可视化定义 Agent（模型、System Prompt、工具、MCP、Skills），选择执行环境，创建可恢复的有状态 Session，并提供 SSE 流式输出、工具审批与 Human-in-the-loop。
3. **Agent Teams**：多 Agent 编排与协作。支持 Lead / Member 角色、共享任务、单播与广播消息、Claim / Assign、Plan Approval，以及跨进程、跨 Session 的团队状态保持。

它对外暴露统一 Gateway 与 Web Console；内部由控制面（`aistiod`）、数据面（Java Dataplane）、调度器（Scheduler）和 PostgreSQL 协同完成产品状态与运行时状态的分离治理。开发者不必先理解每一层细节，也可以从 Console 创建 Agent、跑通第一条 Session，并在 Dashboard 看到完整运行轨迹。

与「只提供一个聊天框」的产品不同，AgentScope Service 把 Agent 当成可管理的系统资源：有版本、有环境、有会话、有事件、有团队。对平台管理员，它是舰队控制台；对业务开发者，它是少写基础设施的托管运行时；对安全与合规团队，它是工具边界、审批与审计的统一落点。

## 为什么需要 AgentScope Service

今天构建 Agent 通常有几条常见路径：

1. **本机 / CLI 助手**
   如 Claude Code、各类 Coding Agent、个人工作区助手。启动快、体验好，但状态落在本机，难共享、难审计，也不适合多团队共管。电脑关机，任务也常常跟着停。

2. **SDK / 应用内嵌 Harness**
   用 AgentScope、LangChain、ADK 等在业务服务里直接跑 agent loop。灵活度高，但租户隔离、版本发布、Session 恢复、HITL、事件落库、跨副本协调都要自己补齐。每个业务线做一遍，标准很难对齐。

3. **低代码 / 工作流平台**
   通过可视化节点拼装 Agent。上手容易，却常把记忆压缩、重试、权限、子任务回收拆成大量配置项；效果和稳定性依赖配置经验，平台升级也难惠及所有存量 Agent。

4. **部分托管 Agent 产品**
   开始提供云端 Session 与沙箱执行，但往往绑定单一框架或单一执行面；跨框架舰队运营、客户 VPC 内 Hands、以及企业级多 Agent 协作仍不完整。

这些路径并不互斥。一家公司里，研发可能用 Coding Agent，业务中台用 LangChain，新项目想直接上托管 Harness——这很常见。真正上线时，团队通常会同时碰到三类断点：

- **定义断点**：Agent 的 prompt、工具、MCP、Skills 如何版本化？改坏了如何回滚？谁有权限发布？
- **运行断点**：进程重启后 Session 能否续上？上下文压力如何观测与压缩？敏感工具谁来确认？长任务中断后如何继续？
- **治理断点**：不同团队、不同框架写出的 Agent，能否进入同一 Dashboard？任务与团队协作状态能否跨 Session 保留？出了问题能否按事件轨迹复盘？

AgentScope Service 的目标，不是替换你现有的 Agent 框架，而是提供一层统一控制面：

- 既支持在平台内可视化定义并运行 Managed Agent；
- 也支持把已有 AgentScope / LangChain / Claude 等运行时接入同一舰队视图；
- 同时补齐 Session 上下文监测与压缩、任务确认与鉴权、Memory / Vault、Cron / Webhook 触发，以及 Agent Teams 协作。

换句话说，你可以继续用最合适的框架写 Agent 逻辑，但不必再为每条业务线各自搭建一套「半成品平台」。一句话：**为企业提供一站式的 Agent 管控与治理中心。**

## 整体架构

Agent Service 是产品品牌；实现上由 Gateway、控制面、数据面与调度器组成。对外只需暴露 Gateway，内部组件通过共享 Token 通信，并保持清晰的数据边界。

```text
┌────────────────────────────────────────────────────────────────────────────┐
│                              Agent Service                                 │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ Web Console：Dashboard · Managed Agents · Agent Teams               │  │
│  └───────────────────────────────┬──────────────────────────────────────┘  │
│                                  │                                         │
│                        Browser / SDK / CLI                                 │
│                                  │                                         │
│                                  ▼                                         │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ service-gateway :8080 · 认证与公共 API 路由                         │  │
│  └───────────────────┬──────────────────────────────────┬───────────────┘  │
│                      │                                  │                  │
│                      ▼                                  ▼                  │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ aistiod :8081               │    │ service-dataplane :8082         │  │
│  │ 产品与运行时控制面          │    │ AgentScope Brain                │  │
│  │ 舰队注册与 Agent Teams      │    │ Turn · Event · SSE · HITL       │  │
│  └──────────────┬───────────────┘    └────────────────┬────────────────┘  │
│                 │                                     │                   │
│                 │         ┌───────────────────────────┘                   │
│                 ▼         ▼                                               │
│  ┌──────────────────────────────┐    ┌─────────────────────────────────┐  │
│  │ PostgreSQL                  │◀───│ service-scheduler :8083         │  │
│  │ cp · rt · dp schemas        │    │ Channel · Cron · Hands Worker   │  │
│  └──────────────────────────────┘    └─────────────────────────────────┘  │
│                                                                            │
│     Managed HarnessAgent Runtime · BYO AgentScope Runtime · Sandbox       │
└────────────────────────────────────────────────────────────────────────────┘
```

四类平面的职责可以这样理解：

| 平面 | 负责 | 不负责 |
| --- | --- | --- |
| Gateway | 公共入口、认证与 API 路由 | 业务状态与 Agent 执行 |
| Control（`aistiod`） | 产品资源、控制台、舰队状态、Session、Team 与运行时命令 | 模型 Turn |
| Dataplane | Harness Runtime、事件日志、SSE、Turn Lease、HITL 与 Work Queue | 直读产品 Catalog 表 |
| Scheduler | Channel、Cron、出站任务与 Self-hosted Hands Worker | 推理循环 |

产品上还有一个关键拆分：**Brain** 与 **Hands**。

- **Brain**：管理上下文、推理、工具决策和事件日志；由平台托管的 AgentScope Harness 承担。
- **Hands**：决定工具在哪里执行。可选 `local`、`sandbox`（如 E2B）、`remote`、以及客户侧出站 Worker 的 `self_hosted`。

这意味着企业可以分别回答三个问题：模型能看到哪些上下文？工具能访问哪些网络和文件？工具结果中哪些内容可以回传 Brain？信任边界被拆开后，权限审核与故障定位都会清晰得多。

这也解释了为什么「托管」不等于「所有数据都必须离开客户环境」。对脱敏可在云侧推理的场景，可用托管沙箱；对必须触达内网系统或敏感文件系统的场景，可把 Hands 放在客户 VPC，由出站 Worker 执行工具并回传结果。Brain 仍然负责编排与状态恢复，只是执行面被替换了。

对希望进一步理解 Turn 路径、事件契约与 schema 边界的读者，我们另有一篇技术向文章：[AgentScope Service 技术解读](./agentscope-service-release-tech.md)。

### 产品资源模型（用户视角）

在 Console / API 中，你主要和这些资源打交道：

| 资源 | 作用 |
| --- | --- |
| Agent | 可版本化的 System Prompt、模型、工具、MCP、Skill |
| Environment | 工具执行边界：本地、沙箱、远程或自托管 Worker |
| Session | Agent、环境、Memory、Vault 与事件流的有状态绑定 |
| Memory / Vault | 跨会话知识与加密凭据 |
| Deployment / Channel | 定时、Webhook、手动触发与消息通道 |
| Team | Lead / Member、消息、任务、计划与生命周期 |

不必一次用全。最小闭环只要 Agent + Environment + Session；需要凭据隔离时加 Vault，需要定时跑批时加 Deployment，需要多人协作时再开 Team。资源模型的目标是让「平台能力」可组合，而不是逼业务一开始就填写一张超长配置表。

## 核心能力展示（UI Console）

> 本节后续会补充产品截图；当前先说明各模块面向用户提供的能力。

### Dashboard

Dashboard 回答的是运营问题：「现在跑了什么、哪里需要关注、历史上发生了什么」。

它把以前散落在日志平台、业务后台和各框架自带面板中的信息，收敛成同一张舰队视图：

- 在线 Agent、健康实例与离线 / 历史视图分层呈现
- Dataplane 健康状态、版本、副本与活跃 Session
- Session 时间线、上下文压力、Token 用量、错误与运行时操作
- Agent Team 活动、成员状态、任务进度与生命周期
- Managed Agent 与 BYO Agent 进入同一运营模型

对值班同学，Dashboard 首先是「哪里红了」；对平台同学，它是容量与健康；对业务同学，它是某条 Session / 某个 Team 为什么卡住。同一运营模型降低了跨团队沟通成本——你不必先问「这是不是我们那个 LangChain 服务」或「这是不是 Console 新建的 Agent」。

<!-- TODO: 在此插入 Dashboard 总览截图 -->

<!-- TODO: 在此插入 Session 详情 / 上下文压力截图 -->

### Managed Agents

Managed Agents 覆盖从定义到持久化对话的完整托管生命周期。平台托管的是 Harness 工程能力，业务侧主要定义差异化部分：角色、Skills、工具、MCP 与权限策略。

- **可版本化 Agent 定义**：模型、System Prompt、内置工具、MCP Server、Skills
- **明确执行环境**：`local` / `sandbox` / `remote` / `self_hosted`
- **顶级 Sessions**：静态创建、事件驱动 Turn、历史恢复、SSE 流式输出
- **工具审批与 HITL**：敏感操作可暂停等待确认，再续跑
- **Session 级资源**：Memory、Vault、Workspace 与资源覆盖
- **多种触发入口**：Manual、Webhook、Cron、Channel

典型路径很直接：创建 Agent → 创建 Environment → 创建 Session → 发送第一条消息 → 在 Dashboard 观察事件流。Session 创建本身不会立刻跑 Agent；第一条用户消息才会真正启动 Turn。这样做的好处是绑定关系清晰、资源可预检，也避免「创建即计费跑一轮空推理」。

对长任务场景，Managed Agents 尤其关键的是**可恢复**：事件落库、状态可重建、HITL 可暂停续跑。前端刷新或服务副本切换，不应等于任务从头开始。UI 流式展示可以很「轻」，但权威结果始终以持久化事件为准。

<!-- TODO: 在此插入 Agent 定义 / 版本管理截图 -->

<!-- TODO: 在此插入 Session 对话与事件流截图 -->

### Agent Teams

当单个 Agent 不足以覆盖复杂任务时，Agent Teams 把独立 Agent 组织成具备持久化协作状态的团队。

- Lead / Member 角色、团队目标与可复用团队组成
- 单播 / 广播消息、共享任务、Claim / Assign 与 Plan Approval
- 带数量与白名单约束的动态成员
- 成员 Wakeup、优雅关闭、生命周期时限与故障恢复
- 跨进程、跨 Session 保留的团队消息与任务

团队不是「聊天室」，而是一套可运营的协作单元：任务可认领、计划可审批、成员可唤醒，状态也不会因为某个 Session 结束而全部消失。一个常见模式是 Lead 负责任务拆解与验收，Member 按能力认领调研、编码、核验等子任务；平台负责消息路由、任务板与生命周期，而不是让业务代码手写一套临时多进程通信。

Managed Agent 与 BYO Agent 也可以进入同一团队视图。对已经在生产的异构 Agent 舰队来说，这比要求全部重写到单一框架现实得多。

<!-- TODO: 在此插入 Team 总览 / 任务板截图 -->

<!-- TODO: 在此插入 Team 消息与成员状态截图 -->

## 如何接入

AgentScope Service 同时服务两类用户：

- 想「少写基础设施、直接开跑」的团队——用 Console / API 创建 Managed Agent；
- 已有 Agent 应用、希望纳入统一治理——通过扩展 / SDK / Sidecar 接入控制面。

两条路径可以并存。很多团队会先用 Managed Agents 跑通新产品，再把存量 BYO Agent 逐步纳入 Dashboard；也有团队反过来，先接入观测与治理，再把通用 Agent 迁到托管 Harness。

### AgentScope

原生支持。Java 应用可加入 `agentscope-extensions-aistio` 依赖，将现有 AgentScope Runtime 注册到控制面，与 Managed Agent 一同出现在 Dashboard。会话状态、健康信息与运行时观测沿同一套契约上报，舰队视图无需另做一套后台。

适合已经基于 AgentScope / Harness 构建业务 Agent、希望平滑进入平台治理的团队。对这类用户，价值通常不是「换一个新框架」，而是马上获得版本、Session、舰队与后续 Teams 能力。

### LangChain

通过 `aistio.instrument()` 接入。对 LangChain / LangGraph 应用，控制面侧以旁路方式采集 Session 快照、上下文与运行时指标；主业务路径先成功，上报失败不影响推理本身。

这样一来，LangChain 写就的 Agent 也能进入 AgentScope Service 的舰队运营与 Session 观测，而不必重写业务链路。对已经沉淀大量链与图的团队，这是进入统一治理的务实路径。

### Claude SDK / Coding Agent

Claude Agent SDK 可通过同样的 `instrument()` 路径接入；对于 Claude Code、Qoder 等难以直接改二进制的 Coding Agent，则可借助 **Sidecar** 桥接：旁路观察本地 Session 目录与运行状态，上报到控制面，并承接压缩、终止等运营命令。

这条路径的意义在于：企业不必在「用最强 Coding Agent」和「纳入统一治理」之间二选一。研发提效工具可以继续跑在开发者环境，平台仍能看见它、管理它、在必要时干预它。

### 本地快速体验

如果你想先完整体验产品面，可从 Monorepo 启动本地环境：

```bash
export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
BUILDER_REBUILD=1 scripts/dev-up.sh
```

打开 http://localhost:8080（默认账号 `admin` / `admin`），建议按这个顺序走一遍：

1. 在 **Managed Agents** 创建 Agent；
2. 创建一个 `local` Environment；
3. 打开 **Sessions**，绑定 Agent 与 Environment，发送第一条消息；
4. 回到 **Dashboard** 查看在线状态、事件与运行时信息；
5. 如需协作，再进入 **Agent Teams** 创建团队并观察任务与成员状态。

完整 curl 步骤见仓库内 [`agentscope-service/docs/guide/03-quickstart.md`](../../../agentscope-service/docs/guide/03-quickstart.md)。默认账号仅用于本地开发，请勿用于生产。

## Roadmap

AgentScope Service 会沿着「更开放的接入、更完整的自动化、更强的事件驱动」继续演进。近期重点包括：

1. **更多 Agent 框架与 Coding Agent 接入**
   补齐并深化 LangChain、ADK、Claude、Qoder、OpenAI Agents 等适配，降低 BYO 接入成本，让异构舰队进入同一契约更容易。

2. **更完整的托管生命周期与运维能力**
   强化版本发布、灰度、审计、配额与多租户治理，让 Managed Agents 更贴近企业生产标准。

3. **Automation**
   围绕 Deployment、Cron、Webhook、Channel 扩展自动触发与闭环执行，让 Agent 从「人来开会话」走向「事件来了就干活」。

4. **更多事件驱动集成**
   接入 GitHub / GitLab、钉钉、企微等研发与协作入口，把代码变更、工单、群消息直接变成 Agent Turn 或 Team Task。

5. **Agent Teams 持续增强**
   完善动态成员、计划审批、失败恢复与跨 Session 协作体验，让多智能体编排真正可运营，而不是演示级别的临时串场。

我们会优先做真实生产会卡住的能力：接入成本、恢复可靠性、审批边界、自动触发，以及跨团队可观测性。欢迎用 issue 与场景告诉我们，你的舰队卡在哪一层。

## 谁适合现在开始用

如果你符合下面任一情况，AgentScope Service 会比较值得试用：

- 已经有 AgentScope / Harness 应用，希望补上舰队视图、Session 治理与审批，而不是重写业务；
- 计划上线托管 Agent，但不想从零自研事件日志、Turn 租约、HITL 与 Environment 切换；
- 公司里同时存在多种框架或 Coding Agent，需要一张统一运营面板；
- 工具必须触达客户 VPC 或敏感文件系统，需要 Brain / Hands 分离与自托管执行面。

如果你只是验证单次 prompt 效果，本机 CLI 或最小 SDK 示例可能更轻；等你开始关心版本、恢复、审批和跨团队运营时，再上 Service 会更合适。

## 结语

Agent 普及的下一阶段，比拼的不只是单次回答质量，而是系统能否被定义、被观察、被审批、被恢复、被多人协作地长期运行。

AgentScope Service 把 Dashboard、Managed Agents 与 Agent Teams 收敛到同一产品面：既给你开箱可用的托管 Harness，也为已有 Agent 舰队留下接入入口。无论你从 Console 新建第一个 Agent，还是把现有 AgentScope / LangChain / Claude 应用接入控制面，目标都一样——**让企业拥有一站式的 Agent 管控与治理中心**。

欢迎试用，也欢迎反馈真实场景中的缺口。更多架构说明、API 与运维文档见 [`agentscope-service`](../../../agentscope-service/README_zh.md) 目录；技术细节可阅读[技术解读版](./agentscope-service-release-tech.md)。
