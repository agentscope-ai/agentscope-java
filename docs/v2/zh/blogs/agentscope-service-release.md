---
hide-toc: true
---

今天，社区正式推出了 **AgentScope Service** — 基于 AgentScope Harness 构建的 Agent 管控与治理平台，为企业提供统一的控制面与治理中心！

<!-- 这是一张图片，ocr 内容为：AISTIO LS FLEET OVERVIEW CONTROL PLANE CONSOLE CROSS-FRAMEWORK AGENT INSTANCES AND RUNTIME SESSIONS REPORTED INTO AISTIOD. 品 DASHBOARD TOKENS (24H, IDLE SESSIONS STALE AGENTS HEALTHY ERRORS (24H) ACTIVE OVERVIEW A) INSTANCES INSTANCES SESSIONS 0 1 R AGENTS 1 R 6,319 3 SESSIONS GOVERNANCE AGENTS COUNTS LIVE INSTANCES ONLY.3 HISTORICAL MANAGED AGENTS TOKEN USAGE(24H) 8 TEAMS HOURLY SUM OF USAGE DELTAS (NOT CUMULATIVE SNAPSHOTS) 14:00:6,319 TOKENS TOP 10 AGENTS BY TOKENS TOP 10 SESSIONS BY TOKENS RANKED BY TOKEN USAGE DELTAS - LAST 24H RANKED BY TOKEN USAGE DELTAS `LAST 24H #SESSION ACTIVE TOKENS ERRORS 井 AGENT PHASE TOKENS ADMIN MAIN-ED0098A8-E94E-42A9-9578- DEFAULT 1 6,319 6.319 B5FAD881F839 DEFAULT ACTIVE PROFILE USERS DEFAULT -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785989956180-2b6581fd-cf41-4155-baaf-08db90a6eb5d.png)



+ **AgentScope Service 是一个控制面。**为企业内的所有 agent 提供智能体注册、查询、分布式协调服务，兼容 AgentScope、LangChain、ADK、Claude / Qoder 等主流 agent 运行时，让企业可以有一个集中的 agent 指标查看入口，同时可以对运行中的 session 会话进行上下文压缩等操作；
+ **AgentScope Service 提供低代码 Agent 创建与部署能力。**底层基于 AgentScope Harnes 运行时，可以让您快速将多个 Agent 运行在一套统一管理的 Managed Agents 平台上，平台提供 Harness 能力托管，工具执行则可以委托给用户自己控制的 sandbox 沙箱；
+ **注册在 AgentScope Service 中的智能体，可以被组建为一个或多个 Teams 团队。**不论是自己部署的 AgentScope 运行时还是低代码托管的 agent harness 运行时，都可以被编排在一起，共同协作完成更复杂的任务。

## 什么是 AgentScope Service
AgentScope Service 的目标不是替换你现有的 Agent 框架，而是提供一层统一控制面，让你把用不同框架、不同技术栈（Claude、OpenClaw、QwenPaw等）搭建的智能体统一管控起来。

今天，构建企业级 Agent 已经有很多不同的选择。AgentScope 作为一款优秀的 Agent Framework，为企业提供了构建智能体的完整方案。但**传统 Agent Framework 并不是当前唯一的智能体构建方式**，我们看到 Coding Agent 产品正延伸到更多领域，比如使用 Claude SDK、Qoder CLI 等构建企业级智能体也正成为很多企业用户的选择。

1. **使用 Agent Framework**
   用 AgentScope、LangChain、ADK 等在业务服务里直接跑 agent loop。灵活度高，但租户隔离、版本发布、Session 恢复、HITL、事件落库、跨副本协调都要自己补齐。每个业务线做一遍，标准很难对齐。
2. **使用 Coding Agent 或个人工作区助手**
   如 Claude Code、各类 Coding Agent、个人工作区助手。启动快、体验好，但状态落在本机，难共享、难审计，也不适合多团队共管。电脑关机，任务也常常跟着停。
3. **使用低代码或 Managed Agents 平台**
   传统低代码平台通过可视化节点拼装 Agent，上手容易，却常把记忆管理、上下文压缩、工具组装等拆成大量配置项，让用户为效果和稳定性负责。Managed Agents 则强调云端 Harness 能力全面托管，让用户不再为担心、客户 VPC 内 Hands、以及企业级多 Agent 协作仍不完整。

这些路径并不互斥，一家公司里，研发可能用 Coding Agent，业务中台用 AgentScope，新项目想直接上托管 Harness——这很常见。AgentScope Service 不绑定任何智能体框架或平台，它为所有智能体运行时提供统一的管控能力。

### Control Plane
Control Plane（控制面）是 AgentScope Service 的核心组件，所有 Agent 应用都通过控制面进行统一注册，通过 SDK 或 Sidecar 方式支持主流 Agent Framework（AgentScope、LangChain、ADK）以及 Claude、Qoder 等注册与接入。



Dashboard 则是控制面的可视化 UI 控制台，为整个集群提供在线 agent 列表、部署实例信息、活跃 session 信息、token 消耗等全局观测信息，方便了解集群工作状况。



<!-- 这是一张图片，ocr 内容为：AISTIO AS FLEET OVERVIEW CONTROL PLANE CONSOLE CROSS-FRAMEWORK AGENT INSTANCES AND RUNTIME SESSIONS REPORTED INTO AISTIOD. 品 DASHBOARD TOKENS (24H. HEALTHY IDLE SESSIONS STALE ERRORS (24H) AGENTS ACTIVE OVERVIEW 4) INSTANCES INSTANCES SESSIONS 1 R R AGENTS O 1 6,319 3 SESSIONS GOVERNANCE AGENTS COUNTS LIVE INSTANCES ONLY.3 HISTORICAL MANAGED AGENTS TOKEN USAGE(24H) 8 TEAMS HOURLY SUM OF USAGE DELTAS (NOT CUMULATIVE SNAPSHOTS) 14:00:6,319 TOKENS TOP 10 AGENTS BY TOKENS TOP 10 SESSIONS BY TOKENS RANKED BY TOKEN USAGE DELTAS `LAST 24H RANKED BY TOKEN USAGE DELTAS `LAST 24H SESSION TOKENS ACTIVE ERRORS # PHASE AGENT TOKENS ADMIN MAIN-ED0098A8-E94E-42A9-9578 DEFAULT 1 6,319 6,319 B5FAD881F839 DEFAULT ACTIVE PROFILE USERS DEFAULT -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785934371848-7b1b934e-11ed-4625-97cc-820f2fe5d214.png)



此外，还可以在 dashboard 中查看 session 会话信息，查看活跃 session 的实时上下文状态（各部分数据占比），动态调整或压缩会话上下文，介入会话过程等。

<!-- 这是一张图片，ocr 内容为：AISTIO S SESSIONS CONTROL PLANE CONSOLE 7818AE8D-D486-4B41-BD2A ABORT TUM RESTORE EXIT PLAN ENTER PLAN COMPRESS TERMINATE CF8B7EB67224 品 DASHBOARD AGENTSCOPE-PAW - DEFAULT - AGENTSCOPE-JAVA - TURN #7 OVERVIEW AGENTS LIFETIME USAGE PHASE LAST ACTIVE INSTANCE MODEL PRESSURE SESSIONS 31% 46,777 2026/7/30 HEALTHY 22:55:01 GOVERNANCE ZPROMPT+COMPLETION U-FF406114-1819... HTTP://LOCALHOS MANAGED AGENTS WINDOW-IN 45,260/ OUT 1.517 TEAMS CONTEXT VIEW COMPACTED. 6 CFFECTIVE MSGS -7 TOOLS - WINDOW 125/ 32,768 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785946414310-ff29cee8-2b2b-40df-9ec8-0211ee03fe8c.png)

### Managed Agents
Managed Agents 是由 `agentscope-builder` 平台升级而来，它的定位仍旧是一个低代码 Agent 平台，为开发者提供 Agent 定义、Agent 托管运行的 SaaS 化平台能力。同时更强调推理与工具执行的分离，推理 harness 能力更彻底的托管，工具执行则开放给用户更多的控制权。



<!-- 这是一张图片，ocr 内容为：AISTIO AGENTS NEW AGENT CONTROL PLANE CONSOLE LOW-CODE MANAGED AGENTS. EACH AGENT IS SHAPED BY ITS WORKSPACE - AGENTS.MD, TOOLS, SKILLS AND SUBAGENTS. 品 DASHBOARD CLONE-ONLY O ALL 4 SHARED WITH ME O MINE 4 GLOBAL MANAGED AGENTS AGENTS SESSIONS BBB PPP OWNER OWNER OWNER CCC 调用专用AGENT 擅长做微服务相关搜索 WORKSPACES BBB TEST AG_4ECD3838B3CD AG_1426C299ADF8 AG_5AF01156E61F ENVIRONMENTS WORKSPACE LINKED WORKSPACE LINKED WORKSPACE LINKED MEMORY VAULTS DEPLOYMENTS OWNER AAA CHANNELS XXXXX AG_CECC0395E056 8 TEAMS WORKSPACE LINKED ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948183107-014a5cb1-6fcf-4b04-93cb-f01341b35350.png)



Agent 定义总体围绕 AgentScope Harness 的核心设计理念设计，首先定义好 Workspace、Memory 等基础概念，通过将 workspace、memory 与 agent 关联即可创建一个智能体。



定义Workspace：

<!-- 这是一张图片，ocr 内容为：AISTIO WORKSPACES CONTROLPLANE CONSOLE TEST MANAGE AGENTS.MD, SKILLS, TOOLS AND SUBAGENTS FOR LINKED AGENTS. 品 DASHBOARD V1 SKILLS O SUBAGENTS O . AGENTS.MD MANAGED AGENTS SUBAGENTS AGENTS.MD SKILLS TOOLS MARKETPLACE AGENTS SESSIONS BUILTIN TOOLSET WORKSPACES BASH FILESYSTEM EXECUTE A SHELL COMMAND ENVIRONMENTS READ FILESYSTEM MEMORY READ A FILE FROM THE WORKSPACE VAULTS WRITE FILESYSTEM WRITE A FILE IN THE WORKSPACE DEPLOYMENTS EDIT FILESYSTEM EDIT A FILE VIA STRING REPLACEMENT CHANNELS GLOB FILESYSTEM 8 TEAMS FIND FILES BY GLOB PATTERN GREP FILESYSTEM SEARCH FILE CONTENTS WITH REGEX WEB_FETCH WEB FETCH CONTENT FROM A URL WEB SEARCH WEB SEARCH THE WEB FOR INFORMATION MEMORY_SAVE HARNESS SAVE A LONG-TERM MEMORY FACT MEMORY_GET HARNESS GET A MEMORY ENTRY -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948616059-4d7be456-50bf-4e68-9ebf-3d48ce0ca9d3.png)



定义 Agent：

<!-- 这是一张图片，ocr 内容为：AISTIO SY NEW AGENT CONTROL PLANE CONSOLE DASHBOARD PREFER LINKING A WORKSPAGE SO AGENTS.ND / SKILLS/  TOOLS / SUBAGENTS ARE AUTHORED ONCE AND INTO THIS AGENT.OR LEAVE WORKSPACE EMPTY FOR AN AGENT-PRIVATE DEFINITION. MANAGED AGENTS NAME AGENTS ASSISTANT SESSIONS WORKSPACES DESCRIPTION ENVIRONMENTS DEMO ASSISTANT AGENT MEMORY WORKSPACE VAULTS TEST DEPLOYMENTS LINK A WORKSPACE TO INHERIT AGENTS.ND; SKILLS, TOOLS AND SUBAGENTS,MANAGE WORKSPACES FROM THE WORKSPA CHANNELS WILLINHERIT FROM TEST(V1):AGENTS.ND - SKILS O- SUBAGENTS O,LEAVE SYSTEM PROMPT BLANK TO USE WORKSPACE TEAMS AGENTS.MD. DEFAULT ENVIRONMENT(OPTIONAL) NONE-CHAT WILL ENSURE A LOCAL DEFAULT USED WHEN OPENING CHAT / CHANNEL SESSIONS.VAULTS AND MEMORY STORES CAN BE ATTACHED LATERIN SETTINGS WORKSPACE PATH(OPTIONAL OVERRIDE) LEAVE BLANK FOR DEFAULT UNDER AISTIOD WORKSPACE ROOT LEAVE BLANK TO USE THE CONTROL-PLANE DEFAULT PATH.ABSOLUTE PATHS ARE USED AS-IS. ADMIN SYSTEM PROMPT PROFILE USERS YOU ARE A HELPFUL ASSISTANT. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948697346-865380da-fb2c-420b-9968-4275b51a85b6.png)



此次升级最大的变化是底层托管运行时逻辑与架构 -- Managed Agents。平台总体将静态定义（Agent、Workspace）与动态运行（Environment、Session）区分开来，通过 Environment、Session 等来编排 Agent 运行时行为。



创建 session，绑定 self_hosted sandbox 运行时 environment：

<!-- 这是一张图片，ocr 内容为：AISTIO AS SESSIONS CONTROLPLANE CONSOLE NEW SESSION CREATES A SESSION RESOURCE BOUND TO AN AGENT AND MOUNTS.NO TURN STARTS UNTILYOU SEND A DASHBOARD MESSAGE IN CHAT. MANAGED AGENTS AGENT ASSISTANT AGENTS SESSIONS NEW SESSION WORKSPACES / CREATE A SESSION DEFINITION ONLY - NO TURN STARTS UNTIL THE FIRST MESSAGE. CHOOSE ENVIRONMENT, I VAULTS, AND MEMORY STORES. AGENT SESSION DEFAULTS PREFILL THE FORM. ENVIRONMENTS RESET TO AGENT DEFAULTS MEMORY ENVIRONMENT VAULTS SELF-HOSTED-FC-SANDBOX(SELF_HOSTED) DEPLOYMENTS VAULTS CHANNELS NO VAULTS.CREATE ONE UNDER BUILD > VAULTS. TEAMS MEMORY STORES BBB AAA OPTIONAL OVERRIDES CREATE SESSION CANCEL -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948796759-2723bb0e-e25e-49a6-aad4-27f8eb368d8d.png)



Session 创建后，本身并不会启动 SSE Event 事件流，用户主动发起 user message 才会启动会话，执行整个推理过程。如下图，你可以在控制台 chat 页面发送 user message：

<!-- 这是一张图片，ocr 内容为：AISTIO CHAT SESSION DETAILS SESS_AD018033431F SESSIONS SY CONTROL PLANE CONSOLE ENV:SELF-HOSTED-FC-SANDBOX . VAULTS:0 MEMORY:1 ALL SESSIONS MANAGED SESSION DETAILS SESS_AD018033431F DASHBOARD NEW SESSION MANAGED AGENTS USER你好 AGENTS SESSIONS ASSISTANT 你好!有什么可以帮助你的吗? WORKSPACES ENVIRONMENTS MEMORY VAULTS DEPLOYMENTS CHANNELS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948840673-56827ddd-93f4-4091-9b7c-bbafa217a511.png)



创建 Agent → 创建 Environment → 创建 Session → 发送第一条消息 → 在 Dashboard 观察事件流。Session 创建本身不会立刻跑 Agent。对长任务场景，Managed Agents 尤其关键的是**可恢复**：事件落库、状态可重建、HITL 可暂停续跑。前端刷新或服务副本切换，不应等于任务从头开始。

在运行架构上，设计与 Claude Managed Agents 非常类似，Harness 基础设施与运行时全托管（底层依赖AgentScope Harness Runtime），基于 Brain/Hands 分离的架构让用户对工具执行环境有更多控制权。部署架构上，分为控制面、托管数据面两大组件，具体可参考后面的部署架构章节。

### Agent Teams
注册在 AgentScope Service 控制面的所有智能体，不论是使用框架开发部署、自行注册到控制面的（Langchain、AgentScope、ADK、Claude SDK等）智能体，或者是使用 Managed Agents 低代码方式直接创建的托管 Agent，都可以把它们按照你想要的方式编排在一起，形成一个可以互相协作的 Agent Teams 来协作处理复杂。

<!-- 这是一张图片，ocr 内容为：AISTIO IS TEAM1 BACK COMPLETE TEAM FORCE DELETE LEAD CLOSE CONTROL PLANE CONSOLE CCC SOSS_025CA811A27B 帮我分析E2B沙箱和DAYTONA沙箱 SESS_E25CA811A27B FULL PAGE TEAM CHAT DASHBOARD IDLE NS-DEFAULT TASKS 1/2COMPLETE 1 IN PROGRESS 0 PENDING TASK-1.I ALSO LET THEM KNOW THAT THEY CAN REACH OUT IF THEY NEED ANY MANAGED AGENTS SPECIFIC RESOURCES OR HAVE ANY TOPOLOGY AGENTS QUESTIONS. SESSIONS WORKER1 LEAD IS THERE ANYTHING ELSE YOU WOULD LIKE TO ADDRESS AT THIS MOMENT? WORKS PACES ENVIRONMENTS OPEN CHAT CHAT OPEN [TEAM:TEAM1 FROM WORKER1]I HAVE MEMORY CLAIMED TASK-1 AND WILL START THE VAULTS ANALYSIS OF THE E2B SANDBOX.I WILL REACH OUT IF I NEED ANY SPECIFIC DEPLOYMENTS TASK BOARD MEMBERS MESSAGES RESOURCES OR HAVE ANY QUESTIONS. CHANNELS NEW TASK SUBJECT ADD TASK TEAMS TOOL:CLAIMTASK CA11_78816 UNASSIGNED(0) BLOCKED((() COMPLETED(1) IN PROGRESS(1) ASSIGNED(0) TOOL: CA11_5B2 TEAMS 分析E2B治理沙箱 分析DAYTONA 治理沙 箱 TEMPLATES TOOL:TEAM COMPLETE UNCLAIM SEND MESSAGE AG_4ECD3838B3CD... FAILED(0) ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948895276-d0221173-9683-4a94-b281-55f9372cee65.png)

在 AgentScope Service 设计中，Teams 团队不是聊天室，而是一套可运营的协作单元：任务可认领、计划可审批、成员可唤醒，状态也不会因为某个 Session 结束而全部消失。一个常见模式是 Lead 负责任务拆解与验收，Member 按能力认领调研、编码、核验等子任务，平台负责消息路由、任务板与生命周期，而不是让业务代码手写一套临时多进程通信。

值得特别说明的是，AgentScope 框架原生支持 Agent Teams 能力，这套机制是基于 AgentScope Service 控制面做分布式任务管理与调度，所以您既可以使用 AgentScope Framework 原生的 Teams 能力在主 agent 编码阶段实现多 agent 编排，也可以在控制台上根据需求将多个独立的 agent 动态编排在一起完成某一项复杂任务。具体取决于您的使用场景。

## 整体架构
### 总体架构
<!-- 这是一张图片，ocr 内容为：HUMAN 用户USER REST API DASHBOARD 接入入口:日 SDK/CURL BROWSER .可视化控制台 第三方系统集成 AGENTSCOPE SERVICE 控制面.CONTROLPLANE CONTROLPLANE 框架接入FRAMEWORKS AGENTSCOPE CLAUDE QWENPAW LANGCHAIN SIDECAR  接入 原生接入 SIDECAR 接入 INSTRUMENT SDK AGENTSCOPE  SERVICE  系统架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785984168683-7e939049-046d-4ffa-b30d-1c0e6c0ff01b.png)

Human 通过 Dashboard（浏览器操作）或 REST API（SDK / curl / 第三方系统集成）两条入口进入 AgentScope Service Control Plane；控制面之下统一管理四类 Agent 接入方式：AgentScope 原生接入、LangChain 通过 `instrument()` 接入，Claude 与 QwenPaw 则通过 Sidecar 旁路接入。

### Managed Agents
<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785976191807-3dde2cf8-ece0-4819-b376-328b498ed00c.png)



<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785976204028-86690651-b369-4c47-b3eb-73c9f7da508e.png)

### Agent Teams 协作流程
一个 Team 里的成员不要求来自同一个框架、同一种托管方式。用户在控制台里选择若干个已经注册到控制面的 Agent，指定谁是 Lead、谁是 Worker，就完成了一次编排——Lead 负责创建与分配任务，Worker 负责认领与执行，协作状态则统一交给控制面维护：

<!-- 这是一张图片，ocr 内容为：HUMAN/CONSOLE 发起方HUMAN 创建 TEAM:选择已注册 AGENT 组成 LEAD +WORKERS AGENTSCOPE SERVICE CONTROL PLANE TEAM: RESEARCH 控制面CONTROL PLANE TASK BOARD (PENDING / CLAIMED / DONE) MAILBOX(单播 TO-MEMBER  广播TO 空) TEAM-JOIN (BYO 成员)/FIND-OR-CREATE SESSION (MANAGED 成员) 团队成员TEAMMEMBERS WORKER 3 WORKER 1 WORKER  2 LEAD (代码评审) (安全扫描) (法务合规) (CTO) LANGCHAIN/CLAUDE SIDECAR AGENTSCOPE 原生 MANAGED AGENT MANAGED AGENT SELF-CLAIM 创建任务.ASSIGN SELF-CLAIM CLAIM 未分配任务 未分配任务 已分配任务 自动认领 自动认领 共享 TEAM 状态 跨进程/跨 SESSION 持久化消息与任务 共享状态SHARED STATE AGENTSCOPE SERVICE - TEAM 协作架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785985724730-b8f8d88c-669a-430c-bbf6-0c9f2d64b0f7.png)

图里几个关键点：

+ **成员类型可以异构**。示例里的 Lead 与 Worker 1 是 Managed Agent（控制面为其创建绑定 `teamContext` 的 Session，直接投递 `user.message` 起跑），Worker 2 是自行部署的 AgentScope 原生运行时，Worker 3 则是 LangChain（或经 Sidecar 接入的 Claude）——控制面对不同成员下发的加入方式不同（Managed 走 `find-or-create session`，BYO 走 `team_join` 命令），但暴露给 Lead 的协作模型是一致的：都是 Team 里可以被分配任务、可以收发消息的成员。
+ **Lead 分配，Worker 认领，两条路径都存在**。Lead 创建任务时可以直接指定 `owner`（assign 给某个 Worker），该 Worker 收到后走 claim → start → complete；Lead 也可以创建一个不指定 `owner` 的任务扔进 Task Board，由空闲的 Worker 自己 `POST .../claim` 完成 self-claim——两种模式在同一个 Task Board 上并存，不需要 Lead 时刻盯着谁在忙。
+ **消息分单播和广播**。Mailbox 支持指定 `to=member` 的定向消息（比如 Lead 单独提醒某个 Worker），也支持不填 `to` 的广播（所有成员可见），二者共用同一套持久化通道。
+ **协作状态不挂在某一次会话上**。Task Board 和 Mailbox 的数据独立于任何单个成员的 Session 生命周期：某个 Worker 的进程重启、Session 结束，不影响任务是否还在、消息是否还能追溯——这也是为什么某个 Worker 崩溃后，控制面能够识别成员状态变为 `Lost` 并触发恢复，而不是直接丢掉整个团队的进度。

### AgentScope原生框架 + 控制面
AgentScope 框架本身提供了完善的企业级 Agent 解决方案，支持 Harness、Agent Teams、Multi-agent 协作、Sandbox 隔离等，在真正的企业级部署架构下，很多能力依赖分布式组件协调，AgentScope Service 控制面为 AgentScope 提供了原生分布式协调能力。

#### 控制面分布式协调
AgentScope HarnessAgent 一旦从单实例走向多副本部署，会话状态、工作区文件、沙箱快照与并发锁、跨副本消息、异步工具、子任务与 Turn 并发控制，都不能再假设"进程内存里就是权威数据"。

下图展示的是运行时拓扑：多个 `HarnessAgent` 副本如何分别与 Control Plane、AgentStateStore 后端交互，而不是 `DistributedStore` 的接口定义。



<!-- 这是一张图片，ocr 内容为：控制面:CONTROLPLANE AGENTSCOPE SERVICE CONTROL PLANE (AISTIO) 面向HARNESS的托管能力 WORKSPACE 共享  AGENT TEAMS (消息/任务协作) SESSION 并发控制.异步工具执行 协调类API调用(无状态数据本身) 运行副本WORKERS HARNESSAGENT HARNESSAGENT HARNESSAGENT 副本2(JVM) 副本1(JVM) 副本N(JVM) 直连读写会话状态(不经过控制面) 状态后端 `STATE STORE AGENTSTATESTORE 后端 REDIS / MYSQL / POSTGRES / OSS 业务自备,多副本共享同一后端 AGENTSCOPE SERVICE `HARNESS 托管运行架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785985855412-a79588d2-9ae3-4922-a30b-337ff4e6e526.png)



图中有两条**互不经过彼此**的独立链路，这一点很关键：

+ **向上：协调类 API 调用**。每个 `HarnessAgent` 副本通过 SDK 调用 Control Plane，托管的是开发者实际感知到的四类 Harness 能力：底层分别对应 `BaseStore`、`SandboxSnapshotSpec` / `SandboxExecutionGuard`、`MessageBus`、`TaskRepository`、`SessionTurnGate`、`AsyncToolRegistry` 等接口，协调类状态落在控制面自己的 Postgres 里，业务方不需要另起一套基础设施。
    - **Workspace 共享**：工作区文件（`MEMORY.md`、`skills/`、`sessions/` 等）以及沙箱快照与并发锁，让同一个工作区可以被任意副本读写、恢复；
    - **Agent Teams**：跨副本的消息投递与子任务委派，Lead / Member 之间的单播、广播与任务认领不受具体运行在哪个副本影响；
    - **Session 并发控制**：同一 Session 在多副本下的 Turn 级并发闸门，避免两个副本同时推进同一轮对话；
    - **异步工具执行**：后台运行的长耗时工具，其执行状态与结果可以被任意副本感知和回收。
+ **向下：直连会话状态后端**。`AgentStateStore`（对话上下文、压缩摘要、权限规则、Plan Mode 状态等）**不经过控制面**，而是由每个副本直接连接业务自备的 Redis / MySQL / Postgres / OSS。控制面只是可选地通过 Session 并发控制（`SessionTurnGate`）配合 `AgentStateStore` 自身的 `getVersioned` / `saveIfVersion` 乐观并发（CAS），减少多副本下重复触发同一个 LLM Turn——协调的是"谁能跑这一轮"，不是状态数据本身。

对开发者而言，这意味着只需在 `HarnessAgent.builder()` 上配置一个 `distributedStore`（协调类组件走 `ControlPlaneStores.fromEnv()`，`AgentStateStore` 单独指定一个共享后端），即可让 Agent 具备真正的水平扩展能力，而不必逐个自研会话恢复、文件共享、沙箱快照与任务队列。

#### 自动编排 Agent Teams
AgentScope 框架本身有一套闭环的 Agent Teams 能力，组队的触发方式与控制面直接编排不同：开发阶段并不需要提前把成员编排成某个固定的 Team 结构，只需要像 Subagent 模式一样，给 Main Agent 预先注册好一批可调用的 Subagent（`agentRef`）；到了运行期，只要 Human（或上游系统）发给 Main Agent 的消息里带上"需要组队处理"的任务描述，Main Agent 自己就会判断要不要组队、挑哪几个预注册的 Subagent 来当 Worker，动态创建一个 Team：



<!-- 这是一张图片，ocr 内容为：开发阶段:为MAIN AGENT预先注册一组可调用的 SUBAGENTREF),不提前编排成固定 TEAM 预定义SUBAGENT池(只是候选成员名单,不是TEAM) REVIEWER - SECURITY-SCANNER " PERF-TESTER " 运行期:HUMAN发一条消息给MAIN AGENT,消息里带着团队任务描息给MAN发一条消息里带着团队任务团队任务团队任务团队任务团队任务团队任务团队任务团队任务团队任务描述 "帮我并行做一次代码评审+安全扫描+性能测试,组个团队来处理" (HARNESSAGENT)收到消息后自行判断需要组队 MAIN AGENT 从SUBAGENT池中挑人调用 SPAWNMEMBER CREATETEAM 不需要人工编排,也不用改代码 动态组建TEAM LEAD WORKER WORKER WORKER MAIN AGENT PERF-TESTER REVIEWER SECURITY-SCANNER 成员之间可直接互发消息,共享同一个 TASK BOARD(认领/完成/通知) (框架内抽象接口) TEAMCLIENT LOCALTEAMCLIENT(BASESTORE,闭环) / CONTROLPLANETEAMCLIENT (HTTP,托管) AGENTSCOPE SERVICE  `动态组队(DYNAMIC  TEAM) 流程图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987685062-a679fc73-257a-4f74-8ff8-d214a82b7c88.png)



上图几个关键点：

+ **预先定义的是"可用成员池"，不是"团队"**。开发阶段只是把 `reviewer`、`security-scanner`、`perf-tester` 这些 Subagent（跟 Subagent 模式共用同一套 `agentRef` 注册机制）挂到 Main Agent 上，谁跟谁组队、什么时候组队，这一步完全没有决定，也不需要提前设计好 Lead / Worker 结构。
+ **组队的触发点是一条运行期消息，不是代码或控制台配置**。Human 发给 Main Agent 的一条普通消息里，只要带着"组个团队来处理"这类意图，Main Agent 的推理过程就会决定调用 `createTeam`（需要时再用 `spawnMember` 追加成员），把自己设为 Lead、把挑中的 Subagent 实例化成 Worker——这个决策发生在一次 LLM 推理里，既不用人工预先编排，也不用改一行代码。
+ **组队之后走的是同一套 Team 协作机制**：Lead 与各 Worker 共享同一个 `TeamClient`（Task Board + Mailbox），可以是不依赖 Control Plane 的 `LocalTeamClient`（闭环，直接基于 `BaseStore` 做乐观并发），也可以接入 `ControlPlaneTeamClient` 换取跨副本协调与 Dashboard 可观测性——这一点和上一张控制台动态编排图完全一致，区别只在"团队怎么形成"。

这套能力和前面提到的 [Subagents](../docs/harness/subagent.md) 模式用的是同一批 Subagent 定义，但协作方式完全不同，容易混淆，值得专门对比一下：

```latex
Subagent 模式（单向委派，互相隔离）
    Main Agent ──task()──▶ Subagent A ──result──▶ Main Agent
    Main Agent ──task()──▶ Subagent B ──result──▶ Main Agent
    Subagent A 与 Subagent B 之间没有通信路径，也不共享任务列表

Agent Team 模式（对等协作，共享状态）
    Lead ──createTask / assignTask──▶ Worker A
    Worker A ◀── sendMessage / broadcastMessage ──▶ Worker B
    Worker A、Worker B 都能在共享 Task Board 上 claim 未分配的任务
```

+ **Subagent：单向委派，互相隔离**。Main Agent 通过 Task 工具把一段指令发给某个 Subagent，Subagent 在独立、无状态的上下文里执行，把结果原样报回 Main Agent；两个 Subagent 之间没有任何直接通信路径，也不知道对方存在，更不会共享任务列表——协作的全部逻辑都由 Main Agent 一个人持有。
+ **Agent Team：对等协作，共享状态**。Team 里的 Lead 和 Worker 共用同一个 Task Board 和 Mailbox：Lead 可以 `assignTask` 指定某个 Worker 做什么，Worker 之间也能通过 `sendMessage` / `broadcastMessage` 直接对话，未分配的任务谁空闲谁 `claimTask`——协作状态不再只由发起方一个人掌握，而是团队成员共同维护的一份共享数据。

一句话总结：Subagent 是"发指令、等结果"的层级委派；Agent Team 是"共享看板、互相认领、直接沟通"的对等协作。而这张图想说明的是，AgentScope 原生 Agent Teams 能让"从 Subagent 池组建一个对等协作的 Team"这件事，完全由 Main Agent 在运行时按需触发，不需要提前规划好团队结构——这与上一张控制台动态编排图（由 Human 在控制台上现场选人组队）是两种互补的路径，二者共享同一个 `TeamTool` / `TeamClient` 编程模型，可以按需选用。

#### 多智能体协作 Remote Subagent
Agent Teams 或 AgentScope Subagent 委派的目标，不一定运行在同一个进程里——可能是同一 `HarnessAgent` 内的本地 Subagent，也可能是另一个 Managed Agent（跑在 Dataplane），甚至是一个通过 `instrument()` 接入的 LangChain Agent。

对于远端AgentScope Service Control Plane 在其中扮演的角色，是让 Agent A 发起一次 `delegate` 调用时，不需要关心目标到底在哪、是什么框架：



<!-- 这是一张图片，ocr 内容为：TARGET AGENT(TECHLEAD) AGENTSCOPE SERVICE AGENT A(CTO) MANAGED AGENT / LANGCHAIN CONTROL PLANE(AISTIOD) AGENTSCOPO FRAMEWORK AGENT AGENT TEAMS/SUBAGENT 委派 DELEGATE("TECHLEAD", "REVIEW THIS PR") IS TECHLEAD A LOCAL, IN-PROCESS SUBAGENT? [ TECHLEAD 是本地 IN-PROCESS SUBAGENT ] ALT YES- SKIP CONTROL PLANE (AGENT 直接本地调用) [NO-REMOTE / CROSS-FRAMEWORK TARGET] POST /API/V1/AGENT-CHAT/TECHLEAD 1.CHECK ACL / TEAM MEMBERSHIP 2. LOOK UP TARGET INSTANCE (MANAGED  AGENT 或 LANGCHAIN REGISTERED VIA INSTRUMENT) 3.PROXY TO TARGET'S CHAT URL POST /ASK 或/SESSIONS/{ID}/EVENTS TARGET RESPONSE RELAY RESPONSE AGENTSCOPE SERVICE  AGENT-TO-AGENT 委派与控制面代理 时序图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987846445-098ee5ce-1d73-4343-a3b2-09cc17ebf963.png)



上图体现了使用控制面实现 remote subagent 调用的流量代理能力，而不是一次特定的 API 设计：

+ **本地优先，能不经过控制面就不经过**。如果 `techlead` 恰好是 Agent A 同一个 `HarnessAgent` 进程内声明的本地 Subagent，委派直接走进程内调用，控制面完全不参与——这是延迟最低、也是最常见的路径。
+ **跨实例 / 跨框架时，控制面负责"发现 + 鉴权 + 代理"三件事**：先确认 Agent A 与 `techlead` 之间存在合法的协作关系（同一 Team、白名单 ACL），再按 Agent ID 在舰队注册表里查到目标实例——这里的目标可能是一个 Managed Agent（转发到 Dataplane 的 Session Turn 接口），也可能是一个通过 `aistio.instrument()` 注册上来的 LangChain Agent（转发到其上报的 chat 端点）——最后把请求代理转发过去，并把响应原样透传回 Agent A。
+ **Agent A 全程不知道对方是什么框架**。对发起方而言，`delegate("techlead", ...)` 的调用方式不因为目标是本地 Subagent、Managed Agent 还是 LangChain Agent 而改变；框架差异被控制面的路由层吸收掉了。

这也是为什么前面在“如何接入”里强调 AgentScope、LangChain、Claude 可以用不同方式接入同一个控制面：一旦接入完成，它们就都具备了被其他 Agent 找到、委派任务、并拿到结果的能力，而不需要每一对框架之间单独打通。

### 生产部署架构
<!-- 这是一张图片，ocr 内容为：AGENT SERVICE WEB CONSOLE: DASHBOARD - MANAGED AGENTS - AGENT TEAMS BROWSER / SDK / CLI 认证与公共API路由 :8080 SERVICE-GATEWAY 统一入口鉴权反向代理 AISTIOD :8081  SERVICE-DATAPLANE :8082 产品与运行时控制面 AGENTSCOPE BRAIN TURN ` EVENT . SSE ` HITL AGENT 注册.AGENT TEAMS SERVICE-SCHEDULER :8083 POSTGRESQL CHANNEL`CRON`HANDS WORKER CP RT DP SCHEMAS RUNTIME MANAGED AGENTS RUNTIME - SELF HOSTED FRAMEWORK RUNTIME ' SANDBOX -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987384213-cab424c2-502c-43b3-ac78-ee0e43eb9c9c.png)

四类平面的职责可以这样理解：

| **平面** | **负责** | **不负责** |
| --- | --- | --- |
| Gateway | 公共入口、认证与 API 路由 | 业务状态与 Agent 执行 |
| Control Plane（`aistiod`） | 产品资源、控制台、Agent状态、Session、Team 与运行时命令 | Harness 推理、Session 流传输 |
| Dataplane | Managed Harness Runtime、事件日志、SSE、Turn Lease、HITL 与 Work Queue | 直读产品 Catalog 表 |
| Scheduler | Channel、Cron、出站任务与 Self-hosted Hands Worker | 推理循环 |


产品上还有一个关键拆分：**Brain** 与 **Hands**。

+ **Brain**：管理上下文、推理、工具决策和事件日志；由平台托管的 AgentScope Harness 承担。
+ **Hands**：决定工具在哪里执行。可选 `local`、`sandbox`（如 E2B）、`remote`、以及客户侧出站 Worker 的 `self_hosted`。

这意味着企业可以分别回答三个问题：模型能看到哪些上下文？工具能访问哪些网络和文件？工具结果中哪些内容可以回传 Brain？信任边界被拆开后，权限审核与故障定位都会清晰得多。

这也解释了为什么「托管」不等于「所有数据都必须离开客户环境」。对脱敏可在云侧推理的场景，可用托管沙箱；对必须触达内网系统或敏感文件系统的场景，可把 Hands 放在客户 VPC，由出站 Worker 执行工具并回传结果。Brain 仍然负责编排与状态恢复，只是执行面被替换了。

对希望进一步理解 Turn 路径、事件契约与 schema 边界的读者，我们另有一篇技术向文章：[AgentScope Service 技术解读](./agentscope-service-release-tech.md)。

## Agent 如何接入
AgentScope Service 同时服务两类用户：

1. 平台服务型团队，提供 SaaS 化平台方便快速构建托管智能体——用 Console / API 创建 Managed Agent。
2. 业务研发团队，已有不同技术方案开发的 Agent 应用、希望纳入统一治理——通过扩展 / SDK / Sidecar 接入控制面。

两条路径可以并存，很多团队会先用 Managed Agents 跑通新产品，再把存量自研 Agent （BYO）逐步纳入 Dashboard。

下来我们分析就分析第 2 种模式，自己开发部署的 Agent 如何接入 AgentScope Service 控制面，总的来说有 SDK、Sidecar 两种接入方式，对于 Agent Framework 类应用，可以通过引入 SDK 实现到控制面的注册，可以通过。

### Agent Framework
#### AgentScope
AgentScope Java 目前原生支持 Agent 应用接入，通过引入 `agentscope-extensions-aistio` 依赖，即可自动将现有 AgentScope Runtime 注册到控制面，与 Managed Agent 一同出现在 Dashboard。会话状态、健康信息与运行时观测沿同一套契约上报。

同时 AgentScope 分布式部署需要的 Agent Teams 跨副本的消息投递与子任务委派、跨节点异步任务状态跟踪、Session 并发控制、Workspace 状态同步等，都可以由控制面提供原生支持。

#### LangChain
目前我们在社区提供了 python sdk，用户可以通过 `aistio.instrument()` wrapper 实现接入。对 LangChain / LangGraph 应用，控制面侧以旁路方式采集 Session 快照、上下文与运行时指标；主业务路径先成功，上报失败不影响推理本身。

这样一来，LangChain 开发的 Agent 也能进入 AgentScope Service 的舰队管理与 Session 观测，而不必重写业务链路。



更多框架如 Claude Agent SDK、Google ADK 等将陆续提供支持，具体请查看 roadmap。

### Coding Agent
对于 Claude Code、QoderCli 等难以直接改二进制的 Coding Agent，则可借助 **Sidecar** 桥接：旁路观察本地 Session 目录与运行状态，上报到控制面，并承接压缩、终止等运营命令。

这条路径的意义在于：企业不必在「用最强 Coding Agent」和「纳入统一治理」之间二选一。研发提效工具可以继续跑在开发者环境，平台仍能看见它、管理它、在必要时干预它。



QwenPaw 等个人工作区助手理论上也可以通过 sidecar 方式实现接入，具体请查看 roadmap。

## 本地快速体验
AgentScope Service 处于快速迭代阶段，如果你想先完整体验产品面，可以下载仓库源码启动，在本地环境快速体验。

1. 启动控制面、Managed Agents 数据面等所有组件（如上文中的生产部署架构图）：

```shell
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java
```

```bash
export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

2. 打开 [http://localhost:8080](http://localhost:8080)，输入用户名/密码（`admin` / `admin`）

接下来就可以直接体验 Managed Agents 快速创建智能体了：

    1. 在 **Managed Agents** 创建 Agent；
    2. 创建一个 `local` Environment；
    3. 打开 **Sessions**，绑定 Agent 与 Environment，发送第一条消息；
    4. 回到 **Dashboard** 查看在线状态、事件与运行时信息；
    5. 如需协作，再进入 **Agent Teams** 创建团队并观察任务与成员状态。
3. 如果要体验 BYO Agent 注册，可以使用源码仓库中的示例 agentscope-samples/agents/agentscope-paw，启动后即可在 dashboard 中看到智能体注册成功。

## Roadmap & 总结
AgentScope Service 把不同模式构建的 Agent（Framework、Coding Agent、Managed Agents）等收敛在统一控制平面内，为 Agent 间协作提供统一视图。无论你从 Console 新建第一个 Agent，把 Harness 运行托管给 AgentScope Service 平台，还是把现有 AgentScope / LangChain / Claude 应用接入控制面，目标都一样——**让企业拥有一站式的 Agent 管控与治理中心**。

接下来，AgentScope Service 会沿着「更开放的接入、更完整的自动化、更强的事件驱动」继续演进。近期重点包括：

1. **围绕 AgentScope Framework 原生能力持续迭代，提供**
2. **支持更多 Agent 框架与 Coding Agent 接入**
   补齐并深化 LangChain、ADK、Claude、Qoder、OpenAI Agents 等适配，降低 BYO 接入成本，让异构Agent 进入同一契约更容易。
3. **Automation**
   围绕 Deployment、Cron、Webhook、Channel 扩展自动触发与闭环执行，让 Agent 从人主动发起会话，走向事件驱动的任务处理模式。
4. **更多事件驱动集成**
   接入 GitHub / GitLab、钉钉、企微等研发与协作入口，把代码变更、工单、群消息直接变成 Agent Turn 或 Team Task。


如果您关注企业级能力，环境关注阿里云 [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview)、[Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html) 产品。
