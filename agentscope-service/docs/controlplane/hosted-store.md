# 托管分布式协调能力（Hosted Store）

> 状态：**已实施**（P0–P4 + CR-005 第二批代码已落地；对象存储 presigned 与 TokenReview 身份仍为后续）
> 关联文档：[contract.md](./contract.md)、[storage-design.md](./storage-design.md)、[../blogs/design-changes.md](../blogs/design-changes.md)（CR-004 修订 CR-003；CR-005 第二批协调元数据）

## 实施状态

已落地（第一批 P0–P4）：

- Store repos：`memory` + `postgres`（KV / locks / snapshots / bus / async tools）
- `/api/v1/dp/*` HTTP API + 总开关 `--enable-hosted-store`
- Java `ControlPlaneStores` 及五项组件（`BaseStore` / `SandboxExecutionGuard` / `SandboxSnapshotSpec` / `MessageBus` / `AsyncToolRegistry`）
- **`AgentStateStore` 仍由用户自备**（Redis / MySQL / PostgreSQL / OSS）

已落地（第二批 CR-005）：

- `dp_tasks` + `ControlPlaneTaskRepository`（服务端 leader-only orphan sweep）
- `ControlPlaneSessionTurnGate`（基于 `dp_locks` 的 per-session turn 串行化）
- Gateway session/route 映射、`StoreBackedPeriodicGate`、`BaseStoreSkillUsageBackend`（均走 `BaseStore` CAS）
- Core `AgentStateStore` 乐观并发 API（`getVersioned` / `saveIfVersion`，`ConflictPolicy` OVERWRITE / FAIL / APPEND_MERGE）——**`AgentStateStore` 本身仍不托管**

后续：对象存储 presigned URL、K8s TokenReview 身份绑定。

控制面托管 AgentScope 数据面 `DistributedStore` 接口族中的**协调类能力**，让数据面不再直连 Redis / OSS 等中间件。

## 0. 范围与非目标

| 组件 | 是否托管 | 说明 |
|---|:---:|---|
| `BaseStore` | 是 | 工作区 KV + CAS |
| `SandboxExecutionGuard` | 是 | 分布式锁（TTL 租约） |
| `SandboxSnapshotSpec` | 是 | 沙箱快照 blob |
| `MessageBus` | 是 | 队列 / 回放日志 / 广播 |
| `AsyncToolRegistry` | 是 | 异步工具执行记录 |
| `TaskRepository` | 是 | 子 agent 后台任务（`dp_tasks` + orphan sweep） |
| `SessionTurnGate` | 是 | 基于 `dp_locks` 的 per-session turn 租约（可选，防重复 LLM turn） |
| **`AgentStateStore`** | **否** | **由用户自备后端**（Redis / MySQL / PostgreSQL / OSS / COS）；core 已提供 `getVersioned` / `saveIfVersion` 乐观并发 API，但存储不在控制面 |

**`AgentStateStore` 明确排除**，理由：

1. 它是会话主数据的**强一致读写热路径**（`call()` 结束整体写、下一轮必须精确读回），一旦落控制面，控制面不可用等价于 agent 无法继续对话，且这条链路**没有可接受的降级方案**——本地 WAL + 回填会引入状态分叉。
2. 保留它在用户侧，agent 的基础会话能力与控制面**完全解耦**：不装控制面、或控制面宕机，会话照常。
3. 其余五项都有可接受的降级路径（见 §5）。

这与 CR-003 原始范围的差异见 [CR-004](../blogs/design-changes.md)；第二批扩展见 [CR-005](../blogs/design-changes.md#cr-005)。

## 0.1 第二批组件（CR-005）

在第一批五项之上，第二批把 harness 里仍依赖本地/workspace 的协调点接到控制面或 core CAS：

| 能力 | 实现 | 存储 |
|---|---|---|
| 子 agent 后台任务 | `ControlPlaneTaskRepository` | `dp_tasks`（migration `0006_hosted_tasks`） |
| Orphan sweep | `TaskSweepWorker`（leader-only） | 同上 |
| Per-session turn 串行 | `ControlPlaneSessionTurnGate` | 复用 `dp_locks` |
| Gateway session / route 映射 | `HarnessGateway#setBaseStore` | `BaseStore` CAS |
| 周期性维护 claim | `StoreBackedPeriodicGate` | `BaseStore` CAS |
| Skill 用量记录 | `BaseStoreSkillUsageBackend` | `BaseStore` CAS |
| 会话状态并发正确性 | `AgentStateStore#getVersioned` / `saveIfVersion` + `ConflictPolicy` | **用户自备后端**（InMemory / Redis / Postgres / MySQL 已实现；JsonFile / OSS / COS / JPA 仍为 LWW） |

**沙箱文件系统模式：** 使用 `SandboxFilesystemSpec` 且需要子 agent 后台任务（spawn / async promote）时，应启用托管 `TaskRepository`——`WorkspaceTaskRepository` 依赖共享工作区文件，纯沙箱模式下任务状态无法跨副本持久化。`ControlPlaneStores.withAgentStateStore(...)` 已自动注入 `taskRepository()` 与 `sessionTurnGate()`。

Turn gate 与 `ConflictPolicy.FAIL` 为**可选**优化：多副本下减少重复 LLM turn 的浪费；**正确性**仍来自用户侧 `AgentStateStore` 的 CAS（当后端支持 versioning 时）。

## 1. 三条硬约束（来自数据面代码）

设计前先确认了 `agentscope-harness` 的实际行为，以下三条直接决定接入形态。

### 1.1 `DistributedStore` 无法在不提供 `AgentStateStore` 的情况下构造

`agentStateStore()` 是非 default 方法，且 `DistributedStore.Builder#build()` 强制校验非空
（`agentscope-harness/src/main/java/io/agentscope/harness/agent/DistributedStore.java:231-233`）：

```java
public DistributedStore build() {
    Objects.requireNonNull(agentStateStore, "agentStateStore is required");
    Objects.requireNonNull(baseStore, "baseStore is required");
    // ...
}
```

**结论：** 控制面侧不能提供一个"开箱即用"的 `DistributedStore` 实现，必须由用户注入状态后端。设计上做成**必填构造参数**，让排除 `AgentStateStore` 这件事由类型系统强制，而非文档提醒。

### 1.2 harness 拒绝"共享工作区 + 本地状态存储"

`HarnessAgent.Builder#build()` 中，`RemoteFilesystemSpec` 搭配本地 `AgentStateStore`
（`JsonFileAgentStateStore` / `InMemoryAgentStateStore`）直接抛 `IllegalStateException`。

**结论：** 用户使用控制面托管的 `BaseStore` 时，**仍必须自备一个分布式 `AgentStateStore`**，否则 build 阶段失败。
最终形态是 **「控制面 + 一个状态后端」两个依赖**，不是一个——对外表述必须写清楚，避免"装一个控制面就够了"的误导。
好在该校验已存在，用户不会踩到静默错误。

### 1.3 组件可自由混搭，无跨后端校验

`HarnessAgent.Builder#build()` 的自动接线是**逐组件判空**的
（`agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java:2037-2059`）：
`agentStateStore` / `baseStore` / `snapshotSpec` / `executionGuard` / `messageBus` / `asyncToolRegistry`
各自独立注入，没有任何"必须同源"的约束。

**结论：** 本方案**不需要改动 harness 一行代码**，纯新增 extension 实现。

## 2. 接入形态（Java 侧）

```java
ControlPlaneStores cp = ControlPlaneStores.fromEnv();

HarnessAgent.builder()
        .distributedStore(cp.withAgentStateStore(redisStore.agentStateStore()))
        .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
        .build();
```

- `ControlPlaneStores.fromEnv()` 读 `AISTIO_CONTROL_PLANE_HTTP` / `BUILDER_INTERNAL_TOKEN` / `AISTIO_AGENT_NAME` / `AISTIO_NAMESPACE`。
- 已在跑 `SessionBridge` 的用户用 `ControlPlaneStores.from(aistioConfig)` 复用同一份 endpoint/token，不重复配置。
- `withAgentStateStore(AgentStateStore)` 内部即 `DistributedStore.builder()` 拼装，其余五项填控制面实现；**参数必填**（对应 §1.1）。

### 2.1 新增类

模块：`agentscope-extensions/agentscope-extensions-aistio`，新包 `io.agentscope.extensions.aistio.store`。
pom 增加 `agentscope-harness`（`provided`），与 `agentscope-extensions-redis` 一致。

| 类 | 实现接口 | 备注 |
|---|---|---|
| `ControlPlaneHttpClient` | — | 抽自 `HttpSelfRegistration` 的约定：JDK `HttpClient` + Jackson + token 头 + 超时/重试 |
| `ControlPlaneStores` | — | 组件工厂 + `withAgentStateStore` 门面 |
| `ControlPlaneBaseStore` | `BaseStore` | |
| `ControlPlaneSandboxExecutionGuard` | `SandboxExecutionGuard` | 阻塞重试 + 后台续租 |
| `ControlPlaneSnapshotSpec` | 继承 `RemoteSnapshotSpec` | 复用现成 `RemoteSnapshotClient` 抽象，零新增接口 |
| `ControlPlaneMessageBus` | `MessageBus` | |
| `ControlPlaneAsyncToolRegistry` | `AsyncToolRegistry` | |
| `ControlPlaneTaskRepository` | `TaskRepository` | CR-005；`/api/v1/dp/tasks/*` |
| `ControlPlaneSessionTurnGate` | `SessionTurnGate` | CR-005；复用 lock 端点 |

## 3. 鉴权与租户隔离

新增接口由**数据面**调用（非运维身份），因此挂在 `/api/v1/dp/*`，与 `/api/v1/dataplanes/*` 同级，
**不进** `/api/v1` 那个走 K8s SAR / 控制台 JWT 的运维组。

### 3.1 租户来源：请求体 + 共享 internal token

沿用现有的 `X-Builder-Internal-Token`（`internalTokenMiddleware()`）。租户由请求体的
`agentName` + `namespace` 归一化为 `tenant = {namespace}/{agentName}`，全部表按 `tenant` 落库与查询。

**曾评估过 per-agent 凭证（无状态 HMAC token），P0 阶段降级，理由如下。**

服务端要自行判定租户，可选来源只有三个：

1. 请求体的 `agentName` —— 客户端可任填
2. `instanceId` + 查注册表 —— **走不通**：`internal/dataplane/registry.go` 是进程内 `map`，
   A 副本注册的实例 B 副本不认识（`data_planes` 表在 migration 0002 建了但代码未使用）
3. 请求自带可验证凭证（HMAC 签名 token）

第 3 条降级的原因：

- **防不住最关键一步**：`register` 本身只由集群共享的 internal token 保护，任何持有它的 Pod
  都能声称 `agentName: victim` 并领到 scope 为 victim 的合法 token。对"集群内恶意 workload"
  这个威胁模型只是多一步，不是墙。
- **不缩小凭证泄露影响面**：注册必须用 internal token，每个 agent Pod 的 env 里本就揣着这个
  集群级、永不过期的密钥；泄露它远比泄露一个 24h scoped token 严重，而二者同处一个 env。
- **成本与新增故障模式**：签发 + 定长比较校验 + 双密钥轮转 + 心跳续期 + 客户端 401 重注册状态机
  ≈ 1 人周，并给原本不会因鉴权失败的链路引入"时钟偏移 / 轮转失误 → 401 风暴"。
- **当前主要风险是误操作而非恶意**，而 `tenant` 归一化本身已覆盖：dev/prod 指向同一控制面、
  agent 改名撞库这类事故不需要凭证就能防住。

### 3.2 保留可逆性

`tenant` 列**保留在全部 schema 中**（§4.7）。将来要上真身份，只需把 tenant 的来源从请求体改为凭证
claims——**换中间件即可，不动表、不迁数据**。

届时首选**不是** HMAC，而是 K8s 模式下投射的 ServiceAccount token + TokenReview
（`internal/httpapi/server.go` 的 `kubeAuth` 已有现成实现），因为它同时解决"谁能声称自己是谁"；
standalone 模式再降级为请求体取值。

### 3.3 当前安全边界（须写入用户文档）

- `/api/v1/dp/*` 的信任边界 = **持有 internal token 的集群内 workload 彼此可信**
- **不适用于**同一控制面纳管互不信任的多租户 agent；该场景须等 §3.2 的身份方案
- `queueDrain` 是 destructive（ack-on-read），跨 tenant 误调会**丢消息**，是当前最需要文档警示的点

## 4. 服务端设计

### 4.1 路由与开关

- 总开关 `--enable-hosted-store`（默认 **关**）。
- 路由组 `/api/v1/dp`，中间件 `internalTokenMiddleware()`。
- 新 handler 文件 `internal/httpapi/dpstore_handler.go`，在 `registerRoutes()` 中按 `s.store != nil && s.hostedStore` 挂载。
- 错误响应沿用 `ErrorResponse{Error, Message, Code, Hint}`；分页沿用 `parseLimit` / `parseOffset`。

**锁名与 bus key 含 `/` 和 `:`，一律走 body 或 query，不放 path**，避免 URL 编码歧义。

### 4.2 KV（`BaseStore`）

| 端点 | 语义 |
|---|---|
| `GET /api/v1/dp/kv/item?ns=a&ns=b&key=k` | 200 `{key, value, version}` / 404 |
| `PUT /api/v1/dp/kv/item` | body `{namespace[], key, value{}, expectedVersion?}` → 200 `{version}` / 409 `{currentVersion}` / 413 |
| `DELETE /api/v1/dp/kv/item?ns=..&key=..` | 204（幂等） |
| `GET /api/v1/dp/kv/search?ns=..&limit=&offset=` | 200 `{items:[{key, value, version}]}` |

- `expectedVersion` 缺省 = 无条件写（对应 `put`）；显式传值 = CAS（对应 `putIfVersion`），`0` 表示要求键不存在。
- CAS 用 `UPDATE ... WHERE version = $expected`；`expectedVersion = 0` 走 `INSERT ... ON CONFLICT DO NOTHING`。以影响行数判定成败。
- 单值大小上限（默认 1 MiB）超限返回 413；溢出对象存储留后续。

**namespace 编码：** Java 侧 `InMemoryStore` 用 `\0` 拼接 namespace，但 **PostgreSQL 的 `TEXT` 不能存 NUL 字节**，服务端不能照搬。
采用 `ns_path TEXT`，分隔符 `\x1f`（unit separator，`TEXT` 合法），配 `text_pattern_ops` 索引以支持前缀查询；
LIKE 前缀需转义 `%` `_` `\`。

**`search` 语义（已钉死）：** `InMemoryStore` 对 namespace 前缀做匹配，搜 `["a"]` **会命中子 namespace** `["a","b"]` 中的条目。
契约测试见 `BaseStoreContractTest`；控制面 KV / `ControlPlaneBaseStore` 必须保持同一语义。

### 4.3 Lock Service（`SandboxExecutionGuard`）

| 端点 | 语义 |
|---|---|
| `POST /api/v1/dp/locks/acquire` | body `{name, ttlSeconds, holder}` → 200 `{ownerToken, fencingToken, expiresAt}` / 409 `{holder, expiresAt}` |
| `POST /api/v1/dp/locks/renew` | body `{name, ownerToken, ttlSeconds}` → 200 `{expiresAt}` / 409 |
| `POST /api/v1/dp/locks/release` | body `{name, ownerToken}` → 204（幂等） |

acquire 用**单条原子 SQL**，避免读改写竞态；返回空行即"被他人持有且未过期"→ 409：

```sql
INSERT INTO dp_locks (tenant, lock_name, owner_token, fencing_token, holder, acquired_at, expires_at)
VALUES ($1, $2, $3, nextval('dp_lock_fencing_seq'), $4, now(), now() + $5::interval)
ON CONFLICT (tenant, lock_name) DO UPDATE
   SET owner_token   = EXCLUDED.owner_token,
       fencing_token = EXCLUDED.fencing_token,
       holder        = EXCLUDED.holder,
       acquired_at   = now(),
       expires_at    = EXCLUDED.expires_at
 WHERE dp_locks.expires_at <= now()
RETURNING owner_token, fencing_token, expires_at;
```

- `renew`：`UPDATE ... WHERE tenant=$1 AND lock_name=$2 AND owner_token=$3`，0 行 → 409，表示锁已被抢占，**客户端必须中止临界区**。
- `release`：`DELETE ... WHERE owner_token=$3`，CAS 删除，避免误删他人锁。

**客户端比 Redis 现状更优：** `SandboxExecutionGuard` 接口无续租概念，Redis 实现只能把 TTL 设成 30 分钟兜底，
代价是持有者崩溃后锁卡 30 分钟。控制面版在返回的 `SandboxLease` 内挂后台续租任务
（`SandboxLease` 仅是 `AutoCloseable`，**接口无需改动**），TTL 降到 60s、每 20s 续一次，
崩溃恢复从 30 分钟缩短到 1 分钟。`fencing_token` 供后续需要防迟到写入的场景使用。

`tryEnter` 的阻塞语义对齐 Redis 实现：409 后按 `retryInterval`（默认 500ms）重试，响应线程中断则抛 `InterruptedException`。

### 4.4 Snapshot（`SandboxSnapshotSpec`）

复用现成的 `RemoteSnapshotSpec` + `RemoteSnapshotClient`（`upload` / `download` / `exists`），零新增抽象。

| 端点 | 语义 |
|---|---|
| `PUT /api/v1/dp/snapshots/{id}` | `application/octet-stream` → 201 `{sizeBytes}` / 413 |
| `GET /api/v1/dp/snapshots/{id}` | 200 octet-stream / 404 |
| `HEAD /api/v1/dp/snapshots/{id}` | 200 / 404 |
| `POST /api/v1/dp/snapshots/{id}/upload-url` | 阶段二：返回对象存储 presigned URL |

客户端设计成**先问上传目标**：控制面返回 `{mode:"inline"}` 则直传控制面，返回 `{mode:"presigned", url}` 则直传对象存储。
这样阶段二接入对象存储时**客户端不必改动**。阶段一 inline 存 `BYTEA`，默认上限 32 MiB。

### 4.5 Bus（`MessageBus`）

`MessageBus` 是这批里最大的接口：Mode A 队列、Mode C 回放日志、Mode D 广播，10 个核心方法 + 一批 default 域方法。

| 端点 | 对应方法 |
|---|---|
| `POST /api/v1/dp/bus/queue/push` | `queuePush` |
| `POST /api/v1/dp/bus/queue/drain` | `queueDrain` |
| `POST /api/v1/dp/bus/queue/delete` | `queueDelete` |
| `GET /api/v1/dp/bus/queue/peek?key=` | `queuePeek` |
| `POST /api/v1/dp/bus/log/append` | `logAppend`（带 `maxLen`） |
| `GET /api/v1/dp/bus/log/read?key=&since=&maxCount=` | `logRead` |
| `POST /api/v1/dp/bus/log/trim` | `logTrim` |

- **queueDrain 天然 ack-on-read 且多副本安全**，沿用代码库已有的 `FOR UPDATE SKIP LOCKED` 约定：

```sql
DELETE FROM dp_bus_entries
 WHERE id IN (
   SELECT id FROM dp_bus_entries
    WHERE tenant = $1 AND bus_key = $2 AND kind = 0
    ORDER BY id LIMIT $3 FOR UPDATE SKIP LOCKED)
RETURNING id, payload;
```

- `logRead` 游标即自增 `id`：`WHERE id > $since ORDER BY id LIMIT $n`；`entryId` 用 `id` 的字符串形式。
- **Mode D 广播**用「小 `maxLen` 日志 + 客户端轮询」承载（`publish`/`subscribe` 语义是无历史的瞬时广播，用有界日志实现语义相容），
  已严格优于 `WorkspaceMessageBus` 每 3 秒发空 map 的现状。SSE 推送留作可选优化。

### 4.6 `AsyncToolRegistry`

| 端点 | 对应方法 |
|---|---|
| `POST /api/v1/dp/async-tools` | `register` |
| `POST /api/v1/dp/async-tools/{id}/complete` | `complete` |
| `POST /api/v1/dp/async-tools/{id}/fail` | `fail` |
| `POST /api/v1/dp/async-tools/{id}/timeout` | `markTimeout` |
| `GET /api/v1/dp/async-tools/stale?sessionId=&ttlSeconds=` | `findStale` |

**不复用 `team_tasks`**：后者是 team 域、带 `subject` / `blockedBy` / `claim` 语义；
`AsyncToolRegistry` 是 session 域、状态机为 `RUNNING/COMPLETED/FAILED/TIMEOUT`。硬套两边都别扭。
同理 `MessageBus` 不复用 `team_messages`（team outbox，`delivered` / `attempts`，面向 gRPC 投递成员）。
只复用**约定**：`ErrConflict`、`FOR UPDATE SKIP LOCKED`、409 响应形状。这是对 CR-003 的修订，见 CR-004。

### 4.7 DB schema（migration `0005_hosted_store`）

一次建全，代码分期填。所有表带 `tenant TEXT`（= `{namespace}/{agentName}`，由请求体归一化得出，见 §3.1）。

```sql
CREATE TABLE IF NOT EXISTS dp_kv (
    tenant      TEXT   NOT NULL,
    ns_path     TEXT   NOT NULL,              -- namespace segments joined by \x1f
    item_key    TEXT   NOT NULL,
    value       JSONB  NOT NULL,
    version     BIGINT NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, ns_path, item_key)
);
CREATE INDEX IF NOT EXISTS idx_dp_kv_prefix ON dp_kv (tenant, ns_path text_pattern_ops, item_key);

CREATE SEQUENCE IF NOT EXISTS dp_lock_fencing_seq;
CREATE TABLE IF NOT EXISTS dp_locks (
    tenant        TEXT   NOT NULL,
    lock_name     TEXT   NOT NULL,
    owner_token   TEXT   NOT NULL,
    fencing_token BIGINT NOT NULL,
    holder        TEXT,
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant, lock_name)
);
CREATE INDEX IF NOT EXISTS idx_dp_locks_expiry ON dp_locks (expires_at);

CREATE TABLE IF NOT EXISTS dp_snapshots (
    tenant       TEXT   NOT NULL,
    snapshot_id  TEXT   NOT NULL,
    size_bytes   BIGINT NOT NULL,
    storage_mode TEXT   NOT NULL,             -- inline | external
    payload      BYTEA,
    external_url TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    accessed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, snapshot_id)
);

CREATE TABLE IF NOT EXISTS dp_bus_entries (
    id         BIGSERIAL PRIMARY KEY,
    tenant     TEXT     NOT NULL,
    bus_key    TEXT     NOT NULL,
    kind       SMALLINT NOT NULL,             -- 0 = queue, 1 = log
    payload    JSONB    NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dp_bus_key ON dp_bus_entries (tenant, bus_key, kind, id);

CREATE TABLE IF NOT EXISTS dp_async_tools (
    tenant       TEXT NOT NULL,
    record_id    TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    tool_name    TEXT,
    tool_call_id TEXT,
    status       TEXT NOT NULL,
    result       TEXT,
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant, record_id)
);
CREATE INDEX IF NOT EXISTS idx_dp_async_stale ON dp_async_tools (tenant, session_id, status, created_at);
```

### 4.8 保留策略与指标

**`dp_kv` 是用户持久数据，必须从 `PurgeOlderThan` 中排除。** 其余新表各加保留开关：

| flag | 默认 | 依据列 |
|---|---|---|
| `--retention-bus-queue` | 7d | `created_at`（未消费队列保留久一些） |
| `--retention-bus-log` | 3d | `created_at` |
| `--retention-async-tools` | 7d | `updated_at` |
| `--retention-sandbox-snapshots` | 7d | `accessed_at` |

过期锁清理（`expires_at < now() - 1h`）挂到现有 `RetentionWorker`（已是 leader-only）。

新增 Prometheus 指标：`aistio_dpstore_requests_total{capability,result}`、
`aistio_dpstore_request_duration_seconds{capability}`、`aistio_dp_locks_held`。

## 5. 降级策略

| 能力 | 控制面不可用时 |
|---|---|
| `BaseStore` | **抛异常**。静默返回空会让 agent 误判记忆 / 技能为空，比失败更危险 |
| `SandboxExecutionGuard` | 重试超时后**降级为进程内锁 + WARN 日志**：保住单副本正确性，牺牲跨副本互斥，符合易用性优先原则 |
| Snapshot | `persist` 失败则跳过快照（沙箱下次冷启动）；`restore` 失败则 `isRestorable()=false`——现有接口语义天然容错 |
| `MessageBus` / `AsyncToolRegistry` | 构造期探活失败则回落 Workspace 实现；运行期失败按 Reactor 错误传播 |

沿用 `SessionBridge` 的旁路原则：**上报类失败一律吞掉**，但**存储读写类失败不能吞**（语义不同，见上表）。

## 6. 分期计划

总量约 7 人周。P1 完成即可对外宣布"共享工作区不再需要 Redis"，是第一个有对外价值的里程碑。

### P0 基座（约 1 周）

Go：

- `internal/httpapi/dpstore_handler.go` + `/api/v1/dp` 路由组 + `internalTokenMiddleware()`
- tenant 归一化助手（`{namespace}/{agentName}`，namespace 缺省为 `default`）
- flag：`--enable-hosted-store`
- `internal/store/store.go` 增 5 个 repository 接口；migration `0005_hosted_store`（表一次建全）

Java：

- 从 `HttpSelfRegistration` 抽出 `ControlPlaneHttpClient`，**保持现有行为不变**
- aistio 模块 pom 增 `agentscope-harness`（provided）；新包 `...aistio.store`
- `ControlPlaneStores` 骨架 + `withAgentStateStore`

文档：本文 + CR-004。

**验收：** 开关关闭时新路由完全不存在；开启后缺 token / 错 token 均 401；
tenant 归一化正确（namespace 缺省、空白与大小写处理一致）；不同 tenant 的同名 key 互不可见。

### P1 KV / `BaseStore`（约 1.5 周）

先做这个：它是 `RemoteFilesystemSpec` 的硬依赖，语义最简单，无降级歧义。

1. **先写 `BaseStore` 契约测试**，跑通 `InMemoryStore` + `RedisStore`，钉死 `search` 递归语义、
   `putIfVersion(0)` 语义、`delete` 幂等性。此步会顺带暴露现有后端间的不一致。
2. Go：`memory` + `postgres` 两套 `KVRepository` + 路由 + 大小上限
3. Java：`ControlPlaneBaseStore`（失败抛异常）

**验收：** 契约测试对控制面实现全绿；两个 harness 副本共享同一工作区；`putIfVersion` 并发冲突正确 409。

### P2 Lock Service / `SandboxExecutionGuard`（约 1.5 周）

Go：三端点 + 抢占式 acquire + fencing token。
Java：阻塞重试 + 后台续租 + `close()` 幂等且不抛异常 + 进程内锁降级。

**验收：** N 线程 / 多副本抢同一 key，同时仅一个持有者；杀死持有者进程后 TTL 到期可被接管；
`renew` 收到 409 时临界区正确中止；控制面下线后降级路径生效并打出 WARN。

### P3 Snapshot（约 1 周）

inline 模式 + 大小上限 + `upload-url` 端点占位（返回 `mode:"inline"`），客户端按 §4.4 协商上传目标。

**验收：** 沙箱跨副本恢复；超限 413；控制面不可用时沙箱冷启动而非报错。

### P4 `MessageBus` + `AsyncToolRegistry`（约 2 周）

放最后：现状有 workspace fallback 可用，最不紧急，且 `MessageBus` 接口面最大。

**验收：** 三种 Mode 语义各自有测试；`queueDrain` 多副本并发不重复消费；构造期探活失败回落 Workspace。

## 7. 测试策略

| 层 | 内容 |
|---|---|
| Go store | `internal/store/storetest/suite.go` 为 5 个新 repository 各加 `t.Run`，**memory 与 postgres 双驱动**跑同一套断言 |
| Go 并发 | 锁的互斥性 / TTL 接管 / renew 冲突；`queueDrain` 并发不重复 |
| Go httpapi | handler 级：鉴权、tenant 归一化与隔离、CAS 409、413、404 |
| Java 单测 | 沿用模块现有风格（`SessionBridgeContractTest` 不走网络），用 `com.sun.net.httpserver` 起桩服务端 |
| Java 契约 | `BaseStore` 契约测试同时跑 `InMemoryStore` / `RedisStore` / `ControlPlaneBaseStore`，防跨后端语义漂移 |
| E2E | aistiod + PostgreSQL + 两个 harness 副本共享工作区；含控制面下线的降级验证 |

## 8. 容量与 QPS 估算

主要压力来自**锁续租**与**bus 轮询**，均为周期性请求：

| 来源 | 估算（100 副本） |
|---|---|
| 锁续租 | 100 副本 × 5 活跃 lease ÷ 20s ≈ **25 QPS** |
| bus 轮询 | 100 副本 ÷ 2s ≈ **50 QPS**（仅在有订阅者时轮询） |
| KV 读写 | 低频（`MEMORY.md` / skills 写入稀疏），SDK 侧可加本地缓存 |

轮询间隔全部可配；bus 仅在存在订阅者时轮询。上述量级对单实例 PostgreSQL 无压力，但需在开启前压测确认。

## 9. 待决与已知限制

- **`search` 语义**未对齐（§4.2），P1 第一项工作解决。
- **租户隔离只防误操作，不防恶意**（§3.1/§3.3）：tenant 取自请求体，客户端可伪造。
  真身份方案（K8s TokenReview）未排期，但 `tenant` 列已就位，切换成本仅为换中间件。
- **`data_planes` 表是死表**：migration 0002 已建但代码未使用，注册表纯进程内。本方案不依赖它；
  是否补上持久化注册表属独立议题（参见 [storage-followups.md](./storage-followups.md)）。
- **对象存储直传**（`mode:"presigned"`）未排期，取决于是否引入对象存储依赖。
- **`AgentStateStore` 永不纳入本方案**；若未来要做，需独立设计强一致状态表与降级方案，不复用观测快照表。
