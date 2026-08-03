## AgentScope Control Plane

### 设计目标概览
实现一个agent control plane控制面项目，依托Kubernetes平台设计与实现。

1. 作为控制面，提供rest api接口，支持托管与调度agent数据面的能力，api 设计有点像是langsmith managed agents，但是控制面只做Kubernetes资源的声明与agent数据面调度；
2. 控制面基于kubernetes平台构建，提供必要的CRD资源定义，比如Agent CRD，CRD定义可适当参考Kagent，但Agent的数据格式上我想要遵循Langsmith Managed Agents规范
3. 控制面在规划上还有一个非常重要的职责，那就是具备协调agent数据面的能力，从一定程度上和Istio这种Service Mesh比较像，我期望它能做skill、mcp管控，同时还能协调agent数据面的分布式部署与流量管理。
4. 未来控制面要调度的数据面，我期望是AgentScope Java开发的agent智能体（这个是第一期规划）。但面向未来的话，我期望能兼容尽可能多的数据面组件，比如Langchain、Claude Code等等。
5. 控制面应该管理好集群中的每个agent数据面，比如有多少个agent，每个agent部署了几个实例，每个agent有哪些活跃的session。同时对每个sesion还可以看到它的state，对state进行查看（plan内容、task拆解与进度等）。还能对某个session进行主动的会话压缩等等命令
6. 控制面应该具备，数据面如果需要sandbox的话，应该能访问控制面相关接口申请sandbox，从这个层面考虑，控制面应该能适配agent-sandbox项目，代理给agent-sandbox来管理sandbox实例。

### 参考资料
1. Langsmith Managed Agents:
   - https://docs.langchain.com/mcp
   - https://docs.langchain.com/langsmith/managed-deep-agents-overview
2. Kagent:
   - /Users/ken/agentscope-2/kagent
   - https://kagent.dev/docs/kagent
3. Istio Service Mesh: 
   - /Users/ken/agentscope-2/istio
   - https://istio.io/latest/docs/overview/what-is-istio/
4. Agent-Sandbox
   - /Users/ken/agentscope-2/agent-sandbox
   - https://agent-sandbox.sigs.k8s.io/docs/

---

### 详细项目规划

#### 一、整体架构

与 Istio 类似，每个 Agent 是一个独立的 K8s Service，用户直接访问 Agent 服务，控制面不在用户请求路径上。控制面只管理基础设施和配置分发。

```
用户请求路径（控制面不参与）:
─────────────────────────────
  用户 → Ingress/Gateway → Agent K8s Service → Agent Pod
                                                  │
                                                  ├── /chat          (对话)
                                                  ├── /sessions      (session 管理)
                                                  └── /chat/stream   (流式)

管理路径 + ASDP 通信:
─────────────────────
┌─────────────────────────────────────────────────────────────────────┐
│                      AgentScope Control Plane                      │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │  REST API     │  │  Controllers │  │  ASDP gRPC Server        │  │
│  │  (管理操作)   │  │  (ctrl-rt)   │  │  (配置推送 / 状态接收)   │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────────┘  │
│         │                  │                     │                   │
│  ┌──────┴──────────────────┴─────────────────────┴───────────────┐  │
│  │                    Kubernetes API Server                       │  │
│  │  ┌─────────┐ ┌────────────┐ ┌──────────┐ ┌────────────────┐  │  │
│  │  │Agent CRD│ │MCPServer   │ │ModelConfig│ │AgentTeam CRD   │  │  │
│  │  │         │ │CRD         │ │CRD        │ │                │  │  │
│  │  └─────────┘ └────────────┘ └──────────┘ └────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│         │                                                           │
│  ┌──────┴───────────────────────────────────────────────────────┐   │
│  │                  Sandbox Broker                               │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
          │ ASDP gRPC          │ ASDP gRPC          │ ASDP gRPC
    ┌─────┴─────┐        ┌────┴─────┐         ┌────┴─────┐
    │ Agent Pod │        │ Agent Pod │         │ Agent Pod │
    │ (AS Java) │        │ (AS Go)  │         │ (Custom) │
    │ ┌───────┐ │        │ ┌───────┐ │         │ ┌───────┐ │
    │ │Service│◀┼─ 用户   │ │Service│◀┼─ 用户   │ │Service│◀┼─ 用户
    │ └───────┘ │        │ └───────┘ │         │ └───────┘ │
    └───────────┘        └──────────┘         └──────────┘
```

**核心设计原则：**
1. **Agent 即服务**：每个 Agent 是独立的 K8s Service，用户直接访问，控制面不代理用户流量（对标 Istio：控制面管配置，不碰数据流量）
2. **声明式优先**：所有状态通过 CRD 声明，Controller reconcile 到期望状态
3. **数据面无关**：通过 DataPlaneAdapter 接口抽象数据面差异，第一期适配 AgentScope Java
4. **两种部署模式并重**：Declarative（控制面托管）和 BYO（自主部署+自动发现）同等支持
5. **Session 透明路由**：Session 管理是数据面内部事务，对用户完全透明（None 模式走共享存储，Instance 模式数据面内部转发）
6. **渐进式落地**：分期迭代，第一期聚焦核心 CRUD 与部署调度

---

#### 二、Agent 管理模式与数据面契约

这是控制面最核心的设计决策。参考 Kagent 的 Declarative/BYO 二元模型，但在 BYO 方向上做了显著增强——不仅仅是"平台帮你拉起一个自定义镜像"，而是"你自己部署，控制面自动发现并纳管"。

##### 2.1 两种管理模式

```
                   ┌────────────────────────────────────┐
                   │        AgentScope Control Plane     │
                   │                                      │
                   │  ┌────────────┐  ┌───────────────┐  │
                   │  │  Agent     │  │  Discovery    │  │
                   │  │ Controller │  │  Controller   │  │
                   │  └─────┬──────┘  └───────┬───────┘  │
                   │        │                  │          │
                   └────────┼──────────────────┼──────────┘
                            │                  │
              ┌─────────────┴──┐        ┌──────┴─────────────┐
              │                │        │                      │
        Declarative 模式    BYO 模式
        (控制面创建部署)    (自主部署，控制面发现纳管)
              │                │
     ┌────────┴───────┐   ┌───┴─────────────────────┐
     │ CLI push 配置   │   │ 用户自己:                │
     │ → Agent CRD     │   │  1. 写 AgentScope Java  │
     │ → CP 创建       │   │  2. 打包镜像             │
     │   Deployment    │   │  3. kubectl apply 部署   │
     │   Service       │   │                          │
     │   ConfigMap     │   │ 控制面自动:               │
     └────────────────┘   │  1. 发现带标签的 Workload │
                          │  2. 探测数据面 API         │
                          │  3. 创建 Agent CRD 纳管   │
                          │  4. SDK 内置自动上报       │
                          └──────────────────────────┘
```

| 维度 | Declarative | BYO |
|---|---|---|
| 谁创建 Deployment | 控制面 AgentController | 用户自己（kubectl / Helm / ArgoCD） |
| Agent 配置来源 | Agent CRD spec.declarative | 打在镜像里 or 用户自己挂 ConfigMap |
| 控制面职责 | 全生命周期管理 | 发现、纳管、观测、协调 |
| 镜像 | 控制面提供的基础运行时镜像 | 用户自定义镜像 |
| 配置热更新 | 控制面推送 | 控制面推送（如数据面实现了热更新接口） |
| 适用场景 | 快速发布、标准化 agent | 已有项目迁移、深度定制 |

##### 2.2 数据面契约（Data Plane Contract）

无论 Declarative 还是 BYO，数据面都需要实现一套标准的 HTTP 接口，控制面通过这些接口与数据面交互。这类似于 Istio 要求 Envoy 实现 xDS 协议、Kagent 要求数据面实现 A2A 协议。

```
┌─────────────────────────────────────────────────────┐
│              Data Plane Contract                     │
│                                                       │
│  必须实现 (Level 1 - 最小可纳管):                     │
│  ┌─────────────────────────────────────────────┐     │
│  │ GET  /agentscope/info                        │     │
│  │   → 返回 agent 元数据 (name, runtime, tools) │     │
│  │ GET  /agentscope/health                      │     │
│  │   → 健康检查                                  │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  建议实现 (Level 2 - 会话观测):                       │
│  ┌─────────────────────────────────────────────┐     │
│  │ GET  /agentscope/sessions                    │     │
│  │   → 活跃会话列表                              │     │
│  │ GET  /agentscope/sessions/{id}/state          │     │
│  │   → 会话状态 (tasks, context, summary)        │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  完整实现 (Level 3 - 全功能协调):                     │
│  ┌─────────────────────────────────────────────┐     │
│  │ POST /agentscope/sessions/{id}/compress       │     │
│  │   → 触发会话压缩                              │     │
│  │ POST /agentscope/sessions/{id}/terminate      │     │
│  │   → 终止会话                                  │     │
│  │ POST /agentscope/config/reload                │     │
│  │   → 热加载新配置                              │     │
│  │ GET  /agentscope/metrics                      │     │
│  │   → Prometheus 格式指标                       │     │
│  └─────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
```

**Level 1 接口详细定义：**

```json
// GET /agentscope/info — 控制面发现后第一个调用的接口
{
  "name": "customer-support-agent",
  "displayName": "客服助手",
  "description": "处理客户咨询的智能体",
  "runtime": "agentscope-java",        // agentscope-java | agentscope-go | langchain | custom
  "version": "1.2.0",                  // 数据面应用版本
  "sdkVersion": "0.8.0",               // AgentScope SDK 版本
  "contractLevel": 3,                  // 实现了哪个等级的契约
  "capabilities": [                    // 数据面声明自己支持的能力
    "session-reporting",
    "hot-reload",
    "context-compression",
    "sandbox-request"
  ],
  "agentConfig": {                     // BYO 模式下数据面自报配置
    "modelProvider": "DashScope",
    "model": "qwen-max",
    "tools": ["search_docs", "get_faq", "create_ticket"],
    "maxTurns": 50
  },
  "port": 8080
}
```

**Level 2 接口：**

```json
// GET /agentscope/sessions
{
  "sessions": [
    {
      "id": "sess-abc123",
      "phase": "Active",              // Active | Idle | Compressing | Terminated
      "startedAt": "2026-06-26T10:00:00Z",
      "lastActiveAt": "2026-06-26T10:35:00Z",
      "messageCount": 42,
      "tokenUsage": {
        "promptTokens": 15000,
        "completionTokens": 8000
      },
      "contextPressure": 0.56,
      "taskSummary": {
        "total": 5,
        "pending": 1,
        "inProgress": 2,
        "completed": 2
      }
    }
  ]
}

// GET /agentscope/sessions/{id}/state — 完整状态快照
{
  "sessionId": "sess-abc123",
  "summary": "用户咨询了订单退款流程，已完成退款申请提交...",
  "contextPressure": {
    "usedTokens": 18000,
    "maxTokens": 32000,
    "ratio": 0.5625
  },
  "tasks": [
    {"id": "task-1", "subject": "查询订单信息", "state": "completed"},
    {"id": "task-2", "subject": "提交退款申请", "state": "in_progress"}
  ],
  "currentIter": 3,
  "permissionMode": "default",
  "activatedToolGroups": ["crm", "knowledge-base"]
}
```

##### 2.3 BYO 自动发现机制

三层发现机制，从被动到主动，适配不同接入深度：

**机制一：标签发现（Istio 式，推荐）**

用户在自己的 Deployment 上打标签，控制面的 DiscoveryController 自动发现：

```yaml
# 用户自己的 Deployment —— 只需加几个 label 和 annotation
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-support-agent
  namespace: production
  labels:
    agentscope.io/managed: "true"                  # 必须：开启发现
    agentscope.io/agent-name: "support-agent"      # 可选：指定 Agent CRD 名称
  annotations:
    agentscope.io/runtime: "agentscope-java"       # 可选：运行时类型
    agentscope.io/agent-port: "8080"               # 可选：数据面端口，默认 8080
    agentscope.io/contract-path: "/agentscope"     # 可选：契约 API 前缀
spec:
  replicas: 2
  selector:
    matchLabels:
      app: my-support-agent
  template:
    metadata:
      labels:
        app: my-support-agent
        agentscope.io/managed: "true"
    spec:
      containers:
        - name: agent
          image: registry.example.com/my-team/support-agent:v1.2.0   # 用户自己的镜像
          ports:
            - containerPort: 8080
```

也支持命名空间级别的自动发现（类似 Istio 的 namespace injection）：

```yaml
# 标记整个命名空间，该命名空间下所有带 agentscope.io/managed 标签的 Deployment 都被发现
apiVersion: v1
kind: Namespace
metadata:
  name: agent-workloads
  labels:
    agentscope.io/discovery: "enabled"       # 开启命名空间级发现
    agentscope.io/discovery: "enabled"        # 开启发现（自动探测契约 API）
```

**机制二：CLI 显式纳管（便捷命令）**

用户通过 CLI 将已有 Deployment 纳管。本质是帮用户打标签，触发机制一的标签发现：

```bash
agentscope-cli agent adopt \
  --deployment my-support-agent \
  --namespace production

# CLI 做的事：
# 1. 验证 Deployment 存在
# 2. 给 Deployment 打上 agentscope.io/managed=true 标签
# 3. 等待 DiscoveryController 完成发现 + 契约 API 探测
# 4. 输出创建的 Agent CRD 信息
#
# 等价于：kubectl label deployment my-support-agent agentscope.io/managed=true -n production
```

##### 2.4 DiscoveryController 设计

```
DiscoveryController
  │
  ├── Watch: Deployments with label agentscope.io/managed=true
  │          (跨命名空间，受 agentscope.io/discovery=enabled 的 namespace 范围约束)
  │
  ├── 发现新 Deployment：
  │   ├── 1. 从 label/annotation 提取 agent-name, runtime, port
  │   ├── 2. 等待至少一个 Pod Ready
  │   ├── 3. 调用 Pod 的 GET /agentscope/info
  │   │      ├── 成功 → 获取 agent 元数据和 contractLevel
  │   │      └── 失败/超时 → 使用 label/annotation 兜底，contractLevel=0
  │   ├── 4. 创建 Agent CRD (type: BYO (workloadRef))
  │   │      ├── metadata.ownerReferences → 不设置（Agent CRD 不拥有用户的 Deployment）
  │   │      ├── spec.type = BYO + spec.byo.workloadRef
  │   │      ├── spec.discovered.workloadRef = Deployment name
  │   │      ├── spec.discovered.contractLevel = 探测结果
  │   │      └── spec.agentConfig = 从 /agentscope/info 获取
  │   └── 5. 探测完成，Agent CRD 创建成功
  │
  ├── Deployment 变更（replicas, image 等）：
  │   └── 更新 Agent CRD status（replicas, endpoints, version）
  │
  ├── Deployment 删除：
  │   └── 更新 Agent CRD status.conditions → Ready=False, reason=WorkloadDeleted
  │       （不删除 Agent CRD，保留历史记录，可配置自动清理策略）
  │
  └── 定期健康探测（每 30s）：
      └── 对所有 BYO (workloadRef) Agent 的 Pod 调用 GET /agentscope/health
          └── 更新 Agent CRD status.conditions
```

##### 2.5 SDK 内置控制面客户端

数据面通过 SDK 内置的轻量客户端与控制面通信，无需额外 sidecar 容器。这比 Istio 的 sidecar 模式更适合 agent 场景——agent 的核心状态（session context、task list）在进程内存中，只有 agent 运行时自己能观察到。

**SDK 客户端职责：**

```
┌─────────────────────────────────────────────────┐
│              Agent 进程 (单容器)                   │
│                                                   │
│  ┌───────────────────┐   ┌─────────────────────┐ │
│  │  Agent Runtime     │   │  CP Client (SDK)    │ │
│  │  (业务逻辑)        │   │                     │ │
│  │                    │   │  - 暴露契约 API      │ │
│  │  session context ──┼──▶│  - gRPC/HTTP 上报    │ │
│  │  task list         │   │  - 监听配置变更      │ │
│  │  tool calls        │   │  - Prometheus /metrics│ │
│  │                    │   │  - Prometheus /metrics│ │
│  └───────────────────┘   └──────────┬──────────┘ │
│                                      │            │
└──────────────────────────────────────┼────────────┘
                                       │
                              HTTP POST/GET
                                       │
                                       ▼
                              ┌─────────────────┐
                              │  Control Plane   │
                              │  (agentscoped)   │
                              └─────────────────┘
```

**AgentScope Java SDK 集成方式：**

```java
// application.yml 配置即开启，零代码侵入
agentscope:
  controlplane:
    enabled: true
    endpoint: "http://agentscoped.agentscope-system.svc:8080"
    report-interval: 10s        # session 状态上报间隔
    grpc-endpoint: "agentscoped.agentscope-system.svc:15010"
```

SDK 内部自动完成：
1. **契约 HTTP API**：暴露 `/agentscope/info`、`/health`、`/metrics`（Level 1，供 DiscoveryController 探测和 Prometheus 采集）
2. **gRPC 连接**：建立到控制面的 ASDP 双向流（Level 2 — 配置推送、状态上报、session 指令、team 事件）
3. **断连处理**：gRPC 断开时用最后一份配置继续运行，后台自动重连（对标 Istio xDS 行为，不降级到 HTTP）

**BYO 用户接入方式：**

```xml
<!-- 引入 SDK 依赖即可获得全部能力 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-controlplane-client</artifactId>
    <version>0.1.0</version>
</dependency>
```

如果 BYO 用户不愿引入 SDK，只需自行实现 Level 1 契约 API（`/agentscope/info` + `/agentscope/health`），控制面通过 HTTP 轮询也能完成基础纳管。

**与 sidecar 方案的对比：**

| 维度 | SDK 内置（本方案） | Sidecar |
|---|---|---|
| 容器数 | 1（无额外开销） | 2（多一个 proxy 容器） |
| 状态可见性 | 完整（进程内观察） | 有限（只能从网络层推断） |
| 配置热更新 | ConfigMap watch + HTTP 轮询 | gRPC stream |
| 延迟 | 秒级（轮询间隔） | 亚秒级（stream push） |
| 侵入性 | 需引入 SDK 或实现契约 API | 零侵入 |
| 适用场景 | 绝大多数 agent 场景 | 完全不改代码的旧系统（未来可选） |

##### 2.6 CLI 工作流与 Push API（参考 Langsmith Managed Deep Agents）

参考 Langsmith 的 `deepagents init` → `deepagents deploy` 工作流，AgentScope 采用类似的项目结构和 CLI 体验。

**项目结构（参考 Langsmith 项目布局）：**

```
$ agentscope-cli init my-agent && cd my-agent
$ tree
my-agent/
├── agentscope.yaml                # Agent 元数据 + model + 部署配置
├── AGENTS.md                      # system prompt（类似 Langsmith AGENTS.md）
├── tools.yaml                     # MCP 工具配置（类似 Langsmith tools.json）
├── skills/                        # 可复用技能（类似 Langsmith skills/）
│   └── code-review/
│       ├── skill.yaml
│       └── review_prompt.md
├── subagents/                     # 子 agent 定义（类似 Langsmith subagents/）
│   └── researcher/
│       ├── agent.yaml
│       └── AGENTS.md
└── .gitignore
```

**agentscope.yaml（用户编辑的主配置）：**

```yaml
name: customer-support
displayName: "客服助手"
description: "处理客户咨询"

# model 直接内联配置（不需要引用外部 CRD 名字）
model:
  provider: dashscope
  modelId: qwen-max
  apiKeyFrom: env:DASHSCOPE_API_KEY      # CLI 从环境变量读取
  options:
    temperature: 0.7
    maxTokens: 4096

# system prompt 引用本地文件
systemPrompt: ./AGENTS.md

# 运行时
runtime: agentscope-java
stream: true
maxTurns: 50

# 部署配置
replicas: 3
resources:
  cpu: "2"
  memory: "4Gi"
```

**tools.yaml（MCP 工具配置）：**

```yaml
tools:
  - name: search_docs
    mcpServerUrl: https://mcp.internal.com/knowledge
    mcpServerName: knowledge-base
  - name: lookup_customer
    mcpServerUrl: https://crm.internal.com/mcp
    mcpServerName: crm

# 工具级别的人工审批（参考 Langsmith interrupt_config）
interruptConfig:
  "https://mcp.internal.com/knowledge::delete_doc": true
```

**CLI 操作流程：**

```bash
# 1. 初始化项目
agentscope-cli init my-agent

# 2. 编辑配置文件
vim agentscope.yaml AGENTS.md tools.yaml

# 3. 预览（参考 Langsmith --dry-run）
agentscope-cli deploy --dry-run
# 输出 push API 的 request body（JSON），不实际部署

# 4. 部署（幂等：首次创建，后续更新，每次产生新 revision）
agentscope-cli deploy --namespace production
# Agent "customer-support" deployed (revision: a3f8c1d2)
# Created: ModelConfig/customer-support-model
# Created: MCPServer/knowledge-base
# Created: MCPServer/crm
# Deployment: 3 replicas starting...

# 5. 查看状态
agentscope-cli agent list --all-namespaces
# NAMESPACE    NAME                TYPE          RUNTIME          REPLICAS  SESSIONS  REVISION
# production   customer-support    Declarative   agentscope-java  3/3       12        a3f8c1d2
# production   order-agent         BYO           agentscope-java  2/2       5         (probed)

# 6. 查看 revision 历史
agentscope-cli agent revisions customer-support -n production
# REVISION    CREATED              CHANGES
# a3f8c1d2   2026-06-27 11:30     Updated system prompt, added CRM tool
# 7b2e9a01   2026-06-27 10:00     Initial deploy

# 7. 回滚
agentscope-cli agent rollback customer-support --revision 7b2e9a01
```

**CLI deploy → REST API → CRD 分解流程：**

```
agentscope-cli deploy
  │
  ├── 1. 读取项目文件:
  │     agentscope.yaml → 元数据 + model + 部署
  │     AGENTS.md → system prompt 内容
  │     tools.yaml → MCP 工具配置
  │     skills/ → 打包上传到 OCI registry
  │
  ├── 2. 处理凭证:
  │     model.apiKeyFrom: env:DASHSCOPE_KEY → 读取环境变量值
  │     MCP auth tokens → 读取环境变量值
  │     全部内联到 request body（不发送环境变量名）
  │
  ├── 3. POST /api/v1/agents/{name}/push
  │     body 是自包含的 JSON（所有 secret 值和文件内容已内联）
  │
  └── 4. 控制面收到后自动分解:
        ├── model 字段 → Secret + ModelConfig CRD
        ├── tools 字段 → MCPServer CRD(s)（查找已有或创建新的）
        ├── 组装 → Agent CRD (type=Declarative)
        ├── reconcile → ConfigMap + Deployment + Service
        └── 返回 revision ID + 创建的资源列表
```

**Push API Request Body（参考 Langsmith CreateAgentRequest）：**

```json
// POST /api/v1/agents/customer-support/push
{
  "displayName": "客服助手",
  "description": "处理客户咨询",
  "runtime": "agentscope-java",

  "systemPrompt": "你是一个专业的客服助手...",

  "model": {
    "provider": "dashscope",
    "modelId": "qwen-max",
    "apiKey": "sk-xxxx",
    "options": { "temperature": 0.7, "maxTokens": 4096 }
  },

  "tools": {
    "tools": [
      { "name": "search_docs", "mcpServerUrl": "https://mcp.internal.com/knowledge",
        "mcpServerName": "knowledge-base" },
      { "name": "lookup_customer", "mcpServerUrl": "https://crm.internal.com/mcp",
        "mcpServerName": "crm" }
    ],
    "interruptConfig": {
      "https://mcp.internal.com/knowledge::delete_doc": true
    }
  },

  "skills": [
    { "type": "inline", "name": "code-review",
      "description": "Review code for quality", "instructions": "..." },
    { "type": "oci", "ref": "oci://registry.example.com/skills/planning:v1" }
  ],

  "deployment": {
    "replicas": 3,
    "resources": {
      "requests": { "cpu": "500m", "memory": "1Gi" },
      "limits":   { "cpu": "2",    "memory": "4Gi" }
    }
  },

  "extras": { "team": "customer-service", "owner": "zhangsan" }
}
```

**Push API Response（201 Created / 200 Updated）：**

```json
{
  "name": "customer-support",
  "namespace": "production",
  "type": "Declarative",
  "revision": "a3f8c1d2",
  "createdAt": "2026-06-27T10:00:00Z",
  "updatedAt": "2026-06-27T11:30:00Z",
  "status": {
    "phase": "Running",
    "replicas": { "desired": 3, "ready": 3 }
  },
  "createdResources": [
    { "kind": "Agent",      "name": "customer-support" },
    { "kind": "ModelConfig", "name": "customer-support-model" },
    { "kind": "MCPServer",   "name": "knowledge-base" },
    { "kind": "MCPServer",   "name": "crm" },
    { "kind": "Secret",      "name": "customer-support-model-key" }
  ]
}
```

**用户永远只接触 agentscope.yaml + AGENTS.md + tools.yaml。底层的 CRD 拆分是控制面内部的事。**

---

#### 三、CRD 资源定义

##### 3.1 Agent CRD — 核心资源（两种 type）

只有两种 type：**Declarative**（控制面管一切）和 **BYO**（用户自己的镜像或部署）。原先的 Discovered 合并入 BYO——通过 `image` vs `workloadRef` 二选一区分"控制面建 Deployment"和"控制面纳管已有 Deployment"。

**Declarative 模式示例（CLI deploy 生成）：**

```yaml
apiVersion: agentscope.io/v1alpha1
kind: Agent
metadata:
  name: customer-support-agent
  namespace: production
spec:
  type: Declarative               # Declarative | BYO
  runtime: agentscope-java        # agentscope-java | agentscope-go | custom（顶层，通用）
  displayName: "客服助手"
  description: "处理客户咨询的智能体"

  # ==========================================
  # Declarative 模式：控制面托管全生命周期
  # agentConfig 中的 model/tools 是控制面要下发给数据面的配置
  # ==========================================
  declarative:
    agentConfig:
      systemMessage: "你是一个专业的客服助手..."
      systemMessageFrom:
        kind: ConfigMap
        name: support-prompts
        key: system-prompt
      modelConfigRef: "customer-support-model"    # 引用 ModelConfig CRD（由 push API 自动创建）
      stream: true
      maxTurns: 50
    tools:
      - type: McpServer
        mcpServer:
          name: "knowledge-base"
          toolNames: ["search_docs", "get_faq"]
          requireApproval: ["delete_doc"]
      - type: Skill
        skill:
          name: "code-review"
          version: "v1.2"
    skills:
      refs: ["oci://registry.example.com/skills/support-knowledge:v1"]

    # Subagents（进程内委派，映射到 HarnessAgent 的 SubagentDeclaration）
    subagents:
      - name: security-scanner
        description: "Scan code for security vulnerabilities"
        model: "qwen-turbo"                       # 可选，默认继承 parent model
        instructions: "You are a security scanner..."
        tools: ["read_file", "grep_files"]         # 工具白名单过滤
        steps: 15                                   # 最大推理迭代次数
        workspaceMode: isolated                     # isolated | shared
      - name: remote-lint
        description: "External linting service"
        url: "http://lint-agent.tools.svc:8080"     # 远程模式：集群内其他 Agent

    # Team Templates（预设团队模板，运行时由 Lead LLM 实例化为 AgentTeam CRD）
    teamTemplates:
      - name: full-review
        description: "Complete review with security + perf + test experts"
        members:
          - role: security-reviewer
            agentRef: security-agent                # 引用集群中其他 Agent CRD
            prompt: "Focus on auth, injection, data exposure."
          - role: perf-reviewer
            agentRef: perf-agent
            prompt: "Focus on N+1 queries, memory leaks."
        dynamicMembers:
          enabled: true
          maxTotal: 5
        config:
          taskClaimStrategy: self-claim

    # 运行配置（常用字段，有默认值）
    replicas: 3
    resources:
      requests: { cpu: "500m", memory: "1Gi" }
      limits:   { cpu: "2",    memory: "4Gi" }
    env:
      - name: LOG_LEVEL
        value: "info"

    # 高级配置（大多数用户不碰）
    advanced:
      contextStrategy:
        triggerRatio: 0.8
        reserveRatio: 0.1
        compressionModel: "qwen-turbo-config"
      reactConfig:
        maxIters: 20
        stopOnReject: false
      labels: { team: "customer-service" }
      nodeSelector: {}
      tolerations: []

  # ==========================================
  # 通用可选配置
  # ==========================================
  sandbox:
    enabled: false
    templateRef: "python-sandbox-template"
    network:
      allowedDomains: ["*.internal.com"]
    lifecycle:
      shutdownPolicy: Delete
      idleTimeout: "30m"

  allowedNamespaces:
    from: Same

status:
  observedGeneration: 3
  revision: "a3f8c1d2"                 # 当前 revision（每次 push 递增）
  managementMode: "CP-Managed"
  conditions:
    - type: Accepted
      status: "True"
      reason: "ConfigValid"
    - type: Ready
      status: "True"
      reason: "DeploymentReady"
      message: "3/3 replicas available"
    - type: DataPlaneConnected
      status: "True"
      reason: "ContractLevel3Verified"
  replicas:
    desired: 3
    ready: 3
    available: 3
  activeSessions: 12

  # 从数据面 /agentscope/info 探测获取（不由用户声明）
  dataPlaneInfo:
    contractLevel: 3
    model: "qwen-max"
    modelProvider: "DashScope"
    tools: ["search_docs", "get_faq", "delete_doc"]
    sdkVersion: "0.8.0"
    version: "1.2.0"
    sessionAffinity: "instance"
    durability: "persistent"
    capabilities: ["session-reporting", "hot-reload", "context-compression"]
    lastProbeAt: "2026-06-27T10:30:00Z"

  endpoints:
    - ip: "10.0.1.5"
      port: 8080
    - ip: "10.0.1.6"
      port: 8080
```

**BYO 模式示例（用户提供镜像）：**

```yaml
apiVersion: agentscope.io/v1alpha1
kind: Agent
metadata:
  name: order-agent
  namespace: production
spec:
  type: BYO
  runtime: agentscope-java            # 或 custom（第三方产品）
  displayName: "订单处理"
  description: "处理订单查询与退款"

  byo:
    # --- 二选一（CRD validation: 必须且只能指定一个） ---

    # 方式 A: 给镜像 → 控制面创建 Deployment（ownerRef → Agent CRD，级联删除）
    image: "registry.example.com/my-team/order-agent:v1.2.0"
    command: ["java", "-jar", "agent.jar"]
    args: ["--port=8080"]

    # 方式 B: 给引用 → 控制面纳管已有 Deployment（不创建、不修改、不删除）
    # workloadRef:
    #   kind: Deployment
    #   name: "order-agent-deployment"

    # --- 镜像模式可选配置 ---
    replicas: 2
    resources:
      requests: { cpu: "1", memory: "2Gi" }
    env:
      - name: DB_HOST
        value: "postgres.internal"

    # --- workloadRef 模式可选配置 ---
    agentPort: 8080                    # 默认 8080
    contractPath: "/agentscope"        # 默认 /agentscope

    # --- 控制面追加的 MCP 绑定（可选，追加到镜像内置的 tools） ---
    overrides:
      tools:
        - type: McpServer
          mcpServer:
            name: "shared-knowledge-mcp"

    # --- 自定义健康检查（第三方运行时不一定是 /agentscope/health） ---
    healthProbe:
      httpGet: { path: /health, port: 8080 }

    advanced:
      labels: { team: "order-team" }

  # 不再有 agentMetadata ——
  # model、tools、contractLevel 全部从 /agentscope/info 探测获取，
  # 写入 status.dataPlaneInfo，用户不需要手动声明。

status:
  managementMode: "CP-Managed"         # image 方式
  # managementMode: "Adopted"          # workloadRef 方式
  conditions:
    - type: Ready
      status: "True"
    - type: DataPlaneConnected
      status: "True"
      reason: "ContractLevel3Verified"
  replicas:
    desired: 2
    ready: 2
  activeSessions: 5
  dataPlaneInfo:                        # 完全从探测获取
    contractLevel: 3
    model: "qwen-max"
    modelProvider: "DashScope"
    tools: ["query_order", "process_refund"]
    sdkVersion: "0.8.0"
    version: "1.3.0"
    sessionAffinity: "none"
    durability: "ephemeral"
    capabilities: ["session-reporting", "hot-reload"]
    lastProbeAt: "2026-06-27T10:35:00Z"
```

**CRD Validation 规则：**

```
type=Declarative:
  spec.declarative 必须存在 / spec.byo 必须为空

type=BYO:
  spec.byo 必须存在 / spec.declarative 必须为空
  spec.byo.image 和 spec.byo.workloadRef 二选一（互斥且必选其一）
  image 模式: replicas、resources 可选 → 控制面创建 Deployment
  workloadRef 模式: replicas、resources 不生效 → 控制面只读观测
```

##### 3.2 两种模式下控制面行为对比

```
                        Declarative          BYO (image)         BYO (workloadRef)
                        ──────────          ───────────         ─────────────────
谁创建 Deployment       控制面               控制面               用户已创建
Deployment owner        Agent CRD           Agent CRD           无 ownerRef
删除 Agent CRD 时       级联删除             级联删除             不删 Deployment
镜像来源                官方基础运行时镜像    用户指定             用户已部署
ConfigMap               控制面创建+管理      不管理               不管理
扩缩容                  控制面管 replicas     控制面管            只读观测
model/tools 来源        spec 声明(下发)      探测获取(status)    探测获取(status)
配置热更新              控制面推送           控制面推送(CL≥3)    控制面推送(CL≥3)
Session 观测            ✅                  ✅ (CL≥2)           ✅ (CL≥2)
MCP 追加                spec.tools          overrides.tools     overrides.tools
```

**设计说明：**
- Declarative 的 model/tools 在 spec 中声明，因为它们是控制面要**下发给**数据面的配置。
- BYO 的 model/tools 不在 spec 中声明，因为用户已在代码中配置。控制面通过探测 `/agentscope/info` 获取，写入 `status.dataPlaneInfo`。
- BYO (workloadRef) 下控制面不修改用户的 Deployment——不调 replicas、不改 image、不加 ownerRef。如果用户想让控制面管 Deployment，应给 image 而不是 workloadRef。
- DiscoveryController 自动创建的 Agent CRD 也是 `type: BYO` + `workloadRef`。

##### 3.3 Subagents 与 Team Templates 的关系

Agent CRD 中同时包含 `subagents` 和 `teamTemplates` 两个字段，它们解决不同层次的协作问题：

```
subagents（进程内委派）                     teamTemplates（跨 Agent 协作模板）
────────────────────                       ────────────────────────────────
定义在单个 Agent CRD 的 spec 中             定义在 Lead Agent CRD 的 spec 中
跑在同一个 HarnessAgent 进程内              跨 Pod、跨 Agent 类型
生命周期跟随 parent agent                   由 AgentTeam CRD 独立管理（TTL 清理）
通信: 进程内方法调用                         通信: 控制面 API 消息路由
映射: HarnessAgent SubagentDeclaration      映射: AgentTeam CRD（运行时实例化）
deploy 时: 写入 ConfigMap 文件树            deploy 时: 存入 Agent CRD spec（菜单）
运行时: LLM 直接委派                        运行时: LLM 引用模板名 → SDK 调控制面 API
适合: 轻量聚焦任务（扫描、校验、查询）       适合: 多专家协作（评审团队、调查小组）
```

**控制面分解逻辑：**

```
push API payload
  │
  ├── subagents[] → 写入 ConfigMap 文件树，由 HarnessAgent 加载
  │   ├── subagents/security-scanner/AGENTS.md    ← instructions
  │   ├── subagents/security-scanner/agent.json   ← {model, tools, steps}
  │   └── subagents/remote-lint/agent.json        ← {url, headers}
  │
  └── teamTemplates[] → 存入 Agent CRD spec.declarative.teamTemplates
      │   不在 deploy 时创建 AgentTeam CRD（只是预设菜单）
      │
      └── 运行时: Lead 的 LLM 说 "用 full-review 模板组建团队"
          → SDK 读取 template → POST /api/v1/teams（带模板填充）
          → 控制面创建 AgentTeam CRD → 在目标 Agent Pod 上创建 session
```

##### 3.4 ModelConfig CRD — 模型供应商配置

参考Kagent v1alpha2 ModelConfig，支持多供应商。

```yaml
apiVersion: agentscope.io/v1alpha1
kind: ModelConfig
metadata:
  name: qwen-max-config
spec:
  provider: DashScope     # DashScope | OpenAI | Anthropic | Gemini | Ollama | ...
  model: "qwen-max"
  apiKeySecret: "dashscope-credentials"
  apiKeySecretKey: "api-key"
  dashscope:
    baseUrl: "https://dashscope.aliyuncs.com"
    temperature: "0.7"
    maxTokens: 4096
    topP: "0.9"
  tls:
    disableVerify: false
    caCertSecretRef: ""
```

##### 3.5 MCPServer CRD — MCP 服务注册

参考Kagent RemoteMCPServer，扩展stdio和http两种传输。

```yaml
apiVersion: agentscope.io/v1alpha1
kind: MCPServer
metadata:
  name: knowledge-base-mcp
spec:
  description: "企业知识库MCP服务"
  type: Remote       # Remote | Stdio
  remote:
    protocol: STREAMABLE_HTTP
    url: "https://mcp.internal.com/knowledge"
    headersFrom:
      - kind: Secret
        name: mcp-auth
        key: token
        header: Authorization
    timeout: "30s"
  stdio:              # type=Stdio 时使用
    command: "python"
    args: ["-m", "knowledge_mcp_server"]
    env:
      DB_HOST: "postgres.internal"
  allowedNamespaces:
    from: All
status:
  conditions:
    - type: Accepted
      status: "True"
  discoveredTools:
    - name: search_docs
      description: "搜索知识库文档"
    - name: get_faq
      description: "获取FAQ答案"
```

##### 3.6 AgentSession — 会话管理（DB 存储，无 CRD）

AgentSession **不是 CRD**，控制面**不为会话创建任何 etcd 对象**。Session 随访问量无界增长、且状态每 10s 被 SessionReport 刷新，属于高基数 + 高 churn 的运行时流水（详见 3.8）。它的权威存储在 **PostgreSQL（`SessionStore`）**，路由索引在 Redis，第三方文件态 checkpoint 在对象存储；对外只通过 REST API（`/api/v1/agents/{name}/sessions/...`）暴露。

下面是 AgentSession 的**数据模型 / REST 响应结构**（等价于 `GET /api/v1/agents/{name}/sessions/{id}`），仅用于说明字段：

```json
{
  "sessionId": "session-abc123",
  "agentRef": "customer-support-agent",
  "instanceRef": "customer-support-agent-pod-0",
  "phase": "Active",                    // Active | Idle | Compressing | Terminated
  "startedAt": "2026-06-26T10:00:00Z",
  "lastActiveAt": "2026-06-26T10:35:00Z",
  "messageCount": 42,
  "tokenUsage": {
    "promptTokens": 15000,
    "completionTokens": 8000,
    "totalTokens": 23000
  },
  "state": {
    "summary": "用户咨询了订单退款流程，已完成退款申请提交...",
    "currentIter": 3,
    "tasks": [
      { "id": "task-1", "subject": "查询订单信息", "state": "completed" },
      { "id": "task-2", "subject": "提交退款申请", "state": "in_progress" }
    ],
    "contextPressure": { "usedTokens": 18000, "maxTokens": 32000, "ratio": 0.5625 }
  }
}
```

控制面下发的会话指令（compress / terminate）不落 etcd，而是经 ASDP gRPC `SessionCommand` 直接推给持有该 session 的实例（详见 6.1 / 6.4）。

##### 3.7 SandboxClaim CRD — 沙箱申请（CRD 存储）

控制面作为 broker 代理数据面的沙箱需求到 agent-sandbox。

> **存储模型（定论）**：SandboxClaim **是 CRD，存 etcd**。理由：一个沙箱就是一个 Pod，沙箱总数天然受**集群 Pod 容量**约束（etcd 本来就承载全集群的 Pod，量级完全在其舒适区），不存在 AgentSession 那种"随流量无界增长 + 10s 高频刷新"的问题；且 CRD 能用 `ownerReference` 对下游 agent-sandbox `Sandbox` 做级联删除，与 agent-sandbox 自身的 CRD 模型天然对齐。因此这里**不引入 DB**。

```yaml
apiVersion: agentscope.io/v1alpha1
kind: SandboxClaim
metadata:
  name: agent-support-sandbox-1
spec:
  agentRef:
    name: customer-support-agent
  sessionRef:
    name: session-abc123
  sandboxTemplate:
    # 直接映射到 agent-sandbox 的 SandboxSpec
    podTemplate:
      spec:
        containers:
          - name: sandbox
            image: "python:3.11-slim"
            resources:
              limits:
                cpu: "1"
                memory: "2Gi"
    lifecycle:
      shutdownPolicy: Delete
      idleTimeout: "30m"
    network:
      allowedDomains: ["pypi.org", "*.internal.com"]
status:
  phase: Bound         # Pending | Bound | Released
  sandboxRef:
    name: "sandbox-xyz789"     # agent-sandbox 创建的实际 Sandbox 资源名
    namespace: "agent-sandboxes"
  serviceFQDN: "sandbox-xyz789.agent-sandboxes.svc.cluster.local"
  conditions:
    - type: Ready
      status: "True"
```

##### 3.8 控制面存储架构（声明式核心 + 运行时状态服务）

前面几个 CRD 的"存储模型"注解共享同一条原则。这里统一说明。

**判据：数据的基数由什么驱动 + 写入频率。**

```
etcd（CRD）  ←  期望状态：基数 = #agent / #config（有界），低频变更
外部 DB      ←  观测态 + 运行时流水：基数 = 流量/会话/消息（无界），高频写
```

etcd 是为低基数、低变更的声明式配置设计的强一致存储，不是通用数据库：每次 update 生成新 revision（MVCC），高 churn 会拖垮 compaction/defrag；且控制面 CRD 通常与集群核心资源**共用同一个 etcd**，session/message 级别的写入会波及整个 kube-apiserver（爆炸半径）。因此控制面拆成两个平面：

```
AgentScope Control Plane
├── 声明式核心（controller-runtime，只碰 etcd CRD）
│     Agent / ModelConfig / MCPServer / AgentTeam(声明)
│     职责：reconcile 期望状态 → Deployment / Service / ConfigMap
│
└── 运行时状态服务（DB-backed，自有存储层 + REST API）
      ├── SessionStore        PostgreSQL: session 元数据 + state 快照
      ├── SessionMessageStore PostgreSQL: 对话 transcript（冷数据按 TTL 归档对象存储）
      ├── TeamTaskStore       PostgreSQL: tasks（version 列做乐观锁）
      ├── TeamMessageStore    PostgreSQL: 成员消息
      ├── RevisionStore       PostgreSQL: 配置 revision 快照
      └── RoutingIndex        Redis: session→实例/checkpoint 路由缓存（可重建）
```

每个数据实体只有**一个权威归属**，不存在同一份状态既建 CRD 又落 DB 的情况；对象存储仅承担两类明确职责——CR-001 第三方文件态 checkpoint、以及冷数据的 TTL 归档，不作为任何 store 的第二副本。

**数据实体归属一览（每项唯一归属）：**

| 数据实体 | 基数驱动 | 写入频率 | 唯一归属 |
|---|---|---|---|
| Agent `spec` / `status` | agent 数（有界） | 低 / 周期 | etcd（CRD） |
| ModelConfig / MCPServer | 配置数 | 极低 | etcd（CRD） |
| **SandboxClaim** | 沙箱数（≤ 集群 Pod 容量，有界） | 中 | etcd（CRD，ownerRef 级联删除） |
| AgentTeam `spec` + 粗粒度 `phase` | 团队数（有界） | create / 状态迁移 | etcd（CRD） |
| AgentTeam 运行时明细（members/tasks 状态） | 团队 × 事件 | 高 | DB（**不在 CRD status 里**） |
| **AgentSession** | 随流量增长 | 极高（10s） | DB（无 CRD） |
| **Team Tasks** | 随团队活动增长 | 高 | DB（version 列乐观锁） |
| **Team Messages** | 随流量增长 | 极高 | DB |
| **Agent Revisions** | 随 push 次数增长 | append-only | DB |
| **Session Messages** | 单会话内无界 | 高 | DB（冷数据归档对象存储） |

划分规则（决定性，无灰色地带）：**只要是声明式配置、或与某个 K8s 工作负载/Pod 一一对应（基数受集群容量约束）→ etcd/CRD；只要随流量无界增长或高频刷新 → DB。** 二者互斥，同一份数据不重复存两处。

**数据流改向：** 数据面通过 ASDP gRPC 上报的 `SessionReport` / `TeamEventReport` 直接落 DB 存储层，**不经过 etcd/API server**；北向 REST API 读写也走 DB 存储层；controller 只 watch etcd 上的声明式 CRD。

**关键设计点：**
- **乐观并发换实现不换语义**：task claim 用 DB `version` 列或 `SELECT ... FOR UPDATE`，冲突仍返回 409，对上层行为一致，不再依赖 etcd resourceVersion。
- **"watch" 换成事件总线**：Dashboard/内部消费者感知 DB 侧变化改用 PostgreSQL LISTEN/NOTIFY 或进程内事件总线，再驱动 ASDP gRPC 下推 + Dashboard SSE（复用既有 gRPC 双向流）。
- **跨平面引用靠 name/UID，不靠 ownerReference**：DB 行记 `agentRef=<Agent CRD name>`，但 etcd 级联 GC 管不到 DB。Agent CRD 删除时发事件让存储层清理/标记孤儿 session。清理靠**应用层 + TTL**。
- **读放大用缓存**：热路由索引（session→实例/checkpoint）放 Redis，DB 作权威源与重建来源。

**代价：** 控制面从无状态 controller 变为**有状态服务**，需要 DB 的 HA、备份、schema 迁移、连接池；DB 侧资源不提供 K8s 原生 watch/kubectl/RBAC，全部通过 REST API + 自建 authz 访问。换来的是 session/team/message 规模与集群 etcd 彻底解耦，保护整个集群的爆炸半径。

**非目标（明确不做）：** 不为 AgentSession / Team Tasks / Messages 提供 `kubectl` 原生资源，**不引入 aggregated apiserver / Kine**。这些运行时状态只走 REST API / CLI / Dashboard。此举避免"既有 CRD 视图又有 DB 存储"的双轨复杂度——系统尚在新开发阶段，无历史兼容包袱，直接选定 DB 单一路径。

---

#### 四、分布式 Agent Teams

AgentScope 数据面支持 agent teams 协作——多个 agent 组成团队共同完成任务。所有团队协调统一走控制面 API，不区分本地/分布式模式。原因：即使所有 teammate 在同一个 Pod 上，为了 crash recovery 和可观测性，team 状态也必须持久化到控制面。既然控制面已经持有完整状态，就没必要再维护一套本地协调路径——团队操作（task claim、消息传递）频率低（每分钟几次），API 调用的毫秒级延迟对比进程内微秒级完全无感知差异。

##### 4.1 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                        Control Plane                              │
│                                                                    │
│  ┌──────────────┐  ┌─────────────────┐  ┌──────────────────────┐ │
│  │ AgentTeam    │  │ Team Task Store  │  │ Team Message Router  │ │
│  │ Controller   │  │ (DB, version 锁) │  │ (gRPC 转发+DB 存储)  │ │
│  └──────┬───────┘  └───────┬─────────┘  └──────────┬───────────┘ │
│         │                  │                        │             │
│         │    ┌─────────────┴────────────────────────┴──────┐     │
│         │    │         Team Coordination API                │     │
│         │    │  POST /teams/{id}/tasks                      │     │
│         │    │  POST /teams/{id}/messages                   │     │
│         │    │  POST /teams/{id}/members                    │     │
│         │    │  GET  /teams/{id}/members                    │     │
│         │    └─────────────────────────────────────────────┘     │
└─────────┼────────────────────────────────────────────────────────┘
          │
    ┌─────┴──────────────────────────────────────┐
    │                                              │
    ▼                    ▼                         ▼
┌──────────┐      ┌──────────┐              ┌──────────┐
│ Agent-X  │      │ Agent-Y  │              │ Agent-Z  │
│ Pod-0    │      │ Pod-2    │              │ Pod-1    │
│          │      │          │              │          │
│ [Lead    │◀════▶│[Teammate │◀════════════▶│[Teammate │
│  session]│      │ A session│              │ B session│
│          │      │          │              │          │
└──────────┘      └──────────┘              └──────────┘
```

**核心设计原则：**
- **控制面是唯一协调中枢**：task store、消息路由、成员管理全部走控制面 API。不维护本地协调路径。
- **一个 teammate = 一个 session**：不存在"一个 teammate 多实例"。需要并行就 spawn 多个同类型 teammate。
- **Lead 异步驱动**：Lead spawn teammate 后不阻塞等待，通过事件通知感知 teammate 状态变化。
- **完全对等可见**：所有 teammate 能看到彼此、能互发消息、能看到共享任务列表。但 spawn/terminate/approve 是 Lead 特权。
- **静态声明 + 动态扩展**：CRD 可预设成员（适合 GitOps），Lead 也可运行时加人（适合探索）。
- **自动故障恢复**：teammate 或 Lead 的 Pod 故障后，控制面自动调度替代者到其他实例，注入前任工作成果，恢复执行。

##### 4.2 AgentTeam CRD

```yaml
apiVersion: agentscope.io/v1alpha1
kind: AgentTeam
metadata:
  name: pr-review-team
  namespace: production
spec:
  # 团队目标（注入所有成员的 system prompt）
  objective: "Review PR #142, covering security, performance, and test coverage"

  # Lead 定义
  lead:
    agentRef:
      name: senior-reviewer
    prompt: "You are the team lead. Coordinate the review, synthesize findings."

  # 静态成员（CRD 创建时即 spawn，可选，留空则完全由 Lead 动态组队）
  members:
    - name: security-reviewer
      agentRef:
        name: security-agent
      prompt: "Focus on security: auth, injection, data exposure."
      planApproval: false

    - name: perf-reviewer
      agentRef:
        name: perf-agent
      prompt: "Focus on performance: N+1 queries, memory leaks, hot paths."
      planApproval: true             # 需要 lead 审批计划后才能实施

  # 动态成员策略（Lead 运行时 spawn）
  dynamicMembers:
    enabled: true                    # 允许 Lead 运行时添加成员
    maxTotal: 8                      # 静态 + 动态总上限
    allowedAgentRefs:                # 可选：限制 Lead 能引用哪些 Agent 类型
      - name: security-agent
      - name: perf-agent
      - name: test-agent
      - name: general-reviewer
    # 留空 allowedAgentRefs = 允许引用任意 Agent

  # 共享上下文（所有成员可访问）
  sharedContext:
    configMapRef: "pr-142-context"

  # 故障恢复策略
  recovery:
    reschedulePolicy: Auto           # Auto | Manual | None
    # Auto:   控制面自动调度替代者到其他 Pod（默认）
    # Manual: 通知 Lead，由 Lead 决定是否恢复
    # None:   不恢复，标记 Lost 后由 Lead 处理剩余任务
    maxRestarts: 3                   # 单个成员最大重启次数，超过后标记 Failed
    restartBackoff: "30s"            # 连续重启间隔（避免快速崩溃循环）
    graceWindow: "10s"               # 故障检测后等待窗口（等原 Pod 自行恢复）

  # 生命周期
  lifecycle:
    maxDuration: "2h"                # 团队最长运行时间，超时自动终止
    ttlAfterCompleted: "1h"          # 完成后保留 1 小时供审计，然后自动删除 CRD
    ttlAfterFailed: "24h"            # 失败后保留 24 小时供排查

  # 团队级配置
  config:
    taskClaimStrategy: self-claim    # self-claim | lead-assign
    shutdownPolicy: lead-decides     # lead-decides | all-complete | timeout

# 存储模型：AgentTeam CRD 只存"声明（spec）+ 粗粒度生命周期"。成员/任务的
# 细粒度运行时态随团队事件高频变化，权威源在 DB，不回写 CRD status（详见 3.8）。
status:
  phase: Running                     # Pending | Running | Completed | Failed（低频写）
  startedAt: "2026-06-27T10:00:00Z"
  conditions:
    - type: Ready
      status: "True"
      reason: "AllMembersConnected"
```

成员与任务的细粒度运行时态**不在 CRD 里**，通过 REST API 从 DB 读取（`GET /api/v1/teams/{team}`、`GET /api/v1/teams/{team}/members`、`.../tasks`）。响应结构示例：

```json
{
  "lead": { "sessionId": "sess-lead-001", "instanceRef": "senior-reviewer-pod-0", "restartCount": 0 },
  "members": [
    { "name": "security-reviewer", "origin": "static", "agentRef": "security-agent",
      "sessionId": "sess-sec-002", "instanceRef": "security-agent-pod-3",
      "phase": "Working", "currentTask": "task-3",
      "restartCount": 1, "lastRestartAt": "2026-06-27T10:25:00Z", "lastRestartReason": "PodOOMKilled" },
    { "name": "arch-consultant", "origin": "dynamic", "agentRef": "general-reviewer",
      "sessionId": "sess-arch-001", "instanceRef": "general-reviewer-pod-0",
      "phase": "Working", "currentTask": "task-8",
      "restartCount": 0, "addedAt": "2026-06-27T10:15:00Z" }
  ],
  "tasks": { "total": 10, "pending": 3, "inProgress": 4, "completed": 3 }
}
```

**三种使用方式（静态/动态/混合）：**

| 方式 | spec.members | dynamicMembers.enabled | 适用场景 |
|---|---|---|---|
| 全静态 | 完整声明所有成员 | false | GitOps 流水线、可重复的标准流程 |
| 全动态 | 留空 `[]` | true | 临时调研、探索性任务（Lead 自主组队） |
| 混合（推荐） | 声明核心成员 | true | 保证基础团队到位，Lead 可按需加人 |

##### 4.3 Teammate 与 Agent 实例的关系

```
关键区分:

  Agent CRD (类型定义)              Teammate (团队角色)
  ─────────────────                ─────────────────
  可以多实例 (replicas=3)          永远是单 session
  是一个"工种"                     是一个"具体的人"
  定义能力和配置                    有自己的对话上下文、任务、状态

  例:
  Agent: security-agent (replicas=3)
    ├── Pod-0  ← 可能跑着别的 session
    ├── Pod-1  ← teammate "security-reviewer" 的 session 在这里
    └── Pod-2  ← 可能跑着别的 session

  如果 Lead 需要两个安全审计:
    spawn "security-reviewer-1" using security-agent → session on Pod-0
    spawn "security-reviewer-2" using security-agent → session on Pod-2
    两个 teammate，同一个 Agent 类型，各有独立 session 和上下文
```

Teammate session 天然遵循 `sessionAffinity=Instance` 模型——session 状态绑定在特定 Pod 上，控制面通过 session 归属索引定向路由消息和指令。

##### 4.4 异步生命周期与事件驱动

Lead spawn teammate 后不阻塞等待，通过事件流感知状态变化：

```
Lead session 运行中
  │
  ├── 调用: spawn teammate "security-reviewer"
  │     → SDK POST /teams/{id}/members → 控制面返回 202 Accepted
  │     → Lead 不阻塞，继续工作
  │
  ├── 继续: 创建 tasks、spawn 更多 teammates、自己做分析
  │
  │   ......（控制面异步创建 session、等 Pod Ready）......
  │
  ├── gRPC stream 推送事件:
  │     TeamEvent { type: MemberJoined, member: "security-reviewer" }
  │     → Lead 知道 teammate 就绪，可分配任务或等其自行 claim
  │
  ├── 后续事件流:
  │     MemberIdle       → teammate 做完当前任务，等待新任务
  │     PlanPending      → teammate 提交计划等审批
  │     TaskCompleted    → 某个任务被完成
  │     TaskUnblocked    → 依赖解除，任务可被 claim
  │     MemberFailed     → teammate session 异常（Pod 故障等）
  │
  └── Lead 的 LLM 根据事件决定下一步动作
```

**事件协议（扩展现有 gRPC ConfigPush）：**

```protobuf
message TeamEvent {
  string team_name = 1;
  string event_type = 2;
  // MemberJoined | MemberIdle | MemberFailed | MemberShutdown
  // PlanPending | PlanApproved | PlanRejected
  // TaskCreated | TaskClaimed | TaskCompleted | TaskUnblocked
  // MessageReceived
  string member_name = 3;
  string task_id = 4;
  bytes  payload = 5;           // 事件附加数据（JSON）
  int64  timestamp = 6;
}
```

所有团队成员（包括 Lead 和 teammates）都通过同一套事件流接收通知。

##### 4.5 对等通信

所有 teammate 可以看到彼此、直接互发消息。这是 Agent Teams 区别于 subagent 的关键。

```
                    ┌─────────────────┐
                    │  Control Plane   │
                    │  Message Router  │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
          ┌────────┐    ┌────────┐    ┌────────┐
          │ Lead   │◀──▶│Teammate│◀──▶│Teammate│
          │        │    │   A    │    │   B    │
          └────────┘    └────────┘    └────────┘

  所有成员都可以:
  ✅ 看到完整的成员列表（名字、角色、当前状态）
  ✅ 给任意成员发消息（by name）
  ✅ 看到共享任务列表（包括谁在做什么）
  ✅ claim 任何未被 claim 的任务

  Lead 特权:
  🔒 spawn 新成员
  🔒 终止成员
  🔒 审批成员的 plan
  🔒 结束团队
```

**每个 teammate session 启动时注入的 team context：**

```json
{
  "teamName": "pr-review-team",
  "objective": "Review PR #142",
  "myRole": "security-reviewer",
  "isLead": false,
  "members": [
    { "name": "lead",              "agentRef": "senior-reviewer", "status": "working" },
    { "name": "security-reviewer", "agentRef": "security-agent",  "status": "working" },
    { "name": "perf-reviewer",     "agentRef": "perf-agent",      "status": "idle" }
  ],
  "availableActions": [
    "listTasks", "claimTask", "completeTask",
    "sendMessage", "broadcastMessage", "listMembers"
  ]
}
```

成员列表变化时（加入/退出），控制面通过 gRPC stream 推送 `MemberJoined`/`MemberShutdown` 事件给**所有**成员。

**消息路由流程：**

```
security-reviewer 发现问题，想和 perf-reviewer 确认:
  │
  ├── 1. SDK 调用: sendMessage("perf-reviewer", "auth 模块用了 bcrypt, 100ms/call")
  │      → POST /api/v1/teams/pr-review-team/messages
  │
  ├── 2. 控制面查 perf-reviewer 的 session 位置:
  │      → AgentTeam.status → instanceRef → perf-agent-pod-1
  │
  ├── 3. 通过 perf-agent-pod-1 的 gRPC stream 推送 TeamEvent{MessageReceived}
  │
  └── 4. perf-reviewer 的 SDK 收到消息 → 注入到 session 对话上下文
        → perf-reviewer 的 LLM 据此调整分析
        → 可能回复: sendMessage("security-reviewer", "bcrypt 参数是默认 10 轮?")
```

##### 4.6 分布式任务列表

本地文件系统的任务列表（`~/.claude/tasks/`）替换为控制面 API + 乐观并发控制。

> **存储模型**：Task Store 存于**外部 DB（PostgreSQL）**，不是 etcd。claim/complete/unclaim 是高频写且频繁冲突重试，用 etcd 会造成 revision churn。乐观锁改用 **DB `version` 列**（或 `SELECT ... FOR UPDATE`），语义与之前的 resourceVersion 一致（冲突返回 409），但脱离 etcd（详见 3.8）。

```
                  ┌──────────────────────────────┐
                  │  Control Plane Task Store     │
                  │  (PostgreSQL, version 列乐观锁) │
                  │  task-1: completed             │
                  │  task-3: in_progress → sec     │── 乐观锁: version
                  │  task-4: pending               │
                  │  task-5: pending (blocked by 3)│
                  └──────────────┬─────────────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                   │
         security-reviewer  perf-reviewer       test-reviewer
         "claim task-4"     "也 claim task-4"
              │                  │
         claim 成功 (先到)    409 Conflict → 尝试 task-6
```

**任务 API：**

```
POST   /api/v1/teams/{team}/tasks                  # Lead 创建任务
GET    /api/v1/teams/{team}/tasks                  # 列出所有任务
GET    /api/v1/teams/{team}/tasks/{id}             # 任务详情
PUT    /api/v1/teams/{team}/tasks/{id}             # 更新任务
POST   /api/v1/teams/{team}/tasks/{id}/claim       # claim（乐观锁）
POST   /api/v1/teams/{team}/tasks/{id}/complete    # 标记完成
POST   /api/v1/teams/{team}/tasks/{id}/unclaim     # 释放
```

**Claim 乐观并发控制：**

```json
// POST /api/v1/teams/pr-review-team/tasks/task-4/claim
{
  "claimedBy": "security-reviewer",
  "version": 7                    // DB 行版本号（非 etcd resourceVersion）
}
// 成功 → 200, version 递增
// 冲突 → 409, 返回当前状态让成员选下一个
```

任务状态变更时，控制面推送 `TaskClaimed`/`TaskCompleted`/`TaskUnblocked` 事件给所有成员。

##### 4.7 动态成员管理

Lead 在运行时通过 API 添加/移除团队成员：

```
POST   /api/v1/teams/{team}/members               # 添加成员
DELETE /api/v1/teams/{team}/members/{name}         # 移除成员
GET    /api/v1/teams/{team}/members                # 列出成员
```

**添加流程：**

```
Lead 调用: spawn "arch-consultant" using general-reviewer
  │
  ├── 1. SDK POST /api/v1/teams/{team}/members
  │     { "name": "arch-consultant", "agentRef": "general-reviewer",
  │       "prompt": "Review architecture decisions..." }
  │
  ├── 2. TeamController 校验:
  │     ├── dynamicMembers.enabled == true?
  │     ├── 当前成员数 < maxTotal?
  │     ├── agentRef 在 allowedAgentRefs 内?（如有限制）
  │     └── 目标 Agent 有可用实例?
  │
  ├── 3. 在 general-reviewer 的某个 Pod 上创建 AgentSession:
  │     ├── 注入 team context（objective、成员列表、task API）
  │     └── 标记 teamRef + role
  │
  ├── 4. 更新 AgentTeam status.members[] += {origin: dynamic, ...}
  │
  └── 5. 推送 MemberJoined 事件给所有成员
```

##### 4.8 团队生命周期

```
AgentTeam CRD 创建
  │
  ├── 1. TeamController 为 Lead 创建 AgentSession
  ├── 2. 为每个静态 member 创建 AgentSession（并行）
  ├── 3. 初始化 Team Task Store
  ├── 4. 所有 session Ready → phase = Running
  │
  ├── 5. Lead session 收到 team context，开始协调
  │      → 创建 tasks → 成员 claim → 协作执行
  │      → 可选：Lead 运行时 spawn 动态成员
  │
  ├── 6. 结束条件:
  │      ├── lead-decides:  Lead 调用 completeTeam()
  │      ├── all-complete:  所有任务完成
  │      └── timeout:       超时自动结束
  │
  └── 7. 清理:
         ├── 终止所有成员 session
         ├── AgentTeam CRD phase = Completed（保留审计）
         ├── ttlAfterCompleted 到期后自动删除 CRD
         └── 任务列表保留（供回溯）
```

##### 4.9 故障恢复与自动调度

Teammate 或 Lead 的 Pod 故障后，控制面自动调度替代者恢复执行。

**Teammate 故障恢复流程：**

```
正常运行:
  security-reviewer → agent-y pod-2 (sess-sec-001)
                │
                ▼ Pod-2 故障（OOMKill / Node 下线 / 进程崩溃）

1. 故障检测（秒级）
   ├── gRPC stream 断开 → 控制面立即感知
   └── 或 health probe 连续失败（兜底）
                │
                ▼
2. 进入 graceWindow（默认 10s）
   等待原 Pod 自行恢复（K8s restart）
   ├── 原 session 重连 → 取消调度，恢复原 session ✅
   └── 超时未重连 → 继续 ↓
                │
                ▼
3. 标记 + 善后
   ├── security-reviewer: phase = Lost
   ├── 其正在执行的 task-3: state 回退为 pending（unclaim）
   └── 推送 TeamEvent{MemberLost} 给所有存活成员
                │
                ▼
4. 自动调度替代者（reschedulePolicy=Auto 时）
   ├── 检查 restartCount < maxRestarts ?
   │     超过 → 标记 Failed，不再恢复，通知 Lead
   ├── 等待 restartBackoff（默认 30s）
   ├── 查找 security-agent 的可用 Pod（避开已有 team session 的 Pod）
   └── 在 Pod-3 上创建新 session (sess-sec-002)
                │
                ▼
5. 状态注入（新 session 启动时）
   注入 team context + 恢复上下文:
   ├── 前任已完成的 task 及结果 → 不需要重做
   ├── 前任中断的 task → 标记为"需重新处理"
   ├── 近期消息历史 → 保持协作上下文
   └── 角色 prompt → 和前任相同
                │
                ▼
6. 更新状态 + 通知
   ├── AgentTeam status: security-reviewer →
   │     sessionId=sess-sec-002, instanceRef=pod-3, restartCount=1
   └── 推送 TeamEvent{MemberRecovered} 给所有成员
```

**恢复上下文数据结构（注入新 session）：**

```json
{
  "recoveryContext": {
    "previousSessionId": "sess-sec-001",
    "restartCount": 1,
    "completedTasks": [
      { "id": "task-1", "subject": "Check auth tokens",
        "result": "发现 JWT 未验证 exp 字段" },
      { "id": "task-2", "subject": "Check SQL injection",
        "result": "使用参数化查询，无风险" }
    ],
    "interruptedTask": {
      "id": "task-3",
      "subject": "Review CORS configuration",
      "note": "回退为 pending，需重新处理"
    },
    "recentMessages": [
      { "from": "perf-reviewer",
        "content": "auth 模块用了 bcrypt, 100ms/call",
        "timestamp": "2026-06-27T10:20:00Z" }
    ]
  }
}
```

**可恢复性矩阵：**

| 状态 | 可恢复 | 来源 |
|---|---|---|
| 已完成 task 及结果 | ✅ 完全 | 控制面 Task Store（DB） |
| 未完成 task | ✅ 回退为 pending | 控制面 Task Store（DB） |
| 成员列表 | ✅ 完全 | 控制面 AgentTeam 运行时态（DB） |
| 消息历史 | ✅ 恢复 | 控制面 Message Store（DB / 对象存储） |
| 前任对话上下文 | ⚠️ 部分（摘要） | 取决于 session context 持久化策略 |
| 前任 LLM 推理过程 | ❌ 丢失 | LLM 内部状态无法持久化 |

**Lead 故障的特殊处理：**

```
Lead 比普通 teammate 更关键，但故障处理流程相同:

Lead Pod 故障
  │
  ├── 控制面自动调度新 Lead session 到同 Agent 类型的其他 Pod
  ├── 注入完整团队状态（成员列表、所有任务、所有 findings）
  └── 新 Lead 继续协调

关键点: Lead 故障不阻塞 teammate 工作。
  ├── task claim 走控制面 API（不经过 Lead）→ teammate 继续 claim + 执行
  ├── 消息路由走控制面（不经过 Lead）→ teammate 间通信不中断
  └── Lead 只负责高层决策（创建任务、审批计划、综合结果）
      → 这些操作在 Lead 恢复后继续
```

##### 4.10 SDK Team Client 接口

数据面 SDK 新增 team 协作 API，作为 tool 暴露给 LLM：

```java
public interface TeamClient {

    // === 任务（所有成员可用） ===
    List<TeamTask> listTasks();
    TeamTask claimTask(String taskId);
    void completeTask(String taskId);

    // === 消息（所有成员可用） ===
    void sendMessage(String to, String content);
    void broadcastMessage(String content);
    List<TeamMember> listMembers();
    String myRole();

    // === Lead 特权 ===
    TeamTask createTask(String subject, String description);
    void spawnMember(String name, String agentRef, String prompt);
    void shutdownMember(String memberName);
    void approvePlan(String memberName);
    void rejectPlan(String memberName, String feedback);
    void completeTeam();
}
```

##### 4.11 与 Claude Code Agent Teams 的对比

| 维度 | Claude Code | AgentScope Control Plane |
|---|---|---|
| 部署模型 | 单机多进程 | 多 Pod 跨 Node |
| 成员类型 | 同一 Claude 实例 | 不同 Agent CRD（不同能力/模型） |
| 任务存储 | 本地文件 `~/.claude/tasks/` | 控制面 DB（PostgreSQL） |
| 任务 claim | 文件锁 | 乐观并发（DB version 列） |
| 消息传递 | 本地 mailbox | gRPC stream 转发 + Team HTTP API（DB 存储） |
| 成员发现 | 本地 `config.json` | AgentTeam 运行时态（DB）+ session 索引 |
| 成员可见性 | 完全对等 | 完全对等（通过控制面路由） |
| Lead 选举 | 固定（主 session） | 固定（CRD 声明） |
| 动态组队 | Lead 自主 spawn | Lead 通过 API spawn（控制面校验） |
| 故障处理 | 进程挂了就没了 | 自动调度替代者 + 注入前任工作成果恢复执行 |
| Crash recovery | 无 | graceWindow 等待 → 自动调度 → 状态注入 |
| CRD 生命周期 | 无（纯运行时） | TTL 自动清理（completed 1h / failed 24h） |
| 团队定义 | 纯运行时 | CRD 声明（静态/动态/混合）|

##### 4.12 Store-backed Teams（Claude 对等落地，当前主路径）

CRD 仍可作为 GitOps 投影；**权威运行时态在 DB store**（`teams` / `team_members` / tasks / messages）。Standalone 无 kube 也可 `POST /api/v1/teams`。

**双入口、一套语义：**

- 入口 A：Console Teams 区（`/teams`）或 REST 编排
- 入口 B：`HarnessAgent.teamsMode(TeamClient, TeamContext)`（本地 `LocalTeamClient` 或 `ControlPlaneTeamClient`）

**任务板：** Lead `assign` 与 worker `self-claim` 并存（`owner==''` 或 assignee 开始）；乐观 `version`。

**激活：**

| 成员 | Session id | 起跑 |
|---|---|---|
| Managed | product `find-or-create`（`externalKey=team\|{ns}/{team}\|{member}`） | resolve 注入 `teamContext` + `POST /api/sessions/{id}/events` |
| BYO | CP UUID | ASDP / HTTP `team_join` → `registerExternalSession` → wakeup |

**人机对话：** 复用 Managed session 的 events + SSE；BYO 在 Console **只观测**（不做 `session-input`）。中间产物走共享 workspace / 对象存储引用，mailbox 只传短文本 + refs。

样例与脚本见 [`examples/agentteam`](../../../examples/agentteam/)。

---

#### 五、Controller 设计

##### 5.1 Controller 清单

| Controller | Watch 资源 | 职责 |
|---|---|---|
| `AgentController` | Agent (Declarative/BYO) | 创建/更新 Deployment、Service、ConfigMap；维护 status |
| `DiscoveryController` | Deployment (label: agentscope.io/managed) | 发现带标签的工作负载，探测数据面契约 API，自动创建 BYO Agent CRD (workloadRef) |
| `BYOWorkloadController` | Agent (BYO + workloadRef) | 观测已纳管的外部 Deployment 状态，定期探测契约 API，同步 status；下发 overrides |
| `AgentTeamController` | AgentTeam | 创建 Lead/Member session；管理团队生命周期（启动/结束/TTL 清理）；维护 task store 和 message router；成员故障检测与自动调度恢复 |
| `ModelConfigController` | ModelConfig | 验证 provider 配置，检测 Secret 变更，更新 secretHash，通知依赖的 Agent 滚动更新 |
| `MCPServerController` | MCPServer | 验证连通性，发现工具列表写入 status.discoveredTools |
| `SessionService`（非 controller） | — | 运行时状态服务：接收 ASDP SessionReport 落 SessionStore(DB)，下发 compress/terminate，不 watch etcd（详见 3.8） |
| `SandboxBrokerController` | SandboxClaim | 翻译 SandboxClaim 到 agent-sandbox 的 Sandbox CRD，跟踪生命周期 |

##### 5.2 Reconcile 关键流程

**AgentController — Declarative/BYO 模式：**

```
Watch(Agent where type in {Declarative, BYO})
  │
  ├── [Declarative] 分支:
  │   ├── 1. 解析 spec.declarative.agentConfig，验证 ModelConfig、MCPServer 引用
  │   ├── 2. 通过 DataPlaneAdapter(runtime) 构建 ConfigMap
  │   │      → adapter.BuildConfigMap(agent, resolvedTools) → agent-config.json
  │   ├── 3. 通过 DataPlaneAdapter(runtime) 构建 Deployment
  │   │      → adapter.BuildDeployment(agent)
  │   │      → 主容器: 控制面提供的基础运行时镜像
  │   │      → 挂载: agent-config.json, model credentials
  │   │      → 环境变量注入控制面地址（供 SDK 客户端连接）
  │   │      → ownerReference → Agent CRD（级联删除）
  │   ├── 4. 创建 Service（ClusterIP）
  │   └── 5. spec 变更时触发配置热更新（见五）
  │
  ├── [BYO] 分支:
  │   ├── 1. 构建 Deployment
  │   │      → 主容器: spec.byo.image（用户提供的镜像）
  │   │      → 可选挂载: 用户指定的 ConfigMap/Secret
  │   │      → ownerReference → Agent CRD（级联删除）
  │   ├── 2. 创建 Service
  │   └── 3. 等 Pod Ready 后探测 /agentscope/info，更新 contractLevel
  │
  └── [通用] 更新 Agent.status:
      ├── conditions: Accepted, Ready
      ├── replicas: 从 Deployment status 获取
      ├── contractLevel: 从数据面探测获取
      ├── dataPlaneVersion / sdkVersion: 从 /agentscope/info 获取
      ├── activeSessions: 通过 AgentSession 列表聚合
      └── endpoints: 从 Endpoints/EndpointSlice 获取
```

**DiscoveryController — 自动发现流程：**

```
Watch(Deployments with label agentscope.io/managed=true)
  │
  ├── 新 Deployment 出现:
  │   ├── 1. 检查是否已有对应的 Agent CRD
  │   │      → 有则跳过（避免重复创建）
  │   ├── 2. 从 labels/annotations 提取元数据:
  │   │      agent-name, runtime, agent-port, contract-path
  │   ├── 3. 等待至少 1 个 Pod Ready
  │   ├── 4. 探测数据面契约 API:
  │   │      GET http://<pod-ip>:<port>/agentscope/info
  │   │      ├── 成功 → 解析 contractLevel、capabilities、agentConfig
  │   │      └── 超时/失败 → contractLevel=0, 仅用 label 元数据
  │   ├── 5. 创建 Agent CRD:
  │   │      type: BYO (workloadRef)
  │   │      spec.discovered.workloadRef → Deployment
  │   │      spec.discovered.contractLevel → 探测结果
  │   │      metadata.annotations: discovered-at, discovered-by
  │   │      注意：不设置 ownerReference（不拥有用户的 Deployment）
  │   └── 6. 等 Pod Ready 后探测 /agentscope/info，更新 contractLevel
  │
  ├── Deployment 更新 (image/replicas 变更):
  │   └── 触发对应 Agent CRD 的 status reconcile
  │
  └── Deployment 删除:
      └── 更新 Agent CRD status:
          conditions[Ready] = False, reason=WorkloadDeleted
          （保留 Agent CRD 用于历史审计，可配置自动清理策略）
```

**BYOWorkloadController — 持续纳管（workloadRef 模式）：**

```
Watch(Agent where type=BYO and workloadRef is set)
  │
  ├── 定期探测 (30s 间隔):
  │   ├── 遍历 workloadRef 引用的 Deployment 的所有 Ready Pod
  │   ├── GET /agentscope/health → 更新 conditions[DataPlaneConnected]
  │   ├── GET /agentscope/sessions → 更新 status.activeSessions
  │   └── contractLevel 变化时更新 status.contractLevel
  │
  ├── spec.discovered.overrides 变更:
  │   ├── contractLevel >= 3:
  │   │   └── POST /agentscope/config/reload → 推送 overrides 到数据面
  │   └── contractLevel < 3:
  │       └── 记录 event: "数据面不支持热更新，overrides 将在下次重启后生效"
  │
  └── 更新 Agent.status:
      ├── replicas: 只读取 Deployment.status（不修改 Deployment）
      ├── dataPlaneVersion / sdkVersion: 从探测结果更新
      └── managementMode: "Adopted"
```

**SandboxBrokerController 核心 reconcile 逻辑：**

```
Watch(SandboxClaim)
  ├── 1. 验证 agentRef、sessionRef 合法
  ├── 2. 检查是否已有绑定的 Sandbox
  ├── 3. 没有 → 创建 agent-sandbox 的 Sandbox CRD
  │     ├── 映射 SandboxClaim.spec.sandboxTemplate → Sandbox.spec.podTemplate
  │     ├── 设置 ownerReference 指向 SandboxClaim（级联删除）
  │     └── 标签: agentscope.io/agent=<name>, agentscope.io/session=<session>
  ├── 4. 监听 Sandbox.status.conditions
  │     └── Ready=True → 更新 SandboxClaim.status.phase=Bound, 填充 sandboxRef
  └── 5. Session 终止或 SandboxClaim 删除 → Sandbox 通过 ownerReference 级联清理
```

---

#### 六、数据面协调机制

这是本项目与 Kagent 的核心差异。Kagent 是静态的"部署到运行"模式，而 AgentScope Control Plane 要做到运行时协调。

##### 6.1 ASDP 协议设计（AgentScope Data Plane Protocol）

参考 Istio xDS 的「统一信封 + 类型区分 + 单流复用」模式，但不设 HTTP 兜底通道。对标 Istio：xDS stream 断开时 Envoy 用最后一份配置继续运行并自动重连，不降级到 HTTP。

**三个通道及职责划分：**

```
┌─────────────────────────────────────────────────────────────────────┐
│                AgentScope Data Plane Protocol (ASDP)                 │
│                                                                       │
│  通道 1: gRPC 双向流（唯一的配置/状态通道，对标 xDS ADS）           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  service AgentDataPlaneService {                               │  │
│  │    rpc Connect(stream Upstream) returns (stream Downstream);   │  │
│  │  }                                                             │  │
│  │  CP → DP: ConfigPush, SessionCommand, TeamEvent                │  │
│  │  DP → CP: ConnectRequest, ConfigAck, SessionReport,            │  │
│  │           TeamEventReport                                      │  │
│  │  断连时: 数据面用最后一份配置继续运行, 后台自动重连             │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  通道 2: 契约 HTTP API（仅探测 + metrics，不做配置/指令）           │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  GET /agentscope/info       ← DiscoveryController 发现时必需  │  │
│  │  GET /agentscope/health     ← K8s probe + 健康探测             │  │
│  │  GET /agentscope/metrics    ← Prometheus scrape                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  通道 3: 控制面 HTTP API（仅需要 req/resp 语义的操作）              │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  Team:    POST /teams/*/tasks/*/claim     (需要 409 冲突响应) │  │
│  │           POST /teams/*/messages          (需要送达确认)      │  │
│  │           POST /teams/*/members           (需要调度结果)      │  │
│  │  Sandbox: POST /internal/sandbox/request  (需要地址返回)      │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘

不存在的通道（已删除）:
  ❌ HTTP 兜底配置拉取 (/internal/config/{agent})
  ❌ HTTP 兜底状态上报 (/internal/sessions/report)
  ❌ HTTP 兜底指令 (/agentscope/config/reload, /compress, /terminate)
```

**数据面能力等级（从 3 级简化为 2 级）：**

| Level | 要求 | 控制面可做 |
|---|---|---|
| Level 1: HTTP only | 暴露 `/agentscope/info` + `/health` + `/metrics` | 发现、健康监测、Prometheus 采集 |
| Level 2: gRPC connected | SDK 建立 gRPC stream 到控制面 | 全功能：配置推送 + 状态上报 + session 管控 + team 协作 |

Level 1 是第三方 agent（无 SDK）的最低接入标准。AgentScope 官方 SDK 自动提供 Level 2。

**gRPC 双向流协议（通道 1 详细定义）：**

```protobuf
syntax = "proto3";
package agentscope.protocol.v1;

service AgentDataPlaneService {
  // 唯一的 RPC：双向流，复用传输所有类型的消息（对标 xDS ADS）
  rpc Connect(stream Upstream) returns (stream Downstream);
}

// ═══════════ 数据面 → 控制面 (Upstream) ═══════════

message Upstream {
  UpstreamMeta meta = 1;
  oneof payload {
    ConnectRequest   connect        = 10;  // 握手
    ConfigAck        config_ack     = 11;  // 配置 ACK/NACK（对标 xDS DiscoveryRequest）
    SessionReport    session_report = 12;  // Session 状态上报
    TeamEventReport  team_event     = 13;  // Team 事件上报
  }
}

message UpstreamMeta {
  string agent_name = 1;       // Agent CRD name
  string instance_id = 2;      // Pod name
  string namespace = 3;
  int64  timestamp = 4;
}

// 握手（连接建立时发送，对标 xDS 首次 DiscoveryRequest）
message ConnectRequest {
  string runtime = 1;                    // agentscope-java | agentscope-go | custom
  string sdk_version = 2;
  repeated string capabilities = 3;      // session-reporting, hot-reload, ...
  string session_affinity = 4;           // none | instance
  string durability = 5;                 // ephemeral | persistent
  string state_location = 6;             // durability=persistent 时的 checkpoint 存储位置
}

// 配置确认（对标 xDS ACK/NACK）
message ConfigAck {
  string config_type = 1;     // AgentConfig | ToolConfig | SkillConfig | ...
  string version = 2;         // 确认的版本号
  string nonce = 3;           // 对应 Downstream ConfigPush 的 nonce
  bool   accepted = 4;        // true=ACK, false=NACK
  string reject_reason = 5;   // NACK 时的原因
}

// Session 状态上报（定时批量，如每 10s）
message SessionReport {
  repeated SessionSnapshot sessions = 1;
}

message SessionSnapshot {
  string session_id = 1;
  string phase = 2;            // Active | Idle | Compressing | Terminated
  int32  message_count = 3;
  int64  prompt_tokens = 4;
  int64  completion_tokens = 5;
  double context_pressure = 6;
  TaskSummary task_summary = 7;
  string team_id = 8;          // 如果此 session 参与了 team
  string team_role = 9;
  string state_location = 10;  // durability=persistent 时的 checkpoint 存储位置
  string checkpoint_version = 11;  // 最近同步的 checkpoint 版本，用于恢复取数
}

message TaskSummary {
  int32 total = 1;
  int32 pending = 2;
  int32 in_progress = 3;
  int32 completed = 4;
}

// Team 事件上报
message TeamEventReport {
  string team_id = 1;
  string event_type = 2;       // TaskClaimed | TaskCompleted | MessageSent | ...
  string member_name = 3;
  string task_id = 4;
  bytes  detail = 5;           // JSON
}

// ═══════════ 控制面 → 数据面 (Downstream) ═══════════

message Downstream {
  oneof payload {
    ConnectResponse  connect_ack = 10;  // 握手响应
    ConfigPush       config_push = 11;  // 配置推送（对标 xDS DiscoveryResponse）
    SessionCommand   session_cmd = 12;  // Session 指令
    TeamEvent        team_event  = 13;  // Team 事件通知
  }
}

message ConnectResponse {
  bool   accepted = 1;
  string reject_reason = 2;
  string control_plane_version = 3;
}

// 配置推送（对标 xDS DiscoveryResponse）
message ConfigPush {
  string config_type = 1;     // 资源类型（对标 xDS type_url）
  string version = 2;         // 配置版本
  bytes  resources = 3;       // JSON 序列化的配置体
  string nonce = 4;           // 唯一标识，用于 ACK/NACK 关联
}

message SessionCommand {
  string session_id = 1;
  string command = 2;          // compress | terminate
  bytes  params = 3;           // 可选参数 JSON
}

message TeamEvent {
  string team_id = 1;
  string event_type = 2;       // MemberJoined | MemberLost | TaskCompleted | MessageReceived | ...
  string member_name = 3;
  string task_id = 4;
  bytes  payload = 5;          // JSON
  int64  timestamp = 6;
}
```

**ConfigType 资源类型清单（对标 xDS LDS/RDS/CDS/EDS）：**

| config_type | 内容 | CRD 来源 | 推送时机 |
|---|---|---|---|
| `AgentConfig` | systemMessage, model, maxTurns, contextStrategy, reactConfig | Agent CRD spec.declarative | Agent spec 变更 |
| `ToolConfig` | MCP server 列表、工具过滤、requireApproval 规则 | Agent tools + MCPServer CRD | tools 或 MCPServer 变更 |
| `SkillConfig` | skill 列表、OCI refs | Agent skills | skills 变更 |
| `OverrideConfig` | BYO 模式下控制面追加的 MCP/tool 绑定 | Agent byo.overrides | overrides 变更 |

**连接生命周期与断连行为（对标 Istio xDS）：**

```
Pod 启动
  │
  ├── 1. SDK 暴露契约 HTTP API (通道 2: /info, /health, /metrics)
  │
  ├── 2. SDK 建立 gRPC stream → ConnectRequest 握手 → ConnectResponse
  │     → 控制面推送全量配置快照 (ConfigPush × N)
  │     → 数据面逐个 ACK
  │     → 进入稳态: 增量推送 + 定时上报
  │
  │  ═══════ 稳态运行 ═══════
  │
  ├── 3. CRD 变更 → 控制面增量 ConfigPush → 数据面 ACK/NACK
  ├── 4. 数据面定时上报 SessionReport (每 10s)
  ├── 5. 控制面下发 SessionCommand (compress/terminate)
  ├── 6. Team 事件双向传输 (TeamEvent / TeamEventReport)
  │
  │  ═══════ 断连 (对标 Istio: 不降级到 HTTP) ═══════
  │
  ├── 7. gRPC stream 断开
  │     ├── 配置: 继续使用最后收到的配置运行
  │     ├── Session 上报: 暂停（控制面通过 health probe 感知）
  │     ├── Team HTTP 操作: 不受影响（独立通道）
  │     └── SDK 后台重连: 指数退避 (1s, 2s, 4s, 8s, max 30s)
  │
  └── 8. 重连成功 → 重复步骤 2 (握手 + 全量推送)
```

**与 xDS 的概念映射：**

| xDS 概念 | ASDP 对应 | 说明 |
|---|---|---|
| ADS (Aggregated Discovery) | `Connect` stream | 一条流传输所有消息类型 |
| DiscoveryRequest | `Upstream.config_ack` | ACK/NACK |
| DiscoveryResponse | `Downstream.config_push` | 配置推送 |
| type_url | `config_type` | 资源类型标识 |
| version_info + nonce | `version` + `nonce` | 版本跟踪 + ACK 关联 |
| LDS / RDS / CDS / EDS | AgentConfig / ToolConfig / SkillConfig | 按资源分类 |
| 无对应 | `SessionReport` | Istio 不上报业务状态 |
| 无对应 | `SessionCommand` | Istio 不下发运行时指令 |
| 无对应 | `TeamEvent` | Istio 无协作概念 |
| HTTP fallback | **无** | 和 Istio 一致，不降级 |

##### 6.2 数据面适配器接口（DataPlaneAdapter）

为支持多种数据面运行时，定义两层接口：

```go
// DataPlaneAdapter 用于 Declarative/BYO 模式，控制面据此构建 Kubernetes 资源。
// 不同运行时的镜像、端口、配置格式、探针各不相同。
type DataPlaneAdapter interface {
    // RuntimeName 返回数据面标识，如 "agentscope-java", "agentscope-go", "langchain"
    RuntimeName() string

    // BuildDeployment 根据 Agent CRD spec 构建 Kubernetes Deployment。
    BuildDeployment(agent *v1alpha1.Agent) (*appsv1.Deployment, error)

    // BuildConfigMap 将 Agent spec 翻译成数据面可消费的配置格式。
    // AgentScope Java 期望特定的 JSON 格式,
    // Langchain 期望 Python config 格式等。
    BuildConfigMap(agent *v1alpha1.Agent, tools []ToolConfig) (*corev1.ConfigMap, error)

    // HealthProbe 返回数据面的健康检查探针配置。
    HealthProbe() *corev1.Probe

    // SupportsFeature 查询数据面是否支持特定特性。
    SupportsFeature(feature string) bool
}

// DataPlaneProber 封装对数据面 Level 1 契约 HTTP API 的调用。
// 仅用于 DiscoveryController 的初始探测和定期健康检查。
// Session 管控、配置推送等操作全部走 gRPC stream (通道 1)，不在此接口中。
type DataPlaneProber interface {
    // ProbeInfo 调用 GET /agentscope/info，获取数据面元数据。
    ProbeInfo(ctx context.Context, endpoint string) (*DataPlaneInfo, error)

    // ProbeHealth 调用 GET /agentscope/health。
    ProbeHealth(ctx context.Context, endpoint string) (bool, error)
}
```

**两层接口的关系：**

```
                      Declarative / BYO (image)       BYO (workloadRef)
                      ─────────────────────          ─────────────────
构建 K8s 资源          DataPlaneAdapter               不需要（用户自己部署）
初始探测 (HTTP L1)     DataPlaneProber                 DataPlaneProber
运行时交互 (gRPC L2)   gRPC stream (SDK 自动建连)      gRPC stream (SDK 自动建连)
```

第一期实现 `AgentScopeJavaAdapter` + 通用 `HTTPDataPlaneProber`。
`HTTPDataPlaneProber` 是运行时无关的——任何实现了契约 API 的数据面都可以被探测和管理。

**未来展望：Sidecar 模式（用于第三方 Agent 应用）**

当前阶段数据面通过 SDK 内置客户端与控制面通信（Level 2 gRPC）。但对于 Claude Code CLI、Codex CLI 等第三方 agent 应用，无法修改其二进制来嵌入 SDK。这类场景下 sidecar 是唯一可行的桥接方案：

```
第三方 Agent 场景（未来支持）:

┌───────────────────────────────────────────────┐
│  Pod                                            │
│  ┌────────────────┐  ┌──────────────────────┐ │
│  │ Claude Code    │  │ agentscope sidecar   │ │
│  │ CLI (主容器)    │  │                      │ │
│  │                │  │ 观测: 读 session 文件 │ │
│  │ 自有 session   │  │  → 转换 SessionReport │ │
│  │ 本地文件存储 ──┼─▶│  → gRPC stream        │──── 控制面
│  │ ~/.claude/     │  │  → 暴露契约 API       │ │
│  │  (共享卷)      │  │ 持久化: fsnotify 增量  │ │
│  │                │◀─┼──  同步 → 对象存储     │──── S3/OSS/MinIO
│  │ 不感知控制面   │  │  → 上报 checkpoint 位置│ │
│  └────────────────┘  └──────────────────────┘ │
│  ┌────────────────────────────────────────────┐│
│  │ initContainer: 启动前按 sessionId 从对象      ││
│  │ 存储拉回 .claude/ → 主容器 --resume 续上     ││
│  └────────────────────────────────────────────┘│
└───────────────────────────────────────────────┘

Sidecar 职责:
  观测（对标 SDK 的状态可见性）:
  - 监听主容器的本地 session 文件变更（fsnotify）
  - 将第三方格式的 session 状态转换为 ASDP SessionReport
  - 建立 gRPC stream 到控制面（代替 SDK）
  - 暴露 /agentscope/info、/health（代替 SDK 的契约 API）
  - 可选: 接收控制面指令（如 compress/terminate）并翻译为第三方 agent 能理解的操作

  持久化（Instance 模式 durability=Persistent，详见 6.4）:
  - 主容器与 sidecar 通过共享卷（emptyDir）挂载 session 目录（如 ~/.claude/）
  - fsnotify 监听变更 → 增量同步到对象存储，object key 携带 sessionId
  - 通过 SessionReport 上报 session → checkpoint 存储位置（而非 session → podIP）
  - Pod 重建时由 initContainer 先从对象存储拉回 session 目录，再启动主容器

与 SDK 方案的区别:
  SDK:     嵌入 agent 进程内，直接访问 session 内存 → 自有 agent 使用
  Sidecar: 独立容器，通过文件系统/API 间接观测 → 第三方 agent 使用
```

**Session 状态持久化与恢复（方案 B — 对象存储备份/恢复）**

第三方 CLI（Claude Code / Codex 等）的 session 不是内存态，而是**持久化在本地目录的文件态**（Claude CLI 在 `~/.claude/projects/<hash>/*.jsonl` 保存完整对话历史，`claude --resume <session-id>` 即可续上）。因此 Pod 销毁时真正会丢的不是"session 逻辑",而是承载它的那块磁盘。sidecar 方案把状态持久化到集群外对象存储，使 session 可跨节点恢复：

```
持续备份:
  主容器写 ~/.claude/  →  sidecar fsnotify 感知变更
                       →  增量上传对象存储 (key: <agent>/<sessionId>/...)
                       →  SessionReport 上报 checkpoint 版本 + 存储位置

Pod 销毁 / 重调度 (任意节点):
  1. 控制面查 AgentSession.status 得到该 session 的 checkpoint 位置
  2. 新 Pod 的 initContainer 按 sessionId 从对象存储拉回 .claude/
  3. gate: 确认恢复完成后才启动主容器
  4. 主容器 claude --resume <sessionId> → 从最近 checkpoint 续上
```

与方案 A（StatefulSet + 每实例 PVC）的取舍：

| 维度 | 方案 A: StatefulSet + PVC | 方案 B: Sidecar + 对象存储（本方案） |
|---|---|---|
| 跨节点/zone 恢复 | 受卷的 zone 亲和约束 | ✅ 不绑节点，自由漂移 |
| 部署形态 | 必须改为 StatefulSet | Deployment 即可，零改动 |
| 侵入性 | 需改部署 + 网络存储 | 仅注入 sidecar + initContainer |
| 适配第三方 CLI | 可用但需用户配合改形态 | ✅ 零代码、零形态改动，最贴合 |
| RPO（数据丢失窗口） | 更小（写卷即持久） | 存在两次同步间的窗口 |
| 存储依赖 | 网络块存储（EBS/云盘/Ceph） | 对象存储（S3/OSS/MinIO） |

**必须承认的恢复边界**（与 4.9 可恢复性矩阵一致）：
- 正在进行的那一轮 LLM 推理不可恢复（Pod 在回复中途挂掉，这半句必然丢）。
- 尚未同步的最后一点写入落在 RPO 窗口内，可能丢失。

因此准确表述是"session **可续上到最近一个 checkpoint**",而非"零丢失"。对 Claude CLI 而言，续到最近一条完整的 jsonl 记录通常已足够。

Sidecar 注入方式与 Istio Envoy 一致——通过 MutatingAdmissionWebhook 自动注入：

```yaml
# 方式 1: Namespace 级别自动注入（所有 Pod 都注入）
apiVersion: v1
kind: Namespace
metadata:
  name: third-party-agents
  labels:
    agentscope.io/inject-sidecar: "true"

# 方式 2: Pod 级别注入（按需标注）
metadata:
  labels:
    agentscope.io/inject-sidecar: "true"
  annotations:
    agentscope.io/sidecar-profile: "claude-code"      # 选择适配 Claude Code 的 sidecar 配置
    agentscope.io/session-durability: "persistent"    # 开启对象存储备份/恢复（方案 B）
    agentscope.io/session-state-dir: "/home/agent/.claude"  # 需持久化的 session 目录
    agentscope.io/session-store: "s3://agent-sessions" # checkpoint 对象存储后端
```

用户只需给 Deployment 或 Namespace 打标签，控制面的 Webhook 自动注入 sidecar 容器，零代码改动即可接入控制面管理。

此部分不在当前阶段实现，后续根据第三方 agent 接入需求启动设计。

##### 6.3 Agent 数据面用户 API 与 Session 路由

每个 Agent 是独立的 K8s Service，用户直接访问。控制面不在用户请求路径上（对标 Istio：Istiod 不碰用户流量）。

**数据面对用户暴露的 HTTP API（SDK 内置，与控制面无关）：**

```
Agent Pod 端口 (如 8080):

  # === 用户对话 API ===
  POST /chat                          # 发送消息
                                      # 无 sessionId → 自动创建新 session
                                      # 有 sessionId → 加入已有 session
  POST /chat/stream                   # SSE 流式响应
  WS   /ws                            # WebSocket 长连接（可选）

  # === Session 管理 API ===
  POST   /sessions                    # 显式创建 session（可选，/chat 也能隐式创建）
  GET    /sessions/{id}               # 获取 session 信息
  DELETE /sessions/{id}               # 终止 session

  # === 控制面契约 API（Level 1，供控制面探测，不面向用户）===
  GET /agentscope/info
  GET /agentscope/health
  GET /agentscope/metrics
```

**用户对话流程示例：**

```
# 首次对话（隐式创建 session）
$ curl https://agents.example.com/customer-support/chat \
    -d '{"message": "我要退款"}'
→ {"sessionId": "sess-123", "response": "好的，请提供订单号"}

# 后续消息（带 sessionId）
$ curl https://agents.example.com/customer-support/chat \
    -d '{"sessionId": "sess-123", "message": "订单号 A12345"}'
→ {"response": "已找到订单，正在处理退款..."}

# 用户不知道也不需要知道消息落在哪个 Pod
```

**Session 路由——数据面内部透明处理，两种模式：**

```
sessionAffinity: None (分布式存储)
──────────────────────────────────
  用户 → K8s Service → 随机 Pod 收到
    → Pod 从 Redis/DB 加载 session → 处理 → 写回
    → 下条消息可能路由到不同 Pod，完全无感
  
  K8s Service 默认 round-robin 即可，无需任何特殊路由机制

sessionAffinity: Instance (本地内存)
────────────────────────────────────
  用户 → K8s Service → 随机 Pod 收到
    → Pod 检查: 这个 sessionId 的 session 在我本地吗?
      ├── 是 → 直接处理
      └── 否 → 查 session 归属索引 → 内部转发到正确的 Pod → 返回结果

  内部转发机制:
  ┌──────────┐         ┌──────────┐         ┌──────────┐
  │  Pod-0   │         │  Pod-1   │         │  Pod-2   │
  │          │         │ sess-123 │         │          │
  │ 收到请求 │─转发──▶ │ 在这里   │         │          │
  │ sess-123 │         │ 处理+返回│         │          │
  └──────────┘         └──────────┘         └──────────┘
  
  索引来源: 控制面通过 ASDP gRPC stream 广播 session 归属表
  每个 Pod 在内存中维护一份 {sessionId → podIP} 的路由表

  对用户完全透明——用户始终只访问 Agent 的 K8s Service 地址
```

**金丝雀发布——通过 Deployment 策略实现，不需要控制面参与：**

```
方式 1: Replicas 比例（简单）
  Agent v1 Deployment: replicas=9
  Agent v2 Deployment: replicas=1
  两个 Deployment 的 Pod 共用同一 Service (相同 label selector)
  → K8s Service 自动 ~10% 流量到 v2

方式 2: Argo Rollouts（推荐生产使用）
  Rollout:
    strategy:
      canary:
        steps:
          - setWeight: 10
          - pause: { duration: 1h }
          - setWeight: 50
          - pause: {}

方式 3: Ingress canary annotations
  nginx.ingress.kubernetes.io/canary: "true"
  nginx.ingress.kubernetes.io/canary-weight: "10"

三种方式均不需要控制面参与，使用 K8s 生态标准工具。
```

##### 6.4 分布式实例路由与 Session 归属

同一个 Agent 通常部署多个对等实例（replicas）。控制面在操作具体 session 时，需要解决"该访问哪个实例"的问题。核心取决于数据面的 session 存储模型。

**两种数据面状态模型：**

```
模式 A: Session 分布式存储（无状态实例）       模式 B: Session 本地内存（有状态实例）

  Pod-0    Pod-1    Pod-2                     Pod-0       Pod-1       Pod-2
   │        │        │                       ┌─────┐    ┌─────┐    ┌─────┐
   └───┬────┴───┬────┘                       │s-aa │    │s-cc │    │s-ee │
       │        │                            │s-bb │    │s-dd │    │s-ff │
       ▼        ▼                            └─────┘    └─────┘    └─────┘
  ┌─────────────────┐                         内存        内存        内存
  │  Redis / DB     │                        (不共享)    (不共享)    (不共享)
  │  (所有 session)  │
  └─────────────────┘
  任意实例可操作任意 session                   操作必须路由到持有者
```

**统一抽象：SessionAffinity + Durability（两个正交维度）**

控制面不关心底层用 Redis 还是本地内存，只关心两个正交属性——**绑不绑实例**（affinity）与**能不能恢复**（durability）：

```
SessionAffinity: None        →  任意实例可处理任意 session（无状态）
SessionAffinity: Instance    →  session 绑定特定实例（有状态）

Durability: Ephemeral        →  Pod 死即丢（本地内存 / emptyDir）
Durability: Persistent       →  Pod 死可恢复（PVC / 对象存储）
```

原先 `sessionAffinity` 把"绑不绑实例"和"能不能恢复"混在一起，导致 Instance 模式一律被定性为"Pod 故障即丢"。拆开后可精确描述三类语义：

| affinity | durability | 含义 | 存储载体 | 典型场景 |
|---|---|---|---|---|
| None | (N/A) | 无状态，任意实例可服务 | Redis / DB 共享 | 自研无状态 agent |
| Instance | Ephemeral | 绑实例，Pod 死即丢 | 本地内存 / emptyDir | 可丢弃的临时会话 |
| Instance | Persistent | 绑实例，Pod 死可恢复 | 对象存储（方案 B）/ PVC（方案 A） | **Claude CLI 等第三方文件态 agent** |

该属性通过两种方式获取：
1. 数据面通过 `/agentscope/info` 契约 API 自报告（推荐，BYO 模式）
2. Agent CRD 中显式声明（Declarative 模式）；第三方 sidecar 场景由注入 annotation 声明（见 6.2）

```json
// GET /agentscope/info 响应
{
  "sessionAffinity": "instance",   // "none" | "instance"
  "sessionStorage": "file",        // 补充信息: redis | memory | file | database | ...
  "durability": "persistent",      // "ephemeral" | "persistent"
  "stateDir": "/home/agent/.claude",       // durability=persistent 时需持久化的目录
  "restore": { "mode": "sidecar-objectstore" }  // 恢复方式: sidecar-objectstore | pvc
}
```

```yaml
# Agent CRD 声明
spec:
  declarative:
    agentConfig:
      sessionAffinity: instance   # none | instance
      durability: persistent      # ephemeral | persistent
```

**两种模式下控制面行为差异：**

| 操作 | None（无状态） | Instance（有状态） |
|---|---|---|
| 配置下发 | 广播所有实例 | 广播所有实例 |
| Session 状态查询 | 通过 K8s Service 任意实例 | 查索引 → 定向到持有者 |
| Session compress/terminate | 通过 Service 任意实例 | 查索引 → 定向到持有者 |
| Session 列表 | 任意实例返回全量（共享存储） | 聚合所有实例的上报 |
| 健康探测 | 任意实例 | 任意实例 |
| Pod 故障 | Session 不丢，自动恢复 | Ephemeral: 丢失标记 Lost；Persistent: 从 checkpoint 恢复 |
| 扩缩容 | 直接调整 replicas | 缩容需考虑 session drain（Persistent 可靠 checkpoint 恢复兜底） |

**路由执行流程：**

```
用户请求: POST /api/v1/agents/{name}/sessions/{id}/compress
  │
  ├── 1. 查 Agent → 获取 sessionAffinity 模式
  │
  ├── [None] → 通过 K8s Service ClusterIP 转发（自动负载均衡到任意 Ready Pod）
  │             → Pod 从 Redis/DB 加载 session → 执行 compress → 写回
  │
  └── [Instance] → 查 session 归属索引 → 找到 Pod-1 (10.0.1.6)
                   ├── gRPC: 推送 SessionCommand 到 Pod-1 的 stream
                   └── HTTP: POST http://10.0.1.6:8080/agentscope/sessions/{id}/compress
```

**Session 归属索引（仅 Instance 模式需要）：**

控制面内部维护 `session → instance` 映射，通过数据面上报构建：

```
索引数据（内存，非持久化，可从数据面上报快速重建）:

  session-aaa → Pod-0 (10.0.1.5:8080, gRPC stream #0)
  session-bbb → Pod-0 (10.0.1.5:8080, gRPC stream #0)
  session-ccc → Pod-1 (10.0.1.6:8080, gRPC stream #1)
  session-ddd → Pod-1 (10.0.1.6:8080, gRPC stream #1)
```

索引构建方式：
- **唯一通道 = ASDP gRPC stream**：每个实例各自建立一条 stream，通过 `SessionReport` 上报 sessions 列表。控制面天然知道每条 stream 对应哪个实例——stream 本身就是路由表（无 HTTP 兜底，对齐 6.1）。Redis 缓存热索引，Persistent 归属落 DB。

索引维护：
- 实例上报新 session → 添加索引条目
- 实例上报 session 终止 → 删除索引条目
- gRPC stream 断开 / Pod 不响应 → **Ephemeral**: 标记该实例所有 session 为 `Lost`；**Persistent**: 标记 `Recovering`，等待重调度后从 checkpoint 恢复
- 实例重启后重新上报 → 重建索引

**Persistent 模式下索引需持久化：**

Ephemeral 模式的 Redis 路由缓存"可从数据面上报快速重建"是成立的——Pod 没了 session 也没了，无需记住。但 Persistent 模式下 session 状态活在对象存储里，Pod 销毁后没有任何数据面能上报它，纯缓存会永久丢失路由信息。因此：

- 归属映射（`session → checkpoint 位置`）必须**持久化到 `SessionStore`（PostgreSQL）** 的 session 记录（instanceRef / stateLocation 字段），而非只放 Redis 缓存。
- 恢复路由不依赖"数据面上报重建",而是从 DB 权威读取，天然抗控制面重启。
- checkpoint 版本随 SessionReport 更新到 DB，作为恢复时的取数依据。

**gRPC stream 在 Instance 模式下的天然优势：**

```
控制面持有 N 条 stream 连接（每个 Pod 实例一条）:

  stream[0] ←→ Pod-0  (持有 session-aaa, session-bbb)
  stream[1] ←→ Pod-1  (持有 session-ccc, session-ddd)
  stream[2] ←→ Pod-2  (持有 session-eee, session-fff)

定向推送 = 往正确的 stream 写一条消息（零额外网络开销）
广播推送 = 遍历所有 stream 各写一条
无需知道 Pod IP，无需额外服务发现——连接本身就是路由。
```

**扩缩容处理（Instance 模式）：**

```
缩容 (replicas 3→2, Pod-2 即将终止):

  策略 1: 直接缩容（Ephemeral，第一期实现，简单）
    Pod-2 被终止 → session-eee, session-fff 丢失
    控制面收到 stream 断开 → 标记 sessions 为 Lost
    适用于: session 可丢弃或可由用户重新发起

  策略 2: 优雅 Drain（Ephemeral，后续按需支持）
    1. 控制面标记 Pod-2 为 draining（不再分配新 session）
    2. 等待 Pod-2 上的 sessions 自然结束（设超时）
    3. 超时后触发 session 序列化迁移（需数据面支持）
    4. 确认 sessions 清空后允许 Pod 终止

  策略 3: Checkpoint 兜底（Persistent，方案 B）
    Pod-2 终止前 sidecar 已持续同步到对象存储
    → 无需 drain，缩容后 session 由 checkpoint 保底
    → 用户下次访问时按 sessionId 重调度 + initContainer 恢复
```

**Persistent 模式的故障恢复流程（方案 B，详见 6.2）：**

```
Pod-2 故障 / 被重调度
  │
  ├── 1. 控制面感知 stream 断开 → 该实例 session 标记 Recovering（不是 Lost）
  ├── 2. 用户下次带 sessionId 访问 → 控制面查 SessionStore(DB) 的 stateLocation
  ├── 3. 调度新 Pod（任意节点）→ initContainer 按 sessionId 从对象存储拉回 .claude/
  ├── 4. gate 确认恢复完成 → 主容器 claude --resume <sessionId> 续上
  └── 5. sidecar 重新上报 → 更新 instanceRef，session 回到 Active

恢复边界（与 4.9 一致）:
  ⚠️ 进行中的 LLM 推理不可恢复
  ⚠️ RPO 窗口内未同步的写入可能丢
  → "续上到最近 checkpoint"，非零丢失
```

**对 SessionStore（DB）中 session 记录的影响：**

```jsonc
// Instance + Ephemeral：记录归属实例（Pod 死即 Lost）
{ "instanceRef": "customer-support-agent-pod-1", "instanceIP": "10.0.1.6",
  "durability": "Ephemeral", "phase": "Active" }

// Instance + Persistent：额外记录 checkpoint 位置（Pod 死可恢复）
{ "instanceRef": "claude-agent-pod-2",          // 当前归属实例（恢复后会变）
  "durability": "Persistent",
  "stateLocation": "s3://agent-sessions/claude-agent/sess-abc123/",  // checkpoint 位置
  "checkpointVersion": "v42",                     // 最近同步的 checkpoint 版本
  "phase": "Active" }                             // Active | Recovering | Lost

// None 模式：不绑定特定实例
{ "instanceRef": "", "phase": "Active" }          // instanceRef 空 = 任意实例可服务
```

---

##### 6.5 可观测性

```
数据面 Pod
  ├── Agent Runtime → 埋点 OpenTelemetry traces/metrics
  ├── SDK CP Client → 定时上报 session 状态（ASDP gRPC SessionReport，非 HTTP 兜底）
  └── Prometheus ServiceMonitor → 指标采集

控制面
  ├── 运行时状态服务 → SessionReport 落 SessionStore(DB) → 聚合计数回写 Agent.status.activeSessions
  ├── Grafana Dashboard → Agent 维度的请求量、延迟、token消耗、session分布
  └── REST API → /api/v1/agents/{name}/sessions/{id}/state → 从 DB 实时查看 state
```

**关键指标（Prometheus）：**

```
# Agent 维度
agentscope_agent_sessions_active{agent, namespace}
agentscope_agent_requests_total{agent, namespace, status}
agentscope_agent_token_usage_total{agent, namespace, type}  # type=prompt|completion
agentscope_agent_latency_seconds{agent, namespace, quantile}

# Session 维度
agentscope_session_message_count{agent, session}
agentscope_session_context_pressure_ratio{agent, session}
agentscope_session_task_progress{agent, session, state}  # state=pending|in_progress|completed

# 系统维度
agentscope_controlplane_reconcile_duration_seconds{controller}
agentscope_sandbox_claims_total{agent, phase}
```

---

##### 6.6 AgentScope 数据面深度托管：控制面即 DistributedStore 后端

自研数据面（AgentScope Java / Go）与第三方 CLI（Claude Code、Codex 等）的关键差异在于：AgentScope 已经把数据面**所有需要分布式持久化的存储点收敛到一个接口族 `DistributedStore`**（`agentScope-harness` 中）。控制面要做的不是替它另造一套存储，而是**成为这个接口族的托管实现**——把数据面今天散落在 Redis / MySQL / OSS / Nacos 上的存储点，收敛成"只连控制面一个后端"，从而大幅简化数据面部署。

**为什么天然契合：**

- AgentScope 是**无状态引擎 + 共享 `AgentStateStore`**：任意副本按 `(userId, sessionId)` 从共享后端加载状态。这正是 6.4 的 `sessionAffinity: None`——**自研数据面天然走 None 模式**，不需要 CR-001（第三方 CLI）那套 Instance + checkpoint 恢复。控制面当好共享后端，故障转移 / 滚动发布 / 跨 Pod 接续全自动。
- `AgentStateStore` 按 `(userId, sessionId, key)` 寻址的 session 运行态，**与 3.8 的 `SessionStore` 是同一份数据**。数据面 `call()` 结束保存 `AgentState`，本质就是 ASDP `SessionReport` 落 `SessionStore`——观测与存储合一，无需重复上报。

**映射一览（`DistributedStore` 组件 → 控制面归属）：**

| DistributedStore 组件 | 承载内容 | 控制面归属 | 存储层 |
|---|---|---|---|
| `AgentStateStore` | 对话上下文 / 摘要 / 权限 / Plan / tasks | **合流 `SessionStore`（3.8）** | PostgreSQL + Redis |
| `BaseStore`（带 CAS `putIfVersion`） | 工作区文件 KV：`MEMORY.md` / `memory/` / `skills/` / `sessions/` / `subagents/` | `ControlPlaneBaseStore`（`version` 列做 CAS，正好对齐 DB 乐观锁） | PostgreSQL（大值溢出对象存储） |
| `SandboxSnapshotSpec` | 沙箱工作区 tar 快照 | 元数据入 DB，blob 用 presigned URL **直传对象存储** | 对象存储 |
| `SandboxExecutionGuard` | AGENT/GLOBAL scope 分布式锁 | Lock Service（`SET NX` 租约语义） | Redis |
| `MessageBus` | 收件箱投递 + session 事件流 | **复用 Team Message Router**（gRPC 转发 + DB 存储） | PostgreSQL + gRPC |
| `AsyncToolRegistry` | 异步工具 / 子 agent 后台任务 | **复用 Team Task Store**（同款 `version` 乐观锁） | PostgreSQL |
| Skill 仓库 | 可复用技能包 | **复用 push API `skills[]` / OCI ref** | 对象存储 / OCI + DB |
| Prompt 配置中心 | system prompt 热更新 | **复用 Agent CRD `spec.declarative` + ASDP ConfigPush** | etcd（CRD） |
| A2A 注册发现 | AgentCard 注册 / 发现 | **复用 DiscoveryController + Agent status** | etcd（CRD） |

右侧绝大多数是 CR-002 已规划的组件（`SessionStore` / Team Router / Task Store），真正新增的只有 `ControlPlaneBaseStore` 与 Lock Service。这一映射同时**接管了 Nacos 今天在 AgentScope 里扮演的"统一控制面"角色**（Skill / Prompt / A2A 三件事），数据面不再需要独立引入 Nacos。

**SDK 接入（一行）：**

```java
HarnessAgent.builder()
    .distributedStore(ControlPlaneDistributedStore.fromEnv())  // 读控制面 endpoint / Token
    .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
    .build();
```

- **BYO**：引 `agentscope-controlplane-client` 依赖 + 一行配置，Pod 不再直连任何存储中间件。
- **Declarative**：`AgentScopeJavaAdapter` 构建 Deployment 时自动注入控制面 endpoint + ServiceAccount Token（沿用 2.5 的 `application.yml controlplane` 约定），用户 `agentscope.yaml` **完全不写存储配置**。这是"大幅简化"的落点：写 agent，存储由控制面托管。
- `IsolationScope`（SESSION/USER/AGENT/GLOBAL）映射到控制面存储的命名空间前缀，隔离语义原样保留。

**边界与权衡（避免把控制面变成关键路径瓶颈）：**

- **只承载低频写 + 可缓存读**：`AgentState` 只在 `call()` 结束整体写一次（非每条消息落盘），`BaseStore` / memory 写频率也低；热路径读用 SDK 本地缓存 + Redis 兜。
- **大 blob 直传对象存储**：沙箱快照、会话 transcript 走 presigned URL 直传，控制面只发凭证 + 记元数据，不做数据搬运。
- **断连要能降级**：控制面不可用时 SDK 本地落盘 + 重连回填（比 ASDP 配置通道更严格——配置可用"最后一份继续跑"，状态写不能丢）。
- **锁服务是新增的强一致依赖**：需带 TTL 租约；锁服务不可用时 fail-safe 降级为单副本串行或拒绝并发。
- **边界止于 `DistributedStore` 接口层**：控制面提供"托管后端"，不侵入 ReAct 循环 / middleware。

**分期落地（保持第一期精简，不过度设计）：**

- **第一期不含托管存储**：adapter 只负责把 AgentScope Java 数据面跑起来，数据面沿用其**自带后端**（单机文件 / 自备 Redis）。托管存储不进第一期。
- **第二期**：`ControlPlaneAgentStateStore` 合流 `SessionStore`——复用第二期本就要建的 `SessionStore`，几乎零额外新增，即可让自研数据面的 session 状态直接落控制面。
- **后续期**：`ControlPlaneBaseStore`（memory / skills / sessions 文件）、Snapshot + Lock Service、`MessageBus` / `AsyncToolRegistry`（复用 Team Router / Task Store）；最后再取代 Nacos 的 Skill / Prompt / A2A（复用 push API / ConfigPush / Discovery）。

---

#### 七、REST API 设计

REST API 作为控制面的北向接口，供 Dashboard、CLI、CI/CD 调用。参考 Langsmith Managed Deep Agents API（`/v1/deepagents`）的资源分组和语义设计。

##### 7.1 北向 API（面向用户 / CLI / Dashboard）

```
# ========== Agent 生命周期（参考 Langsmith /agents） ==========
POST   /api/v1/agents/{name}/push              # 创建或更新（幂等），自包含 body
                                                # CLI deploy 调用此接口
                                                # 首次 → 创建 Agent + ModelConfig + MCPServer + Secret
                                                # 再次 → 更新 + 生成新 revision
GET    /api/v1/agents                          # 列出所有 Agent
                                                # 过滤: ?type=Declarative|BYO
GET    /api/v1/agents/{name}                   # 获取 Agent 详情（含最新 revision）
PATCH  /api/v1/agents/{name}                   # 部分更新（omitted fields 不变，参考 Langsmith）
DELETE /api/v1/agents/{name}                   # 删除 Agent（不级联删 session 历史）
GET    /api/v1/agents/{name}/health            # 健康检查（参考 Langsmith /agents/{id}/health）
GET    /api/v1/agents/{name}/revisions         # Revision 历史（后端: RevisionStore — DB/对象存储）
GET    /api/v1/agents/{name}/revisions/{rev}   # 特定 revision 的配置快照（DB/对象存储）
POST   /api/v1/agents/{name}/rollback          # 回滚到指定 revision

# ========== Session 管理（对标 Langsmith /threads + /runs） ==========
# 后端: SessionStore(PostgreSQL) + RoutingIndex(Redis) + 对象存储，非 etcd（详见 3.8）
GET    /api/v1/agents/{name}/sessions          # 列出活跃 Session（DB 查询）
POST   /api/v1/agents/{name}/sessions          # 创建 Session（可选）
GET    /api/v1/agents/{name}/sessions/{id}     # Session 详情（DB）
GET    /api/v1/agents/{name}/sessions/{id}/state    # Session state（tasks, context, summary）（DB）
POST   /api/v1/agents/{name}/sessions/{id}/compress # 触发会话压缩
POST   /api/v1/agents/{name}/sessions/{id}/terminate # 终止会话
GET    /api/v1/agents/{name}/sessions/{id}/messages  # 消息历史（后端: SessionMessageStore — 对象存储/DB）
DELETE /api/v1/agents/{name}/sessions/{id}     # 删除 Session

# ========== MCP Server（namespace 级别，参考 Langsmith /mcp-servers） ==========
POST   /api/v1/mcpservers                      # 注册（workspace 级别，多 Agent 共享）
GET    /api/v1/mcpservers                      # 列出
GET    /api/v1/mcpservers/{name}               # 详情
PATCH  /api/v1/mcpservers/{name}               # 部分更新（omitted fields 不变）
DELETE /api/v1/mcpservers/{name}               # 删除
GET    /api/v1/mcpservers/{name}/tools         # 发现工具（参考 Langsmith /mcp/tools）

# ========== ModelConfig ==========
POST   /api/v1/modelconfigs                    # 创建（通常由 push API 自动创建）
GET    /api/v1/modelconfigs                    # 列出
GET    /api/v1/modelconfigs/{name}             # 详情
PATCH  /api/v1/modelconfigs/{name}             # 更新
DELETE /api/v1/modelconfigs/{name}             # 删除

# ========== Agent Teams（AgentScope 独有） ==========
# 团队声明存 etcd(AgentTeam CRD)；tasks/messages/成员运行时态存 DB（详见 3.8）
POST   /api/v1/teams                           # 创建团队（AgentTeam CRD 声明）
GET    /api/v1/teams                           # 列出
GET    /api/v1/teams/{team}                    # 详情（CRD 声明 + DB 运行时态投影）
DELETE /api/v1/teams/{team}                    # 结束团队
POST   /api/v1/teams/{team}/members            # 添加成员（Lead 调用，DB）
DELETE /api/v1/teams/{team}/members/{name}     # 移除成员
GET    /api/v1/teams/{team}/members            # 列出成员及状态（DB）
POST   /api/v1/teams/{team}/tasks              # 创建任务（TeamTaskStore — DB）
GET    /api/v1/teams/{team}/tasks              # 列出任务（DB）
POST   /api/v1/teams/{team}/tasks/{id}/claim   # claim（乐观锁：DB version 列）
POST   /api/v1/teams/{team}/tasks/{id}/complete # 完成
POST   /api/v1/teams/{team}/messages           # 消息路由（TeamMessageStore — DB）

# ========== 沙箱 ==========
POST   /api/v1/sandboxes                       # 申请沙箱
GET    /api/v1/sandboxes                       # 列出
GET    /api/v1/sandboxes/{name}                # 详情
DELETE /api/v1/sandboxes/{name}                # 释放

# ========== 系统 ==========
GET    /api/v1/namespaces                      # 命名空间列表
GET    /api/v1/version                         # 版本
GET    /healthz                                # 健康
GET    /readyz                                 # 就绪
```

##### 7.2 南向通信

数据面与控制面的运行时通信分为两个通道：

```
# === gRPC 双向流（ASDP 通道 1 — 唯一的配置/状态通道）===
# 控制面暴露 gRPC 端口 (默认 15010)
# 数据面 SDK 启动时自动建连，传输所有配置推送、状态上报、session 指令、team 事件
# 详见 6.1 ASDP 协议设计

# === HTTP API（ASDP 通道 3 — 仅需要 req/resp 语义的操作）===
# Team 操作（需要请求/响应，如 claim 返回成功或 409 冲突）
POST   /api/v1/teams/{team}/tasks              # 创建任务 (Lead)
POST   /api/v1/teams/{team}/tasks/{id}/claim   # claim（乐观锁）
POST   /api/v1/teams/{team}/tasks/{id}/complete # 完成
POST   /api/v1/teams/{team}/messages           # 发送消息
POST   /api/v1/teams/{team}/members            # 添加成员 (Lead)
GET    /api/v1/teams/{team}/tasks              # 列出任务
GET    /api/v1/teams/{team}/members            # 列出成员

# Sandbox 操作（需要响应返回沙箱地址）
POST   /api/v1/internal/sandbox/request        # 申请沙箱
DELETE /api/v1/internal/sandbox/{id}            # 释放沙箱

# 已删除的兜底端点（配置/状态全走 gRPC，对标 Istio 无 HTTP fallback）:
#   ❌ POST /internal/sessions/report
#   ❌ GET  /internal/config/{agent}
```

**与 Langsmith API 的对齐与差异：**

| Langsmith 端点 | AgentScope 对应 | 差异说明 |
|---|---|---|
| `POST /agents` | `POST /agents/{name}/push` | 用 path param 命名，幂等语义 |
| `PATCH /agents/{id}` | `PATCH /agents/{name}` | 按 name 而非 UUID |
| `GET /agents/{id}/health` | `GET /agents/{name}/health` | 一致 |
| `POST /threads` | `POST /agents/{name}/sessions` | 用 Session 替代 Thread |
| `POST /threads/{id}/runs` | 无需显式创建 | Run 由数据面内部管理 |
| `POST /threads/{id}/resolve-interrupt` | 未来扩展 | 工具审批通过 interruptConfig |
| `POST/GET /mcp-servers` | `POST/GET /mcpservers` | namespace 级别，语义一致 |
| 无对应 | `/teams/**` | AgentScope 独有 |
| 无对应 | `/agents/{name}/revisions` | AgentScope 独有 |
| 无对应 | `/api/v1/internal/**` | 数据面南向接口，Langsmith 是 SaaS 不需要 |

---

#### 八、项目目录结构

```
control-plane/
├── cmd/
│   └── agentscoped/              # 控制面主进程 (controller-manager + API server)
│       └── main.go
├── api/
│   └── v1alpha1/                 # CRD Go 类型定义（AgentSession 不在此 — 非 CRD，见 internal/store）
│       ├── agent_types.go
│       ├── modelconfig_types.go
│       ├── mcpserver_types.go
│       ├── agentteam_types.go        # 声明 + 粗粒度 phase（运行时明细在 DB）
│       ├── sandboxclaim_types.go
│       ├── common_types.go
│       ├── groupversion_info.go
│       └── zz_generated.deepcopy.go
├── internal/
│   ├── controller/               # Kubernetes Controllers
│   │   ├── agent_controller.go              # Declarative/BYO 模式
│   │   ├── discovery_controller.go          # 自动发现带标签的 Deployment
│   │   ├── byo_workload_controller.go       # BYO workloadRef 模式持续纳管
│   │   ├── modelconfig_controller.go
│   │   ├── mcpserver_controller.go
│   │   ├── sandbox_broker_controller.go     # watch SandboxClaim CRD
│   │   └── agentteam_controller.go          # watch AgentTeam CRD（声明+phase），团队生命周期
│   │   # 注: 无 session_controller — AgentSession 不是 CRD，由 runtime/ 服务处理
│   ├── adapter/                  # 数据面适配器（Declarative/BYO 构建 K8s 资源）
│   │   ├── adapter.go            # DataPlaneAdapter 接口
│   │   ├── agentscope_java.go    # 第一期：AgentScope Java 适配
│   │   ├── agentscope_go.go      # 第二期：AgentScope Go 适配
│   │   └── registry.go           # 适配器注册表
│   ├── prober/                   # 数据面探测器（所有模式通用）
│   │   ├── prober.go             # DataPlaneProber 接口
│   │   ├── http_prober.go        # 通用 HTTP 实现（调用契约 API）
│   │   └── types.go              # DataPlaneInfo, SessionReport 等
│   ├── discovery/                # 自动发现逻辑
│   │   ├── label_discovery.go    # 标签发现策略
│   │   ├── adopt.go              # CLI adopt 逻辑（给 Deployment 打标签）
│   │   └── webhook.go            # ValidatingWebhook（CRD 准入校验）
│   │   # 注: 不需要 registration.go — BYO 发现通过 label + DiscoveryController 完成
│   ├── asdp/                     # ASDP 协议实现（AgentScope Data Plane Protocol）
│   │   ├── service.go            # gRPC AgentDataPlaneService 实现
│   │   ├── asdp.proto            # protobuf 定义（Upstream/Downstream 消息）
│   │   ├── connect_handler.go    # ConnectRequest 握手处理
│   │   ├── snapshot.go           # 配置快照与版本管理
│   │   └── distributor.go        # 统一分发调度（gRPC 连接优先，未连接走 HTTP）
│   ├── httpapi/                  # REST API
│   │   ├── server.go
│   │   ├── agent_handler.go
│   │   ├── adopt_handler.go          # CLI adopt 接口（给 Deployment 打标签）
│   │   ├── session_handler.go
│   │   ├── modelconfig_handler.go
│   │   ├── mcpserver_handler.go
│   │   ├── route_handler.go
│   │   ├── sandbox_handler.go
│   │   └── types.go              # Request/Response 类型
│   ├── store/                    # 运行时状态服务：DB 存储层（详见 3.8）
│   │   ├── store.go              # Repository 接口汇总（可换后端、便于测试）
│   │   ├── session_store.go      # SessionStore（PostgreSQL）
│   │   ├── session_message_store.go # SessionMessageStore（PostgreSQL，冷数据归档对象存储）
│   │   ├── team_task_store.go    # TeamTaskStore（PostgreSQL，version 列乐观锁）
│   │   ├── team_message_store.go # TeamMessageStore（PostgreSQL）
│   │   ├── revision_store.go     # RevisionStore（PostgreSQL）
│   │   ├── routing_index.go      # RoutingIndex（Redis，可重建缓存）
│   │   ├── events.go             # 变更事件总线（LISTEN/NOTIFY → gRPC 下推/SSE）
│   │   └── migrations/           # DB schema 迁移
│   ├── runtime/                  # 运行时状态服务（处理 AgentSession，非 controller）
│   │   ├── session_service.go   # 接收 ASDP SessionReport 落 store；下发 compress/terminate
│   │   └── routing.go           # session 归属路由（结合 store.RoutingIndex + gRPC stream）
│   ├── team/                     # Agent Teams 协调（存储委托给 store/）
│   │   ├── task_store.go         # 分布式任务列表（调用 store.TeamTaskStore，DB version 乐观锁）
│   │   ├── message_router.go     # 消息路由（gRPC 转发 + store.TeamMessageStore 持久化）
│   │   ├── session_spawner.go    # 成员 session 创建与注入
│   │   └── lifecycle.go          # 团队生命周期（启动、结束、超时）
│   └── sandbox/                  # 沙箱 broker 逻辑
│       ├── broker.go             # SandboxClaim → Sandbox 翻译
│       └── lifecycle.go          # 沙箱生命周期管理
├── config/
│   ├── crd/                      # CRD YAML（kubebuilder 生成）
│   ├── rbac/                     # RBAC 规则
│   ├── manager/                  # controller-manager Deployment
│   └── samples/                  # 示例 CR
├── hack/
│   └── codegen.sh                # 代码生成脚本
├── helm/
│   └── agentscope-controlplane/  # Helm Chart
│       ├── Chart.yaml
│       ├── values.yaml
│       └── templates/
├── test/
│   ├── e2e/
│   └── integration/
├── go.mod
├── go.sum
├── Makefile
└── Dockerfile
```

---

#### 九、分期交付计划

##### 第一期：Declarative 模式 + BYO 发现（~10周）

**目标：** 两种核心模式可用——CLI push 创建 Declarative Agent，用户自部署的 BYO 被自动发现纳管。

| 周次 | 交付内容 |
|---|---|
| W1-W2 | 项目脚手架（kubebuilder init）；Agent CRD（Declarative + BYO 两种 type）、ModelConfig、MCPServer CRD 类型定义；deepcopy 生成 |
| W3-W4 | AgentController（Declarative/BYO）：reconcile Deployment + Service + ConfigMap；AgentScopeJavaAdapter；ModelConfigController |
| W5-W6 | **HTTPDataPlaneProber**（通用契约 API 探测器）；**DiscoveryController**（标签发现 → 自动创建 BYO Agent CRD (workloadRef)）；BYOWorkloadController（持续纳管 + 健康探测） |
| W7-W8 | REST API 完整实现：Agent CRUD + push 接口 + adopt 接口 + type 过滤；MCPServerController |
| W9-W10 | agentscope-cli 骨架（agent push / list / status / adopt）；Helm Chart；Dockerfile；CI/CD；端到端测试 |

**第一期完成标准：**
- Declarative: `agentscope-cli agent push` 能自动创建 Deployment 运行 AgentScope Java 数据面
- BYO (workloadRef): 用户 Deployment 打上 `agentscope.io/managed=true` 标签后，控制面 30s 内自动发现并创建 Agent CRD
- 控制面探测数据面 `/agentscope/info`，正确识别 contractLevel
- `agentscope-cli agent list` 统一展示 Declarative + BYO agents
- MCPServer 能自动发现工具列表

##### 第二期：会话管理与可观测（~6周）

**目标：** 实现数据面状态上报和会话级管控，Declarative 和 BYO 模式统一体验。

| 周次 | 交付内容 |
|---|---|
| W1-W2 | 运行时状态服务 + `internal/store`（SessionStore/PostgreSQL + RoutingIndex/Redis）；ASDP SessionReport 落库；DB schema 与迁移；`ControlPlaneAgentStateStore`（AgentScope 数据面 AgentState 合流 SessionStore，见 6.6） |
| W3-W4 | 会话压缩/终止指令下发（经 ASDP gRPC）；Session state 查询 API（tasks、context pressure、summary，读 DB）；contractLevel 分级降级处理 |
| W5-W6 | Prometheus 指标埋点；Grafana Dashboard 模板；数据面 OpenTelemetry 集成 |

**第二期完成标准：**
- AgentScope Java 数据面通过 `ControlPlaneDistributedStore` 把 AgentState 直接落控制面 SessionStore（自研数据面走 `sessionAffinity: None`，见 6.6）；`BaseStore` / Lock / MessageBus 等其余托管存储留待后续期，第一期数据面仍用自带后端
- Declarative + BYO agent 均能看到活跃 session 列表（contractLevel >= 2）
- 可以通过 API 查看 session state、触发压缩或终止（contractLevel >= 3）
- contractLevel < 2 的 BYO agent 只有基础健康状态，API 返回 "session reporting not supported"
- Grafana 可展示 Agent 维度的核心指标

##### 第三期：配置热更新与流量管理（~6周）

**目标：** 配置热更新、BYO 模式的 overrides 下发。

| 周次 | 交付内容 |
|---|---|
| W1-W2 | gRPC ConfigService server 实现；ConfigMap fsnotify 热更新；HTTP 回调兜底路径；Declarative Agent 配置热更新 |
| W3-W4 | BYO Agent overrides 推送；ValidatingWebhook CRD 准入校验 |
| W5-W6 | MCP / Skill 运行时热更新；限流策略；ValidatingWebhook CRD 准入校验；集成测试 |

**第三期完成标准：**
- Declarative: systemMessage、tools 变更后数据面无需重启即可生效
- BYO: overrides 中的 tools 变更可推送到 contractLevel >= 3 的数据面
- 金丝雀发布通过 K8s Deployment 策略 / Argo Rollouts 实现（不需要控制面参与）
- BYO Agent overrides 推送到 contractLevel >= 3 的数据面可实时生效

##### 第四期：分布式 Agent Teams（~8周）

**目标：** 实现跨 Pod/Agent 的团队协作能力。

| 周次 | 交付内容 |
|---|---|
| W1-W2 | AgentTeam CRD 类型定义；AgentTeamController 核心 reconcile（静态成员 session 创建） |
| W3-W4 | 分布式 Task Store（乐观并发 claim）；Team Coordination REST API |
| W5-W6 | Message Router（gRPC stream 转发 + Team HTTP API）；动态成员 spawn/remove；SDK TeamClient 接口 |
| W7-W8 | 团队生命周期管理（超时、shutdown、清理）；Lead plan approval；集成测试 |

**第四期完成标准：**
- 静态声明的 AgentTeam CRD 能自动在不同 Agent 实例上创建 Lead + Member sessions
- 成员可通过 API 互发消息、claim 任务、查看彼此状态
- Lead 可运行时动态 spawn/remove 成员（受 maxTotal 和 allowedAgentRefs 约束）
- 任务 claim 乐观并发正确工作（无竞态）
- 团队结束后所有成员 session 自动清理

##### 第五期：沙箱集成与多运行时（~6周）

**目标：** 打通 agent-sandbox，支持更多数据面运行时。

| 周次 | 交付内容 |
|---|---|
| W1-W2 | SandboxClaim CRD、SandboxBrokerController、agent-sandbox 集成；数据面沙箱申请接口 |
| W3-W4 | AgentScope Go DataPlaneAdapter；多运行时 BYO 模式集成测试 |
| W5-W6 | Langchain / Custom DataPlaneAdapter 骨架；数据面契约 API SDK（Java + Go 参考实现）；文档完善

**第五期完成标准：**
- 数据面通过 API 申请 sandbox，控制面自动创建 agent-sandbox Sandbox 资源
- Sandbox 生命周期（创建、就绪、过期、清理）完全由控制面管理
- 同一集群可同时运行 AgentScope Java 和 AgentScope Go 数据面（Declarative + BYO 混合）
- 提供数据面契约 API SDK（Java + Go），新数据面只需实现 Level 1 接口即可被发现纳管

##### 五期总览时间线

```
W1 ────── W10       W11 ──── W16     W17 ──── W22     W23 ──── W30     W31 ──── W36
┌──────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐   ┌────────────┐
│  第一期        │   │  第二期      │   │  第三期      │   │  第四期      │   │  第五期      │
│  Declarative  │   │  Session    │   │  Hot-reload │   │  Agent      │   │  Sandbox   │
│  + Discovery  │   │  + 可观测    │   │  + 热更新    │   │  Teams      │   │  + 多运行时 │
│  + CLI        │   │            │   │  + 热更新    │   │  (分布式协作)│   │  + 契约SDK  │
└──────────────┘   └────────────┘   └────────────┘   └────────────┘   └────────────┘
      10w                6w               6w               8w               6w
```

---

#### 十、技术选型

| 组件 | 技术选型 | 理由 |
|---|---|---|
| Controller 框架 | controller-runtime (kubebuilder) | Kubernetes 生态标准，参考 Kagent 和 agent-sandbox |
| REST API | Gin | 轻量高性能，agentscope-go 已在 example 中使用 |
| 配置分发 | ASDP gRPC 双向流 + ConfigMap Watch | 对标 Istio xDS，无 HTTP 兜底；断连时用最后配置继续运行 |
| 数据存储（声明式） | Kubernetes etcd（CRD） | 期望状态：Agent/ModelConfig/MCPServer/AgentTeam(声明)/SandboxClaim，低基数低频（详见 3.8） |
| 数据存储（运行时） | PostgreSQL | 观测态与流水：session/task/message/revision，随流量增长，脱离 etcd 避免 churn 与爆炸半径 |
| 热缓存/路由索引 | Redis | session→实例/checkpoint 路由缓存，可从 DB + gRPC stream 重建 |
| 冷归档/大对象 | 对象存储（S3/OSS/MinIO） | 第三方文件态 checkpoint（CR-001）、冷数据 TTL 归档 |
| 可观测性 | OpenTelemetry + Prometheus + Grafana | 与 agentscope-go 现有 otel middleware 对齐 |
| 包管理 | Helm | Kubernetes 部署标准 |
| CI/CD | GitHub Actions | 与现有仓库一致 |

---

#### 十一、与 agentscope-go 数据面的集成点

控制面会使用 agentscope-go 的以下核心概念，但不直接依赖其 Go module——通过数据格式对齐来解耦：

| agentscope-go 概念 | 控制面对应 | 数据格式映射 |
|---|---|---|
| `agent.Agent` 配置 | Agent CRD `spec.agentConfig` | systemMessage, modelConfig, reactConfig, contextStrategy 字段对齐 |
| `state.AgentState` | AgentSession `state`（DB，非 etcd status） | 数据面 ASDP 上报 JSON，控制面写入 SessionStore |
| `state.TaskContext` | AgentSession state 中的 tasks（DB） | 同一 Task 数据结构：id, subject, state, blocks, blockedBy |
| `workspace.Workspace` | Agent CRD `spec.tools` + MCPServer CRD | MCP 配置格式对齐 `workspace.MCPClientConfig` |
| `workspace.MCPClient` | MCPServer CRD | Remote 对应 MCPHTTPConfig, Stdio 对应 MCPStdioConfig |
| `agent.ContextConfig` | Agent CRD `spec.agentConfig.contextStrategy` | triggerRatio, reserveRatio 直接映射 |
| `permission.Context` | 未来：AgentPolicy CRD | 预留扩展点 |

---

#### 十二、安全设计

1. **RBAC：** 控制面 ServiceAccount 拥有 Agent/ModelConfig/MCPServer/AgentSession/SandboxClaim CRD 的完整 CRUD 权限，以及对 Deployment/Service/ConfigMap/Secret 的操作权限。
2. **Secret 管理：** API Key 等敏感信息通过 Kubernetes Secret 存储，CRD 仅引用 Secret 名称，REST API 创建时可以内联传入 API Key，由控制面自动创建 Secret。
3. **命名空间隔离：** `allowedNamespaces` 遵循 Gateway API 模式，控制跨命名空间资源引用。
4. **数据面认证：** 数据面上报状态和拉取配置时携带 ServiceAccount Token，控制面验证 TokenReview。
5. **Webhook 验证：** 使用 kubebuilder validating/mutating webhook 对 CRD 输入做准入校验。

---

#### 十三、与竞品的差异分析

| 能力 | AgentScope CP | Kagent | Langsmith Managed |
|---|---|---|---|
| 平台 | Kubernetes原生 | Kubernetes原生 | SaaS |
| Agent定义 | CRD (声明式) | CRD (声明式) | REST API |
| 数据面 | 多运行时 (Java/Go/Langchain) | ADK Go/Python | Langchain专属 |
| 配置热更新 | gRPC stream 实时推送 (SDK内置) | ConfigMap重启 | API重建 |
| 金丝雀发布 | K8s Deployment 策略 / Argo Rollouts | 无 | 无 |
| 会话管控 | REST API + DB（压缩/终止/状态查看） | 有限（DB存储） | 有（SaaS） |
| 沙箱集成 | 原生对接 agent-sandbox | 原生对接 agent-sandbox | 无 |
| MCP 管理 | MCPServer CRD + 热更新 | RemoteMCPServer CRD | 无 |
| 可观测性 | OTel + Prometheus + Session state | 有限 | SaaS Dashboard |
