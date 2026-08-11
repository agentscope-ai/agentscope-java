---
hide-toc: true
---

Managed Agents 让 Agent 运行在云端环境中：一方面，推理、编排、Harness 管理等核心环节均由云端统一托管，架构稳定性与运行效果由平台保障；另一方面，长周期任务不再依赖本地设备持续在线——即使个人电脑关机，任务依然可以在云端持续运行。

基于 AgentScope 2.0 Harness 内核与 Sandbox 隔离能力，可以快速搭建一套完整的企业级 Managed Agents 平台：

+ 把 AgentScope 2.0 中已经工程化的 Harness Agent 直接作为 Brain 运行时，托管内核提供稳定的推理与 Harness 能力，文件系统、workspace、工具执行则完全隔离在 Sandbox 沙箱环境。
+ 平台层负责租户、权限、版本、事件和执行面选型。控制面（Agent、Environment、Memory、Vault、Deployment）和数据面（Session、Events、SSE）把这些能力组织成可多租、可审计、可运维的托管产品。

> 如果您看过我们此前发布的开源 Agent Builder，可以把 Managed Agents 理解为它的产品化升级：底层运行时和主要代码路径没有换，变化的是资源模型、API 契约、执行面边界以及面向多租户的治理方式。
>

## Managed Agents 背景
市面上包括百炼、Claude Code、LangChain 等都有类似的 Managed Agents 产品发布。从本质上来讲，我不认为 Managed Agents 产品与以往的低代码 agent 平台在产品形态上有本质区别，它们都是给你一个包含 “Agent 定义 & 运行” 能力的托管平台，只不过在产品表现形式上，Managed Agents 在 harness 时代更突出以下两点：

1. **不再让业务开发者拼装 Harness。**传统平台常常把记忆维护、上下文压缩、状态恢复、工具权限和子任务回收拆成大量配置项。Managed Agents 把这些通用工程能力收进统一 Harness，开发者主要定义与业务相关的 Skills、Tools、Subagents 和权限策略。平台保证机制的一致性与可升级性，但最终任务效果仍取决于模型、system prompt、Skill 质量、工具返回值和业务评测。
2. **让客户掌握工具执行和数据回传边界。**对企业用户而言，Agent 真正产生价值的地方是与企业数据资产连接，而 shell、文件读写、MCP 和业务工具正是数据流动的入口。为此，系统刻意拆分 **Brain（推理编排）** 与 **Hands（工具执行）**：Brain 负责下一轮推理、状态恢复和上下文管理；Hands 负责真正接触文件、网络与业务系统。Hands 可以运行在平台托管的 Cloud Sandbox，也可以运行在客户 VPC 内的 Self-hosted Worker。

第 1 点真正改变的是平台的抽象层级。传统低代码平台往往让用户决定“什么时候总结记忆、超长上下文怎么截断、工具异常重试几次、子任务怎样回收”。这些选项看起来灵活，实际上把 Harness 的工程责任转嫁给了业务开发者：同一个 Agent 因为配置者经验不同，效果和稳定性可能完全不同。Managed Agents 则只暴露业务差异，例如角色提示、Skills、MCP、工具权限和 Environment；至于压缩时机、会话恢复、工具结果淘汰、长期记忆刷新等，交给持续演进的 Harness。平台升级 Harness 后，所有 Agent 都能获得同一套工程改进，而不必逐个修改流程图。

第 2 点改变的是信任边界。模型决定“要调用什么”，不等于模型所在的进程必须“亲自执行什么”。只要工具调用被表示为稳定的 schema、tool_use_id 和结果事件，Hands 就可以被迁移到平台云沙箱或客户 VPC，而不改变 Brain 中的推理循环。这让安全团队能够分别回答三个问题：模型能看到哪些上下文？工具能访问哪些网络和文件？工具结果中哪些内容可以回传给 Brain？这三个问题被拆开以后，权限审核和故障定位都比“一整个 Agent 容器”清晰得多。

以 Claude Managed Agents 为例，它被开发者接受的一个重要背景，是 Claude Code 已经证明了成熟 Coding Agent Harness 的产品价值。用户看到的是模型推理和任务结果，平台真正托管的却是 **可恢复的会话状态、工程化运行策略和可替换的 Hands**。AgentScope 2.0 采用了相似的分层思路：`HarnessAgent` 处理长任务、上下文溢出、状态恢复和任务委派，Managed Agents 再向外补上多租户资源、Environment 与稳定的数据面契约。

有了 Managed Agents 后，Anthropic 在个人、企业用户之间几个不同层次都有对应的解决方案，层层递进：

+ **Claude Code CLI** 面向个人或单机开发工作流，Agent 与本地工作区、终端和会话记录直接结合。
+ **Claude Agent SDK** 把 Session、事件流和工具交互 API 化，适合嵌入企业应用；身份、租户和资源隔离仍由接入方负责。
+ **Managed Agents** 进一步把 Agent、Environment、Session 与执行面变成托管资源，由平台处理版本、权限和运行时治理。

这三层的区别不只是“封装越来越厚”，而是状态归属逐步上移：

| 形态 | 主要状态在哪里 | 谁负责隔离 | 适合谁 |
| --- | --- | --- | --- |
| CLI / 单机应用 | 本机目录与本地会话 | 操作系统用户 | 个人提效 |
| SDK / Harness | 应用提供的 SessionStore / StateStore | 应用开发者 | 单个企业应用 |
| Managed Agents | 平台控制面、共享状态库、Session 事件日志 | 平台按 User / Agent / Environment 管理 | 多团队、多租户平台 |


## 为什么 AgentScope 2.0 适合做 Managed Agents 底座
AgentScope 2.0 的模型抽象、工具与 MCP、消息与事件、状态存储、远程文件系统 / 分布式 BaseStore，以及可插拔沙箱，都为进程外持久化和多副本部署预留了扩展点。这使 Managed Agents 无需从零实现会话恢复、工具结果落盘与跨请求上下文延续，数据面副本必须共享 AgentStateStore、Workspace 后端，并正确处理 turn 租约与节点切换。

其中，Workspace 是 Agent 使用的逻辑目录，Filesystem 和 Sandbox 是承载它的物理后端。两者通过 AbstractFileSystem 解耦：同一套文件工具既可以指向本机目录，也可以指向分布式 BaseStore 或 E2B 沙箱。正因为逻辑工作区与物理执行面分离，Agent 定义才能在不改业务提示词的情况下切换隔离策略。

具体来说，HarnessAgent 在 ReActAgent 之上通过 Hook 装配长期运行所需的工程默认项，例如：

+ **工作区驱动的人格与知识**：`AGENTS.md` / `MEMORY.md` / `KNOWLEDGE.md` 等注入系统提示；
+ **会话持久化**：按 sesionId 恢复 agent 状态，进程重启后仍能续聊；
+ **压缩与溢出处理**：Harness 默认启用 compaction 与 tool-result eviction，并允许业务覆盖阈值或显式关闭；
+ **Skills / Subagents**：工作区 skills、任务委派（task 等）开箱可用；
+ **统一文件系统抽象**：本地、远程 KV、云沙箱（E2B 等）走同一套工具语义，便于 Managed Agents 用 Environment 类型切换执行面而不改 Agent 业务定义。

这些能力不是互相独立的名词。一次长任务可能先从 AgentStateStore 恢复消息与 agent state，再由工作区 Hook 注入 AGENTS.md 和已安装 Skills；推理过程中如果上下文逼近窗口上限，压缩 Hook 会收敛历史，而较大的工具结果可以被淘汰到文件系统中，仅把可检索引用留在上下文里；需要并行研究时，主 Agent 又可以把任务交给 Subagent。最终，无论文件系统落在本地、远程 KV 还是 E2B，模型看到的工具语义保持一致。这种组合后的稳定性才是 Harness 作为平台内核的意义。

另外，HarnessAgent 与 Session 不是同一个生命周期。前者是在具备共享 `AgentStateStore` 与可恢复 Workspace 后端的数据面节点上重建的运行对象，后者是有稳定 ID、事件序列和持久状态的产品资源。分清这两者，才能做真正的水平扩展：节点挂掉时可以丢弃 Java 对象，但对话与长期记忆必须从共享状态恢复；工作区是否连续则取决于 BaseStore、沙箱快照或客户侧持久化，Local 目录并不具备这一保证。

从单个企业智能体应用走向 Managed Agents，关键不是改写推理内核，而是把运行能力提升为稳定的平台资源。这里所说的“增加一层平台 API”绝不只是增加几个 Controller；真正产品化还要补齐租户 ACL、Agent 版本快照、Session 状态机、append-only 事件、turn 租约、HITL ticket、Environment key、Worker 队列、共享协调存储和归档审计。Harness 让平台不必重写 agent loop，但这些分布式职责仍是独立的工程系统。

由此 Managed Agents 的完整形态就是：SaaS 控制面负责资源治理，AgentScope 2.0 提供运行内核，FC Sandbox / E2B 或客户 Worker 承接不同信任边界下的 Hands。

## 企业级 Managed Agents 平台详解
### 总体部署架构
#### 核心组件图
1. **Control Plane**

<!-- 这是一张图片，ocr 内容为：CONTROL PLANE ARCHITECTURE CLIENTS CLI CURL/SDK CONSOLE API GATEWAY ROUTE BY APL SURFACE CONTROL APIS DATA APLS CONTROL PLANE DATA PLANE DATA PLANE APLS(COLLAPSED) DEFINITIONS AGENT/ENV SESSION CREATE SESSIONS .EVENTS. SSE +MEMORY/VAULT SKILLSMCP VERSIONED AGENT HARNESS LOOP - STATE RESTORE READ AGENT/ENV FROM CP ENVIRONMENT ACL/SHARES RESOURCES.TOOLS DATA PLANE APIS CONTROL PLANE API GATEWAY AGENT VERSIONS,ENVIRONMENTS,MEMORY/ ALSO PUBLIC(SESSIONS/EVENTS/SSE); FRONT DOOR FOR CLI/ CONSOLE / CURL;ROUTES VAULT/ACL,SESSION CREATE. COLLAPSED HERE-SEE DIAGRAM 2. CONTROL VS DATA APLS. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785141394899-a68e0d3b-e16b-44be-9a29-4e61f163463f.png)

2. **Data Plane**

<!-- 这是一张图片，ocr 内容为：DATA PLANE ARCHITECTURE HANDS-TOOL EXECUTION BOUNDARY BRAIN- REASONING & ORCHESTRATION CLOUD MANAGED SANDBOX SESSION/EVENTS API TYPESANDBOX .BRAIN INITIATES E2B/FC API USER.MESSAGE . INTERRUPT/HITL CLOUD SANDBOX E2BFILESYSTEMSPEC ISOLATED FS CREATE /TIMEOUT SESSIONTURNRUNNER TYPESANDBOX FS+SHELL CALLS WORKSPACE ROOT TURN LEASE `STATUS - BUILD/ CACHE BRAIN INITIATES AGENTSCOPE KERNEL MODEL HARNESSAGENT TOOL DECISIONS REACT/STREAMEVENTS SELF-HOSTED WORKER HOOKS.COMPACTION TEXT/THINKING TYPESELF_HOSTED OUTBOUND ONLY NO BRAIN INGRESS TYPE-SELF_HOSTED SCHEMAONLYTOOL WORK QUEUE OUTBOUND WORKER EVENTLOG AGENTSTATESTORE POLL/ACK/HEARTBEAT SUSPEND TURN RESTORE BY SESSION AGENT. PERSISTED AGENT.TOOL_USE CUSTOMER WORKER EXECUTE FS/SHELL POST USER.TOOL_RESULT.RESUME BRAIN CONTROL-PLANE REFS AGENT VERSION `ENVIRONMENT MEMORY / VAULT PATH CONTRAST SELF-HOSTED WORKER CLOUD MANAGED SANDBOX ENVIRONMENT TYPE-SELF HOSTED.TOOLS ARE SCHEMA-ONLY ON BRAIN;WORKER POLLS, ENVIRONMENT TYPE-SANDBOX.BRAIN CALLS E2B-COMPATIBLE APLS;PLATFORM OWNS SANDBOX LIFECYCLE.NO CUSTOMER WORKER. EXECUTES,POSTS USER.TOOL_RESULT TO RESUME. -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785141716807-8d15ef5f-4d56-4c24-958a-caf553476262.png)

#### 核心数据流转
客户端通过（session/event）接口发送任务请求到 Managed Data Plane（Brain），Brain 从共享状态恢复 Agent，进而执行整个推理、编排流程，如果中间有工具调用，Brain 再按 Environment 配置将工具调用请求路由到 Worker（可能是托管sandbox环境、用户自管理sandbox环境等）。

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

结合上面的架构分析与实现，可以把整套系统读成四层：

| 层 | 职责 |
| --- | --- |
| **控制面** | 租户资源、Agent 版本、Environment、Memory/Vault、ACL |
| **数据面** | Session 生命周期、事件落库、SSE、turn 租约 |
| **运行时 Brain** | 解析定义、命中缓存或重建 → 按 RuntimeContext 恢复 → `HarnessAgent.streamEvents` |
| **Hands** | 真正读写文件、shell、外化工具 |


### 创建一个 Agent 并运行
下面先完成最小初始化：登录 Managed Agents，创建一个可复用的 Workspace Copilot Agent，再演示 Local、Cloud Sandbox 和 Self-hosted 三种 Wroker 运行模式，可以直观的看到“Agent 定义不变，Hands 位置改变”。

下文假设 Managed Agents 已启动在 `http://localhost:8080`，并已配置模型密钥（如 `DASHSCOPE_API_KEY`）。

**0. 登录**

```bash
export BASE=http://localhost:8080
TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -er .token)
```

**1. 定义示例 Agent**

我们创建一个「copilot 工作区助手」 Agent：包含简短的系统提示词，配套 read_files、list_fukes、write_files 等工具，便于演示不同工具在不同 worker 模式下的运行情况。

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



接下来是三种 worker 模式的展示，同一个 Agent，同一套 Brain 推理编排执行环境，在三条 Hands 路径上的运行方式：

| 模式 | 工具在哪里执行 | 谁发起工具调用 | 数据边界 | 典型用途 |
| --- | --- | --- | --- | --- |
| Local | 当前Brain所在宿主机 | Brain 进程 | 托管集群内部 | 开发与可信环境 |
| Cloud Sandbox | E2B / FC 云沙箱 | Brain 通过 E2B 兼容 API 调用 | 平台托管的云沙箱 | 托管隔离执行 |
| Self-hosted | 客户 Worker / 客户沙箱 | 客户 Worker 读取工具任务后执行 | 客户 VPC | 私有工作区与内置 shell / FS 工具 |


已有 Session 不支持中途切换 worker 执行环境；要更换信任边界，应创建新 Session，以免同一条事件历史跨越不同执行语义。

#### Worker in Local 模式
Local 模式最适合开发联调。Session、Harness 推理、模型请求和工具执行都由 Managed 集群发起，文件与 shell 直接落在 Brain 进程可见的本地环境中。

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

在 **Local** 模式下，Environment `type=local`：文件系统与（若启用的）shell 都在托管集群宿主机命名空间内完成，没有独立 Hands 队列，也不调用云沙箱。适合开发联调与可信内网。

#### Worker in Cloud Sandbox 模式
Cloud Sandbox 保留托管 Brain，但把文件和 shell 移入独立沙箱。Harness 推理、模型请求以及工具调用的发起方仍在 Managed 集群；真正的命令执行和文件读写发生在 FC Sandbox / E2B 兼容环境中。

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

Agent 通过 E2B 客户端协议申请容器，并在容器内执行 shell / FS 操作，**Brain 主动发起调用，Worker 不参与**。若使用兼容 E2B 协议的 Aliyun FC Sandbox，需要先准备服务地址、模板和 API Key。

Cloud Sandbox 的托管边界可以拆成三个动作：**创建沙箱、在沙箱里执行、在 Session 结束或超时后回收/持久化**。Managed Agents 通过 `E2bFilesystemSpec` 把文件和 shell 工具映射到同一沙箱上下文；`isolationScope=SESSION` 时，不同 Session 默认不会共享工作目录。若选择快照或 TAR 等持久化模式，恢复策略还需要与 `AgentStateStore` 一起考虑：恢复了模型上下文却没有恢复文件，或反过来，都会造成“Agent 记得做过、工作区却不存在”的不一致。生产系统必须把两者当作一个恢复单元设计。

#### Worker in Self-hosted 模式
Self-hosted 把 Hands 进一步移动到客户环境。Brain 仍在 Managed 集群中完成 Harness 推理，但工具任务进入队列，由客户侧 Worker 主动出站轮询、管理本地工作目录或沙箱，并把结果回传给 Brain。整个过程中，Brain 不需要进入客户网络。

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

在 Self-hosted 下，Brain **关闭本地 shell/FS 实执行**，把相关工具注册为外化 schema；模型一旦 `tool_use`，事件落库并进入挂起/排队，由用户侧 Worker 持 Environment Key **出站** poll → 管理本地工作目录并执行，或接入客户自有沙箱 → 回传 `user.tool_result` 续跑。这与 Cloud Sandbox「Brain 主动打沙箱 API」正好相反：**执行发起权在用户侧；是否使用以及如何管理沙箱，也由客户侧实现决定**。

Self-hosted 的目标场景是让数据库、代码仓库和发布系统等企业资源留在客户边界内，但当前参考 Worker 开箱支持的范围是内置 shell / FS 工具。数据库、自定义业务工具与内网 MCP 仍需要后续 Worker 扩展 SPI，或由使用方自行在 Worker 外封装。对于已经接入 Worker 协议的工具，Brain 能看到 schema、调用参数和最终回传结果，但不必直接连入客户 VPC；Worker 只需主动向平台发起 HTTPS 请求，并可在回传前做结果脱敏、大小限制和审计。

### 一个更复杂的 Agent Team 编排示例
#### 定义多个 Agent
下面用 AgentDev 场景展示一个三角色团队，输入是一项 Java 库发布规划任务：

+ Repo Surgeon 从代码质量视角给出检查项，只拥有工作区读取与检索能
+ Ops Publisher 从发布流程视角生成工单草案，在本次演示中只生成文本草案，外部 MCP 接入作为可选配置单独说明
+ Team Lead 汇总风险与验收清单，Team Lead 尽量不直接接触业务数据，只负责委派和汇总

拆成三个 Agent 不是为了堆叠角色，而是为了分别约束工作区权限、外部系统接入和汇总职责，这样做的收益是最小权限与独立审计，而不是把所有工具塞进一个超级 Agent 后只靠提示词约束。

先创建可直接参与 fan-out 的 Ops Publisher。它只生成发布草案，不调用外部系统，因此不会让 `/api/multiagent/run` 卡在人工确认阶段：

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

# 发布规划 Agent：本次只生成文本草案
OPS=$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$OPS_BODY")
OPS_ID=$(echo "$OPS" | jq -er .id)
```

如果要在生产中接入工单 MCP，可在 Agent body 中增加如下片段；`enableTools` 只暴露明确允许的工具，URL 与工具名必须替换为真实值：

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

如需发布 Skill，也可以在确认 Workspace 已安装对应内容后增加 `"skills": [{"type": "workspace", "name": "release-notes"}]`。当前 `mcp_toolset.defaultConfig.permissionPolicy` 不会进入 `ToolConfirmationMiddleware`，因此高风险 MCP 写操作还需要在 MCP 网关侧做身份、审批与幂等控制，不能只依赖 Agent body 中的 `always_ask`。随后创建只读访问代码工作区的 Repo Surgeon：

```bash
# 操作用户侧资源 —— 例如「代码/仓库」类 Agent：偏 filesystem tools + skills
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

如果已安装代码审查 Skill，可再加入 `"skills": [{"type": "workspace", "name": "code-review"}]`。最后创建 Team Lead。它保留委派与结果收集工具，并通过真实 `MultiagentSpec` 记录前两个成员；当前运行入口不会仅凭这个字段自动启动成员，实际执行仍由下面的 Harness 委派或平台 fan-out 发起。

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

`MultiagentSpec` 的 wire schema 是 `type + agents[]`，成员引用包含 `type`、`id` 和可选 `version`。`wait_async_results` 用于阻塞等待通用异步 inbox；`sessions_pending_completions` 用于枚举已经完成但尚未消费的子 Session 结果。两者服务于不同的异步模式，因此 Team Lead 同时启用它们，但应由 system prompt 明确什么时候使用哪一种。

#### 编排在一起
系统提供两种不同的多 Agent 运行方式：

+ **Harness 原生委派**：Team Lead 在推理过程中使用 `sessions_spawn` / Subagent 工具动态拆解任务，父子任务之间存在明确的委派与结果回收关系。
+ **平台 fan-out**：`/api/multiagent/run` 为多个 Agent 分别创建 Managed Session，并把同一消息顺序或并行发送给它们，适合独立分析、批处理和投票。

### 深入了解工作原理
前文从使用者视角介绍了 Brain、Hands 协调工作过程，并通过一个 Agent Team 示例演示了多 agent 编排场景。下面进一步拆开控制面、数据面与 Worker，说明每一层保存什么状态、承担什么故障责任，以及 AgentScope 2.0 在其中扮演什么角色。

**一句话**：控制面管「定义与权限」，数据面管「跑起来并记下来」，Worker 管「在谁的机器上动手」；AgentScope 2.0 的 `HarnessAgent` + 文件系统/沙箱抽象是数据面与 Hands 的内核，SaaS API 做好平台层语义建设，不重新实现推理循环。

#### 控制面
控制面负责“定义什么可以运行，以及谁可以使用它”。它管理 Agent 静态定义及其版本，也管理 Model、Skills、MCP、Tools、Environment、Memory、Vault 和 Resources 等可复用资源。资源既可以属于单个用户并按 owner / ACL 隔离，也可以由平台全局预置，例如公共 Skill、MCP 目录和内置工具集。

这些资源可以按“定义、引用、挂载”三种关系理解。Model、Tools、MCP、Skills 进入 Agent 版本定义；Environment 独立存在，由 Session 引用；Memory Store、Vault、Files/Resources 则在 Session 创建时挂载。全局预置资源提供平台默认能力，用户资源带 owner / share ACL。这样既避免每个 Agent 重复复制公共 Skill，也不会因为共享目录而让不同租户相互看见数据。

控制面还承担变更治理。Agent 更新生成新版本，旧 Session 可以继续记录并恢复当前已支持的历史字段；Environment key 可以 rotate；资源可以 archive 而不是立即物理删除；高风险内置工具权限随版本记录。对生产平台而言，这些能力往往比“能否调用一个新模型”更关键，因为它们决定回滚、灰度和事故追责是否可行。

Environment 与 Session 容易混淆，但两者属于不同层次。本文采用如下边界：

+ **Environment 归属控制面**：它是「执行面模板」（local / sandbox / remote / self_hosted + config + environment key），可被多个 Session 引用，带归档与分享，本身不产生对话事件。
+ **Session 归属数据面**：它是 Agent × Environment 的一次运行实例，带状态机与事件日志；创建参数会引用控制面的 agentId / environmentId，但生命周期 API（events / stream / interrupt）是数据面核心。

因此：

+ 发起 session → **数据面**（下一节展开）
+ 定义 Environment → **控制面**（`POST /api/environments`，rotate key，archive）

#### 数据面
数据面负责“让一个记录了 Agent 版本的 Session 真正运行起来，并完整记录过程”。它承载模型调用、ReAct loop、Harness hooks、turn 租约、Session 状态机、事件持久化与 SSE 推送，也处理 interrupt、HITL 和外化工具结果续跑。

这些操作不是普通 CRUD 的补充，而是围绕状态机展开：创建 Session 记录 Agent 版本和 Environment 引用，`user.message` 把状态从 idle 推向 running，工具确认把 requires_action 恢复为 running，interrupt 尝试取消当前 turn，archive 终止后续使用但保留审计历史，delete 才清理会话及事件。客户端应该根据事件驱动 UI，而不是轮询某个内部线程是否仍然存活。

数据面由对等 SaaS 副本组成，请求可以到达任意实例。副本先通过 `agentId` 找到控制面的版本定义，再根据 Agent 版本、Environment 与挂载信息计算构建键：命中缓存就复用 `HarnessAgent`，未命中才重新构建。每个 turn 都通过包含 `userId`、`sessionId` 的 `RuntimeContext` 定位会话状态，因此这里的“无状态副本”是指不持有不可替代的权威状态，而不是每个请求都重新创建 Java 对象。

`RuntimeContext` 可以理解为一次运行的“身份与资源定位器”，而不是把所有状态塞进一个 Map。`userId` 决定多租命名空间与 ACL，`sessionId` 定位可恢复的短期 brain state；Environment 决定文件系统/沙箱实现；Memory Store 与 Vault 则在构建阶段解析成文件系统路由和凭证。Harness 只依赖这些稳定抽象，因此同一个请求打到另一台副本时，可以重新装配出语义等价的运行环境。

数据面实际托管了四类生命周期不同的状态：

| 状态层 | 典型内容 | 生命周期 / 真相源 |
| --- | --- | --- |
| Agent 版本 | name、system、model、tools、skills、MCP 等 | 控制面保存完整快照；当前运行时仅对部分字段完成历史重建 |
| Session 事件 | user.message、tool_use、agent.message、status | 追加写日志，审计与客户端补流的真相源 |
| Agent brain state | 模型消息、压缩后的上下文、Hook 状态 | `AgentStateStore`，按 userId/sessionId 恢复 |
| Workspace / Sandbox | 文件、任务产物、工具副作用 | Local / BaseStore / E2B / Self-hosted 执行面 |


这四层不能用一个“保存对话历史”概括。比如 Session 事件能证明模型曾请求写文件，但不能代替文件本身；AgentStateStore 能恢复上下文，却不自动恢复外部数据库的副作用。恢复流程必须分别恢复每一层，再用事件 ID、tool call ID 和资源引用把它们重新关联起来。

当 Harness 推理需要调用工具时，具体怎么执行由 Environment 决定。Cloud Sandbox 直接复用 Harness 的 filesystem / sandbox 抽象，由 Brain 发起 E2B 兼容调用；Self-hosted 则把工具替换为 schema-only 定义，在 `agent.tool_use` 后挂起 turn，经 work queue 和 Worker 协议回传结果。

AgentScope 2.0 在这里的角色非常明确：**提供 HarnessAgent 与 FS/Sandbox 抽象，保证「效果默认项」和「执行面可替换」**；Managed Agents 负责租约、事件契约、多租与 ACL，而不是再包一层私有化的 ReAct。

#### Worker
Worker 关注工具如何从 Brain 到达真正的执行环境，系统有两条路径，区别在于谁发起工具调用、谁管理沙箱生命周期。

在全托管模式下，Brain 负责创建和回收 Sandbox，也主动通过 AgentScope 提供的 E2B 兼容 API 发起文件或 shell 调用。后台可以由 FC Sandbox 等兼容服务承接，工具进程与工作目录都位于沙箱实例中。平台掌握完整句柄，因此可以统一设置超时、隔离范围和持久化策略。

在 Self-hosted 模式下，Brain 收到模型的 tool call 后，不会连接客户 VPC，而是持久化 `agent.tool_use` 并创建 work item。客户侧 Worker 主动 poll 队列，在自己的主机或沙箱中执行工具，再通过 `user.tool_result` 回传结果，使 Brain 恢复下一轮推理。

两者在故障恢复上的责任也不同。全托管模式下，Brain 知道沙箱句柄并可以统一设置超时、快照和回收策略；Self-hosted 下，Brain 只知道 work 状态和工具结果，客户 Worker 必须负责本地沙箱是否仍存活、重复任务是否安全、结果是否需要脱敏。平台提供协议和状态机，但不能替客户定义业务工具的幂等语义。

Work 状态机为 `queued → starting → active → stopping → stopped`。

部署独立 Worker 时，需要同时配置 Brain 和客户侧进程。下面给出最小启动方式与生产检查项。

## 总结
AgentScope 2.0 定位面向企业级分布式场景，它既可以做分布式 Agent Framework，用来开发企业内的 DataAgent、SreAgent 等，又可以用同一套 Harness 撑起企业内的 Managed Agents，成为 Managed Agents 底层的 Agent Runtime。可以让企业不必在「自己拼积木」和「完全黑盒托管」之间二选一，同一套 Harness 内核两种模式都能实现。

+ 文档：[https://java.agentscope.io](https://java.agentscope.io)
+ GitHub：[https://github.com/agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)
+ AgentScope Builder：[https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-service)

