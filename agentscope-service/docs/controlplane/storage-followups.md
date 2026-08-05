# 存储迁移后续工作清单

> 状态：待实施
> 创建背景：运行时数据持久化（[storage-design.md](./storage-design.md)）首轮已落地——`internal/store`（memory + postgres）、删除 AgentSession / TeamMessage / TeamTask CRD、REST/CLI 改查 DB、控制命令 API 直发。本文档汇总**本轮明确延后或未覆盖**的事项，供下一轮排期。
> 关联文档：[storage-design.md](./storage-design.md)、[framework-integration.md](./framework-integration.md)、[sdk-design.md](./sdk-design.md)（数据面生产者 / 协议扩展）

---

## 1. Agent / AgentTeam status 迁出 CRD（高优先级）

### 1.1 原设计要求

[storage-design.md §5.2](./storage-design.md) 要求：

- Agent CRD 删除 `status` 子资源（运行时状态写 DB）
- AgentTeam CRD 删除 `status` 子资源（运行时状态写 DB）
- reconcile 结果（replicas、endpoints、activeSessions 等）写入 DB `agent_metrics` / `team_state` 表，不再回写 CRD status

### 1.2 本轮为何延后

实测依赖面过大，强删会破坏现有行为：

| 依赖方 | 读/写的 status 字段 | 用途 |
|--------|---------------------|------|
| `SessionPollerReconciler` | `Agent.status.DataPlaneInfo.ContractLevel` | 是否开启 session 轮询的 gate（`< 2` 则跳过） |
| `AgentSessionReconciler`（已删）/ 未来命令路径 | 同上 | compress/terminate 的 contractLevel gate |
| `AgentReconciler` / `BYOWorkloadReconciler` / `DiscoveryReconciler` | `Replicas`、`Conditions`、`DataPlaneInfo`、`ObservedGeneration`、`ActiveSessions` | reconcile 结果回写 + Ready 判定 |
| `AgentTeamReconciler` / `Lifecycle` / `TeamEventSink` | `Phase`、`Lead`、`Members`、`Tasks`、`Conditions` | 团队生命周期状态机 |
| `httpapi` / `aistioctl` | `Replicas`、`ActiveSessions`、`Conditions` | REST / CLI 展示 |
| kubectl printcolumn | `Ready`、`Replicas`、`Sessions` | `kubectl get agents` 可读性 |

另：彻底删除 status 也偏离 controller-runtime 惯例（`observedGeneration` / `Conditions` 通常留在 CRD）。

### 1.3 建议方案（拆分，而非一刀切）

```
CRD status 保留（K8s 原生 reconcile 结果）:
  observedGeneration / revision / managementMode
  conditions（Ready 等）
  replicas（desired / ready / available）
  endpoints（可选）

迁入 DB（真正的运行时观测）:
  activeSessions          → agent_metrics / sessions 聚合
  dataPlaneInfo 探测结果  → 新建 agent_runtime 表或扩 agent_metrics
  AgentTeam 成员会话/重启明细 → 新建 team_state / team_member_state 表
  AgentTeam.Status.Tasks 汇总 → 已可从 TeamTasks().GetSummary 实时算，可停写 CRD
```

### 1.4 实施要点

1. 新增 `agent_runtime`（或扩展 `agent_metrics`）表：存 `contract_level`、`sdk_version`、`last_probe_at`、`capabilities` 等
2. SessionPoller / 命令路径的 ContractLevel gate 改为读 Store，不再读 `agent.Status.DataPlaneInfo`
3. AgentTeam 成员健康、phase 状态机逐步迁到 DB；CRD status 可先瘦身为 `phase + conditions`
4. 更新 kubectl printcolumn：去掉依赖运行时字段的列，或改为只显示 replicas/Ready
5. 更新 `httpapi` agent 详情接口：运行时字段改查 DB
6. **仍禁止双写**：同一字段只存在于 CRD 或 DB 一侧

### 1.5 验收标准

- [ ] `Agent.status` 中不再出现 `activeSessions`、`dataPlaneInfo`
- [ ] Session 轮询 gate 完全不依赖 CRD status
- [ ] `kubectl get agents` 仍能显示 Ready / Replicas（或明确文档说明改用 CLI）
- [ ] AgentTeam 任务汇总只来自 Store，CRD 不再缓存 `status.tasks`

---

## 2. SQLite 驱动（中优先级）

### 2.1 原设计

[storage-design.md §6.2 / §8.2](./storage-design.md)：开发 / 单节点用嵌入式 SQLite，`aistiod --storage=sqlite --sqlite-path=/var/lib/aistio/aistio.db`。

### 2.2 本轮现状

仅实现 `memory`（默认）与 `postgres`。`memory` 重启即丢数据，多副本不共享，不适合哪怕是「轻量持久化」的单节点场景。

### 2.3 实施要点

1. 新增 `internal/store/sqlite`，实现全部 `store.Store` 接口
2. 驱动选型注意：**纯 Go、无 cgo**（aistiod 是 multi-arch 静态镜像），推荐 `modernc.org/sqlite`
3. Schema：与 postgres 的 `0001_init` 对齐语义；SQLite 无 `JSONB` / 部分索引 / `FOR UPDATE SKIP LOCKED`，需用 `TEXT` + JSON 函数、普通索引、应用层锁替代
4. Migration：可复用 embed.FS 模式，或维护一份 SQLite 方言的 SQL
5. 注册：`store.RegisterOpener(store.DriverSQLite, Open)`；flag `--storage-driver=sqlite`、`--sqlite-path=...`
6. Helm：单节点 profile（如 `profiles/sqlite.yaml`）挂 emptyDir / PVC

### 2.4 验收标准

- [ ] `storetest.RunSuite` 在 sqlite 实现上全绿
- [ ] `aistiod --storage-driver=sqlite` 可启动，重启后 session 数据仍在
- [ ] CI 可用临时文件跑 sqlite 集成测试（无需 PG 容器）

---

## 3. AgentEntry / AgentInstance CRD（中优先级）

### 3.1 原设计

[storage-design.md §5.5](./storage-design.md)：类比 Istio ServiceEntry / WorkloadEntry，声明外部 / BYO agent 端点与具体实例。

| Istio | aistio | 说明 |
|-------|--------|------|
| K8s Service | Agent CRD → Deployment → Pods | 集群内自动发现（已有） |
| ServiceEntry | **AgentEntry** | 外部 / BYO agent 端点声明 |
| WorkloadEntry | **AgentInstance** | 具体实例地址与属性 |

### 3.2 本轮现状

集群内 Agent 仍只靠 Agent CRD → Deployment → Pod 自动发现。外部 agent 无法以声明式方式纳管；`aistio register --address ...` CLI 也未实现。

运行时实例清单（subagent / workspace / health）的数据面上报协议见 [sdk-design.md §3.5 InventoryReport](./sdk-design.md)；与本 CRD 的边界是：CRD 管声明式拓扑，InventoryReport / Store 管运行时观测。

### 3.3 边界（再次强调）

| 属于 CRD（声明式拓扑） | 属于 DB（运行时状态） |
|------------------------|------------------------|
| 实例地址、协议、能力声明 | 实例当前是否健康 |
| 标签、分组、归属关系 | 最近心跳时间 |
| 健康检查配置（怎么检） | 健康检查结果 |
| 纳管关系 | sessions 数、token 用量 |

### 3.4 实施要点

1. 新增 `api/v1alpha1/agententry_types.go`、`agentinstance_types.go` + CRD YAML + RBAC + webhook
2. Reconciler：把 Entry/Instance 纳入 endpoint 解析（扩展 `internal/endpoints`），供 SessionPoller / compress/terminate / ASDP 连接使用
3. 健康检查结果写 Store（新表或 `agent_metrics`），不回写 CRD status（与 §1 原则一致）
4. CLI：`aistioctl register --address HOST:PORT --framework ... --name ...`（内部创建 AgentEntry + AgentInstance）
5. Team 成员的 `agentRef` 需能引用 AgentEntry

### 3.5 验收标准

- [ ] 用户可 YAML 声明外部 agent 并被 SessionPoller 轮询
- [ ] `aistioctl register` 可一键注册
- [ ] 健康检查结果只在 DB / CLI / Dashboard 可见，不进 CRD status

---

## 4. 控制命令走 ASDP（低优先级优化）

### 4.1 现状

compress / terminate 已改为 API 直发，但仍走 **HTTP prober**（`internal/prober` → 数据面 HTTP 合约）。`asdp.Distributor.SendSessionCommand` 早已存在，从未接线。

### 4.2 目标

优先通过 ASDP gRPC 下游 `SessionCommand` 下发；HTTP 作为无 ASDP 连接时的回退。

### 4.3 实施要点

1. `httpapi` compress/terminate：先查本副本 / 任意副本是否有该 agent 的 ASDP 连接
2. 有连接 → `Distributor.SendSessionCommand`；无连接 → 回退 `Prober.SendCompress/SendTerminate`
3. 多副本下连接不在本机时：需引入跨副本转发，或要求客户端重试 / 通过 leader 聚合——需单独设计
4. 更新 contract / ASD 文档说明双通道行为

---

## 5. framework-integration 控制面增强（与存储相关的尾巴）

[framework-integration.md](./framework-integration.md) Phase 5 原写「AgentSession CRD 扩展」，现应改为 Store 侧增强。**写入方与协议细节以 [sdk-design.md](./sdk-design.md) 为准**（当前 `session_events` / `context_snapshots` 表与 REST 读路径已就绪，缺数据面生产者）。

| 项 | 说明 |
|----|------|
| Context 查询完善 | `GET /sessions/{id}/context` 已有骨架；需对接 Level 4（ASDP `ContextReport` + 数据面 `GET /agentscope/sessions/{id}/context`）并 `PutIfChanged`；见 sdk-design §3.4 / §4 / §6 |
| 按 framework / phase / 时间查询 | SessionFilter 已支持 framework/phase；补时间范围、Dashboard 聚合 API |
| Level 2 事件流开关 | 默认关闭；通过 Agent 注解或全局配置开启后，ASDP `EventReport` → `session_events`；见 sdk-design §3.3 |
| ASDP SessionSnapshot 扩展字段 | proto 增加 `framework` / `context_hash` / `is_compacted` / `effective_message_count`；见 sdk-design §3.1 |

---

## 6. 运维与体验尾巴

| 项 | 说明 |
|----|------|
| memory 默认的生产误用防护 | 已有启动日志 + NOTES 告警；可考虑：多副本且 driver=memory 时 fail-fast，或 Helm 在 `replicaCount>1` 时强制要求 postgres |
| 分区与归档 | `session_snapshots` / `session_events` 按天分区、冷数据归档（capacity §10）；当前仅 RetentionWorker 按时间 DELETE |
| Store 指标 | Prometheus：upsert QPS、pending messages、purge 行数、连接池使用率 |
| 备份文档 | 补充 CloudNativePG backup / restore 与 aistio 数据面的操作手册 |
| getting-started / 英文 README | 持续扫清对已删 CRD 的引用（博客类历史文档可保留并加注） |

---

## 7. 建议排期

| 轮次 | 内容 | 依赖 |
|------|------|------|
| 下一轮 A | §1 Agent/AgentTeam status 拆分迁 DB | 无硬依赖 |
| 下一轮 B | §3 AgentEntry / AgentInstance | 与 §1 的「健康结果写 DB」可并行设计 |
| 下一轮 C | §2 SQLite 驱动 | 独立，可与 A/B 并行 |
| 顺带 | §4 ASDP 命令通道、§5 Context/事件流、§6 运维加固 | 按需插入 |

---

## 8. 明确不在本清单内（已完成）

以下首轮已交付，无需再排：

- `internal/store` 接口 + memory + postgres + migration + storetest
- 删除 AgentSession / TeamMessage / TeamTask CRD 及全部残留
- SessionPoller / ASDP sink 写 DB
- Team 消息 outbox + `TeamMessageDispatcher`
- Team 任务改 Store（原 `TaskStoreInterface` 薄封装）
- REST `/api/v1/sessions...`、compress/terminate API 直发
- `aistioctl session(s)` / `team messages|tasks`
- `RetentionWorker`、Helm storage 配置、`profiles/postgres.yaml`、CNPG 样例
