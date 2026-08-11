---
hide-toc: true
---

**AgentScope Service** — an Agent control plane built on AgentScope Harness.

<!-- 这是一张图片，ocr 内容为：AISTIO LS FLEET OVERVIEW CONTROL PLANE CONSOLE CROSS-FRAMEWORK AGENT INSTANCES AND RUNTIME SESSIONS REPORTED INTO AISTIOD. 品 DASHBOARD TOKENS (24H, IDLE SESSIONS STALE AGENTS HEALTHY ERRORS (24H) ACTIVE OVERVIEW A) INSTANCES INSTANCES SESSIONS 0 1 R AGENTS 1 R 6,319 3 SESSIONS GOVERNANCE AGENTS COUNTS LIVE INSTANCES ONLY.3 HISTORICAL MANAGED AGENTS TOKEN USAGE(24H) 8 TEAMS HOURLY SUM OF USAGE DELTAS (NOT CUMULATIVE SNAPSHOTS) 14:00:6,319 TOKENS TOP 10 AGENTS BY TOKENS TOP 10 SESSIONS BY TOKENS RANKED BY TOKEN USAGE DELTAS - LAST 24H RANKED BY TOKEN USAGE DELTAS `LAST 24H #SESSION ACTIVE TOKENS ERRORS 井 AGENT PHASE TOKENS ADMIN MAIN-ED0098A8-E94E-42A9-9578- DEFAULT 1 6,319 6.319 B5FAD881F839 DEFAULT ACTIVE PROFILE USERS DEFAULT -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785989956180-2b6581fd-cf41-4155-baaf-08db90a6eb5d.png)


+ **AgentScope Service is a control plane.** It provides agent registration, discovery, and distributed coordination services for every agent in the enterprise. It works with mainstream agent runtimes including AgentScope, LangChain, ADK, and Claude / Qoder, giving you a single place to inspect agent metrics and operate on live sessions — for example, compressing session context.
+ **AgentScope Service provides low-code Agent creation and deployment.** Built on the AgentScope Harness runtime, it lets you run multiple Agents on one Managed Agents platform under unified operations. The platform hosts Harness capabilities, while tool execution can be delegated to a sandbox that you control.
+ **Agents registered with AgentScope Service can be assembled into one or more Teams.** Whether the Agent is a self-hosted AgentScope runtime or a low-code Managed Agent Harness runtime, Agents can be orchestrated together to tackle more complex work.

## What Is AgentScope Service

AgentScope Service is not meant to replace your existing Agent frameworks. It adds a unified control plane so you can govern Agents built with different frameworks and stacks — Claude, OpenClaw, QwenPaw, and more — in one place.

Enterprises already have many ways to build Agents. AgentScope, as a capable Agent Framework, offers an end-to-end path for building agents. But **traditional Agent Frameworks are no longer the only way to build agents**. Coding Agent products are expanding into more domains, and building enterprise Agents with the Claude SDK, Qoder CLI, and similar tools is becoming a common choice.

1. **Use an Agent Framework**
   Run the agent loop directly inside business services with AgentScope, LangChain, ADK, and similar frameworks. Flexibility is high, but tenant isolation, version rollout, Session recovery, HITL, event persistence, and cross-replica coordination all have to be built by each team. When every business line reinvents those pieces, standards diverge.
2. **Use a Coding Agent or personal workspace assistant**
   Examples include Claude Code, other Coding Agents, and personal workspace assistants. They start quickly and feel great locally, but state lives on the developer machine — hard to share, hard to audit, and a poor fit for multi-team ownership. When the laptop closes, work often stops with it.
3. **Use a low-code or Managed Agents platform**
   Traditional low-code platforms assemble Agents from visual nodes. They are easy to start with, but often expose memory management, context compaction, tool wiring, and similar concerns as dense configuration surfaces, leaving users responsible for both quality and stability. Managed Agents emphasize fully hosted cloud Harness capabilities so users no longer carry that operational burden, while giving users more control over tool execution — including Hands that can run inside the customer VPC — but enterprise multi-agent collaboration was still incomplete.

These paths are not mutually exclusive. Inside one company, R&D may use Coding Agents, a business platform may run AgentScope, and a new project may want hosted Harness from day one — that mix is common. AgentScope Service does not lock you into any single agent framework or platform; it provides unified control-plane capabilities across agent runtimes.

### Control Plane

The Control Plane is the core of AgentScope Service. Every Agent application registers through it. Via SDK or Sidecar, it supports mainstream Agent Frameworks (AgentScope, LangChain, ADK) as well as Claude, Qoder, and similar runtimes.



The Dashboard is the Control Plane's visual console. It gives the whole fleet a live view of online agents, deployment instances, active sessions, token usage, and other global signals so operators can see how the cluster is doing.



<!-- 这是一张图片，ocr 内容为：AISTIO AS FLEET OVERVIEW CONTROL PLANE CONSOLE CROSS-FRAMEWORK AGENT INSTANCES AND RUNTIME SESSIONS REPORTED INTO AISTIOD. 品 DASHBOARD TOKENS (24H. HEALTHY IDLE SESSIONS STALE ERRORS (24H) AGENTS ACTIVE OVERVIEW 4) INSTANCES INSTANCES SESSIONS 1 R R AGENTS O 1 6,319 3 SESSIONS GOVERNANCE AGENTS COUNTS LIVE INSTANCES ONLY.3 HISTORICAL MANAGED AGENTS TOKEN USAGE(24H) 8 TEAMS HOURLY SUM OF USAGE DELTAS (NOT CUMULATIVE SNAPSHOTS) 14:00:6,319 TOKENS TOP 10 AGENTS BY TOKENS TOP 10 SESSIONS BY TOKENS RANKED BY TOKEN USAGE DELTAS `LAST 24H RANKED BY TOKEN USAGE DELTAS `LAST 24H SESSION TOKENS ACTIVE ERRORS # PHASE AGENT TOKENS ADMIN MAIN-ED0098A8-E94E-42A9-9578 DEFAULT 1 6,319 6,319 B5FAD881F839 DEFAULT ACTIVE PROFILE USERS DEFAULT -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785934371848-7b1b934e-11ed-4625-97cc-820f2fe5d214.png)



From the Dashboard you can also inspect session details, view the live context state of an active session (including how different parts of the context contribute), dynamically adjust or compress session context, and intervene in a running conversation.

<!-- 这是一张图片，ocr 内容为：AISTIO S SESSIONS CONTROL PLANE CONSOLE 7818AE8D-D486-4B41-BD2A ABORT TUM RESTORE EXIT PLAN ENTER PLAN COMPRESS TERMINATE CF8B7EB67224 品 DASHBOARD AGENTSCOPE-PAW - DEFAULT - AGENTSCOPE-JAVA - TURN #7 OVERVIEW AGENTS LIFETIME USAGE PHASE LAST ACTIVE INSTANCE MODEL PRESSURE SESSIONS 31% 46,777 2026/7/30 HEALTHY 22:55:01 GOVERNANCE ZPROMPT+COMPLETION U-FF406114-1819... HTTP://LOCALHOS MANAGED AGENTS WINDOW-IN 45,260/ OUT 1.517 TEAMS CONTEXT VIEW COMPACTED. 6 CFFECTIVE MSGS -7 TOOLS - WINDOW 125/ 32,768 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785946414310-ff29cee8-2b2b-40df-9ec8-0211ee03fe8c.png)

### Managed Agents

Managed Agents evolve from the `agentscope-builder` platform. They remain a low-code Agent platform that gives developers SaaS-style Agent definition and hosted execution. The upgrade further emphasizes the split between reasoning and tool execution: Harness capabilities are hosted more thoroughly, while tool execution stays under greater user control.



<!-- 这是一张图片，ocr 内容为：AISTIO AGENTS NEW AGENT CONTROL PLANE CONSOLE LOW-CODE MANAGED AGENTS. EACH AGENT IS SHAPED BY ITS WORKSPACE - AGENTS.MD, TOOLS, SKILLS AND SUBAGENTS. 品 DASHBOARD CLONE-ONLY O ALL 4 SHARED WITH ME O MINE 4 GLOBAL MANAGED AGENTS AGENTS SESSIONS BBB PPP OWNER OWNER OWNER CCC 调用专用AGENT 擅长做微服务相关搜索 WORKSPACES BBB TEST AG_4ECD3838B3CD AG_1426C299ADF8 AG_5AF01156E61F ENVIRONMENTS WORKSPACE LINKED WORKSPACE LINKED WORKSPACE LINKED MEMORY VAULTS DEPLOYMENTS OWNER AAA CHANNELS XXXXX AG_CECC0395E056 8 TEAMS WORKSPACE LINKED ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948183107-014a5cb1-6fcf-4b04-93cb-f01341b35350.png)



Agent definition follows the core design of AgentScope Harness. You first define foundational concepts such as Workspace and Memory, then associate a workspace and memory with an agent to create it.



Define a Workspace:

<!-- 这是一张图片，ocr 内容为：AISTIO WORKSPACES CONTROLPLANE CONSOLE TEST MANAGE AGENTS.MD, SKILLS, TOOLS AND SUBAGENTS FOR LINKED AGENTS. 品 DASHBOARD V1 SKILLS O SUBAGENTS O . AGENTS.MD MANAGED AGENTS SUBAGENTS AGENTS.MD SKILLS TOOLS MARKETPLACE AGENTS SESSIONS BUILTIN TOOLSET WORKSPACES BASH FILESYSTEM EXECUTE A SHELL COMMAND ENVIRONMENTS READ FILESYSTEM MEMORY READ A FILE FROM THE WORKSPACE VAULTS WRITE FILESYSTEM WRITE A FILE IN THE WORKSPACE DEPLOYMENTS EDIT FILESYSTEM EDIT A FILE VIA STRING REPLACEMENT CHANNELS GLOB FILESYSTEM 8 TEAMS FIND FILES BY GLOB PATTERN GREP FILESYSTEM SEARCH FILE CONTENTS WITH REGEX WEB_FETCH WEB FETCH CONTENT FROM A URL WEB SEARCH WEB SEARCH THE WEB FOR INFORMATION MEMORY_SAVE HARNESS SAVE A LONG-TERM MEMORY FACT MEMORY_GET HARNESS GET A MEMORY ENTRY -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948616059-4d7be456-50bf-4e68-9ebf-3d48ce0ca9d3.png)



Define an Agent:

<!-- 这是一张图片，ocr 内容为：AISTIO SY NEW AGENT CONTROL PLANE CONSOLE DASHBOARD PREFER LINKING A WORKSPAGE SO AGENTS.ND / SKILLS/  TOOLS / SUBAGENTS ARE AUTHORED ONCE AND INTO THIS AGENT.OR LEAVE WORKSPACE EMPTY FOR AN AGENT-PRIVATE DEFINITION. MANAGED AGENTS NAME AGENTS ASSISTANT SESSIONS WORKSPACES DESCRIPTION ENVIRONMENTS DEMO ASSISTANT AGENT MEMORY WORKSPACE VAULTS TEST DEPLOYMENTS LINK A WORKSPACE TO INHERIT AGENTS.ND; SKILLS, TOOLS AND SUBAGENTS,MANAGE WORKSPACES FROM THE WORKSPA CHANNELS WILLINHERIT FROM TEST(V1):AGENTS.ND - SKILS O- SUBAGENTS O,LEAVE SYSTEM PROMPT BLANK TO USE WORKSPACE TEAMS AGENTS.MD. DEFAULT ENVIRONMENT(OPTIONAL) NONE-CHAT WILL ENSURE A LOCAL DEFAULT USED WHEN OPENING CHAT / CHANNEL SESSIONS.VAULTS AND MEMORY STORES CAN BE ATTACHED LATERIN SETTINGS WORKSPACE PATH(OPTIONAL OVERRIDE) LEAVE BLANK FOR DEFAULT UNDER AISTIOD WORKSPACE ROOT LEAVE BLANK TO USE THE CONTROL-PLANE DEFAULT PATH.ABSOLUTE PATHS ARE USED AS-IS. ADMIN SYSTEM PROMPT PROFILE USERS YOU ARE A HELPFUL ASSISTANT. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948697346-865380da-fb2c-420b-9968-4275b51a85b6.png)



The biggest change in this upgrade is the hosted runtime logic and architecture — Managed Agents. The platform cleanly separates static definitions (Agent, Workspace) from dynamic runtime (Environment, Session), and uses Environment and Session to orchestrate how the Agent actually runs.



Create a session and bind a self-hosted sandbox runtime environment:

<!-- 这是一张图片，ocr 内容为：AISTIO AS SESSIONS CONTROLPLANE CONSOLE NEW SESSION CREATES A SESSION RESOURCE BOUND TO AN AGENT AND MOUNTS.NO TURN STARTS UNTILYOU SEND A DASHBOARD MESSAGE IN CHAT. MANAGED AGENTS AGENT ASSISTANT AGENTS SESSIONS NEW SESSION WORKSPACES / CREATE A SESSION DEFINITION ONLY - NO TURN STARTS UNTIL THE FIRST MESSAGE. CHOOSE ENVIRONMENT, I VAULTS, AND MEMORY STORES. AGENT SESSION DEFAULTS PREFILL THE FORM. ENVIRONMENTS RESET TO AGENT DEFAULTS MEMORY ENVIRONMENT VAULTS SELF-HOSTED-FC-SANDBOX(SELF_HOSTED) DEPLOYMENTS VAULTS CHANNELS NO VAULTS.CREATE ONE UNDER BUILD > VAULTS. TEAMS MEMORY STORES BBB AAA OPTIONAL OVERRIDES CREATE SESSION CANCEL -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948796759-2723bb0e-e25e-49a6-aad4-27f8eb368d8d.png)



Creating a Session does not by itself start an SSE event stream. The conversation and full inference path begin only when the user sends a message. As shown below, you can send a user message from the console chat page:

<!-- 这是一张图片，ocr 内容为：AISTIO CHAT SESSION DETAILS SESS_AD018033431F SESSIONS SY CONTROL PLANE CONSOLE ENV:SELF-HOSTED-FC-SANDBOX . VAULTS:0 MEMORY:1 ALL SESSIONS MANAGED SESSION DETAILS SESS_AD018033431F DASHBOARD NEW SESSION MANAGED AGENTS USER你好 AGENTS SESSIONS ASSISTANT 你好!有什么可以帮助你的吗? WORKSPACES ENVIRONMENTS MEMORY VAULTS DEPLOYMENTS CHANNELS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948840673-56827ddd-93f4-4091-9b7c-bbafa217a511.png)



Create Agent → create Environment → create Session → send the first message → watch the event stream in the Dashboard. Creating a Session alone does not start the Agent. For long-running work, Managed Agents especially emphasize **recoverability**: events are persisted, state can be rebuilt, and HITL can pause and resume. A front-end refresh or a service replica change should not mean starting over.

Runtime design is closely aligned with Claude Managed Agents. Harness infrastructure and runtime are fully hosted (backed by AgentScope Harness Runtime). The Brain/Hands split gives users more control over where tools actually run. Deployment separates into a Control Plane and a managed Dataplane — see the production deployment section below.

### Agent Teams

Every agent registered with the AgentScope Service Control Plane — whether self-deployed and registered through a framework (LangChain, AgentScope, ADK, Claude SDK, and so on), or created as a Managed Agent through the low-code path — can be orchestrated into one or more Agent Teams to collaborate on complex work.

<!-- 这是一张图片，ocr 内容为：AISTIO IS TEAM1 BACK COMPLETE TEAM FORCE DELETE LEAD CLOSE CONTROL PLANE CONSOLE CCC SOSS_025CA811A27B 帮我分析E2B沙箱和DAYTONA沙箱 SESS_E25CA811A27B FULL PAGE TEAM CHAT DASHBOARD IDLE NS-DEFAULT TASKS 1/2COMPLETE 1 IN PROGRESS 0 PENDING TASK-1.I ALSO LET THEM KNOW THAT THEY CAN REACH OUT IF THEY NEED ANY MANAGED AGENTS SPECIFIC RESOURCES OR HAVE ANY TOPOLOGY AGENTS QUESTIONS. SESSIONS WORKER1 LEAD IS THERE ANYTHING ELSE YOU WOULD LIKE TO ADDRESS AT THIS MOMENT? WORKS PACES ENVIRONMENTS OPEN CHAT CHAT OPEN [TEAM:TEAM1 FROM WORKER1]I HAVE MEMORY CLAIMED TASK-1 AND WILL START THE VAULTS ANALYSIS OF THE E2B SANDBOX.I WILL REACH OUT IF I NEED ANY SPECIFIC DEPLOYMENTS TASK BOARD MEMBERS MESSAGES RESOURCES OR HAVE ANY QUESTIONS. CHANNELS NEW TASK SUBJECT ADD TASK TEAMS TOOL:CLAIMTASK CA11_78816 UNASSIGNED(0) BLOCKED((() COMPLETED(1) IN PROGRESS(1) ASSIGNED(0) TOOL: CA11_5B2 TEAMS 分析E2B治理沙箱 分析DAYTONA 治理沙 箱 TEMPLATES TOOL:TEAM COMPLETE UNCLAIM SEND MESSAGE AG_4ECD3838B3CD... FAILED(0) ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948895276-d0221173-9683-4a94-b281-55f9372cee65.png)

In AgentScope Service, a Team is not a chat room. It is an operable collaboration unit: tasks can be claimed, plans can be approved, members can be woken, and state does not vanish just because a Session ends. A common pattern is a Lead that decomposes and accepts work, with Members claiming research, coding, verification, and other subtasks by capability. The platform owns message routing, the task board, and lifecycle — business code should not have to hand-roll temporary multi-process communication.

One point worth calling out: AgentScope Framework natively supports Agent Teams. That mechanism uses the AgentScope Service Control Plane for distributed task management and scheduling. So you can either use AgentScope Framework's native Teams capability during main-agent development to compose multi-agent collaboration, or dynamically assemble independent Agents in the console for a specific complex task. Which path you choose depends on the scenario.

## Architecture Overview

### Overall Architecture

<!-- 这是一张图片，ocr 内容为：HUMAN 用户USER REST API DASHBOARD 接入入口:日 SDK/CURL BROWSER .可视化控制台 第三方系统集成 AGENTSCOPE SERVICE 控制面.CONTROLPLANE CONTROLPLANE 框架接入FRAMEWORKS AGENTSCOPE CLAUDE QWENPAW LANGCHAIN SIDECAR  接入 原生接入 SIDECAR 接入 INSTRUMENT SDK AGENTSCOPE  SERVICE  系统架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785984168683-7e939049-046d-4ffa-b30d-1c0e6c0ff01b.png)

Humans reach the AgentScope Service Control Plane through two entry points: the Dashboard (browser) and the REST API (SDK / curl / third-party integration). Under the control plane, four Agent attachment models are managed together: native AgentScope attachment, LangChain via `instrument()`, and Claude / QwenPaw via Sidecar.

### Managed Agents

<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785976191807-3dde2cf8-ece0-4819-b376-328b498ed00c.png)



<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785976204028-86690651-b369-4c47-b3eb-73c9f7da508e.png)

### Agent Teams Collaboration Flow

Members of a Team do not need to come from the same framework or hosting model. In the console, you pick several Agents already registered with the control plane, choose who is Lead and who is Worker, and the orchestration is done — the Lead creates and assigns tasks, Workers claim and execute them, and collaboration state is maintained by the control plane:

<!-- 这是一张图片，ocr 内容为：HUMAN/CONSOLE 发起方HUMAN 创建 TEAM:选择已注册 AGENT 组成 LEAD +WORKERS AGENTSCOPE SERVICE CONTROL PLANE TEAM: RESEARCH 控制面CONTROL PLANE TASK BOARD (PENDING / CLAIMED / DONE) MAILBOX(单播 TO-MEMBER  广播TO 空) TEAM-JOIN (BYO 成员)/FIND-OR-CREATE SESSION (MANAGED 成员) 团队成员TEAMMEMBERS WORKER 3 WORKER 1 WORKER  2 LEAD (代码评审) (安全扫描) (法务合规) (CTO) LANGCHAIN/CLAUDE SIDECAR AGENTSCOPE 原生 MANAGED AGENT MANAGED AGENT SELF-CLAIM 创建任务.ASSIGN SELF-CLAIM CLAIM 未分配任务 未分配任务 已分配任务 自动认领 自动认领 共享 TEAM 状态 跨进程/跨 SESSION 持久化消息与任务 共享状态SHARED STATE AGENTSCOPE SERVICE - TEAM 协作架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785985724730-b8f8d88c-669a-430c-bbf6-0c9f2d64b0f7.png)

Key points in the figure:

+ **Members can be heterogeneous.** In the example, Lead and Worker 1 are Managed Agents (the control plane creates a Session bound with `teamContext` and kicks them off by delivering a `user.message`), Worker 2 is a self-deployed native AgentScope runtime, and Worker 3 is LangChain (or Claude attached via Sidecar). The control plane uses different join paths for different members (Managed Agents use `find-or-create session`; BYO agents use the `team_join` command), but the collaboration model exposed to the Lead is the same: every member can be assigned tasks and can send or receive messages inside the Team.
+ **Lead assignment and Worker claiming both exist.** When creating a task, the Lead can set an `owner` (assign to a specific Worker); that Worker then follows claim → start → complete. The Lead can also create a task with no `owner` and drop it on the Task Board so an idle Worker self-claims via `POST .../claim`. Both patterns coexist on the same Task Board, so the Lead does not have to watch every idle slot.
+ **Messages support unicast and broadcast.** The Mailbox supports directed messages with `to=member` (for example, the Lead nudging one Worker) and broadcast messages with an empty `to` (visible to every member). Both share the same persistence channel.
+ **Collaboration state is not tied to a single Session.** Task Board and Mailbox data are independent of any member's Session lifecycle: a Worker process restart or Session end does not erase whether a task still exists or whether messages remain traceable. That is why, when a Worker crashes, the control plane can mark the member `Lost` and trigger recovery instead of dropping the whole team's progress.

### AgentScope Native Framework + Control Plane

AgentScope Framework itself already provides a complete enterprise Agent stack — Harness, Agent Teams, multi-agent collaboration, Sandbox isolation, and more. In a real enterprise deployment, many of those capabilities depend on distributed coordination. The AgentScope Service Control Plane supplies that native distributed coordination for AgentScope.

#### Control-Plane Distributed Coordination

Once an AgentScope `HarnessAgent` moves from a single instance to multiple replicas, session state, workspace files, sandbox snapshots and concurrency locks, cross-replica messaging, async tools, subtasks, and Turn concurrency control can no longer assume that "process memory is the source of truth."

The figure below shows the **runtime topology**: how multiple `HarnessAgent` replicas interact with the Control Plane and an `AgentStateStore` backend — not the interface definition of `DistributedStore`.



<!-- 这是一张图片，ocr 内容为：控制面:CONTROLPLANE AGENTSCOPE SERVICE CONTROL PLANE (AISTIO) 面向HARNESS的托管能力 WORKSPACE 共享  AGENT TEAMS (消息/任务协作) SESSION 并发控制.异步工具执行 协调类API调用(无状态数据本身) 运行副本WORKERS HARNESSAGENT HARNESSAGENT HARNESSAGENT 副本2(JVM) 副本1(JVM) 副本N(JVM) 直连读写会话状态(不经过控制面) 状态后端 `STATE STORE AGENTSTATESTORE 后端 REDIS / MYSQL / POSTGRES / OSS 业务自备,多副本共享同一后端 AGENTSCOPE SERVICE `HARNESS 托管运行架构图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785985855412-a79588d2-9ae3-4922-a30b-337ff4e6e526.png)



Two independent paths that **never pass through each other** matter most:

+ **Upward: coordination API calls.** Each `HarnessAgent` replica calls the Control Plane through the SDK. What developers actually see are four hosted Harness capabilities. Underneath they map to interfaces such as `BaseStore`, `SandboxSnapshotSpec` / `SandboxExecutionGuard`, `MessageBus`, `TaskRepository`, `SessionTurnGate`, and `AsyncToolRegistry`. Coordination state lands in the control plane's own Postgres, so the business side does not need another infrastructure stack.
    - **Workspace sharing**: workspace files (`MEMORY.md`, `skills/`, `sessions/`, and so on) plus sandbox snapshots and concurrency locks, so any replica can read, write, and restore the same workspace;
    - **Agent Teams**: cross-replica message delivery and subtask delegation, so unicast, broadcast, and task claiming among Lead / Members do not depend on which replica a member happens to run on;
    - **Session concurrency control**: a Turn-level gate for the same Session across replicas, preventing two replicas from advancing the same conversation turn at once;
    - **Async tool execution**: long-running background tools whose status and results can be observed and collected by any replica.
+ **Downward: direct session-state backend access.** `AgentStateStore` (conversation context, compaction summaries, permission rules, Plan Mode state, and similar) **does not go through the control plane**. Each replica connects directly to a business-provided Redis / MySQL / Postgres / OSS backend. The control plane only optionally complements this with Session concurrency control (`SessionTurnGate`) and `AgentStateStore`'s own `getVersioned` / `saveIfVersion` optimistic concurrency (CAS), reducing duplicate LLM Turns across replicas — it coordinates *who may run this Turn*, not the state data itself.

For developers, that means configuring a `distributedStore` on `HarnessAgent.builder()` (coordination components via `ControlPlaneStores.fromEnv()`, with `AgentStateStore` pointed at a shared backend of your own) is enough to give the Agent real horizontal scale — without rebuilding session recovery, file sharing, sandbox snapshots, and task queues one by one.

#### Self-Assembling Agent Teams

AgentScope Framework also ships a closed-loop Agent Teams capability. How a team is formed differs from direct control-plane orchestration: at development time you do **not** need to pre-wire members into a fixed Team topology. You only need, as in the Subagent pattern, to pre-register a pool of callable Subagents (`agentRef`) on the Main Agent. At runtime, when a Human (or upstream system) sends the Main Agent a message that describes work requiring a team, the Main Agent itself decides whether to form a team, which pre-registered Subagents to use as Workers, and dynamically creates the Team:



<!-- 这是一张图片，ocr 内容为：开发阶段:为MAIN AGENT预先注册一组可调用的 SUBAGENTREF),不提前编排成固定 TEAM 预定义SUBAGENT池(只是候选成员名单,不是TEAM) REVIEWER - SECURITY-SCANNER " PERF-TESTER " 运行期:HUMAN发一条消息给MAIN AGENT,消息里带着团队任务描息给MAN发一条消息里带着团队任务团队任务团队任务团队任务团队任务团队任务团队任务团队任务团队任务描述 "帮我并行做一次代码评审+安全扫描+性能测试,组个团队来处理" (HARNESSAGENT)收到消息后自行判断需要组队 MAIN AGENT 从SUBAGENT池中挑人调用 SPAWNMEMBER CREATETEAM 不需要人工编排,也不用改代码 动态组建TEAM LEAD WORKER WORKER WORKER MAIN AGENT PERF-TESTER REVIEWER SECURITY-SCANNER 成员之间可直接互发消息,共享同一个 TASK BOARD(认领/完成/通知) (框架内抽象接口) TEAMCLIENT LOCALTEAMCLIENT(BASESTORE,闭环) / CONTROLPLANETEAMCLIENT (HTTP,托管) AGENTSCOPE SERVICE  `动态组队(DYNAMIC  TEAM) 流程图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987685062-a679fc73-257a-4f74-8ff8-d214a82b7c88.png)



Key points above:

+ **What you predefine is a candidate member pool, not a Team.** At development time you only attach Subagents such as `reviewer`, `security-scanner`, and `perf-tester` to the Main Agent (sharing the same `agentRef` registration mechanism as the Subagent pattern). Who teams with whom, and when, is left undecided — you do not design a Lead / Worker structure up front.
+ **The trigger is a runtime message, not code or console configuration.** When a Human sends the Main Agent an ordinary message that carries intent such as "form a team to handle this," the Main Agent's reasoning decides to call `createTeam` (and `spawnMember` when extra members are needed), sets itself as Lead, and instantiates the chosen Subagents as Workers. That decision happens inside one LLM turn — no human pre-orchestration, no code change.
+ **Once formed, the Team uses the same collaboration machinery.** Lead and Workers share one `TeamClient` (Task Board + Mailbox). That can be a Control-Plane-free `LocalTeamClient` (closed-loop, optimistic concurrency directly on `BaseStore`), or a `ControlPlaneTeamClient` that buys cross-replica coordination and Dashboard observability. This matches the console orchestration path above; the only difference is how the Team is formed.

This capability and the [Subagents](../docs/harness/subagent.md) pattern reuse the same Subagent definitions, but the collaboration model is completely different — and easy to confuse — so it is worth comparing them explicitly:

```text
Subagent mode (one-way delegation, peers isolated)
    Main Agent ──task()──▶ Subagent A ──result──▶ Main Agent
    Main Agent ──task()──▶ Subagent B ──result──▶ Main Agent
    Subagent A and Subagent B have no communication path and do not share a task list

Agent Team mode (peer collaboration, shared state)
    Lead ──createTask / assignTask──▶ Worker A
    Worker A ◀── sendMessage / broadcastMessage ──▶ Worker B
    Worker A and Worker B can both claim unassigned tasks on the shared Task Board
```

+ **Subagent: one-way delegation, peers isolated.** The Main Agent sends an instruction to a Subagent through a Task tool. The Subagent runs in an independent, stateless context and reports the result back to the Main Agent. Two Subagents have no direct communication path, do not know about each other, and never share a task list — the Main Agent holds all coordination logic alone.
+ **Agent Team: peer collaboration, shared state.** Lead and Workers in a Team share one Task Board and Mailbox. The Lead can `assignTask` to a specific Worker; Workers can talk to each other with `sendMessage` / `broadcastMessage`; unassigned work is `claimTask`'d by whoever is free. Collaboration state is no longer owned only by the initiator — it is shared data maintained by the whole team.

In short: Subagent is hierarchical "send instruction, wait for result" delegation; Agent Team is peer collaboration with a shared board, mutual claiming, and direct communication. What this figure highlights is that AgentScope's native Agent Teams let the Main Agent, at runtime and on demand, assemble a peer-collaborating Team from a Subagent pool — without planning the team structure ahead of time. That complements the console dynamic-orchestration path (a Human assembling teams live in the console). Both paths share the same `TeamTool` / `TeamClient` programming model and can be chosen as needed.

#### Multi-Agent Collaboration: Remote Subagent

The target of Agent Teams or AgentScope Subagent delegation does not have to live in the same process. It may be a local Subagent inside the same `HarnessAgent`, another Managed Agent (running on the Dataplane), or even a LangChain Agent attached through `instrument()`.

For remote cases, the AgentScope Service Control Plane's role is to let Agent A issue a `delegate` call without caring where the target lives or which framework it uses:



<!-- 这是一张图片，ocr 内容为：TARGET AGENT(TECHLEAD) AGENTSCOPE SERVICE AGENT A(CTO) MANAGED AGENT / LANGCHAIN CONTROL PLANE(AISTIOD) AGENTSCOPO FRAMEWORK AGENT AGENT TEAMS/SUBAGENT 委派 DELEGATE("TECHLEAD", "REVIEW THIS PR") IS TECHLEAD A LOCAL, IN-PROCESS SUBAGENT? [ TECHLEAD 是本地 IN-PROCESS SUBAGENT ] ALT YES- SKIP CONTROL PLANE (AGENT 直接本地调用) [NO-REMOTE / CROSS-FRAMEWORK TARGET] POST /API/V1/AGENT-CHAT/TECHLEAD 1.CHECK ACL / TEAM MEMBERSHIP 2. LOOK UP TARGET INSTANCE (MANAGED  AGENT 或 LANGCHAIN REGISTERED VIA INSTRUMENT) 3.PROXY TO TARGET'S CHAT URL POST /ASK 或/SESSIONS/{ID}/EVENTS TARGET RESPONSE RELAY RESPONSE AGENTSCOPE SERVICE  AGENT-TO-AGENT 委派与控制面代理 时序图 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987846445-098ee5ce-1d73-4343-a3b2-09cc17ebf963.png)



The figure above shows **traffic proxying** for remote Subagent calls through the control plane — not a one-off API design:

+ **Local first — skip the control plane when you can.** If `techlead` happens to be a local Subagent declared inside the same `HarnessAgent` process as Agent A, delegation is an in-process call and the control plane is not involved. That is the lowest-latency path, and usually the most common one.
+ **Across instances / frameworks, the control plane does three jobs: discovery, auth, and proxying.** It first confirms a legitimate collaboration relationship between Agent A and `techlead` (same Team, whitelist ACL), then looks up the target instance by Agent ID in the fleet registry — the target may be a Managed Agent (forwarded to the Dataplane Session Turn API) or a LangChain Agent registered via `aistio.instrument()` (forwarded to the chat endpoint it reported) — and finally proxies the request and streams the response back to Agent A unchanged.
+ **Agent A never needs to know the peer's framework.** For the caller, `delegate("techlead", ...)` does not change whether the target is a local Subagent, a Managed Agent, or a LangChain Agent. Framework differences are absorbed by the control plane's routing layer.

That is why the "how to attach" section stresses that AgentScope, LangChain, and Claude can attach to the same control plane in different ways: once attached, each becomes discoverable to other Agents, can receive delegated work, and can return results — without point-to-point integration between every pair of frameworks.

### Production Deployment Architecture

<!-- 这是一张图片，ocr 内容为：AGENT SERVICE WEB CONSOLE: DASHBOARD - MANAGED AGENTS - AGENT TEAMS BROWSER / SDK / CLI 认证与公共API路由 :8080 SERVICE-GATEWAY 统一入口鉴权反向代理 AISTIOD :8081  SERVICE-DATAPLANE :8082 产品与运行时控制面 AGENTSCOPE BRAIN TURN ` EVENT . SSE ` HITL AGENT 注册.AGENT TEAMS SERVICE-SCHEDULER :8083 POSTGRESQL CHANNEL`CRON`HANDS WORKER CP RT DP SCHEMAS RUNTIME MANAGED AGENTS RUNTIME - SELF HOSTED FRAMEWORK RUNTIME ' SANDBOX -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785987384213-cab424c2-502c-43b3-ac78-ee0e43eb9c9c.png)

The four planes can be understood as follows:

| **Plane** | **Owns** | **Does not own** |
| --- | --- | --- |
| Gateway | Public entry, authentication, and API routing | Business state and Agent execution |
| Control Plane (`aistiod`) | Product resources, console, Agent state, Sessions, Teams, and runtime commands | Harness inference and Session stream transport |
| Dataplane | Managed Harness Runtime, event log, SSE, Turn Lease, HITL, and Work Queue | Direct reads of product Catalog tables |
| Scheduler | Channel, Cron, outbound jobs, and Self-hosted Hands Workers | The inference loop |


There is another important product split: **Brain** and **Hands**.

+ **Brain**: manages context, reasoning, tool decisions, and the event log; provided by the platform-hosted AgentScope Harness.
+ **Hands**: decides where tools run. Options include `local`, `sandbox` (for example E2B), `remote`, and `self_hosted` via a customer-side outbound Worker.

That lets an enterprise answer three questions separately: which context can the model see? which networks and files can tools touch? which tool results may flow back to the Brain? Once the trust boundary is factored this way, permission review and incident isolation both become clearer.

It also explains why "managed" does not mean "all data must leave the customer environment." When desensitization on the cloud side is acceptable, use a hosted sandbox; when tools must reach internal systems or sensitive filesystems, put Hands in the customer VPC so an outbound Worker executes tools and returns results. The Brain still owns orchestration and state recovery — only the execution plane is swapped.

Readers who want a deeper look at Turn paths, event contracts, and schema boundaries can read our companion technical post: [AgentScope Service Technical Deep Dive](./agentscope-service-release-tech.md).

## How Agents Attach

AgentScope Service serves two kinds of users at once:

1. Platform / platform-services teams that want a SaaS path to build hosted agents quickly — create Managed Agents through the Console / API.
2. Business engineering teams that already have Agents built with different stacks and want them under unified governance — attach to the control plane through extensions / SDKs / Sidecar.

The two paths coexist. Many teams start with Managed Agents to ship a new product, then gradually bring existing self-built Agents (BYO) into the Dashboard.

Below we focus on the second path: how a self-developed, self-deployed Agent attaches to the AgentScope Service Control Plane. Broadly there are two attachment models — SDK and Sidecar. For Agent Framework applications, introducing an SDK is enough to register with the control plane.

### Agent Framework

#### AgentScope

AgentScope Java natively supports attaching Agent applications. By adding the `agentscope-extensions-aistio` dependency, an existing AgentScope Runtime can register itself with the control plane automatically and appear in the Dashboard alongside Managed Agents. Session state, health, and runtime signals report through the same contract.

Cross-replica message delivery and subtask delegation for Agent Teams, cross-node async task tracking, Session concurrency control, Workspace state sync, and other distributed deployment needs can all be provided natively by the control plane.

#### LangChain

The community currently ships a Python SDK. Users can attach through the `aistio.instrument()` wrapper. For LangChain / LangGraph apps, the control plane collects Session snapshots, context, and runtime metrics out of band; the main business path succeeds first, and reporting failures do not affect inference itself.

That way, Agents built with LangChain can still enter AgentScope Service fleet management and Session observability without rewriting the business path.



Support for more frameworks such as the Claude Agent SDK and Google ADK will land over time — see the roadmap.

### Coding Agent

For Coding Agents that are hard to modify as binaries — Claude Code, Qoder CLI, and similar — a **Sidecar** can bridge the gap: observe local Session directories and runtime state out of band, report them to the control plane, and accept operational commands such as compress or terminate.

The point of this path is that enterprises do not have to choose between "use the strongest Coding Agent" and "bring it under unified governance." Productivity tools can keep running in developer environments while the platform can still see them, manage them, and intervene when needed.



Personal workspace assistants such as QwenPaw can in principle attach via Sidecar as well — see the roadmap for details.

## Try It Locally

AgentScope Service is iterating quickly. If you want the full product surface first, clone the repository and start it in a local environment.

1. Start the control plane, Managed Agents dataplane, and other components (as in the production deployment diagram above):

```shell
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java
```

```bash
export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

2. Open [http://localhost:8080](http://localhost:8080) and sign in with username / password (`admin` / `admin`).

From there you can try Managed Agents and create an Agent quickly:

    1. Create an Agent under **Managed Agents**;
    2. Create a `local` Environment;
    3. Open **Sessions**, bind the Agent and Environment, and send the first message;
    4. Return to the **Dashboard** to inspect online status, events, and runtime info;
    5. For collaboration, open **Agent Teams**, create a team, and watch tasks and member state.
3. To try BYO Agent registration, use the sample at `agentscope-samples/agents/agentscope-paw` in the repository. After it starts, you should see the agent registered successfully in the Dashboard.

## Roadmap & Closing

AgentScope Service brings Agents built in different modes — Framework, Coding Agent, Managed Agents, and more — onto one control plane, and gives Agent-to-Agent collaboration a unified view. Whether you create your first Agent from the Console and host the Harness runtime on AgentScope Service, or attach existing AgentScope / LangChain / Claude applications to the control plane, the goal is the same: **give the enterprise a one-stop Agent control and governance center**.

Going forward, AgentScope Service will keep evolving toward more open attachment, fuller automation, and stronger event-driven integration. Near-term focus includes:

1. **Continue iterating on AgentScope Framework-native capabilities**
2. **Support more Agent frameworks and Coding Agents**
   Deepen adapters for LangChain, ADK, Claude, Qoder, OpenAI Agents, and more, lower BYO attachment cost, and make it easier for heterogeneous Agents to enter the same contract.
3. **Automation**
   Extend automatic triggers and closed-loop execution around Deployment, Cron, Webhook, and Channel, so Agents move from human-initiated sessions toward event-driven task handling.
4. **More event-driven integrations**
   Attach GitHub / GitLab, DingTalk, WeCom, and other engineering / collaboration entry points, turning code changes, tickets, and group messages directly into Agent Turns or Team Tasks.


If you care about enterprise-grade offerings on Alibaba Cloud, also see [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview) and [Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html).
