# 设计改动记录（Design Changes Log）

> 本文档记录对 `design.md`（AgentScope Control Plane 基础设计）的重要方案性改动，采用 ADR（Architecture Decision Record）风格，便于回溯每次决策的背景、结论与影响面。
>
> 关联文档：[`design.md`](./design.md)

---

## 目录

- [CR-001 Instance 模式 Session 持久化：Sidecar + 对象存储（方案 B）](#cr-001)
- [CR-002 控制面存储架构：声明式核心（etcd）+ 运行时状态服务（DB）](#cr-002)
- [CR-003 AgentScope 数据面深度托管：控制面即 DistributedStore 后端](#cr-003)

---

<a id="cr-001"></a>

## CR-001 Instance 模式 Session 持久化：Sidecar + 对象存储（方案 B）

**状态：** 已采纳（Accepted）
**影响章节：** `design.md` 6.2、6.4

### 背景 / 问题

`sessionAffinity: Instance` 的 session 状态绑定在特定 Pod。对于 Claude Code CLI、Codex CLI 等**第三方 agent**，session 是持久化在本地目录的**文件态**（如 `~/.claude/projects/<hash>/*.jsonl`），无法嵌入 SDK。原设计将 Instance 模式下的 Pod 故障一律定性为"Session 丢失，标记 Lost",这对第三方文件态 agent 不可接受——它们的 session 本可通过 `claude --resume` 恢复，真正会丢的只是承载它的那块磁盘。

### 决策

采用**方案 B：Sidecar + 对象存储备份/恢复**，而非方案 A（StatefulSet + 每实例 PVC）。

- 主容器与 sidecar 共享卷挂载 session 目录；sidecar 用 fsnotify 监听变更，增量同步到对象存储（S3/OSS/MinIO），object key 携带 sessionId。
- Pod 重建（任意节点）时，`initContainer` 先按 sessionId 从对象存储拉回 session 目录，gate 确认后再启动主容器，`--resume` 续上。
- 通过 ASDP `SessionReport` 上报 `session → checkpoint 位置`（而非 `session → podIP`）。

### 为什么选 B 而非 A

| 维度 | 方案 A: StatefulSet + PVC | 方案 B: Sidecar + 对象存储（采纳） |
|---|---|---|
| 跨节点/zone 恢复 | 受卷 zone 亲和约束 | 不绑节点，自由漂移 |
| 部署形态 | 必须改为 StatefulSet | Deployment 即可，零改动 |
| 适配第三方 CLI | 需用户配合改形态 | 零代码、零形态改动，最贴合 |
| RPO | 更小 | 存在两次同步间的窗口 |
| 存储依赖 | 网络块存储 | 对象存储 |

### 引入的模型：affinity × durability 两个正交维度

原 `sessionAffinity` 把"绑不绑实例"与"能不能恢复"混在一起，拆成两个正交维度：

| affinity | durability | 含义 | 典型场景 |
|---|---|---|---|
| None | (N/A) | 无状态，任意实例可服务 | 自研无状态 agent |
| Instance | Ephemeral | 绑实例，Pod 死即丢 | 可丢弃的临时会话 |
| Instance | Persistent | 绑实例，Pod 死可恢复 | **Claude CLI 等第三方文件态 agent** |

### 具体改动

- **6.2**：扩展 sidecar 架构图与职责（观测 + 持久化两组）；新增「Session 状态持久化与恢复（方案 B）」段落、方案 A/B 取舍表、恢复边界；注入 annotation 增加 `session-durability` / `session-state-dir` / `session-store`。
- **6.4**：`SessionAffinity` 拆为 affinity × durability；更新 `/agentscope/info` 与 Agent CRD 声明示例；行为差异表修正「Pod 故障 / 扩缩容」；归属索引在 Persistent 模式下**落 AgentSession `status`**（stateLocation / checkpointVersion），路由恢复改为声明式、抗控制面重启；新增 Persistent 故障恢复流程与扩缩容「策略 3：Checkpoint 兜底」。
- 协议同步：ASDP `ConnectRequest` 增 `durability` / `state_location`；`SessionSnapshot` 增 `state_location` / `checkpoint_version`；两处 `status.dataPlaneInfo` 补 `durability`。

### 恢复边界（不可恢复项）

- 进行中的那一轮 LLM 推理不可恢复。
- RPO 窗口内未同步的写入可能丢失。
- 准确表述为"续上到最近一个 checkpoint",非零丢失。

---

<a id="cr-002"></a>

## CR-002 控制面存储架构：声明式核心（etcd）+ 运行时状态服务（DB）

**状态：** 已采纳（Accepted）
**影响章节：** `design.md` 3.6、3.7、新增 3.8、4.1、4.2、4.6、4.9、4.11、5.1、6.5、7.1、8、10、11、分期计划第二期

### 背景 / 问题

原设计将 **AgentSession** 建模为"每会话一个 CRD 存 etcd",并将 **Team Task Store** 标注为 `CRD / etcd`、用 etcd `resourceVersion` 做乐观锁。但：

- etcd 是为**低基数、低频变更的声明式配置**设计的强一致存储，不是通用数据库。
- Session 随访问量线性增长，且 `status` 每 10s 被 SessionReport 刷新；每次 update 生成新 MVCC revision → compaction/defrag 压力剧增。**写入 churn 会先于容量把 etcd 打爆。**
- 控制面 CRD 通常与集群核心资源**共用同一个 etcd**，session/message 级别的高频写会波及整个 kube-apiserver（**爆炸半径**）。

### 决策

按"**基数由什么驱动 + 写入频率**"这一判据，将控制面拆成两个平面：

```
etcd（CRD）  ←  期望状态：基数 = #agent / #config（有界），低频
外部 DB      ←  观测态 + 运行时流水：基数 = 流量/会话/消息（无界），高频
```

- **声明式核心**（controller-runtime，只碰 etcd CRD）：Agent / ModelConfig / MCPServer / AgentTeam(声明+phase) / SandboxClaim。
- **运行时状态服务**（DB-backed，自有存储层 + REST API）：SessionStore、SessionMessageStore、TeamTaskStore、TeamMessageStore、RevisionStore、RoutingIndex。

技术选型：**PostgreSQL** 作主库，**Redis** 作热缓存/路由索引，**对象存储** 仅承担第三方文件态 checkpoint（CR-001）与冷数据 TTL 归档。

> **决策定论（无灰色地带）**：系统处于新开发阶段，无兼容包袱。每份数据只有一个权威归属，**不做"既建 CRD 又落 DB"的双轨**。划分规则：声明式配置或与 K8s 工作负载/Pod 一一对应（基数受集群容量约束）→ CRD；随流量无界增长或高频刷新 → DB。据此：**AgentSession 彻底无 CRD**；**SandboxClaim 保持 CRD**（一沙箱≈一 Pod，有界，且用 ownerRef 级联删除）；**AgentTeam 保持 CRD 但只存声明 + 粗粒度 phase**，运行时明细在 DB。

### 数据实体归属一览（每项唯一归属）

| 数据实体 | 基数驱动 | 写入频率 | 唯一归属 |
|---|---|---|---|
| Agent spec / status | agent 数（有界） | 低 / 周期 | etcd（CRD） |
| ModelConfig / MCPServer | 配置数 | 极低 | etcd（CRD） |
| **SandboxClaim** | 沙箱数（≤ 集群 Pod 容量） | 中 | etcd（CRD，ownerRef 级联删除） |
| AgentTeam spec + 粗粒度 phase | 团队数（有界） | create / 状态迁移 | etcd（CRD） |
| AgentTeam 运行时明细（members/tasks 状态） | 团队 × 事件 | 高 | DB（**不在 CRD status**） |
| **AgentSession** | 随流量增长 | 极高（10s） | DB（无 CRD） |
| **Team Tasks** | 随团队活动增长 | 高 | DB（version 列乐观锁） |
| **Team Messages** | 随流量增长 | 极高 | DB |
| **Agent Revisions** | 随 push 次数增长 | append-only | DB |
| **Session Messages** | 单会话内无界 | 高 | DB（冷数据归档对象存储） |

### 关键设计点

- **乐观并发换实现不换语义**：task claim 由 etcd resourceVersion 改为 **DB `version` 列**（或 `SELECT ... FOR UPDATE`），冲突仍返回 409。
- **"watch" 换成事件总线**：改用 PostgreSQL LISTEN/NOTIFY 或进程内事件总线，驱动 ASDP gRPC 下推 + Dashboard SSE。
- **跨平面引用靠 name/UID，不靠 ownerReference**：etcd 级联 GC 管不到 DB，孤儿清理靠应用层 + TTL。
- **数据流改向**：ASDP 上报直接落 DB 存储层，不经过 etcd/API server。
- **非目标**：不为 AgentSession / Team Tasks / Messages 提供 `kubectl` 原生资源，**不引入 aggregated apiserver / Kine**——只走 REST API / CLI / Dashboard，避免双轨复杂度。

### 代价

- 控制面从无状态 controller 变为**有状态服务**：需 DB 的 HA、备份、schema 迁移、连接池。
- DB 承载的资源失去 K8s 原生 watch/kubectl/RBAC，靠 REST API + 自建 authz 补齐。
- 收益：session/team/message 规模与集群 etcd 彻底解耦，保护整个集群的爆炸半径。

### 具体改动

- **3.6**：AgentSession 改为**无 CRD**，示例改为 REST 响应结构（JSON），去掉 `kind: AgentSession` 与 aggregated apiserver 提法。
- **3.7**：SandboxClaim **定论保留 CRD**（一沙箱≈一 Pod、有界、ownerRef 级联），去掉"建议 DB"的模棱表述与 `SandboxRecordStore`。
- **新增 3.8**：控制面存储架构（两平面切分、每项唯一归属表、决定性划分规则、非目标：不引入 aggregated apiserver）。
- **4.1**：架构图 Task Store 由 `CRD / etcd` 改为 `DB, version 锁`；Message Router 标注 DB 存储。
- **4.2**：AgentTeam CRD `status` 只留 `phase`，成员/任务明细移到 REST 响应示例（DB）。
- **4.6**：Task Store 明确 PostgreSQL + version 列乐观锁；claim 请求体 `resourceVersion` 改 `version`。
- **4.9 / 4.11**：可恢复性矩阵与对比表来源改为 DB。
- **5.1**：`AgentSessionController` 改为 `SessionService`（非 controller，接 ASDP、落 DB）。
- **6.4**：session 归属索引唯一走 ASDP gRPC（删除 HTTP 兜底通道）；Persistent 归属持久化到 SessionStore(DB)；session 记录示例改 JSON。
- **6.5**：可观测性上报路径改为 ASDP gRPC + SessionStore(DB)。
- **7.1**：`/sessions`、`/messages`、`/revisions`、`/teams` 相关端点标注后端存储。
- **8**：`api/v1alpha1` 删 `agentsession_types.go`、加 `agentteam_types.go`；`controller/` 删 `session_controller.go`；新增 `internal/store/`（去掉 `sandbox_record_store.go`）与 `internal/runtime/`（session service）。
- **10**：技术选型「数据存储」拆为 声明式(etcd，含 SandboxClaim)/运行时(PostgreSQL)/热缓存(Redis)/冷归档(对象存储)，删除 aggregated apiserver 行。
- **11**：集成点表中 AgentState / TaskContext 映射改为 DB。
- **分期计划第二期**：交付内容改为运行时状态服务 + `internal/store` + DB schema/迁移。

### 后续待办（Open Items）

- DB schema 详细设计（表结构、索引、分区/归档策略）。
- TTL / 归档策略的具体参数（活跃、终止、失败各自保留时长）。
- 多租户下的 DB 隔离与配额策略。

---

<a id="cr-003"></a>

## CR-003 AgentScope 数据面深度托管：控制面即 DistributedStore 后端

**状态：** 已采纳（Accepted）
**影响章节：** `design.md` 新增 6.6、分期计划第二期

### 背景 / 问题

自研数据面 AgentScope Java 已经把所有需要分布式持久化的存储点收敛到**一个接口族 `DistributedStore`**（`AgentStateStore` / `BaseStore` / `SandboxSnapshotSpec` / `SandboxExecutionGuard` / `MessageBus` / `AsyncToolRegistry`），外加 Skill 仓库、Prompt 配置、A2A 注册。但一个生产部署今天仍要**各自拉起并接线 Redis / MySQL / OSS / Nacos**——存储抽象收敛了，运维却没收敛。其中 Nacos 文档更是直接把自己定位为 AgentScope 的"统一控制面"（Skill + Prompt + A2A 三合一），与本控制面职责重叠。

### 决策

控制面**成为 `DistributedStore` 接口族的托管实现**，把数据面的存储点收敛成"只连控制面一个后端"。两条关键对齐：

- **AgentState ≡ SessionStore**：`AgentStateStore` 按 `(userId, sessionId, key)` 寻址，与 3.8 的 `SessionStore` 是同一份数据。数据面 `call()` 结束写 `AgentState` == ASDP `SessionReport` 落 `SessionStore`，观测与存储合一。
- **自研数据面天然 `sessionAffinity: None`**：AgentScope 是无状态引擎 + 共享状态存储，任意副本可加载。控制面当共享后端即可，无需 CR-001（第三方 CLI）的 Instance + checkpoint。

### 映射一览（DistributedStore → 控制面）

| 组件 | 控制面归属 | 存储层 |
|---|---|---|
| `AgentStateStore` | 合流 `SessionStore` | PostgreSQL + Redis |
| `BaseStore`（CAS） | `ControlPlaneBaseStore`（`version` 列 CAS） | PostgreSQL（大值溢出对象存储） |
| `SandboxSnapshotSpec` | 元数据入 DB，blob presigned 直传 | 对象存储 |
| `SandboxExecutionGuard` | Lock Service（`SET NX` 租约） | Redis |
| `MessageBus` | 复用 Team Message Router | PostgreSQL + gRPC |
| `AsyncToolRegistry` | 复用 Team Task Store | PostgreSQL |
| Skill / Prompt / A2A | push API `skills[]` / ConfigPush / DiscoveryController（**取代 Nacos**） | 对象存储/OCI + etcd |

真正新增的只有 `ControlPlaneBaseStore` 与 Lock Service；其余复用 CR-002 已规划组件。

### 关键设计点

- **一行接入**：SDK 提供 `ControlPlaneDistributedStore.fromEnv()`；Declarative 模式由 adapter 自动注入 endpoint/Token，用户不写存储配置。
- **只承载低频写 + 可缓存读**：`AgentState` 仅 call 结束整体写一次；热路径读走 SDK 本地缓存 + Redis。
- **大 blob 直传对象存储**：控制面只发 presigned URL + 记元数据，不做数据搬运。
- **断连降级**：控制面不可用时 SDK 本地落盘 + 重连回填（状态写不能丢）。
- **边界止于 `DistributedStore` 接口层**：不侵入 ReAct 循环 / middleware。

### 分期落地（第一期精简，不过度设计）

- **第一期不含托管存储**：adapter 只把 Java 数据面跑起来，数据面沿用自带后端（单机文件 / 自备 Redis）。
- **第二期**：`ControlPlaneAgentStateStore` 合流 `SessionStore`（复用第二期本就要建的 SessionStore，近零新增）。
- **后续期**：`ControlPlaneBaseStore`、Snapshot + Lock、`MessageBus` / `AsyncToolRegistry`；最后取代 Nacos 的 Skill / Prompt / A2A。

### 具体改动

- **新增 6.6**：AgentScope 数据面深度托管——契合点（AgentState≡SessionStore、天然 None）、DistributedStore → 控制面映射表、一行接入、边界与权衡、分期落地。
- **分期计划第二期**：W1-W2 增 `ControlPlaneAgentStateStore`；完成标准补一条（AgentState 落控制面 SessionStore，其余托管存储留后续期，第一期用自带后端）。

### 后续待办（Open Items）

- `ControlPlaneBaseStore` 的 namespace/CAS 语义与 DB schema。
- Lock Service 的租约续期、fail-safe 降级策略。
- SDK 断连本地 WAL 与回填的一致性设计。
- 取代 Nacos 的迁移路径与并存期方案。
