---
hide-toc: true
---

Managed Agents let agents run in a cloud environment: on the one hand, core stages such as inference, orchestration, and Harness management are uniformly hosted by the cloud, so architecture stability and runtime quality are guaranteed by the platform; on the other hand, long-running tasks no longer depend on the local device staying online—even if a personal computer is shut down, tasks can keep running in the cloud.

Based on the AgentScope 2.0 Harness kernel and Sandbox isolation capabilities, a complete enterprise-grade Managed Agents platform can be built quickly:

+ Harness Agents that have already been productionized in AgentScope 2.0 can be used directly as the Brain runtime. The hosting kernel provides stable inference and Harness capabilities, while the filesystem, workspace, and tool execution are fully isolated inside a Sandbox environment.
+ The platform layer handles tenants, permissions, versioning, events, and execution-plane selection. The Control Plane (Agent, Environment, Memory, Vault, Deployment) and the Data Plane (Session, Events, SSE) organize these capabilities into a multi-tenant, auditable, operable managed product.

> If you have seen our previously released open-source Agent Builder, you can think of Managed Agents as its productized upgrade: the underlying runtime and the main code paths remain the same; what changes are the resource model, API contract, execution-plane boundary, and multi-tenant governance.
>

## Managed Agents Background

The market already has similar Managed Agents products from Bailian, Claude Code, LangChain, and others. Essentially, I do not believe Managed Agents products are fundamentally different in form from previous low-code agent platforms. They all provide a hosted platform that contains “Agent definition & execution” capabilities. The difference in product expression is that, in the Harness era, Managed Agents emphasize the following two points:

1. **No longer make business developers assemble Harness.** Traditional platforms often split memory maintenance, context compaction, state recovery, tool permissions, and subtask cleanup into a large number of configuration items. Managed Agents absorbs these common engineering capabilities into a unified Harness, so developers mainly define business-related Skills, Tools, Subagents, and permission policies. The platform guarantees consistency and upgradability of mechanisms, but the final task quality still depends on the model, system prompt, Skill quality, tool return values, and business evaluation.
2. **Let customers control the boundary of tool execution and data return.** For enterprise users, the real value of an agent comes from connecting it to enterprise data assets, and shell, file I/O, MCP, and business tools are exactly the entry points for data flow. Therefore, the system deliberately splits **Brain (reasoning & orchestration)** and **Hands (tool execution)**: Brain is responsible for the next round of reasoning, state recovery, and context management; Hands is responsible for actually touching files, networks, and business systems. Hands can run in a platform-managed Cloud Sandbox, or in a Self-hosted Worker inside the customer VPC.

Point 1 really changes the abstraction level of the platform. Traditional low-code platforms often let users decide “when to summarize memory, how to truncate overly long context, how many times to retry tool exceptions, how to reclaim subtasks.” These options look flexible, but in practice they shift the engineering responsibility of Harness to business developers: the same Agent can perform and behave very differently depending on the experience of the configurer. Managed Agents only exposes business differences, such as role prompts, Skills, MCP, tool permissions, and Environment; things like compaction timing, session recovery, tool-result eviction, and long-term memory refresh are left to the continuously evolving Harness. After the platform upgrades Harness, all Agents gain the same engineering improvements without having to modify each flowchart one by one.

Point 2 changes the trust boundary. The model decides “what to call,” but that does not mean the process where the model lives must “execute it personally.” As long as tool calls are represented as stable schema, tool_use_id, and result events, Hands can be moved to a platform cloud sandbox or the customer VPC without changing the inference loop inside Brain. This lets the security team answer three separate questions: what context can the model see? what networks and files can tools access? and which contents in tool results can be returned to Brain? Once these three questions are separated, permission auditing and troubleshooting become much clearer than with “one whole Agent container.”

Taking Claude Managed Agents as an example, an important reason it has been accepted by developers is that Claude Code has already proven the product value of a mature Coding Agent Harness. What users see is model reasoning and task results; what the platform really hosts are **recoverable session state, engineered execution policies, and replaceable Hands**. AgentScope 2.0 adopts a similar layered design: `HarnessAgent` handles long tasks, context overflow, state recovery, and task delegation, and Managed Agents then adds multi-tenant resources, Environment, and a stable data-plane contract.

With Managed Agents, Anthropic has several progressively advanced solutions between individual and enterprise users:

+ **Claude Code CLI** targets individuals or single-machine development workflows, where the Agent is directly combined with the local workspace, terminal, and session records.
+ **Claude Agent SDK** exposes Session, event stream, and tool interaction as APIs, suitable for embedding in enterprise applications; identity, tenant, and resource isolation are still the responsibility of the integrator.
+ **Managed Agents** further turns Agent, Environment, Session, and the execution plane into managed resources, with the platform handling versioning, permissions, and runtime governance.

The differences among these three layers are not just “thicker and thicker packaging,” but the gradual upward shift of state ownership:

| Form | Where the main state lives | Who is responsible for isolation | Suitable for |
| --- | --- | --- | --- |
| CLI / single-machine app | Local directory and local session | OS user | Personal productivity |
| SDK / Harness | SessionStore / StateStore provided by the application | Application developer | Single enterprise application |
| Managed Agents | Platform Control Plane, shared state store, Session event logs | Platform manages by User / Agent / Environment | Multi-team, multi-tenant platform |


## Why AgentScope 2.0 Is a Good Foundation for Managed Agents

AgentScope 2.0’s model abstraction, tools and MCP, messages and events, state storage, remote filesystem / distributed BaseStore, and pluggable sandbox all reserve extension points for out-of-process persistence and multi-replica deployment. This means Managed Agents do not have to implement session recovery, tool-result persistence, and cross-request context continuation from scratch. Data-plane replicas must share the AgentStateStore and Workspace backend and correctly handle turn leases and node failover.

Among them, Workspace is the logical directory used by an Agent, while Filesystem and Sandbox are the physical backends that host it. The two are decoupled by AbstractFileSystem: the same set of file tools can point to a local directory, a distributed BaseStore, or an E2B sandbox. Because the logical workspace is separated from the physical execution plane, the Agent definition can switch isolation policies without changing the business prompt.

Specifically, HarnessAgent assembles the engineering defaults required for long-running execution on top of ReActAgent through Hooks, for example:

+ **Workspace-driven persona and knowledge**: `AGENTS.md` / `MEMORY.md` / `KNOWLEDGE.md` and similar files are injected into the system prompt;
+ **Session persistence**: agent state is restored by `sessionId`, so the conversation can continue after a process restart;
+ **Compaction and overflow handling**: Harness enables compaction and tool-result eviction by default, and allows the business to override thresholds or explicitly disable them;
+ **Skills / Subagents**: workspace skills, task delegation (task, etc.) are available out of the box;
+ **Unified filesystem abstraction**: local, remote KV, and cloud sandbox (E2B, etc.) all go through the same tool semantics, so Managed Agents can switch the execution plane by Environment type without changing the Agent business definition.

These capabilities are not independent nouns. A long task may first recover messages and agent state from AgentStateStore, then have AGENTS.md and installed Skills injected by workspace Hooks; during reasoning, if the context approaches the window limit, the compaction Hook will condense history, while large tool results can be evicted to the filesystem, leaving only retrievable references in the context; when parallel research is needed, the main Agent can hand tasks to Subagents. In the end, no matter whether the filesystem lands locally, in a remote KV, or in E2B, the tool semantics seen by the model remain consistent. This combined stability is the meaning of Harness as a platform kernel.

In addition, HarnessAgent and Session do not share the same lifecycle. The former is a runtime object reconstructed on a data-plane node that has shared `AgentStateStore` and a recoverable Workspace backend; the latter is a product resource with a stable ID, event sequence, and persistent state. Only by distinguishing these two can true horizontal scaling be achieved: when a node fails, Java objects can be discarded, but the conversation and long-term memory must be recovered from shared state; whether the workspace is continuous depends on BaseStore, sandbox snapshots, or customer-side persistence, which a Local directory does not guarantee.

Moving from a single enterprise agent application to Managed Agents, the key is not rewriting the inference kernel, but elevating runtime capabilities into stable platform resources. The phrase “add a layer of platform APIs” is not just adding a few Controllers; real productization also requires tenant ACL, Agent version snapshots, Session state machine, append-only events, turn leases, HITL tickets, Environment keys, Worker queues, shared coordination storage, and archive audit. Harness lets the platform avoid rewriting the agent loop, but these distributed responsibilities are still independent engineering systems.

Thus, the complete form of Managed Agents is: a SaaS Control Plane responsible for resource governance, AgentScope 2.0 providing the runtime kernel, and FC Sandbox / E2B or customer Workers carrying Hands under different trust boundaries.

## Enterprise Managed Agents Platform in Detail

### Overall Deployment Architecture

#### Core Component Diagram

1. **Control Plane**

<!-- 这是一张图片，ocr 内容为：CONTROL PLANE ARCHITECTURE CLIENTS CLI CURL/SDK CONSOLE API GATEWAY ROUTE BY APL SURFACE CONTROL APIS DATA APLS CONTROL PLANE DATA PLANE DATA PLANE APLS(COLLAPSED) DEFINITIONS AGENT/ENV SESSION CREATE SESSIONS .EVENTS. SSE +MEMORY/VAULT SKILLSMCP VERSIONED AGENT HARNESS LOOP - STATE RESTORE READ AGENT/ENV FROM CP ENVIRONMENT ACL/SHARES RESOURCES.TOOLS DATA PLANE APIS CONTROL PLANE API GATEWAY AGENT VERSIONS,ENVIRONMENTS,MEMORY/ ALSO PUBLIC(SESSIONS/EVENTS/SSE); FRONT DOOR FOR CLI/ CONSOLE / CURL;ROUTES VAULT/ACL,SESSION CREATE. COLLAPSED HERE-SEE DIAGRAM 2. CONTROL VS DATA APLS. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785141394899-a68e0d3b-e16b-44be-9a29-4e61f163463f.png)

2. **Data Plane**

<!-- 这是一张图片，ocr 内容为：DATA PLANE ARCHITECTURE HANDS-TOOL EXECUTION BOUNDARY BRAIN- REASONING & ORCHESTRATION CLOUD MANAGED SANDBOX SESSION/EVENTS API TYPESANDBOX .BRAIN INITIATES E2B/FC API USER.MESSAGE . INTERRUPT/HITL CLOUD SANDBOX E2BFILESYSTEMSPEC ISOLATED FS CREATE /TIMEOUT SESSIONTURNRUNNER TYPESANDBOX FS+SHELL CALLS WORKSPACE ROOT TURN LEASE `STATUS - BUILD/ CACHE BRAIN INITIATES AGENTSCOPE KERNEL MODEL HARNESSAGENT TOOL DECISIONS REACT/STREAMEVENTS SELF-HOSTED WORKER HOOKS.COMPACTION TEXT/THINKING TYPESELF_HOSTED OUTBOUND ONLY NO BRAIN INGRESS TYPE-SELF_HOSTED SCHEMAONLYTOOL WORK QUEUE OUTBOUND WORKER EVENTLOG AGENTSTATESTORE POLL/ACK/HEARTBEAT SUSPEND TURN RESTORE BY SESSION AGENT. PERSISTED AGENT.TOOL_USE CUSTOMER WORKER EXECUTE FS/SHELL POST USER.TOOL_RESULT.RESUME BRAIN CONTROL-PLANE REFS AGENT VERSION `ENVIRONMENT MEMORY / VAULT PATH CONTRAST SELF-HOSTED WORKER CLOUD MANAGED SANDBOX ENVIRONMENT TYPE-SELF HOSTED.TOOLS ARE SCHEMA-ONLY ON BRAIN;WORKER POLLS, ENVIRONMENT TYPE-SANDBOX.BRAIN CALLS E2B-COMPATIBLE APLS;PLATFORM OWNS SANDBOX LIFECYCLE.NO CUSTOMER WORKER. EXECUTES,POSTS USER.TOOL_RESULT TO RESUME. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785141716807-8d15ef5f-4d56-4c24-958a-caf553476262.png)

#### Core Data Flow

The client sends a task request to the Managed Data Plane (Brain) through the (session/event) interface. The Brain restores the Agent from shared state and then executes the entire reasoning and orchestration flow. If there are tool calls in the middle, the Brain routes the tool-call request to the Worker according to the Environment configuration (which may be a managed sandbox environment, a user-managed sandbox environment, etc.).

<!-- 这是一个文本绘图，源码为：flowchart LR
  C[Client / Console] -->|Session + Events + SSE| DP[Managed Data Plane]
  CP[Control Plane<br/>Agent / Environment / ACL] -->|versioned references| DP
  DP <--> DB[(JDBC<br/>events / state / leases)]
  DP --> B[HarnessAgent Brain]
  B --> M[Model]
  B -->|local tools| L[Brain host FS / shell]
  B -->|E2B-compatible API| S[Cloud Sandbox]
  B -->|tool schema + queue| Q[Self-hosted Work Queue]
  W[Customer Worker] -->|outbound poll / result| Q
  W --> H[Customer-managed FS / sandbox] -->
![](https://intranetproxy.alipay.com/skylark/lark/__mermaid_v3/cee93bfbfdd56bdf1526682edd6df433.svg)

Combining the architecture analysis and implementation above, the whole system can be read as four layers:

| Layer | Responsibility |
| --- | --- |
| **Control Plane** | Tenant resources, Agent versions, Environment, Memory/Vault, ACL |
| **Data Plane** | Session lifecycle, event persistence, SSE, turn leases |
| **Runtime Brain** | Parse definition, cache hit or rebuild → restore by RuntimeContext → `HarnessAgent.streamEvents` |
| **Hands** | Actually read/write files, shell, and externalize tools |


### Create an Agent and Run It

First, complete the minimal initialization: log in to Managed Agents, create a reusable Workspace Copilot Agent, and then demonstrate Local, Cloud Sandbox, and Self-hosted Worker modes. This makes it intuitive to see “the Agent definition stays the same, only the Hands location changes.”

The following assumes Managed Agents is running on `http://localhost:8080` and that a model key (such as `DASHSCOPE_API_KEY`) has been configured.

**0. Log in**

```bash
export BASE=http://localhost:8080
TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -er .token)
```

**1. Define a sample Agent**

We create a “workspace copilot” Agent: a short system prompt plus read_file, list_files, write_file, and similar tools, to demonstrate how different tools run under different worker modes.

```bash
AGENT=$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Workspace Copilot",
    "description": "blog demo agent",
    "system": "You are a workspace copilot. Prefer tools when listing or reading files. Keep answers concise.",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": {
        "enabled": true,
        "permissionPolicy": { "type": "always_allow" }
      },
      "configs": [
        { "name": "read_file", "enabled": true },
        { "name": "list_files", "enabled": true },
        { "name": "write_file", "enabled": true }
      ]
    }]
  }')
AGENT_ID=$(echo "$AGENT" | jq -er .id)
echo "AGENT_ID=$AGENT_ID"
```



Next is a demonstration of three worker modes. The same Agent, the same Brain reasoning and orchestration environment, runs on three different Hands paths:

| Mode | Where tools execute | Who initiates tool calls | Data boundary | Typical use |
| --- | --- | --- | --- | --- |
| Local | Host where Brain currently runs | Brain process | Inside managed cluster | Development and trusted environments |
| Cloud Sandbox | E2B / FC cloud sandbox | Brain invokes via E2B-compatible API | Platform-managed cloud sandbox | Managed isolated execution |
| Self-hosted | Customer Worker / customer sandbox | Customer Worker reads tool tasks and executes | Customer VPC | Private workspace and built-in shell / FS tools |


An existing Session does not support switching the worker execution environment halfway; to change the trust boundary, create a new Session, so the same event history does not cross different execution semantics.

#### Worker in Local Mode

Local mode is best for development and debugging. Session, Harness inference, model requests, and tool execution are all initiated by the Managed cluster, and files and shell directly land in the local environment visible to the Brain process.

<!-- 这是一个文本绘图，源码为：sequenceDiagram
  participant Client as Client
  participant API as Managed_API
  participant Brain as HarnessAgent_Brain
  participant Model as Model
  participant LocalFS as Local_FS_Shell

  Client->>API: POST sessions + user.message
  API->>Brain: turn lease + build HarnessAgent
  Brain->>Model: stream / tool decisions
  Model-->>Brain: tool_use / text
  Brain->>LocalFS: read_file / shell on host namespace
  LocalFS-->>Brain: tool_result
  Brain-->>API: agent.* + session.status_idle
  API-->>Client: SSE / events -->
![](https://intranetproxy.alipay.com/skylark/lark/__mermaid_v3/1216077e52ed0c4fd17a788f16bea26f.svg)

In **Local** mode, the Environment `type=local`: the filesystem and (if enabled) shell are completed inside the host namespace of the managed cluster, there is no independent Hands queue, and no cloud sandbox is called. Suitable for development debugging and trusted internal networks.

#### Worker in Cloud Sandbox Mode

Cloud Sandbox keeps the hosted Brain but moves files and shell into an isolated sandbox. Harness inference, model requests, and the initiator of tool calls still sit in the Managed cluster; actual command execution and file I/O happen in the FC Sandbox / E2B-compatible environment.

<!-- 这是一个文本绘图，源码为：sequenceDiagram
  participant Client as Client
  participant API as Managed_API
  participant Brain as HarnessAgent_Brain
  participant Model as Model
  participant E2B as FC_Sandbox_E2B

  Client->>API: user.message
  API->>Brain: HarnessAgent + type=sandbox
  Brain->>Model: reasoning
  Model-->>Brain: tool_use
  Note over Brain,E2B: Brain initiates sandbox lifecycle and tool calls
  Brain->>E2B: E2B-compatible API FS/shell
  E2B-->>Brain: tool_result
  Brain-->>Client: SSE agent.* / status_idle -->
![](https://intranetproxy.alipay.com/skylark/lark/__mermaid_v3/e286ba42310643e8e1a64e3db8b2417c.svg)

The Agent requests a container through the E2B client protocol and executes shell / FS operations inside it. **The Brain actively initiates the calls; the Worker is not involved.** If you use an Aliyun FC Sandbox compatible with the E2B protocol, you need to prepare the service address, template, and API Key first.

The managed boundary of Cloud Sandbox can be broken down into three actions: **create the sandbox, execute inside the sandbox, and reclaim/persist after the Session ends or times out.** Managed Agents maps file and shell tools to the same sandbox context through `E2bFilesystemSpec`; when `isolationScope=SESSION`, different Sessions do not share working directories by default. If you choose snapshot or TAR persistence modes, the recovery policy must also be considered together with `AgentStateStore`: restoring the model context without restoring files, or vice versa, creates inconsistency where “the Agent remembers it was done, but the workspace does not exist.” Production systems must design the two as a single recovery unit.

#### Worker in Self-hosted Mode

Self-hosted moves Hands further into the customer environment. The Brain still completes Harness inference in the Managed cluster, but tool tasks enter a queue and are actively polled outbound by the customer-side Worker, which manages the local workspace or sandbox and returns results to the Brain. Throughout the process, the Brain does not need to enter the customer network.

<!-- 这是一个文本绘图，源码为：sequenceDiagram
  participant Client as Client
  participant API as Managed_API
  participant Brain as HarnessAgent_Brain
  participant Model as Model
  participant Q as WorkQueue
  participant Worker as Customer_Worker

  Client->>API: user.message
  API->>Brain: type=self_hosted
  Brain->>Model: reasoning
  Model-->>Brain: tool_use
  Brain->>Q: enqueue work + persist agent.tool_use
  Brain-->>Client: requires_action / suspended
  Worker->>Q: poll with EnvKey
  Worker->>Worker: work directory + local tool exec
  Worker->>API: user.tool_result
  API->>Brain: resume turn
  Brain-->>Client: agent.message + status_idle -->
![](https://intranetproxy.alipay.com/skylark/lark/__mermaid_v3/e2b1fc8dcc2e2f7c0aef1980ee91a93c.svg)

In Self-hosted mode, the Brain **disables local shell/FS execution**, registering the relevant tools as externalized schema; once the model emits a `tool_use`, the event is persisted and the turn enters pending/queued, and the user-side Worker holds the Environment Key to **outbound** poll → manage the local workspace and execute, or connect to the customer’s own sandbox → return a `user.tool_result` to resume. This is exactly the opposite of Cloud Sandbox, where “the Brain actively calls the sandbox API”: **the execution initiative is on the user side; whether and how to manage the sandbox is also decided by the customer-side implementation.**

The target scenario for Self-hosted is to keep databases, code repositories, and release systems inside the customer boundary, but the current reference Worker supports built-in shell / FS tools out of the box. Databases, custom business tools, and intranet MCP still require future Worker extension SPI, or need to be wrapped by users outside the Worker. For tools already integrated with the Worker protocol, the Brain can see the schema, call arguments, and final returned results, but does not need to directly connect to the customer VPC; the Worker only needs to actively initiate HTTPS requests to the platform, and can desensitize results, apply size limits, and audit before returning them.

### A More Complex Agent Team Orchestration Example

#### Define Multiple Agents

The following uses an AgentDev scenario to show a three-role team. The input is a Java library release planning task:

+ Repo Surgeon gives checklists from the code-quality perspective and only has workspace read and search capabilities.
+ Ops Publisher generates a ticket draft from the release-process perspective; in this demo it only generates a text draft, while external MCP integration is explained separately as an optional configuration.
+ Team Lead summarizes risks and the acceptance checklist. Team Lead should avoid directly touching business data as much as possible and only be responsible for delegation and summarization.

Splitting into three Agents is not for stacking roles, but for constraining workspace permissions, external-system access, and summarization responsibilities respectively. The gain is least privilege and independent audit, rather than stuffing all tools into one super Agent and relying only on prompt constraints.

First create an Ops Publisher that can directly participate in fan-out. It only generates a release draft and does not call external systems, so it will not make `/api/multiagent/run` get stuck at the human-confirmation stage:

```bash
OPS_BODY=$(jq -n '{
  name: "Ops Publisher",
  system: "Draft changelogs and ticket outlines. Do not invoke tools or modify external systems.",
  tools: [{
    type: "agent_toolset",
    defaultConfig: {
      enabled: false,
      permissionPolicy: {type: "deny"}
    },
    configs: [{
      name: "read_file",
      enabled: true,
      permissionPolicy: {type: "always_allow"}
    }]
  }]
}')

# Release-planning Agent: only generates a text draft in this demo
OPS=$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$OPS_BODY")
OPS_ID=$(echo "$OPS" | jq -er .id)
```

If you want to integrate a ticket MCP in production, you can add the following snippet in the Agent body; `enableTools` exposes only explicitly allowed tools, and the URL and tool names must be replaced with real values:

```json
{
  "mcpServers": [{
    "name": "ticket-mcp",
    "url": "https://mcp.example.com/tickets",
    "transport": "http",
    "enableTools": ["draft_ticket"]
  }]
}
```

If you also want to publish a Skill, you can add `"skills": [{"type": "workspace", "name": "release-notes"}]` after confirming the Workspace has the corresponding content installed. Currently `mcp_toolset.defaultConfig.permissionPolicy` does not enter `ToolConfirmationMiddleware`, so high-risk MCP write operations still require identity, approval, and idempotency controls on the MCP gateway side, and cannot rely solely on `always_ask` in the Agent body. Then create the Repo Surgeon, which has read-only access to the code workspace:

```bash
# Operate user-side resources — for example a "code/repository" Agent: filesystem tools + skills
REPO=$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Repo Surgeon",
    "system": "You review the user workspace in read-only mode and report release risks.",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": { "enabled": true, "permissionPolicy": { "type": "always_allow" } },
      "configs": [
        { "name": "read_file", "enabled": true },
        { "name": "grep_files", "enabled": true },
        { "name": "list_files", "enabled": true }
      ]
    }]
  }')
REPO_ID=$(echo "$REPO" | jq -er .id)
```

If a code-review Skill is installed, you can also add `"skills": [{"type": "workspace", "name": "code-review"}]`. Finally create Team Lead. It keeps delegation and result-collection tools, and records the first two members through a real `MultiagentSpec`; the current execution entry does not automatically start members based solely on this field, and actual execution is still initiated by the Harness delegation or platform fan-out below.

```bash
LEAD_BODY=$(jq -n --arg repo "$REPO_ID" --arg ops "$OPS_ID" '{
  name: "Team Lead",
  system: "You coordinate Repo Surgeon and Ops Publisher. For a direct task, delegate concrete work and collect results. When the prompt already contains member results, do not call tools or spawn sessions; only summarize risks and produce the final checklist. Use sessions_pending_completions for finished child sessions and wait_async_results only for the generic async inbox.",
  tools: [{
    type: "agent_toolset",
    defaultConfig: {
      enabled: true,
      permissionPolicy: {type: "always_allow"}
    },
    configs: [
      {name: "sessions_spawn", enabled: true},
      {name: "sessions_list", enabled: true},
      {name: "sessions_pending_completions", enabled: true},
      {name: "wait_async_results", enabled: true}
    ]
  }],
  multiagent: {
    type: "agent_team",
    agents: [
      {type: "agent", id: $repo},
      {type: "agent", id: $ops}
    ]
  }
}')

LEAD=$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$LEAD_BODY")
LEAD_ID=$(echo "$LEAD" | jq -er .id)
printf 'OPS_ID=%s\nREPO_ID=%s\nLEAD_ID=%s\n' "$OPS_ID" "$REPO_ID" "$LEAD_ID"
```

The wire schema of `MultiagentSpec` is `type + agents[]`; member references contain `type`, `id`, and optional `version`. `wait_async_results` is used to block waiting for a generic async inbox; `sessions_pending_completions` is used to enumerate child Session results that have completed but not yet been consumed. They serve different async patterns, so Team Lead enables both, but the system prompt should make clear when to use which.

#### Orchestrate Together

The system provides two different multi-Agent execution methods:

+ **Harness native delegation**: Team Lead dynamically breaks down tasks using `sessions_spawn` / Subagent tools during reasoning, with explicit delegation and result-recovery relationships between parent and child tasks.
+ **Platform fan-out**: `/api/multiagent/run` creates Managed Sessions for multiple Agents and sends the same message to them sequentially or in parallel, suitable for independent analysis, batch processing, and voting.

### A Deeper Look at How It Works

The previous sections introduced Brain and Hands coordination from the user perspective and demonstrated a multi-agent orchestration scenario through an Agent Team example. The following further breaks down the Control Plane, Data Plane, and Worker, explaining what state each layer stores, what fault responsibilities it bears, and what role AgentScope 2.0 plays.

**In one sentence**: the Control Plane manages “definitions and permissions,” the Data Plane manages “run it and record it,” and the Worker manages “on whose machine to act”; AgentScope 2.0’s `HarnessAgent` + filesystem/sandbox abstraction is the kernel of the Data Plane and Hands, and the SaaS API builds platform-level semantics without re-implementing the inference loop.

#### Control Plane

The Control Plane is responsible for “what is allowed to run and who can use it.” It manages static Agent definitions and their versions, as well as reusable resources such as Model, Skills, MCP, Tools, Environment, Memory, Vault, and Resources. Resources can belong to an individual user and be isolated by owner / ACL, or be globally prebuilt by the platform, such as public Skills, MCP catalogs, and built-in toolsets.

These resources can be understood in three relationships: “define, reference, and mount.” Model, Tools, MCP, and Skills enter the Agent version definition; Environment exists independently and is referenced by Session; Memory Store, Vault, and Files/Resources are mounted when the Session is created. Globally prebuilt resources provide platform defaults, while user resources carry owner / share ACL. This avoids copying public Skills into every Agent, while also preventing different tenants from seeing each other’s data through shared directories.

The Control Plane also undertakes change governance. Agent updates create new versions, and old Sessions can continue to record and recover currently supported historical fields; Environment keys can be rotated; resources can be archived rather than physically deleted immediately; high-risk built-in tool permissions are recorded with versions. For production platforms, these capabilities are often more critical than “whether a new model can be called,” because they determine whether rollback, canary, and incident accountability are feasible.

Environment and Session are easy to confuse, but they belong to different layers. This article uses the following boundary:

+ **Environment belongs to the Control Plane**: it is an “execution-plane template” (`local` / `sandbox` / `remote` / `self_hosted` + config + environment key), can be referenced by multiple Sessions, carries archive and sharing, and itself does not produce conversation events.
+ **Session belongs to the Data Plane**: it is a running instance of Agent × Environment, with a state machine and event log; creation parameters reference the `agentId` / `environmentId` from the Control Plane, but lifecycle APIs (events / stream / interrupt) are the core of the Data Plane.

Therefore:

+ Creating a session → **Data Plane** (expanded in the next section)
+ Defining an Environment → **Control Plane** (`POST /api/environments`, rotate key, archive)

#### Data Plane

The Data Plane is responsible for “making a Session that records an Agent version actually run, and fully recording the process.” It hosts model calls, the ReAct loop, Harness hooks, turn leases, the Session state machine, event persistence and SSE push, and handles interrupt, HITL, and externalized tool-result resumption.

These operations are not ordinary CRUD add-ons; they revolve around the state machine: creating a Session records the Agent version and Environment reference, `user.message` pushes the state from idle to running, tool confirmation restores requires_action to running, interrupt attempts to cancel the current turn, archive terminates subsequent use but keeps audit history, and delete clears the session and events. Clients should drive the UI by events rather than polling whether some internal thread is still alive.

The Data Plane consists of peer SaaS replicas; a request may arrive at any instance. A replica first finds the version definition in the Control Plane by `agentId`, then computes a build key from Agent version, Environment, and mount information: if there is a cache hit, reuse `HarnessAgent`; otherwise rebuild. Each turn locates the session state through a `RuntimeContext` containing `userId` and `sessionId`, so “stateless replicas” here means not holding irreplaceable authoritative state, not recreating Java objects for every request.

`RuntimeContext` can be understood as the “identity and resource locator” for a run, rather than stuffing all state into a Map. `userId` determines the multi-tenant namespace and ACL, `sessionId` locates the recoverable short-term brain state; Environment determines the filesystem/sandbox implementation; Memory Store and Vault are resolved into filesystem routes and credentials during the build phase. Harness depends only on these stable abstractions, so when the same request hits another replica, a semantically equivalent runtime environment can be reassembled.

The Data Plane actually hosts four layers of state with different lifecycles:

| State layer | Typical content | Lifecycle / source of truth |
| --- | --- | --- |
| Agent version | name, system, model, tools, skills, MCP, etc. | Control Plane saves full snapshot; current runtime only partially reconstructs historical fields |
| Session events | user.message, tool_use, agent.message, status | Append-only log, source of truth for audit and client catch-up streams |
| Agent brain state | Model messages, compacted context, Hook state | `AgentStateStore`, recovered by userId/sessionId |
| Workspace / Sandbox | Files, task artifacts, tool side effects | Local / BaseStore / E2B / Self-hosted execution plane |


These four layers cannot be summarized as “save conversation history.” For example, Session events can prove the model once requested to write a file, but cannot replace the file itself; AgentStateStore can recover context, but does not automatically recover side effects of external databases. The recovery flow must recover each layer separately, and then re-associate them through event ID, tool call ID, and resource references.

When Harness inference needs to call a tool, the specific execution is decided by Environment. Cloud Sandbox directly reuses Harness’s filesystem / sandbox abstraction, with the Brain initiating E2B-compatible calls; Self-hosted replaces tools with schema-only definitions, suspends the turn after `agent.tool_use`, and returns results through the work queue and Worker protocol.

AgentScope 2.0’s role here is very clear: **provide HarnessAgent and the FS/Sandbox abstraction, ensuring “effect defaults” and “replaceable execution plane”**; Managed Agents is responsible for leases, event contracts, multi-tenancy, and ACL, rather than wrapping another private ReAct.

#### Worker

Worker focuses on how tools get from the Brain to the real execution environment. The system has two paths, distinguished by who initiates the tool call and who manages the sandbox lifecycle.

In fully managed mode, the Brain is responsible for creating and reclaiming the Sandbox, and also actively initiates file or shell calls through the E2B-compatible API provided by AgentScope. The backend can be supported by compatible services such as FC Sandbox, with the tool process and working directory inside the sandbox instance. The platform holds the full handle, so it can uniformly set timeout, isolation scope, and persistence policies.

In Self-hosted mode, after the Brain receives a tool call from the model, it does not connect to the customer VPC, but persists `agent.tool_use` and creates a work item. The customer-side Worker actively polls the queue, executes the tool in its own host or sandbox, and returns the result through `user.tool_result`, allowing the Brain to resume the next round of reasoning.

Their failure-recovery responsibilities also differ. In fully managed mode, the Brain knows the sandbox handle and can uniformly set timeout, snapshot, and reclaim policies; in Self-hosted mode, the Brain only knows the work state and tool result, and the customer Worker must be responsible for whether the local sandbox is still alive, whether duplicate tasks are safe, and whether results need to be desensitized. The platform provides the protocol and state machine, but cannot define the idempotency semantics of business tools for the customer.

The Work state machine is `queued → starting → active → stopping → stopped`.

When deploying an independent Worker, both Brain and customer-side processes need to be configured. The following gives the minimal startup method and production checklist.

## Summary

AgentScope 2.0 is positioned for enterprise-grade distributed scenarios. It can serve as a distributed Agent Framework for building enterprise DataAgents, SreAgents, and so on, and can also use the same Harness to support enterprise Managed Agents, becoming the Agent Runtime underneath Managed Agents. It lets enterprises avoid having to choose between “piecing blocks together themselves” and “fully black-box managed hosting”; the same Harness kernel can deliver both modes.

+ Docs: [https://java.agentscope.io](https://java.agentscope.io)
+ GitHub: [https://github.com/agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)
+ AgentScope Builder: [https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service)

