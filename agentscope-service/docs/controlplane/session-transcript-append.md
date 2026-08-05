# 会话 Transcript 的追加原语

> 状态：待实施
> 问题域：**怎么写**——追加语义、并发安全、可插拔后端
> 关联文档：[session-event-completeness.md](./session-event-completeness.md)（记录什么）、[session-readpath-cost.md](./session-readpath-cost.md)（怎么读）、[transport-vs-storage.md](./transport-vs-storage.md)（怎么传）

---

## 1. 现有机制：JSONL 会话日志已经存在

`WorkspaceManager` 的工作区布局里，每个会话有两个 JSONL 文件：

```
agents/<agentId>/sessions/
├── sessions.json              ← 会话索引
├── <sessionId>.<ctx>.jsonl    ← 给模型看的压缩上下文（resolveSessionContextFile）
└── <sessionId>.log.jsonl      ← 永不压缩的全量日志（resolveSessionLogFile）
```

所有读写都经过 `AbstractFilesystem`，因此本机磁盘、远端 KV、沙箱三种模式通吃；文档中 OSS 也被列为 `RemoteFilesystemSpec` 的后端之一。`session_search` / `session_history` 工具查的就是这份日志。

**底座是现成的**，方案不需要从零开始。问题出在追加的实现方式上。

---

## 2. 问题：现有的 append 不是 append

### 2.1 整文件读改写

`WorkspaceManager.appendUtf8WorkspaceRelative` 的实现是：

```java
ReadResult rr = filesystem.read(rc, normalized, 0, 0);   // 读整个文件
String existing = ...;
String merged = existing + content;                       // 内存拼接
filesystem.uploadFiles(rc, List.of(Map.entry(normalized,  // 整份写回
        merged.getBytes(StandardCharsets.UTF_8))));
```

对一个「永不压缩」的日志：

- **总 I/O 是 O(N²)**。日志累计到 50MB 时，再追加一行就是一次 50MB 读加一次 50MB 写。
- **整份文件常驻 JVM 堆**，大会话直接构成内存风险。

### 2.2 跨副本会静默丢事件（更严重）

方法自身的注释已经声明了这个限制：

```
A per-path ReentrantLock serialises concurrent callers so that the
read→merge→write cycle is atomic within this process. Across replicas the append is
last-writer-wins; this method does not perform CAS.
```

进程内有 `ReentrantLock` 保护，**跨副本是 last-writer-wins**：两个副本读到同一份基线，各自拼接后写回，后写的覆盖先写的，前者的事件无声消失。

SaaS 多副本 + 子 agent 并发写 + 副本接管会话，这三种情况都会触发。

### 2.3 `BaseStore` 帮不上忙

远端存储抽象 `BaseStore` 的原语是 `get` / `put` / `putIfVersion` / `search` / `delete`，值类型是 `Map<String, Object>`——这是一个**文档型 KV，没有任何 append 语义**。而且 `putIfVersion`（CAS）的默认实现直接返回 `false`，多数后端并未实现。

结论：**方向对，但现有追加原语撑不起「全量记录 + 多副本」的目标**，这是必须先动的地基。

---

## 3. 方案：分段不可变对象

### 3.1 核心思路

不要试图往一个对象上原地追加，而是每次 flush 写一个**新的不可变对象**：

```
{tenant}/{agentId}/{sessionId}/events/{seqStart}-{seqEnd}-{writerId}.jsonl
```

读取时列前缀、按 `seq` 归并。

好处：

| 问题 | 分段方案如何解决 |
|------|-----------------|
| O(N²) I/O | 每次只写新增部分，总 I/O 线性 |
| 堆内存 | 只持有当前批次 |
| 跨副本覆盖 | 每个写者的对象 key 唯一，物理上不可能互相覆盖 |
| 后端可移植 | 不依赖任何原生 append 能力 |
| 并发写者 | `writerId` 天然容纳子 agent 与副本接管 |

代价是小对象数量增加，用后台 compaction 缓解（见 3.4）。

### 3.2 为什么不用原生 append

- **S3**：无原生 append（S3 Express One Zone 除外），分段是唯一选择。
- **阿里云 OSS**：有 `AppendObject`，但会把对象变成 Appendable 类型，与多段上传等特性冲突，且绑定单一云厂商。
- **NAS / NFS**：支持 POSIX 真追加，实现最简单。

已决策后端**可插拔**，因此分段方案作为统一的默认路径；NAS 实现内部可以用 POSIX 追加做优化，但不改变对外语义。

### 3.3 `TranscriptStore` 抽象

需要一个独立于 `BaseStore` 的窄接口。不要把日志语义塞进文档型 KV——两边都会被拖坏。

大致形状：

```java
public interface TranscriptStore {
    /** 追加一批事件，返回写入的分段 key。*/
    String appendSegment(TranscriptRef ref, long seqStart, long seqEnd,
                         String writerId, byte[] jsonl);

    /** 按 seq 顺序列出分段。*/
    List<SegmentInfo> listSegments(TranscriptRef ref);

    InputStream readSegment(String segmentKey);

    /** 合并分段（可选优化）。*/
    default void compact(TranscriptRef ref) {}

    void delete(TranscriptRef ref);
}
```

`TranscriptRef` = `(tenant, agentId, sessionId)`。

实现：

- `FilesystemTranscriptStore`——本机 / NAS，内部可用 POSIX 追加
- `ObjectStoreTranscriptStore`——S3 / OSS，分段对象
- 保留一个走 `AbstractFilesystem` 的实现以兼容沙箱模式

### 3.4 Compaction

会话空闲一段时间后，后台任务把分段合并成整份对象，控制小对象数量与列举成本。合并需幂等且对并发读安全（先写新对象，再原子切换指针，最后删旧分段）。

### 3.5 大 payload 外置

单条工具输出超阈值（建议 64KB）时，JSONL 行只留引用 + 大小 + head/tail 预览，正文单独存对象。这样一次返回 10MB 文件内容的工具调用不会撑爆 transcript 分段。与 [session-event-completeness.md §2.2](./session-event-completeness.md) 的第 5 条是同一件事。

### 3.6 flush 策略与尾部持久性

buffer 攒批可以显著降低对象数，但崩溃会丢尾部。建议至少在 turn 边界强制 flush，并明确可接受的丢失窗口。

**flush 节奏不再受 UI 延迟约束。** 由于已决策热尾留在关系库、由客户端轮询（见 [session-readpath-cost.md §3.2/§3.3](./session-readpath-cost.md)），对象存储只承载冷体，因此可以放心地攒较大的批次：

- 若热数据也在对象存储上，延迟下限会由 flush 节奏决定——每秒轮询但 5 秒才 flush，延迟就是 5 秒起；把 flush 压到 1 秒又会让一个 5 分钟的 turn 产生约 300 个小对象。
- 分层之后，UI 新鲜度由热尾保证，flush 只需在「对象数量」与「崩溃丢失窗口」之间权衡，可取分钟级或按 turn 边界。

热尾与冷体之间需要明确切换点：读取时若请求区间落在热尾保留窗口内走关系库，否则走对象存储；两者的 seq 空间必须连续且不重叠。

---

## 4. 实施要点

1. 定义 `TranscriptStore` / `TranscriptRef` / `SegmentInfo`，确定分段 key 布局与 JSONL 行 schema（行 schema 与第三方 wrapper 契约共用，见 [transport-vs-storage.md](./transport-vs-storage.md)）。
2. 实现 `FilesystemTranscriptStore` 与 `ObjectStoreTranscriptStore`。
3. 会话日志路径切到 `TranscriptStore`；**`appendUtf8WorkspaceRelative` 的其它调用方（memory、审计日志等）保持不变**，不要一次性改动过大。
4. 后台 compaction 任务。
5. 归并读：按 `(seq, timestamp)` 排序，处理分段重叠与 writer 交错。

---

## 5. 验收标准

- [ ] 单会话追加 N 条事件的总 I/O 与 N 成线性，不随已有日志体积增长
- [ ] 两个副本并发向同一会话追加，事件全部保留，无覆盖丢失
- [ ] 同一份 transcript 在本机 / 对象存储 / 沙箱三种后端下读出的内容一致
- [ ] compaction 前后读取结果一致，且 compaction 可重复执行
- [ ] 超阈值 payload 不进分段正文，引用可解析回原文
