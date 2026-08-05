# BYO 会话历史与 Transcript 改造规划

> 实施状态（2026-08-02）：A/B/C/D 主链路已落地。内容截断（A2/A3/A5）按决策保持降级。详见下文各阶段勾选。

## 0. 范围

本规划**只覆盖 Operate 下的 BYO 模式**。

前置决策:放弃将 Managed Agents 纳入 Operate 统一管理。理由是即便完成存储层统一,控制台后端归属、会话身份、鉴权租户、能力协商、实例解析、交互写入这六个维度仍然分歧,而统一的收益本质上只是"修好 Operate"的副产品,不足以单独立项。

Managed Agents 侧的会话能力与已知缺陷,见 [managed-agents-followups.md](./managed-agents-followups.md)。

## 1. 目标与非目标

**目标**

1. 会话历史(消息级)成为一等公民,读取不依赖数据面活实例
2. 工具调用与结果的**过程**完整且可机读:调用了哪个工具、返回了什么、call 与 result 如何配对、顺序如何,均可无歧义还原
3. 多副本并发写入不丢条目
4. 控制面读路径不再逐会话回源重算聚合

目标 2 的边界:**内容层面的完整性暂不作为重点**。工具入参与输出允许截断,只要显式标注 `truncated` 与原始大小,让消费方知道内容被省略而非本就为空。

**非目标**

1. **不做 BYO 事件级 transcript。** token 级 delta 与细粒度事件流不落 transcript;Operate 的事件时间线保持可选能力,来源仍为 ASDP 上报(默认关闭)
2. 不做与 Managed 的统一

粒度决策的依据:harness 的 `<sessionId>.log.jsonl` 本身就是**消息级**的 `SessionEntry` 树,而非事件流。把它补成无损的消息级历史,投入产出比远高于在 harness 中新建一套事件级记录。

## 2. 现状缺陷(均已核实)

### 2.1 transcript 是记忆抽取的副产品

`<sessionId>.log.jsonl` 的唯一生产写入方是 `MemoryFlushManager.offloadToSessionTree`,即会话日志是 **memory flush 的副作用**。后果有两点:transcript 的完整性受 memory flush 的触发条件与节奏牵制;渲染格式是为"让模型抽取记忆"设计的,不是为忠实回放设计的。

### 2.2 工具调用信息有损

`renderContentBlocks` 把整条消息的所有内容块压平成一个字符串,其中:

| 缺陷 | 位置 | 后果 | 性质 | 是否在范围内 |
|---|---|---|---|---|
| 压平为字符串 | `renderContentBlocks` | 调用过程不可机读,须正则反解 `[tool_call: ...]`;入参含 `]` 或换行时解析歧义 | 结构 | **是** |
| 只取首个工具块 id | `extractToolCallId` | 一条消息多次调用时 call 与 result 无法配对 | 结构 | **是** |
| 无渲染块的消息被跳过 | `renderContentBlocks` 返回 null → 写入方 `continue` | 内容全为非文本非工具块的消息(如纯图片)整条消失,连"发生过"都不留痕 | 记录缺失 | **是** |
| 入参截断 500 字符 | `renderToolUse` | 长入参内容不可回放 | 内容 | 降级 |
| 输出截断 1000 字符 | `renderToolResult` | 长输出内容不可回放 | 内容 | 降级 |
| 仅渲染 `TextBlock` | `renderToolResult` | 非文本输出块内容丢失(条目本身仍在) | 内容 | 降级 |

`SessionEntry.MessageEntry` 的字段是 `(role, content: String, toolCallId)`,`content` 为扁平字符串,没有结构化内容块的容身之处——这是前两条结构缺陷的共同根因。

降级的三条不代表放弃,而是允许截断:保留结构与标注即可,值本身可以省略。

### 2.3 远端镜像是整文件上传

`SessionTree` 写本地文件用的是 POSIX 追加(`StandardOpenOption.APPEND`),本地路径没有问题。问题在跨副本镜像:

- `flush()` 触发 `scheduleMirror()`,做**整文件上传**,字节量随会话长度 O(N²) 增长
- 镜像是 fire-and-forget,失败仅告警;"本地写入才是主要保证"
- `syncFromRemote()` 是读远端 → union-merge → `overwriteFile` 整体覆写

多副本同时写同一会话时,两个副本各自整文件上传,后写覆盖先写;union-merge 只在读取时发生,救不回已被覆盖的条目。

### 2.4 控制面历史依赖活实例

Operate 的 `/api/v1/sessions/{id}/messages` 是实时转发到数据面,受 `message-query` capability 门控。实例不在了,历史就看不到。事件列表则因为事件上报默认关闭而通常为空。

## 3. 改造方案

### A 阶段 — transcript 无损化

| 任务 | 内容 | 优先级 | 状态 |
|---|---|---|---|
| A6 | **会话日志写入从 `MemoryFlushManager` 解耦**,成为独立的 transcript 写入路径,不再受 memory flush 触发条件牵制 | 关键前置 | ✅ `SessionTranscriptWriter` + `TranscriptMiddleware` |
| A1 | `SessionEntry` 增加结构化工具条目,保留 `name`、`input`、`output` 的字段结构而非压平为字符串;值允许截断,但须带 `truncated` 标志与原始大小 | 高 | ✅ `ToolUseEntry` / `ToolResultEntry` |
| A4 | 工具关联改为条目级:一条消息内多个工具调用各自成条目,`toolCallId` 一一对应,call 与 result 可配对 | 高 | ✅ |
| A7 | 无渲染块的消息不再被跳过:至少落一条带 role 与块类型摘要的占位条目,保证"发生过"可见 | 高 | ✅ `blockTypes` placeholder |
| A2 | 放宽或去掉 500/1000 字符截断 | 降级 | ⏸ |
| A3 | 非文本输出块的内容纳入记录 | 降级 | ⏸ |
| A5 | 大 payload 外置:超阈值内容写独立对象,JSONL 行只留引用 + 大小 + head/tail 预览 | 降级 | ⏸ |

A6 是 A 阶段的关键项。过程完整性首先取决于条目是否被写下来;现已由 `TranscriptMiddleware` 在每轮结束后独立落盘,不再依赖 memory flush 触发条件。

A5 的降级是 A2/A3 降级的连带结果:既然允许截断,大 payload 就不再是必须解决的问题。若日后恢复内容完整性要求,A5 需同步恢复。

### B 阶段 — 存储与并发正确性

| 任务 | 内容 | 状态 |
|---|---|---|
| B1 | 定义 `TranscriptStore` 接口与 `TranscriptRef`,确定分段 key 布局与 JSONL 行 schema | ✅ |
| B2 | 实现 `FilesystemTranscriptStore`(本机/NAS,内部用 POSIX 追加)与 `ObjectStoreTranscriptStore`(S3/OSS 分段对象) | ✅ |
| B3 | 用分段不可变对象替换 `SessionTree` 的整文件镜像上传与 `syncFromRemote` 的读改写;每次 flush 写新分段并带序号区间,不再覆写既有对象 | ✅（绑定 TranscriptStore 时启用;未绑定时保留 legacy 全文件镜像） |
| B4 | compaction:会话空闲后把分段归并成整份对象,控制小对象数量 | ✅ `TranscriptStore.compact`（按需调用） |

B3 消除 2.3 的两个病症:字节量从 O(N²) 降为 O(N);并发写者各写各的分段,不再互相覆盖。

### C 阶段 — 控制面读路径

| 任务 | 内容 | 状态 |
|---|---|---|
| C1 | 窄索引表 schema，写入时增量维护条目计数与 token 聚合 | **部分完成** — `session_transcript_index` 迁移 + `upsertObservedSession` / dataplane poller 用 DP snapshot 字段维护；注释标明暂不从事件重算 |
| C2 | Operate 的 messages 改读 transcript；`message-query` 门控**降级为 fallback 而非移除** | **部分完成** — `TranscriptMessages` hook + `AISTIO_TRANSCRIPT_FS_ROOT` 文件系统读；miss 时回退活实例并保留能力门控 |
| C3 | 事件读取 API 增加 `before`/`limit`，前端先加载最近 N 条再向上懒加载 | **完成** — `WithEventBefore` / `WithEventBeforeSeq` + newest-first limit |
| C4 | 控制面改读索引表，poller 不再逐会话回源重算聚合；复核并修复快照列表截断导致的误归档 | **部分完成** — 聚合走 snapshot/index；`ArchiveMissing` 在 probe ≥500 或 `truncated`/`hasMore` 时跳过 |

### D 阶段 — 第三方 wrapper 契约

| 任务 | 内容 | 状态 |
|---|---|---|
| D1 | 文档化 JSONL 行 schema、前缀布局、STS 前缀级授权模型 | **完成** — [wrapper-transcript-contract.md](./wrapper-transcript-contract.md) |
| D2 | 明确事件级时间线的定性:BYO 不强制，采纳 transcript 契约即可获得完整消息历史 | ✅ 见契约文档与本规划非目标 |

## 4. 验收标准

1. 工具调用的 `name` 与调用/返回的发生顺序可从 transcript **无歧义机读**还原,无需正则反解文本
2. 一条消息内的多次工具调用,call 与 result 一一配对
3. 内容被截断处带 `truncated` 标志与原始大小,消费方能区分"内容被省略"与"本就为空"
4. 内容全为非文本非工具块的消息(如纯图片)在 transcript 中留有痕迹,不整条消失
5. 两个副本并发写同一会话,条目零丢失
5. 数据面实例下线后,Operate 仍能读到该会话的完整消息历史
6. 长会话首屏不再拉取全量,反向分页可用
7. poller 不再逐会话回源重算聚合;快照截断不再导致误归档
8. `go test ./...` 通过,service-dataplane 与 harness 编译通过

## 5. 依赖与顺序

A6 → A1/A4/A7 → B1–B3 → C1–C2 是主链路。B4、C3、C4、D 可并行。A2/A3/A5 已降级,不阻塞任何后续阶段。

C2 依赖 B2 的对象存储实现落地(控制面需要能读到 transcript);若先用 `FilesystemTranscriptStore` 且控制面与数据面共享 NAS,C2 可以提前验证。
