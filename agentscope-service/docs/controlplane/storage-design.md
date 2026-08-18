# 运行时数据持久化设计

> 状态：**已实施**（`internal/store`，`memory` + `postgres` 驱动）
> 关联文档：[contract.md](./contract.md)、[framework-integration.md](./framework-integration.md)
> 后续待办：[storage-followups.md](./storage-followups.md)（status 迁出、SQLite、AgentEntry/Instance 等）

## 0. 实施状态与偏差说明

本设计已在 `internal/store`（接口定义）、`internal/store/memory`、`internal/store/postgres` 中落地，`AgentSession`
/ `TeamMessage` / `TeamTask` 三个 CRD 已从 `api/v1alpha1`、`internal/controller`、Helm chart 中彻底移除。实际实现与本文档的差异如下：

- **未实现 SQLite 驱动**：第 6.2 / 8.2 节中提到的 SQLite 单节点部署未实现。当前只有两个驱动：
  - `memory`（默认值，进程内存储，**不持久化**，重启或多副本场景下数据丢失/不共享，仅用于开发和测试）
  - `postgres`（生产环境，通过 `jackc/pgx` 连接，推荐搭配 CloudNativePG，见 [`config/samples/cnpg-cluster.yaml`](../../../config/samples/cnpg-cluster.yaml)）

  如需轻量单机持久化，后续可以补一个 `internal/store/sqlite` 驱动并注册到 `store.RegisterOpener`，接口层已经足够通用，无需改动调用方。
- **AgentEntry / AgentInstance 未实现**：第 5.5 节描述的实例注册 CRD 设计属于未来工作，本次迁移不涉及，集群内 Agent 仍通过 Agent CRD → Deployment → Pod 自动发现。
- **接口签名与文档存在少量出入**：实际 `store.Store` 额外暴露 `TeamTasks() TeamTaskRepository`、`Ping(ctx) error`、`PurgeOlderThan(ctx, RetentionConfig) (int64, error)`；`TeamMessageRepository` 增加了 `ListPendingAll`（跨 team 轮询，供 `TeamMessageDispatcher` 使用）与 `IncrementAttempts`；具体字段/方法以 [`internal/store/store.go`](../../../internal/store/store.go)、[`internal/store/types.go`](../../../internal/store/types.go) 为准。
- **配置方式**：未使用第 6.3 节的 YAML 配置文件，而是通过 `aistiod` 命令行 flag 配置（`--storage-driver`、`--storage-dsn`、`--storage-max-open-conns`、`--storage-max-idle-conns`、`--storage-conn-max-lifetime`、`--retention-*`），DSN 也可以通过环境变量 `AISTIO_STORAGE_DSN` 或 Helm 的 `storage.postgres.existingSecret` 注入，避免明文出现在启动参数中。
- **Agent / AgentTeam status 本轮保留**：第 5.2 节要求删除 status 子资源，本轮明确延后——`Agent.status`（含 `DataPlaneInfo.ContractLevel`、`ActiveSessions`、`Conditions`）与 `AgentTeam.status` 仍由 reconciler 回写 CRD，供 kubectl printcolumn 与同步 gate 使用。运行时明细（sessions / events / messages / tasks）只写 DB。
- **sessions 表扩展**：实际 schema 在 §4.1 基础上增加了 `team_id` / `team_role` / `team_context`，以支撑 team 广播与成员健康检查（原依赖 AgentSession labels）。
- **team_tasks 表扩展**：增加 `task_id`（逻辑 ID）、`version`（乐观锁）、`result`、`completed_at`。

## 1. 背景与问题

aistio 当前将所有状态存储在 Kubernetes CRD（etcd）中。随着 Session 观测、Context 快照、事件流等运行时数据的引入，CRD 作为存储介质暴露出严重问题：

### 1.1 现有 CRD 数据性质分析

| CRD | 数据性质 | 判定 | 理由 |
|-----|---------|:---:|------|
| Agent.spec | Agent 定义 | ✅ 保留 | 声明式基础设施资源 |
| Agent.status | 运行时观测 | ❌ 迁 DB | 运行时状态，不是定义 |
| AgentTeam.spec | 静态编排（成员拓扑） | ✅ 保留 | 声明式编排资源 |
| AgentTeam.status | 运行时协作状态 | ❌ 迁 DB | 运行时状态 |
| MCPServer | 资源定义 | ✅ 保留 | 声明式配置 |
| ModelConfig | 资源定义 | ✅ 保留 | 声明式配置 |
| SandboxClaim | 资源申领 | ✅ 保留 | 声明式资源 |
| **AgentEntry**（新增） | 外部 agent 端点注册 | ✅ 新增 | 声明式拓扑（类比 Istio ServiceEntry） |
| **AgentInstance**（新增） | 具体实例声明 | ✅ 新增 | 声明式拓扑（类比 Istio WorkloadEntry） |
| **AgentSession** | 运行时会话 | ❌ **整体删除** | Session 是纯运行时产物，不是基础设施 |
| **TeamMessage** | 事件流 | ❌ **整体删除** | 消息是运行时事件，不是声明式资源 |
| **TeamTask** | 动态工作项 | ❌ **整体删除** | 任务是运行时工作项，不是静态编排 |

### 1.2 核心矛盾

1. **etcd 写放大**：`SessionPollerReconciler` 每 15s 轮询，N agents × M sessions = 高频 status update
2. **对象大小上限**：etcd 单对象 1.5MB，Context 快照 / Transcript 数据无法容纳
3. **无查询能力**：无法 "列出所有 context_pressure > 0.8 的 session"，无法聚合分析
4. **无历史**：CRD 只存当前态，token 用量趋势、session 生命周期审计全部丢失
5. **TeamMessage 堆积**：每条消息一个 CRD 对象，GC 压力大，watch 风暴
6. **未来四级上报模型**：Level 2 事件流 + Level 4 Context 快照的数据量远超 etcd 承载能力

---

## 2. 设计原则

```
CRD = 声明式拓扑与配置（"我要什么"）
DB  = 运行时状态与可观测数据（"现在怎样"）
```

| 层级 | 存储 | 职责 | 数据特征 |
|------|------|------|---------|
| CRD 层（etcd） | Kubernetes API | 实例、应用、Agent 定义、资源定义、静态编排 | 低频写、小对象、供 kubectl/GitOps |
| DB 层（PostgreSQL） | 独立有状态服务 | 一切运行时状态、事件、指标、消息、任务 | 高频写、大对象、供 CLI/Dashboard/Analytics |

### 2.1 CRD 层保留规则

CRD 仅保留以下粒度的资源：

- **应用定义**：Agent（一个 agent 应用的声明，集群内通过 Deployment 管理）
- **实例注册**：AgentEntry（外部/BYO agent 端点声明）、AgentInstance（具体实例地址声明）
- **静态编排**：AgentTeam（成员拓扑、角色分配）
- **资源定义**：MCPServer、ModelConfig、SandboxClaim

判断标准：**如果它是用户通过 YAML/GitOps 声明的、变更频率低、描述"应该有什么"而非"现在怎样"，则属于 CRD。**

### 2.2 DB 层收录规则

除上述 CRD 资源外，**一切运行时数据**均存 DB：

- Session 生命周期与状态（phase、messageCount、contextPressure）
- 事件流 / 时序数据（session events、token usage metrics）
- 大对象（context snapshots、transcript）
- 消息（team messages）
- 动态工作项（team tasks）
- Agent / Team 运行时状态（原 CRD status 字段）
- 需要历史查询 / 聚合分析的一切数据

### 2.3 禁止双写

不存在"CRD 写一份、DB 写一份"的过渡态。运行时数据只写 DB，CRD 不承载任何运行时 status。

---

## 3. 架构总览

```
                    ┌──────────────┐
                    │  kubectl /   │
                    │  GitOps      │
                    └──────┬───────┘
                           │ 声明式期望（YAML）
                           ▼
┌──────────────────────────────────────────┐
│         Kubernetes API (etcd)            │
│  Agent / AgentEntry / AgentInstance /     │
│  AgentTeam / MCPServer / ModelConfig /   │
│  SandboxClaim（纯声明式，无运行时 status） │
└──────────────────┬───────────────────────┘
                   │ watch / reconcile
                   ▼
┌──────────────────────────────────────────┐
│         aistiod (Operator)               │
│                                          │
│  ┌─────────────┐    ┌────────────────┐   │
│  │ Reconciler  │    │ SessionPoller  │   │
│  │(CRD 生命周期)│    │ (运行时采集)    │   │
│  └──────┬──────┘    └───────┬────────┘   │
│         │                   │            │
│         │    ┌──────────────┴─────┐      │
│         │    │   Store Layer      │      │
│         │    │  (DB Repository)   │      │
│         │    └──────────┬─────────┘      │
└─────────┼───────────────┼────────────────┘
          │               │
          ▼               ▼
   ┌────────────┐  ┌────────────┐
   │ K8s 工作负载│  │ PostgreSQL │
   │(Deployment)│  │(运行时全量) │
   └────────────┘  └─────┬──────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
       ┌──────────┐ ┌────────┐ ┌────────┐
       │aistio CLI│ │Dashboard│ │Alert   │
       │          │ │  API   │ │Engine  │
       └──────────┘ └────────┘ └────────┘
```

---

## 4. DB Schema 设计

### 4.1 Session 运行时

```sql
-- Session 主表：一个 agent 上的一个会话
CREATE TABLE sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      TEXT NOT NULL,              -- 框架侧原始 session id
    agent_name      TEXT NOT NULL,              -- 关联 Agent CRD name
    namespace       TEXT NOT NULL,
    framework       TEXT NOT NULL,              -- "claude-agent-sdk", "openclaw", "langgraph", "adk"
    framework_version TEXT,
    phase           TEXT NOT NULL DEFAULT 'active',  -- active / idle / compressing / terminated
    instance_ref    TEXT,                       -- Pod name
    instance_ip     TEXT,
    started_at      TIMESTAMPTZ,
    last_active_at  TIMESTAMPTZ,
    terminated_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(agent_name, namespace, session_id)
);

CREATE INDEX idx_sessions_agent ON sessions(agent_name, namespace);
CREATE INDEX idx_sessions_phase ON sessions(phase) WHERE phase != 'terminated';
```

### 4.2 Session 快照（Level 1 摘要）

```sql
-- 每次轮询产生一条快照，用于趋势分析和告警
CREATE TABLE session_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    captured_at     TIMESTAMPTZ DEFAULT now(),
    message_count   INT,
    prompt_tokens   BIGINT,
    completion_tokens BIGINT,
    total_tokens    BIGINT,
    context_pressure  REAL,                     -- 0.0 ~ 1.0
    is_compacted    BOOLEAN DEFAULT false,
    effective_message_count INT,                -- 压缩后生效消息数
    context_hash    TEXT,                       -- SHA-256 前 16 位，变更检测
    task_summary    JSONB                       -- 当前任务列表快照
);

CREATE INDEX idx_snapshots_session_time ON session_snapshots(session_fk, captured_at DESC);
CREATE INDEX idx_snapshots_pressure ON session_snapshots(captured_at, context_pressure)
    WHERE context_pressure > 0.7;              -- 高压 session 快速查询
```

### 4.3 Session 事件流（Level 2）

```sql
-- 细粒度事件，用于回放和调试
CREATE TABLE session_events (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    seq             INT NOT NULL,               -- 事件序号（session 内递增）
    event_type      TEXT NOT NULL,              -- message / tool_call / tool_result / compaction / error / state_change
    role            TEXT,                       -- user / assistant / system / tool
    content         TEXT,
    tool_name       TEXT,
    tool_input      JSONB,
    tool_output     TEXT,
    tokens_in       INT,
    tokens_out      INT,
    duration_ms     INT,                        -- 该步骤耗时
    framework_meta  JSONB,                      -- 框架特定元信息
    occurred_at     TIMESTAMPTZ DEFAULT now(),
    UNIQUE(session_fk, seq)
);

CREATE INDEX idx_events_session_time ON session_events(session_fk, occurred_at);
CREATE INDEX idx_events_type ON session_events(session_fk, event_type);
```

### 4.4 Context 快照（Level 4）

```sql
-- 某一时刻 agent 的完整生效 Context，用于行为归因
CREATE TABLE context_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    captured_at     TIMESTAMPTZ DEFAULT now(),
    context_hash    TEXT NOT NULL,              -- 去重依据
    system_prompt   TEXT,
    messages        JSONB NOT NULL,             -- 压缩后的生效消息列表
    tools           JSONB,                      -- 当前可用工具
    is_compacted    BOOLEAN DEFAULT false,
    compaction_summary TEXT,
    original_message_count INT,
    compacted_at    TIMESTAMPTZ,
    total_tokens    INT,
    max_tokens      INT,
    framework       TEXT NOT NULL,
    framework_state JSONB                       -- LangGraph graph state / OpenClaw session 元数据等
);

-- 同一 session 相同 hash 不重复存储
CREATE UNIQUE INDEX idx_ctx_dedup ON context_snapshots(session_fk, context_hash);
CREATE INDEX idx_ctx_session_time ON context_snapshots(session_fk, captured_at DESC);
```

### 4.5 Token 用量时序

```sql
-- 用于成本分析、配额管理、趋势图
CREATE TABLE token_usage_metrics (
    id              BIGSERIAL PRIMARY KEY,
    session_fk      UUID REFERENCES sessions(id) ON DELETE SET NULL,
    agent_name      TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    model           TEXT,
    provider        TEXT,
    prompt_tokens   BIGINT,
    completion_tokens BIGINT,
    total_tokens    BIGINT,
    recorded_at     TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_token_agent_time ON token_usage_metrics(agent_name, namespace, recorded_at DESC);
CREATE INDEX idx_token_model ON token_usage_metrics(model, recorded_at DESC);
```

### 4.6 Team 协作

```sql
-- 替代 TeamMessage CRD
CREATE TABLE team_messages (
    id              BIGSERIAL PRIMARY KEY,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    from_member     TEXT NOT NULL,
    to_member       TEXT,                       -- NULL = broadcast
    content         TEXT NOT NULL,
    kind            TEXT DEFAULT 'message',     -- message / task_event / member_event
    nonce           TEXT,
    delivered       BOOLEAN DEFAULT false,
    delivered_at    TIMESTAMPTZ,
    attempts        INT DEFAULT 0,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_team_msg_pending ON team_messages(team_name, namespace, created_at)
    WHERE delivered = false;
CREATE INDEX idx_team_msg_history ON team_messages(team_name, namespace, created_at DESC);

-- 替代 TeamTask CRD
CREATE TABLE team_tasks (
    id              BIGSERIAL PRIMARY KEY,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    subject         TEXT NOT NULL,
    description     TEXT,
    state           TEXT NOT NULL DEFAULT 'pending',  -- pending / in_progress / completed
    owner           TEXT,
    blocked_by      JSONB,                            -- 依赖的任务 id 列表
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_team_tasks_state ON team_tasks(team_name, namespace, state);

-- 任务状态变更审计
CREATE TABLE team_task_history (
    id              BIGSERIAL PRIMARY KEY,
    task_fk         BIGINT REFERENCES team_tasks(id) ON DELETE CASCADE,
    team_name       TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    from_state      TEXT,
    to_state        TEXT NOT NULL,
    owner           TEXT,
    transitioned_at TIMESTAMPTZ DEFAULT now()
);
```

### 4.7 Agent 运行时指标

```sql
-- Agent 级别聚合指标，用于 Dashboard 概览
CREATE TABLE agent_metrics (
    id              BIGSERIAL PRIMARY KEY,
    agent_name      TEXT NOT NULL,
    namespace       TEXT NOT NULL,
    recorded_at     TIMESTAMPTZ DEFAULT now(),
    active_sessions INT DEFAULT 0,
    total_messages  BIGINT DEFAULT 0,
    total_tokens    BIGINT DEFAULT 0,
    avg_context_pressure REAL,
    error_count     INT DEFAULT 0,
    uptime_seconds  BIGINT
);

CREATE INDEX idx_agent_metrics_time ON agent_metrics(agent_name, namespace, recorded_at DESC);
```

---

## 5. CRD 清理方案

> 不考虑兼容性，直接删除。

### 5.1 删除的 CRD

| CRD | 处置 | 数据去向 |
|-----|------|---------|
| AgentSession | 删除 CRD 定义 + controller | sessions / session_snapshots 表 |
| TeamMessage | 删除 CRD 定义 + controller | team_messages 表 |
| TeamTask | 删除 CRD 定义 + controller | team_tasks 表 |

删除范围：
- `api/v1alpha1/agentsession_types.go`
- `api/v1alpha1/teammessage_types.go`
- `api/v1alpha1/teamtask_types.go`
- 对应的 controller、RBAC、deepcopy 生成代码
- `config/crd/` 下对应 YAML

### 5.2 保留与新增的 CRD

| CRD | 变更 |
|-----|------|
| Agent | 删除 `status` 子资源（运行时状态写 DB） |
| **AgentEntry**（新增） | 外部/BYO agent 端点注册 |
| **AgentInstance**（新增） | 具体实例地址声明 |
| AgentTeam | 删除 `status` 子资源（运行时状态写 DB） |
| MCPServer | 保持不变 |
| ModelConfig | 保持不变 |
| SandboxClaim | 保持不变 |

Agent/AgentTeam 的 reconcile 结果（replicas、endpoints、activeSessions 等）写入 DB `agent_metrics` / `team_state` 表，不再回写 CRD status。

### 5.3 运行时数据访问入口

原 `kubectl get agentsessions` 等命令替换为 aistio CLI + Dashboard API：

```bash
# aistio CLI（查 DB）
aistio sessions list --agent my-agent
aistio sessions get <session-id> --context
aistio sessions compress <session-id>
aistio sessions terminate <session-id>
aistio team messages --team my-team
aistio team tasks --team my-team

# Dashboard REST API
GET  /api/v1/sessions?agent=my-agent&phase=active
GET  /api/v1/sessions/{id}/context
POST /api/v1/sessions/{id}/compress
POST /api/v1/sessions/{id}/terminate
GET  /api/v1/teams/{name}/messages
GET  /api/v1/teams/{name}/tasks
```

### 5.4 Session 控制命令

原 AgentSession.spec.commands（compress/terminate）改为通过 API 直接下发：

```
Before: kubectl patch agentsession xxx --type=merge -p '{"spec":{"commands":{"compress":true}}}'
After:  aistio sessions compress <session-id>  →  aistiod API  →  数据面 HTTP/gRPC
```

控制命令不再经过 CRD → reconcile 循环，而是 API 直接触发，延迟更低。

### 5.5 实例管理 CRD 设计（AgentEntry / AgentInstance）

Agent 在集群中多实例部署，控制面需要管理 agent 应用与实例分布。设计类比 Istio 的服务注册模型：

| Istio 概念 | aistio 对应 | 说明 |
|-----------|------------|------|
| K8s Service（自动发现） | Agent CRD → Deployment → Pods | 集群内 agent 从 Pod 自动发现 |
| ServiceEntry（声明外部服务） | **AgentEntry** | 声明外部/BYO agent 端点 |
| WorkloadEntry（声明具体实例） | **AgentInstance** | 声明具体实例地址与属性 |
| WorkloadGroup（实例组） | 通过 labels 分组 | 无需额外 CRD |

**核心区分**：

| 属于 CRD（声明式拓扑） | 属于 DB（运行时状态） |
|---|---|
| 实例地址、协议、能力声明 | 实例当前是否健康 |
| 标签、分组、归属关系 | 最近心跳时间 |
| 健康检查配置（怎么检） | 健康检查结果（检了什么） |
| 纳管关系（"我要管这个"） | 运行指标（sessions 数、token 用量） |

**AgentEntry 示例**（类比 Istio ServiceEntry）：

```yaml
apiVersion: agentscope.io/v1alpha1
kind: AgentEntry
metadata:
  name: external-claude-agent
  namespace: default
spec:
  # 端点地址
  address: "claude-agent.prod.internal"
  port: 8080
  protocol: grpc                    # grpc / http / websocket

  # 框架标识（用于自动选择适配器）
  framework: claude-agent-sdk

  # 能力声明
  capabilities:
    - session-management
    - context-compression
    - tool-execution

  # 健康检查配置（怎么检）
  healthCheck:
    path: /agentscope/health
    intervalSeconds: 10
    timeoutSeconds: 3
    unhealthyThreshold: 3

  # 标签（用于分组、路由、Team 引用）
  labels:
    team: backend
    env: prod
    region: us-east-1
```

**AgentInstance 示例**（类比 Istio WorkloadEntry）：

```yaml
apiVersion: agentscope.io/v1alpha1
kind: AgentInstance
metadata:
  name: claude-agent-vm-01
  namespace: default
spec:
  # 归属的 AgentEntry
  agentEntryRef: external-claude-agent

  # 实例地址
  address: "10.0.1.5"
  port: 8080

  # 实例级标签（zone、权重等）
  labels:
    zone: us-east-1a
    weight: "100"
```

**发现机制**：

```
集群内 Agent（Agent CRD 管理）：
  Agent CRD → Deployment → Pods → 自动发现（watch Pod/Service）
  无需手动创建 AgentInstance

外部 / BYO Agent：
  用户创建 AgentEntry + AgentInstance → 控制面纳管
  或通过 aistio CLI 注册：
    aistio register --address 10.0.1.5:8080 --framework openclaw --name my-agent
```

---

## 6. Store Layer 设计

### 6.1 接口抽象

```go
// internal/store/store.go

// Store 是运行时数据的持久化接口。
// 实现可以是 PostgreSQL、SQLite（开发/测试）、或内存（单元测试）。
type Store interface {
    Sessions() SessionRepository
    Events() EventRepository
    ContextSnapshots() ContextSnapshotRepository
    Metrics() MetricsRepository
    TeamMessages() TeamMessageRepository

    // Migrate 执行 schema 迁移
    Migrate(ctx context.Context) error
    // Close 关闭连接
    Close() error
}

type SessionRepository interface {
    Upsert(ctx context.Context, session *Session) error
    Get(ctx context.Context, agentName, namespace, sessionID string) (*Session, error)
    List(ctx context.Context, filter SessionFilter) ([]*Session, error)
    UpdatePhase(ctx context.Context, id uuid.UUID, phase string) error
}

type EventRepository interface {
    Append(ctx context.Context, event *SessionEvent) error
    List(ctx context.Context, sessionFK uuid.UUID, opts ...EventOption) ([]*SessionEvent, error)
}

type ContextSnapshotRepository interface {
    // PutIfChanged 仅在 context_hash 变化时写入
    PutIfChanged(ctx context.Context, snapshot *ContextSnapshot) (bool, error)
    Latest(ctx context.Context, sessionFK uuid.UUID) (*ContextSnapshot, error)
}

type MetricsRepository interface {
    RecordTokenUsage(ctx context.Context, metric *TokenUsageMetric) error
    RecordSnapshot(ctx context.Context, snapshot *SessionSnapshot) error
    QueryTokenUsage(ctx context.Context, filter TokenFilter) ([]*TokenUsageMetric, error)
}

type TeamMessageRepository interface {
    Send(ctx context.Context, msg *TeamMessage) error
    ListPending(ctx context.Context, teamName, namespace string) ([]*TeamMessage, error)
    MarkDelivered(ctx context.Context, id int64) error
}
```

### 6.2 实现选择

| 环境 | 实现 | 说明 |
|------|------|------|
| 生产（K8s） | PostgreSQL（CloudNativePG） | HA、备份、分区 |
| 开发 / 单节点 | SQLite（嵌入 aistiod） | 零依赖，`--storage=sqlite` 启动 |
| 单元测试 | 内存实现 | 快速、隔离 |

### 6.3 配置

```yaml
# aistiod 配置
storage:
  driver: postgres          # postgres | sqlite | memory
  postgres:
    dsn: "postgres://aistio:***@aistio-db:5432/aistio?sslmode=require"
    maxOpenConns: 20
    maxIdleConns: 5
    connMaxLifetime: 30m
  sqlite:
    path: /var/lib/aistio/aistio.db
  retention:
    sessionEvents: 7d       # 事件流热数据保留
    snapshots: 30d          # 快照保留
    contextSnapshots: 14d   # Context 快照保留
    metrics: 90d            # 指标保留
```

---

## 7. 数据流

### 7.1 SessionPoller

```
Poll Agent（HTTP/gRPC）→ 写 DB (sessions + session_snapshots)
```

不再创建/更新任何 CRD。轮询结果全部落 DB。

### 7.2 Connector SDK（gRPC）

```
SessionReport → 写 DB (sessions + session_snapshots)
EventReport   → 写 DB (session_events)
ContextReport → 写 DB (context_snapshots, PutIfChanged)
TeamEvent     → 写 DB (team_messages / team_tasks)
```

### 7.3 Reconciler（CRD → 工作负载）

```
Agent CRD 变更 → reconcile → 创建/更新 Deployment + Service
                           → 写 DB (agent_metrics: endpoints, replicas)
```

CRD reconcile 只管工作负载生命周期，运行时观测写 DB。

### 7.4 CLI / Dashboard / Alert

```
aistio CLI    → aistiod REST API → 查 DB
Dashboard     → aistiod REST API → 查 DB
Alert Engine  → 定期查 DB → 触发告警
```

所有运行时数据的读写均通过 DB，不经过 K8s API。

---

## 8. 部署方案

### 8.1 PostgreSQL 部署

推荐使用 [CloudNativePG](https://cloudnative-pg.io/) Operator：

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: aistio-db
  namespace: aistio-system
spec:
  instances: 3
  storage:
    size: 50Gi
  postgresql:
    parameters:
      shared_buffers: "256MB"
      effective_cache_size: "1GB"
  backup:
    barmanObjectStore:
      destinationPath: "s3://aistio-backup/pg"
    retentionPolicy: "30d"
```

### 8.2 轻量部署（开发 / 小规模）

单节点场景使用 SQLite，无需额外部署：

```bash
aistiod --storage=sqlite --sqlite-path=/var/lib/aistio/aistio.db
```

---

## 9. 实施策略

> **原则：不考虑兼容性，一步到位。** aistio 尚未发布稳定版，无需双写过渡、deprecated 标记或分阶段清理。

### 直接实施

1. 实现 `Store` 接口 + PostgreSQL / SQLite 实现 + schema migration
2. **删除 AgentSession / TeamMessage / TeamTask 三个 CRD**（定义 + controller + RBAC + CRD YAML）
3. **Agent / AgentTeam CRD 去除 status 子资源**
4. `SessionPoller` 直接写 DB
5. Connector SDK 直接写 DB
6. 实现 aistio CLI + Dashboard REST API（查 DB）
7. Session 控制命令改为 API 直接下发（不经 CRD reconcile）
8. 清理所有引用已删除 CRD 的代码

---

## 10. 容量估算

| 数据 | 单条大小 | 500 agents × 10 sessions | 日增量 |
|------|---------|--------------------------|--------|
| sessions | ~500B | 5000 行 × 500B = 2.5MB | 小 |
| session_snapshots（15s 间隔） | ~300B | 5000 × 5760/天 = 28.8M 行 | ~8.6GB/天 |
| session_events（Level 2 开启） | ~1KB | 取决于事件频率 | ~5-20GB/天 |
| context_snapshots（hash 去重） | ~10-50KB | 仅变更时写入 | ~1-5GB/天 |
| token_usage_metrics | ~200B | 同 snapshots 频率 | ~5.7GB/天 |

**建议**：
- `session_snapshots` 按天分区，热数据 7 天，冷数据归档
- `session_events` 默认不开启（Level 2 可选），开启后 3 天保留
- `context_snapshots` 按 hash 去重，14 天保留
- 生产环境 PostgreSQL 建议 100GB+ 存储 + 定期 VACUUM

---

## 11. 与四级上报模型的关系

| 上报级别 | 数据落地 | 存储位置 |
|---------|---------|---------|
| Level 1 摘要快照 | `session_snapshots` | 仅 DB |
| Level 2 事件流 | `session_events` | 仅 DB |
| Level 3 完整内容 | 按需从数据面拉取，不落盘 | 不持久化（或临时缓存） |
| Level 4 Context 快照 | `context_snapshots` | 仅 DB |

所有级别数据均存 DB。CRD 层不承载任何运行时数据。
