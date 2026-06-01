# AgentScope-Java Admin API 分布式场景方案

> 创建：2026-06-01
> 关联：[ADMIN_OPS_API_PLAN.md](./ADMIN_OPS_API_PLAN.md)、[PLAN_SUBAGENT_ADMIN_GAP.md](./PLAN_SUBAGENT_ADMIN_GAP.md)
> 模块：`agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter/`

## 0. 问题陈述

当前 admin starter 的所有 per-session 操作（`:compact` `:undo` `:redo` `:abort` `:enter-plan-mode` `GET /messages` `/state` `:export` `/plan` `/tasks` `/subagent-tasks`）都隐含一个**单机假设**：调用方知道 sessionId 在哪个进程，把请求精确路由过去。在多副本部署下，这会逼业务方自己实现"sessionId → pod"映射，**完全不可接受**。

需要的语义：**调用方只持有 sessionId，admin 平面**自己**把请求路由到正确的实例**（或在任何实例上直接读写共享存储）。

---

## 1. agentscope-admin 当前的单机痕迹（精确盘点）

| 组件 | 单机性来源 | 分布式语义 |
|---|---|---|
| `InMemoryAgentRegistry`（默认） | `ConcurrentMap<sessionId, Agent>` 进程内 | ❌ 各 pod 各看各的；列表不全 |
| `SnapshotStore`（默认） | `ConcurrentMap<sessionId, SessionStacks>` 进程内 | ❌ undo/redo 只能在做 compact 的那个 pod 上回滚 |
| `MetricsRecorder` | `LongAdder` 进程内 | ⚠️ 单 pod 视角；usage 端点需要聚合 |
| `GracefulShutdownManager` | 单例（per JVM） | ✅ 本就是 per-pod 语义 → 控制面 drain/shutdown 必须 per-pod 操作 |
| `SessionOperations.compact/undo/...` | 调 `agentRegistry.find(sessionId)` 拿 ReActAgent，原地改其 AgentState | ❌ 只能改本 pod 持有的 agent 实例 |
| `Agent.interrupt()` | 本地中断本进程的 reactive flow | ❌ 跨 pod 无效 |
| `ReActAgent.enterPlanMode()` | 改本 pod 的 AgentState 实例 + persist | ⚠️ 改了本 pod 内存但 persist 后 *其他 pod 下次加载会看到* —— 不一致窗口存在 |

**关键观察**：agentscope 已经把 `AgentState` 跟 `Session` 持久化解耦了 —— 也就是说，**对一个"当前没活跃迭代"的会话，理论上任意 pod 都可以从 `Session` 加载、修改、save 回去**。只有"会话正在某 pod 上跑迭代"时才有 ownership 问题。这给方案选型留出空间。

---

## 2. 语义分层：分布式 admin 必须区分的三种操作

| 类别 | 例子 | 一致性要求 | 路由策略 |
|---|---|---|---|
| **A. 纯读，可基于 Session 重建** | `GET /sessions`、`/state`、`/plan`、`/messages`、`:export`、`/tasks` | 最终一致即可 | 任意 pod，直接读共享 `Session`（不依赖 in-memory agent） |
| **B. 写 AgentState，但**会话当前空闲** | `:compact`（空闲时）、`:undo`、`:redo`、`:enter-plan-mode`（无活跃迭代时） | 强一致 + 乐观锁 / ETag | 任意 pod 拿 lease，read-modify-write `Session` |
| **C. 影响活跃迭代** | `:abort`（中断）、`:compact`（迭代中）、`:enter-plan-mode`（迭代中切只读） | 必须**所有者执行** | 必须路由到当前持有迭代的 pod |
| **D. 进程级** | drain / shutdown / usage / status / agents inventory | per-pod | 不路由，按目标 pod 直接打；或 fan-out 聚合 |

混在一起处理就是灾难。**前提是把 admin 端点按上面四类标签化**。

---

## 3. 三种主流方案对比

### 3.1 方案 A：Sticky Routing（粘性会话）
入口 LB / Spring Cloud Gateway / Nginx 按 `sessionId` 一致性哈希到固定 pod，admin 也走同一规则。

- ✅ 实现最简单：对 admin starter 几乎无侵入；老代码自动正确。
- ❌ **依赖网关层**：调用方/服务网格必须感知 sessionId。
- ❌ **冷启动 / 缩扩容**：rebalance 后 sessionId 落到新 pod，但 AgentState in-memory 在老 pod；必须配 `Session` 持久化，新 pod 按需 rehydrate。
- ❌ admin 平台前端通常**不知道**业务路由规则；要么改前端，要么再加一层。

适合：业务网关本就按 sessionId 路由，且 admin 也走同一网关的场景。**不推荐作为 starter 默认路径**，因为侵入业务网关。

### 3.2 方案 B：Shared-State + 乐观锁（无状态 admin）
强制要求 `Session` 是分布式持久化（Redis / MySQL / Nacos），admin 操作不通过任何 in-memory agent，全部基于 `Session` 直接读改：

```
admin → 任意 pod → Session.get(id) → mutate → Session.save(id, version)
                                                 ↑
                                       乐观锁 / ETag / CAS
```

- ✅ admin 完全 stateless；任意副本可服务任意请求。
- ✅ 操作的是"真源"（durable state），不会因 pod 内存与持久化不一致出问题。
- ❌ **无法处理"会话正在跑迭代"的场景** —— 你可以修改 store，但**正在迭代的那个 pod 的 AgentState 实例还是旧值**，要等它下一次 save 才看到（或者它下次 save 反而覆盖你的修改）。
- ❌ `:abort` 无解 —— 中断必须发送给正在跑的 pod。

适合：纯读 (A 类) 和写空闲 session (B 类) 的子集。**单独无法 cover 全部 admin 语义。**

### 3.3 方案 C：Session Locator + 路由代理（推荐）
引入两个 SPI：

```java
public interface SessionLocator {
    /** 返回 sessionId 当前是否被某个 pod 持有迭代 / 持有 AgentState 实例。*/
    Mono<Optional<InstanceRef>> locate(String sessionId);

    /** 进入活跃状态时把 sessionId 绑到当前 pod。*/
    Mono<Void> bind(String sessionId, InstanceRef self, Duration leaseTtl);

    Mono<Void> unbind(String sessionId, InstanceRef self);

    /** 列出所有活跃 sessionId（across cluster）。 */
    Flux<SessionPresence> listAll();
}

public interface RemoteAdminClient {
    <T> Mono<T> forward(InstanceRef target, String method, String path,
                        Object body, Class<T> respType);
}
```

`InstanceRef` 至少含：`instanceId`、`internalUrl`（admin port + base path），可附 zone / version。

`SessionOperations` 加一层装饰：
```java
public Mono<CompactResponse> compact(String sid, ...) {
    return locator.locate(sid).flatMap(opt ->
        opt.map(target -> isSelf(target) ? local.compact(sid, ...) :
                                            client.forward(target, "POST", base + "/" + sid + ":compact", ...))
           .orElseGet(() -> idleCompact(sid, ...))   // 走方案 B 路径：直接读改 Session
    );
}
```

- ✅ 调用方只看到一个统一入口；任意 pod 接到请求都能正确路由或本地处理。
- ✅ 空闲会话走共享存储（廉价）；活跃会话路由到所有者（正确）。
- ✅ `:abort` 路由到所有者后调本地 `agent.interrupt()` 就 work。
- ❌ 需要实现 `SessionLocator` —— 但这本就是分布式必备组件，不是凭空多出来的复杂度。
- ❌ 转发增加一跳延迟（同集群内网 < 5ms，可接受）。
- ⚠️ pod 崩溃 → lease 过期 → 持久化状态可能不一致；需要 session recovery / takeover 协议。

**推荐为 starter 默认抽象**。Default 实现 `LocalSessionLocator` 在单机时直接返回 self（开发体验不变）；生产用 `RedisSessionLocator` / `NacosSessionLocator` 子 starter。

### 3.4 方案 D：消息总线广播
admin 写 intent 到 topic，所有 pod 订阅；持有会话的那个 pod 处理。

- ✅ 完全解耦，无需 SessionLocator。
- ❌ HTTP 响应语义破坏 —— admin 调用要么 fire-and-forget（用户体验差），要么等回执（要再实现 request/reply over MQ，本质又回到 RPC）。
- ❌ 调试 / 审计追踪麻烦。

适合：批量异步操作（"对所有空闲超 1h 的 session 批量 compact"）。**不适合做主路径**，但可以作为可选的批量 backend。

---

## 4. 推荐架构：以方案 C 为骨干

```
                    ┌──────────────────────────┐
   admin caller ──► │  Any AgentScope pod      │
                    │  agentscope-admin        │
                    │  ┌────────────────────┐  │
                    │  │ RoutingSessionOps  │  │
                    │  └─────┬──────────────┘  │
                    │        │                 │
                    │  locator.locate(sid)     │
                    └────────┼─────────────────┘
                             │
                ┌────────────┴────────────┐
                │                         │
        active? (has lease)         idle (no lease)
                │                         │
        ┌───────▼────────┐        ┌──────▼────────────┐
        │ forward to     │        │ load from shared  │
        │ owner pod via  │        │ Session, mutate,  │
        │ RemoteAdminClnt│        │ save w/ETag       │
        └───────┬────────┘        └──────┬────────────┘
                │                         │
        local SessionOps              shared Session
        (existing code)               (Redis/MySQL/…)
```

### 4.1 必须的三件存储/协议
1. **共享 `Session`** —— 业务侧选 Redis/MySQL 的 `Session` 实现 bean（不是 `InMemorySession`），这是方案 B 路径的前提。
2. **共享 `SessionLocator` backend** —— Redis SET `session:locator:<sid>` → `instanceId@host:port`，TTL 续约。或者 Nacos 服务发现 + 自定义 metadata key。
3. **共享 `SnapshotStore`** —— Redis ZSET 按 sid 存 push/pop，否则跨 pod 的 undo/redo 会丢栈。

### 4.2 lease 生命周期（owner 自己维护）
触发点（推荐放在 ReActAgent / HarnessAgent 的 middleware 钩子里，模块化在 admin starter 提供）：

| 时机 | 动作 |
|---|---|
| ReActAgent 收到 reply 请求，开始迭代 | `locator.bind(sid, self, ttl=30s)` |
| 迭代过程中每 10s | 续约 |
| 迭代完成 / interrupted | `locator.unbind(sid, self)` |
| pod 优雅关停（GracefulShutdownManager 钩子） | `locator.unbind` 所有持有的 sid |
| pod 崩溃 | TTL 自然过期；其他 pod 接管时直接 `locator.bind` |

bind 时若 Redis 已有别的 owner → 说明该 sid 在其他 pod 上活跃；ReActAgent 应当**拒绝接受新请求**或转发请求（这一步需要业务网关 / Agent 框架配合，超出 admin starter 范围）。

### 4.3 关键操作的路由决策

| 操作 | 路由 | 备注 |
|---|---|---|
| `GET /sessions` | 不路由，按 `Session.listSessionKeys()` 全集 | 用 `locator.listAll()` 补充活跃标记 |
| `GET /messages` / `/state` / `:export` | 不路由 | 直接读 Session |
| `GET /plan` | 优先读 Session（`PlanModeContextState` 已持久化） | 活跃 pod 的 `planMiddlewareEnabled` 通过 actuator 聚合 |
| `:compact` | locate → 路由 active；idle 走共享 store | 一定带 ETag |
| `:undo` `:redo` | locate → 路由 active；idle 任意 pod 操作共享 SnapshotStore | SnapshotStore 必须共享 |
| `:enter-plan-mode` `:exit-plan-mode` | locate → 路由 active；idle 改 Session 即可 | 活跃时若不通过 owner，所有者内存里的 flag 不刷新 |
| `:abort` | 必须 locate → 路由 active；idle 时 404 / no-op | interrupt 是 in-process 信号 |
| `GET /tasks` | 不路由 | 读 Session |
| `GET /subagent-tasks` | 不路由 | TaskRepository 本身设计为共享（如 `WorkspaceTaskRepository` 落 workspace） |
| `DELETE /subagent-tasks/{tid}` | 取决于 TaskRepository 实现 | 共享实现 → 任意 pod；in-memory → 必须路由 |
| `/actuator/agentscope-status` | **不路由**，pod 本地视角 | admin 网关可 fan-out 聚合 |
| `/actuator/agentscope-drain` | **不路由**，per-pod 操作 | 必要时网关广播给整个 fleet（"drain all"） |
| `/actuator/agentscope-usage` | per-pod；聚合在网关侧合并 | 各 pod 的 `LongAdder` |

---

## 5. 落地路线（增量演进，不破坏单机用户）

### Phase D-1：抽象 SPI（不引入实现）
- 定义 `SessionLocator` + `InstanceRef` + `RemoteAdminClient` 接口（在 admin starter 内）
- `LocalSessionLocator` 默认实现：永远返回 `Optional.of(self)`；`RemoteAdminClient` 默认实现 throws；这俩在单机部署下完全没区别
- `RoutingSessionOperations` 装饰现有 `SessionOperations`：locate 后只走 local 分支，**功能等价**
- 在 `AdminProperties` 加 `routing.enabled`（默认 false），关闭时彻底跳过路由层

**收益**：API 形态稳定下来，业务方可以注入自己的 `SessionLocator` 实现，starter 自身保持单机零依赖。

### Phase D-2：共享 SnapshotStore + 共享 SessionLocator 的 Redis 实现
独立子 starter：`agentscope-admin-redis-spring-boot-starter`
- `RedisSnapshotStore`（Lua script for atomic push+trim）
- `RedisSessionLocator`（SET with TTL；listAll via SCAN）
- 自动激活条件：`spring.data.redis.*` 已配 + `agentscope.admin.routing.backend=redis`

### Phase D-3：WebClient 实现的 `RemoteAdminClient`
- 内部走 admin 自己的 REST 接口转发
- 路径自动加 `X-Admin-Forwarded-From: <self-instance-id>` 防回环
- 鉴权：转发时附带 admin token（pod 间共享 secret，从 K8s Secret 注入）

### Phase D-4：活跃 lease 自动管理
- 在 admin starter 提供 `AdminLeaseMiddleware`（实现 `io.agentscope.harness.agent.middleware.Middleware` 或 Hook），业务方在 HarnessAgent 装它
- 入：bind；每个 stream chunk 续约；出：unbind
- 提供 `AdminLeaseGracefulShutdownListener` 监听 `GracefulShutdownManager`，drain 时批量 unbind

### Phase D-5：fan-out 控制面（可选）
- 新增 `agentscope-admin-gateway`（独立微服务模块）
  - 暴露统一外部入口
  - 实现 fan-out：`GET /v1/admin/fleet/status` 并行打所有 pod 的 actuator，聚合
  - 实现广播：`POST /v1/admin/fleet:drain` 给所有 pod 发 drain
- 否则也可以让 K8s service-monitor / Prometheus 完成 status 聚合

---

## 6. 一致性与故障场景

| 场景 | 行为 | 应对 |
|---|---|---|
| 路由到 owner pod 但它正在 GC / 不响应 | RemoteAdminClient 超时 | 返回 503；调用方重试；不能盲目本地降级（会双写） |
| owner pod 崩溃（lease 未及时释放） | 其他 pod 看到 lease 过期 | 等 TTL 过；admin 操作此时按 idle 路径走 |
| 同一 sid 两个 pod 同时 bind（lease 抢占） | Redis SETNX 失败一边 | 失败方拒绝服务请求或转发；admin 侧不感知，按 locator 结果路由即可 |
| Session.save 并发覆盖 | 乐观锁 ETag 冲突 | 409；admin 端返回带新版本号让调用方重试 |
| SnapshotStore push 后 owner 切换 | 共享 store 不丢栈 | 前提是 Phase D-2 已完成 |
| 调用方拿到老 ETag 做 undo | 409 | 提示重新拉 state 再来 |

---

## 7. 给 starter 的最小契约修改

为了让 D-1 那一步**自然落地不破坏现有代码**，建议下一轮做：

1. `SessionOperations` 拆出接口（现实现改名 `LocalSessionOperations`）：
   ```java
   public interface SessionOperations {
       Mono<CompactResponse> compact(...);
       Mono<UndoResult> undo(...);
       ...
   }
   ```
2. `RoutingSessionOperations implements SessionOperations` 装饰之；controller / endpoint 注入接口而非实现类
3. `AdminProperties` 加：
   ```yaml
   agentscope.admin.routing.enabled: false   # D-1 起开
   agentscope.admin.routing.backend: local   # local / redis / nacos
   agentscope.admin.routing.lease-ttl: 30s
   agentscope.admin.routing.instance-id: ${HOSTNAME}
   ```
4. 控制面 endpoint **保持** per-pod 语义（不要加路由层 —— drain/shutdown 故意是 per-pod）
5. 文档：明确说明 admin starter 默认是**单机视图**；分布式需要额外配 SessionLocator backend

---

## 8. 我目前需要你拍板的设计选择

1. **lease 是 admin starter 的责任还是 core 的责任？**
   - admin starter 侧：避免 core 改动，但 lease 续约要靠 admin starter 提供的 middleware/hook 装到业务 agent 上 —— 是 opt-in，可能漏装
   - core 侧：所有 ReActAgent 默认就 bind/unbind —— 干净但侵入 core
   - **我倾向 admin starter，配文档明确说"开了分布式 admin 就必须装 lease middleware"**
2. **首要后端是 Redis 还是 Nacos？**
   - Redis：SetNX/TTL 天然合适；项目大概率已经依赖 Redis
   - Nacos：项目已有 `agentscope-extensions-nacos` 体系；服务发现 + KV 一站
   - **我倾向先做 Redis，Nacos 跟 `agentscope-extensions-nacos` 走联合 starter**
3. **fan-out gateway 自研还是依赖 Spring Cloud Gateway？**
   - 自研：可控；体量小
   - SCG：成熟；但引入一个大依赖
   - **我倾向自研轻量级 `agentscope-admin-gateway` 模块**
4. **mid-iteration compact 怎么处理？**
   - v1：路由到 owner pod 后，pod 检测有活跃 reply 直接 409（保守）
   - v2：admin 写 "compact-pending" 标志到共享 store，owner 在下次 iteration boundary 自我 compact
   - **我倾向 v1 → v2 演进**

---

## 9. 总结

- 单机 admin starter 现在的所有路径都是正确的，**但只在单机正确**。
- 分布式扩展的关键在**新增一层路由 + 把内存态共享化**，不动业务语义：
  - 共享 `Session`、`SnapshotStore`、`SessionLocator`
  - `RoutingSessionOperations` 在 active/idle 两条路径间切换
  - 控制面端点保持 per-pod，由可选的 admin gateway 做 fan-out 聚合
- 这套抽象单机用户用不到（`LocalSessionLocator` 直接返回 self），不会引入复杂度税。
- 推荐的实现顺序：D-1 SPI → D-2 Redis → D-3 转发 → D-4 lease middleware → D-5 gateway。
