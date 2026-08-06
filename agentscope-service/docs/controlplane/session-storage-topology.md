# 会话数据的存储拓扑与重复

> 状态：待决策
> 问题域：**存在哪**——同一份会话数据的多处副本、库边界与所有权约定
> 关联文档：[transport-vs-storage.md](./transport-vs-storage.md)（怎么传）、[session-readpath-cost.md](./session-readpath-cost.md)（怎么读）、[contract.md](./contract.md)、[storage-design.md](./storage-design.md)

---

## 1. 现状：同一份会话数据存了三份，分布在两个数据库

### 1.1 两个独立的 DSN

`cmd/aistiod/main.go` 里是两个互不相关的配置项：

| flag | 环境变量 | 说明 |
|------|---------|------|
| `--product-dsn` | `AISTIO_PRODUCT_DSN` | Managed Agents 控制面，schema `cp` |
| `--storage-dsn` | `AISTIO_STORAGE_DSN` | runtime store，示例 DSN 指向另一个库 `aistio` |

两者可以指向同一个实例，但配置上完全独立，默认值也不同——`--storage-driver` 默认是 `memory`，`--product-dsn` 默认为空。

### 1.2 三份重叠数据

| # | 位置 | 内容 | 所有者 |
|---|------|------|--------|
| 1 | 库 `builder` schema `cp` | `cp.sessions`，会话生命周期 | 控制面（product） |
| 2 | 库 `builder` schema `dp` | `builder_session_event`，权威事件日志 | Java 数据面 |
| 3 | runtime store（库 `aistio` 或 memory） | `sessions` + `session_events`，Operate 观测副本 | 控制面（aistiod） |

**#2 和 #3 是同一批事件的两份存储**，ASDP `EventReport` 就是它们之间的复制管道。

### 1.3 两张 `session_events` 形态相反

数据面权威表是**信封型**——强类型字段只有骨架，内容全进一个 LOB：

```java
@Lob
@Column(name = "payload_json")
private String payloadJson;
```

runtime store 的表是**展开型**——列都拆开了：

```sql
CREATE TABLE IF NOT EXISTS session_events (
    id BIGSERIAL PRIMARY KEY,
    session_fk UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    event_type TEXT NOT NULL,
    role TEXT, content TEXT,
    tool_name TEXT, tool_input JSONB, tool_output TEXT,
    tokens_in INT, tokens_out INT, duration_ms INT,
    framework_meta JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_fk, seq)
);
```

同一份数据的两种建模，中间靠一层翻译。信封型对分析极不友好——任何聚合都要先解 JSON（参见 [session-readpath-cost.md](./session-readpath-cost.md)）。

---

## 2. 根因：一条自我约束的所有权约定

[contract.md](./contract.md) 中的不变式：

```
Invariant: DP code never SELECTs cp.* after Phase 3; use internal APIs. CP never touches dp.*.
```

在 Managed 形态下，数据面和控制面的表**在同一个 Postgres 实例、同一个库里，只隔一个 schema**。为了绕开这条规矩，我们建了 HTTP 合约端点、轮询器、逐会话重算，还准备再建 gRPC 推送——全部是为了跨越同一个数据库内部的一个 schema 边界。

### 2.1 这条约定不蠢，先替它辩护

它解决的是**模式耦合**：控制面一旦直读 `dp.*`，数据面就不能自由改表结构，每次迁移变成跨团队协调。从 "after Phase 3" 的措辞看，它当初是用来打断一个更糟的耦合的——数据面以前直接 SELECT `cp.*`。

### 2.2 正确解法：把契约下沉到存储层，而不是取消契约

真正的选择从来不是「协议 vs 无契约」，而是**契约用 protobuf 表达，还是用 SQL 视图表达**。

对共享数据库的两个组件，视图在每个维度上都更便宜：没有序列化、没有传输、没有第二份存储、没有复制延迟、没有两侧保留策略不一致。

建议由数据面提供只读视图作为稳定接口，例如：

```
dp.v_session_stats(session_id, message_count, prompt_tokens,
                   completion_tokens, last_event_at, ...)
```

底层表怎么改都行，只要视图契约不变。控制面被显式允许读这个视图，而不是读物理表。

> 注意：如果 transcript 迁到对象存储（见 [session-transcript-append.md](./session-transcript-append.md)），这一节的视图方案可能被更彻底的方案取代——事件正文不再进关系库，控制面只读窄索引表。两条路线需要在实施前二选一，不要同时做。

---

## 3. 持久性缺口

### 3.1 runtime store 默认不持久

```go
flag.StringVar(&storageDriver, "storage-driver", store.DriverMemory,
    "Runtime data storage driver: memory (dev/test, non-durable) or postgres (production).")
```

默认驱动是 `memory`，**默认部署下 Operate 的会话、事件、指标重启即丢**。启动时有告警日志，但仍是默认值。

[storage-followups.md §6](./storage-followups.md) 已记录「memory 默认的生产误用防护」这一条（建议多副本时 fail-fast 或 Helm 强制要求 postgres）。此处仅作交叉引用，需确认生产配置是否已显式设为 postgres。

### 3.2 事件表保留策略两侧不一致

runtime store 侧有 `RetentionWorker`（`session_events` 默认 7 天）；数据面的 `builder_session_event` **没有任何按时间的保留机制**。详见 [session-event-completeness.md §3](./session-event-completeness.md)。

---

## 4. 待决策

1. **消除 #2/#3 的重复**：是让控制面读数据面视图（2.2），还是把 transcript 整体迁到对象存储、关系库只留窄索引？两者互斥，需先定。
2. **`cp` 与 runtime store 是否合库**：目前两个 DSN 独立配置，实际部署是否同实例？若同实例，跨库 JOIN 的限制与运维收益需要重新评估。
3. **信封型 vs 展开型**：如果保留关系库存储，需统一为一种形态；信封型 + LOB 不适合作为长期形态。

## 5. 验收标准

- [ ] 同一份会话事件不再有两份权威存储
- [ ] 控制面与数据面之间的读取契约有明确载体（视图或索引表），且有版本与所有权说明
- [ ] 生产部署下 runtime store 不可能落到 memory 驱动
- [ ] 两侧事件数据的保留策略一致且被文档化
