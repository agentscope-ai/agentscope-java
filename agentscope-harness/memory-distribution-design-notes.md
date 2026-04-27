# HarnessAgent 分布式记忆：现状审计 + OpenAI Agents SDK 对标 + 演进建议

> 本文是一份独立设计备忘，用于沉淀 sandbox / StoreFilesystem 两种模式下"分布式记忆"能力的**当前偏差点**、**对标 OpenAI Agents SDK 的核心差异**，以及**推荐的演进路径**，作为后续改造（`MemoryConsolidator`、`MemoryMaintenanceScheduler`、`MemoryIndex`、`SessionTree` 等）的锚点。
>
> 本文不是实施 PR，不直接修改代码；目的是让后续每一次改动都有一个统一的参照物。

---

## 0. 名词

- **Agent workspace（本地 workspace）**：`HarnessAgent` 启动时在 host 机器上持有的工作目录（`workspaceManager.getWorkspace()`）。存放 `AGENTS.md`、`skills/`、`subagents/`、`knowledge/`、以及 `MEMORY.md`、`memory/*.md`、`agents/<agentId>/sessions/*.jsonl` 等。
- **Sandbox workspace**：`SandboxSession` 内部维护的工作目录（Docker 容器内或 UnixLocal 下的隔离目录）。工具（shell、filesystem）在此执行。
- **`AbstractFilesystem`**：HarnessAgent 统一的文件系统抽象。实现包括：
  - `LocalFilesystem` / `LocalFilesystemWithShell`：直接操作 host 磁盘。
  - `SandboxBackedFilesystem`：把所有 I/O 代理到当前绑定的 `SandboxSession`。
  - `StoreFilesystem`：把 I/O 放到 `BaseStore`（可远端共享）。
  - `CompositeFilesystem`：按路径前缀路由到不同后端。
- **Sandbox 模式**：`HarnessAgent.Builder` 配置 `SandboxFilesystemSpec`，走 `SandboxBackedFilesystem`。
- **Store 模式**：`HarnessAgent.Builder` 配置 `StoreFilesystemSpec`，走 `CompositeFilesystem(local, store-routed paths)`。
- **Local 模式**：两者都不配置，走纯 `LocalFilesystemWithShell`。

---

## 1. 问题陈述

HarnessAgent 支持两种"分布式部署下的记忆共享"形态：

1. **Sandbox 模式**：多个 HarnessAgent 实例通过 `SandboxIsolationKey`（`SESSION / USER / AGENT / GLOBAL`）共享同一个 sandbox 实例，记忆文件存在 sandbox workspace 内。
2. **Store 模式**：不使用 sandbox，通过 `CompositeFilesystem` 把 `MEMORY.md`、`memory/`、`agents/<agentId>/sessions/` 路由到 `StoreFilesystem`（底层 `BaseStore`，可以是 Redis、远端 K-V 等），多个实例读写同一个 Store namespace。

**核心症状**：这两种模式目前**都存在相同的共性问题**——多个与 memory 相关的组件仍然绕过 `AbstractFilesystem`，直接用 `java.nio.file.Files.*` 操作 host 磁盘，导致它们只能看到 host 本地那一份视图，而看不到 sandbox 内 / Store 内的权威内容。

---

## 2. 现状审计（两种模式各自的受损点）

> 判定标准：**数据权威位置（authoritative store）** vs **组件实际访问位置**。凡是不一致的都标记为坏。

### 2.1 Sandbox 模式

| 组件 | 数据权威位置 | 组件实际访问 | 影响 |
|---|---|---|---|
| `FilesystemTool` 读写 | sandbox workspace | 走 `SandboxBackedFilesystem` ✅ | 正确 |
| `ShellExecuteTool` | sandbox workspace | 走 sandbox session.exec ✅ | 正确 |
| `SessionTree.flush`（dual-write） | sandbox workspace + local 磁盘 | 本地 `Files.write` + `filesystem.write` mirror ✅ | 正确 |
| `SessionTree.load/syncFromLog`（restore） | sandbox workspace | 本地不存在时从 filesystem 拉 ✅ | 正确 |
| `MemoryConsolidator.readDailyEntries` | **sandbox workspace** | `Files.list(workspace/memory)` 本地 ❌ | 读不到 daily ledger，永远不触发合并 |
| `MemoryConsolidator` watermark (`memory/.consolidation_state`) | 应随 daily ledger 权威一致 | `Files.readString/writeString` 本地 ❌ | 多实例状态漂移；单机也错配 |
| `MemoryConsolidator.writeMemoryMd` | sandbox workspace | 已改走 `workspaceManager.writeUtf8WorkspaceRelative` ✅ | 正确（但由于上游读不到，不会被触发） |
| `MemoryMaintenanceScheduler.archiveOldDailyFiles` | sandbox workspace | `Files.list/move` 本地 ❌ | 扫不到任何文件，归档失效 |
| `MemoryMaintenanceScheduler.cleanupOldSessions` | sandbox workspace（Session dual-write 另有本地副本） | `Files.list/delete` 本地 ❌ | 只清本地副本，sandbox 内永不清理 |
| `MemoryIndex.indexAllFromWorkspace`（启动全量） | sandbox workspace | `Files.walk(workspace/memory)` 本地 ❌ | 启动索引为空 |
| `MemoryIndex.indexFromString`（增量） | sandbox workspace | 由 `readManagedWorkspaceFileUtf8` → filesystem ✅ | 运行时增量可用 |
| `SessionSearchTool` | sandbox workspace | SessionTree dual-write 保留了本地副本，扫本地 ⚠️ | 可用但靠副作用 |
| `SessionPersistenceHook` / `WorkspaceSession` `_sandbox.json` 等 | 本机专属 resume 状态 | `Files.*` 本地 ✅ | 设计如此（per-instance resume 元数据，暂不共享） |

**结论**：sandbox 模式下，**工具链路径**（FilesystemTool/ShellTool）正确，**SessionTree 因 dual-write 也能工作**，但**long-term memory 管理管线（consolidation / archive / cleanup / 启动索引）全线失效**。

### 2.2 Store 模式（`StoreFilesystemSpec`）

| 组件 | 数据权威位置 | 组件实际访问 | 影响 |
|---|---|---|---|
| `FilesystemTool` 读写 `memory/**`、`MEMORY.md`、`agents/<id>/sessions/**` | `StoreFilesystem` | CompositeFilesystem 路由到 `StoreFilesystem` ✅ | 正确 |
| `FilesystemTool` 读写其他路径 | local 磁盘 | CompositeFilesystem 默认走 LocalFilesystem ✅ | 正确 |
| `ShellExecuteTool` | local 磁盘 | local `ProcessBuilder` ✅ | 设计如此（shell 只能操作 host） |
| `MemoryFlushManager.appendDaily` | Store | `workspaceManager.appendUtf8WorkspaceRelative` → filesystem → Store ✅ | 正确 |
| `MemoryConsolidator.readDailyEntries` | **Store** | `Files.list(workspace/memory)` 本地 ❌ | 本地为空，读不到 daily |
| `MemoryConsolidator` watermark | 应随 Store 中的 daily 权威 | `Files.*` 本地 ❌ | 多实例各写各的，跨实例不一致 |
| `MemoryConsolidator.writeMemoryMd` | Store | `workspaceManager.writeUtf8WorkspaceRelative` → Store ✅ | 正确但上游读不到，永不触发 |
| `MemoryMaintenanceScheduler.archiveOldDailyFiles` | Store | `Files.list/move` 本地 ❌ | 归档完全无法工作，Store 内 daily 无限累积 |
| `MemoryMaintenanceScheduler.cleanupOldSessions` | Store + 本地 dual-write | `Files.list/delete` 本地 ⚠️ | 只删本地副本，Store 内 session jsonl 无限累积 |
| `MemoryIndex.indexAllFromWorkspace`（启动） | Store | `Files.walk` 本地 ❌ | 启动索引空 |
| `MemoryIndex.indexFromString`（增量） | Store | `readManagedWorkspaceFileUtf8` → filesystem → Store ✅ | 运行时增量可用 |
| `SessionSearchTool` | Store + 本地 dual-write | 扫本地 ✅ | 可用（靠 dual-write 副本） |
| `SessionPersistenceHook` / `WorkspaceSession` | per-instance | `Files.*` 本地 ✅ | 设计如此 |

**结论**：Store 模式下的症状**与 sandbox 模式完全一致**——long-term memory 管理管线同样失效。  
而且**问题更严重**，因为 Store 模式存在的全部价值就是"多实例共享权威记忆"；consolidator / scheduler / 全量索引失效意味着核心承诺落空。

### 2.3 两个模式的症状差异（仅有的几点）

| 维度 | Sandbox 模式 | Store 模式 |
|---|---|---|
| 写冲突 | 同 `SandboxIsolationKey` 内串行 resume 保护，冲突极少 | 多实例真实并发读写同一 Store namespace；`MEMORY.md` 等单文件在 last-writer-wins 下冲突概率高 |
| 本地 dual-write 副本存在性 | SessionTree 有、memory/* 无 | SessionTree 有、memory/* 无 |
| `MemoryIndex` SQLite 文件 | per-instance 本地，与 sandbox 权威不同源 | per-instance 本地，与 Store 权威不同源 |

---

## 3. 根因

> **凡是绕开 `AbstractFilesystem` 直接使用 `java.nio.file.Files.*` 的代码，都看不到"被路由走"的那部分内容。**

- 在 sandbox 模式下，被路由走的是整个 sandbox workspace（`SandboxBackedFilesystem` 背后）。
- 在 Store 模式下，被路由走的是 `memory/` / `MEMORY.md` / `agents/<id>/sessions/`（`StoreFilesystem` 背后）。
- Local 模式不受影响，因为此时 `AbstractFilesystem` 的默认后端就是 host 磁盘，直连 `Files.*` 与走 fs 结果一致。

所以**两个模式是同一个病**，只不过站在不同 backend 角度暴露出来。修一次，两边同时治愈。

---

## 4. 对标：OpenAI Agents Python SDK 的做法

> 参考目录：`references/openai-agents-python/src/agents/sandbox/`，特别是 `memory/storage.py`、`memory/manager.py`、`memory/phase_one.py`、`memory/phase_two.py`、`capabilities/memory.py`、`runtime.py`、`runtime_session_manager.py`。

### 4.1 他们的七个关键选择

1. **Memory 必须依托 sandbox**。`Memory.required_capability_types()` 返回 `{"shell", "filesystem"}`；没有 sandbox 就没有 memory。**不存在"非 sandbox 记忆共享"这一问题**。

2. **只有一个 workspace**：sandbox workspace。`MemoryLayoutConfig.memories_dir` / `sessions_dir` 都是**相对路径**，`_validate_relative_path` 禁止绝对路径和 `..`。host 磁盘上不存在一份独立的"我的 memory"。

3. **所有 memory I/O 严格走 `BaseSandboxSession`**。`SandboxMemoryStorage` 是唯一网关：

   ```python
   await self._session.mkdir(...)
   await self._session.read(path)
   await self._session.write(path, data)
   await self._session.ls(path)
   await self._session.exec("test", "-f", str(absolute), shell=False)
   ```

   `grep` 核对过 `sandbox/memory/` 下**没有**任何 `open()` / `.read_text()` / `os.path` 对 host 的真实 I/O。`pathlib.Path` 只用于构造相对路径字符串。

4. **跨 run 一致性不靠 sync，靠 "snapshot + session_state + live session" 三件套**：
   - `SandboxRunConfig.session=<live>`：直接复用活跃 session；
   - `SandboxRunConfig.session_state=<blob>`：`client.resume(state)` 拉起等价 session；
   - `SandboxRunConfig.snapshot=<LocalSnapshotSpec | RemoteSnapshotSpec>`：从快照克隆 workspace。
   
   分布式一致性下推到 **SandboxClient 后端**（或 RemoteSnapshot），**应用层不做文件同步**。

5. **Consolidation = 内嵌一个 LLM agent 在 sandbox 内跑**。`phase_two.py` 里：

   ```python
   agent = SandboxAgent(name="sandbox-memory-phase-two", model=config.phase_two_model)
   await Runner.run(agent, prompt, run_config=RunConfig(sandbox=SandboxRunConfig(session=self._session)))
   ```

   LLM 用 apply_patch / shell 工具直接改 `memories/MEMORY.md`。**"Java 代码该看哪个 fs"**这类问题在设计源头被消除——LLM 从 sandbox 内部视角看什么就改什么。

6. **生命周期绑定到 session pre-stop hook**：

   ```python
   self._session.register_pre_stop_hook(self.flush)
   ```

   `flush` 跑 phase-1（每个 rollout 的提取）+ phase-2（一次性整合）。**没有独立的 cron scheduler**，没有"归档 daily files"的后台线程——需要的话交给 LLM 用 shell 做。

7. **没有 FTS / 本地 SQLite 索引**。Memory 在提示词注入阶段只读 `memories/memory_summary.md`（由 phase-2 维护，截到 15k tokens），直接注入 system prompt。细节检索交给 LLM 用 shell/grep/read 在 sandbox 内完成。

### 4.2 本质上他们做了哪三件事

- **单源（Single source of truth）**：workspace 只有一份，就是 sandbox 内那份。
- **单网关（Single I/O gateway）**：memory I/O 统一走 `BaseSandboxSession`。
- **职责下推**：合并、归档、检索这些"要全局视角"的任务，下推到一个在 sandbox 内部跑的 LLM agent，绕开了"Java 代码视角和权威数据视角不一致"的一整类问题。

---

## 5. 横向对比

| 维度 | OpenAI Agents SDK | HarnessAgent（现状） |
|---|---|---|
| Memory 是否强制 sandbox | 是 | 否（local / sandbox / store 三种） |
| Workspace 数量 | 1（sandbox） | 2（local + sandbox）或 路由视角下的逻辑 2（local + store） |
| Memory I/O 统一网关 | `BaseSandboxSession` | `AbstractFilesystem`（应该是，但 Memory 模块未严格遵守） |
| Consolidation 执行者 | sandbox 内的 LLM agent | Java 代码 `MemoryConsolidator`（直连 `Files.*`） |
| 归档/清理执行者 | 可选（LLM 在 sandbox 内用 shell） | Java `MemoryMaintenanceScheduler`（直连 `Files.*`） |
| 提示词索引 | `memory_summary.md`（LLM 维护） | SQLite FTS（`MemoryIndex`，本地维护，视图与权威脱节） |
| Watermark（合并进度） | 无（每次从 session.ls 推出） | 本地文件 `memory/.consolidation_state`（`Files.*`） |
| 跨 run 一致性 | snapshot + session_state resume（后端保证） | `SandboxStateStore` + `StoreFilesystem`（应用层保证） |
| 写冲突策略 | 同 session 内串行（后端约束） | Sandbox: `SandboxIsolationKey` 串行 resume；Store: last-writer-wins（默认） |
| 非 sandbox 记忆共享 | 不支持 | 支持（`StoreFilesystemSpec`） |

---

## 6. 对 HarnessAgent 的启示

### 6.1 可以**直接借鉴**的设计纪律

1. **Memory I/O 只走 `AbstractFilesystem`，零例外**。`SandboxMemoryStorage` 是正面模板。把 `MemoryConsolidator / MemoryMaintenanceScheduler / MemoryIndex` 改造成仅调用 `AbstractFilesystem` 的能力（read/write/append/list/exists/delete/move），`java.nio.file.Files.*` 仅在 `LocalFilesystem` 实现内部出现。

2. **路径防御**：`AbstractFilesystem` 入口统一做 `_validate_relative_path` 等效校验——拒绝绝对路径、拒绝 `..`、拒绝空路径。避免外层代码把 host 绝对路径泄漏到 store/sandbox 后端。

3. **生命周期钩子化，取消独立 scheduler**。`MemoryMaintenanceScheduler` 的归档/清理动作应合并为：
   - `PreCallEvent` / `PostCallEvent` / `RunnerShutdown` 上的同步任务，或
   - 作为 `SandboxLifecycleHook` 家族的 pre-stop / post-call 回调。
   
   效果上和 OpenAI 的 `register_pre_stop_hook(flush)` 对齐，不引入跨线程 race、不引入"何时扫描"这种外部时序变量。

### 6.2 值得借鉴但需要**本地化调整**的

4. **"LLM 做 consolidation"作为**可选实现**并存**。设计：
   - `MemoryConsolidationPolicy` 接口；
   - `RuleBasedConsolidationPolicy`：保留现有 Java 逻辑的"纯 fs 版本"（要求严格走 `AbstractFilesystem`），作为低成本、可回放的默认；
   - `LlmConsolidationPolicy`：仿 `phase_two`——HarnessAgent 内部构造一个受限的子 Runner，复用同一个 `AbstractFilesystem`，用 apply_patch/filesystem 工具改 MEMORY.md。
   
   两种 policy 都只调用 `AbstractFilesystem`，互相可替换；用户按场景选择（成本 vs 智能度）。

5. **把 `MemoryIndex` 降级为"可选的加速器"，主读路径换成 `memory_summary.md`**。
   - 主链路：启动/运行时把 `memory/memory_summary.md`（若存在）注入 system prompt（截 N tokens）。
   - 辅助工具：FTS 保留，作为显式 `SessionSearchTool` / `MemorySearchTool` 的后端。
   - 全量索引重建：不再在启动时做；改为**按需**（工具被调用时 lazy rebuild）或由 consolidation 后台再建。
   - 这样可以从**"启动时索引本地→和权威脱节"**的架构缺陷中走出来。

### 6.3 我们场景下**不能直接照抄**的

6. **OpenAI 强绑 sandbox 才有 memory**；我们要支持 `StoreFilesystemSpec`（无 sandbox 的分布式）。  
   对策：用 `AbstractFilesystem` **扮演他们 `BaseSandboxSession` 的角色**——只要所有 memory 代码都只和 `AbstractFilesystem` 对话，sandbox / Store / Local 三种后端在 memory 视角下是等价可替换的。

7. **OpenAI 的分布式一致性下推到 `SandboxClient` 后端**（`RemoteSnapshotSpec` 对齐多实例 workspace）。  
   我们的 Docker/UnixLocal 客户端没有天然跨机一致性，所以保留 `StoreFilesystem` 做"共享 K/V 后端"是对的。长期可以考虑：
   - 用 Store 作为 `RemoteSnapshot` 的存储后端（把"sandbox workspace 快照"和"共享 memory 文件"统一到一个 Store）；
   - 或让 Docker client 支持从远端 registry 拉 workspace 快照，补齐"分布式一致性下推"。

---

## 7. 演进路径

> 所有阶段的共同大原则：**动刀点永远是"让 XX 组件只通过 `AbstractFilesystem` 访问 memory"**。

### Phase 1：收敛 `MemoryConsolidator`（最小闭环）

- `readDailyEntries(watermark)`：换成 `filesystem.list("memory/")` + `filesystem.readString("memory/<file>")`。
- Watermark：从本地 `.consolidation_state` 文件搬到 filesystem 的 `memory/.consolidation_state`；读写也走 `AbstractFilesystem`。
- `writeMemoryMd`：已改好，保留。
- 验收：一次 run 的 daily 写入后，MEMORY.md 会被合并；sandbox 模式和 Store 模式都观察到同样结果。

### Phase 2：`AbstractFilesystem` API 扩展

- 新增 primitives：`delete(path)`、`move(src, dst)`、`walk(prefix)`（或 `listRecursive`）、`exists(path)`。
- `LocalFilesystem` 实现用 `Files.*`；`SandboxBackedFilesystem` 用 session 的 `rm/mv/ls`；`StoreFilesystem` 用 Store key 层面的 `delete/scan`；`CompositeFilesystem` 按 prefix 路由。
- 同步引入 `_validate_relative_path` 门禁。

### Phase 3：改造 `MemoryMaintenanceScheduler` & `MemoryIndex`

- **Scheduler**：废弃独立线程，拆成两个 Hook：
  - `MemoryArchiveHook`（`PostCallEvent`）：归档 N 天前的 daily → `memory/archive/YYYY-MM/`。
  - `SessionCleanupHook`（`PostCallEvent` 或 `RunnerShutdown`）：清理过期 session jsonl。
  - 全部走 `AbstractFilesystem.walk/move/delete`。
- **MemoryIndex**：
  - 取消启动时 `indexAllFromWorkspace`。
  - 主读路径改成 `filesystem.readString("memory/memory_summary.md")` 注入 system prompt（已有部分可复用）。
  - FTS 改为 lazy + 增量：第一次检索时从 filesystem 枚举一遍初建；后续由 flush 钩子增量维护。

### Phase 4：`SessionTree` 去 dual-write（可选）

- 当前的 dual-write（本地 + mirror）是过渡期的正确选择（让 `SessionSearchTool` 继续能用）。
- Phase 3 完成后，`SessionSearchTool` 可以改走 `filesystem.walk("agents/<id>/sessions/")`；dual-write 可以退化为"只走 filesystem 单写"。
- 这会让 Store 模式下本地盘完全不保存 session 副本，契合"Store 是权威"的语义。

### Phase 5：LLM-based consolidation 与 `memory_summary.md`（可选，对标 OpenAI）

- 引入 `MemoryConsolidationPolicy` 抽象；`RuleBasedConsolidationPolicy`（Phase 1 的 Java 实现）默认。
- 可选注入 `LlmConsolidationPolicy`，内部用一个子 Runner + 受限工具，对 MEMORY.md / memory_summary.md 做基于 LLM 的整合。
- 两种 policy 都只调用 `AbstractFilesystem`，保持单网关纪律。

### Phase 6：Resume state 的一致化（长线）

- 把 `WorkspaceSandboxStateStore` 当前的 "_sandbox.json 本地文件" 形态抽象为 `SandboxStateStore` 接口 + 可插拔后端：
  - `LocalFileSandboxStateStore`（现状）；
  - `StoreBackedSandboxStateStore`（复用 `BaseStore`）；
  - 未来可能的 DB backend。
- 对齐 OpenAI 的"resume state as blob"模型：不是文件路径，而是 blob，调用方决定持久化位置。

---

## 8. 设计原则清单（落到代码守则）

1. **Memory 领域的所有 I/O 只通过 `AbstractFilesystem`。**  
   严格 code review：`agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/` 下禁止出现 `java.nio.file.Files.*` 直接调用（`LocalFilesystem` 实现本身除外）。

2. **相对路径 + 防御性校验。**  
   `AbstractFilesystem` 入口拒绝绝对路径、拒绝 `..`、拒绝空路径。

3. **状态文件（watermark 等）也是 memory 一部分，同样只走 `AbstractFilesystem`。**  
   `.consolidation_state` 不允许走本地磁盘。

4. **生命周期事件化。**  
   归档/清理/consolidation 统一做 Hook，禁止独立 scheduler 线程（作为辅助加速器时可例外，但必须幂等、可关闭）。

5. **Consolidation policy 可替换。**  
   Java 规则与 LLM 驱动两种 policy 并存，接口只依赖 `AbstractFilesystem` + Runner。

6. **Sandbox / Store / Local 三种后端在 memory 视角下等价可替换。**  
   任何 memory 组件不允许通过 `instanceof` 区分后端类型。

---

## 9. 验收建议

- 增补一个 `MemoryFilesystemComplianceTest`，用 ArchUnit/自定义规则禁止 memory 模块直接引用 `java.nio.file.Files`。
- 增补一组"同一份输入在 Local/Sandbox/Store 三种模式下产生相同 MEMORY.md"的对比测试。
- Phase 3 完成后，复跑现有 sandbox 模式下的 consolidation 场景，观察 MEMORY.md 是否按预期生成。
- Store 模式下，起两个 HarnessAgent 实例并发写同一 namespace，观察 consolidation/归档是否收敛；记录 last-writer-wins 事件数，作为后续 CAS/锁优化的基线。

---

## 10. 附：关键对照表（一页速览）

| 症状 | Sandbox 模式 | Store 模式 | 根因 | Phase |
|---|---|---|---|---|
| MEMORY.md 不自动合并 | ✅ 坏 | ✅ 坏 | `readDailyEntries` 直连本地 | 1 |
| Watermark 跨实例漂移 | ⚠️ 坏 | ✅ 坏 | watermark 直连本地 | 1 |
| 归档老 daily 不工作 | ✅ 坏 | ✅ 坏 | scheduler 直连本地 | 3 |
| 清理老 session 不彻底 | ⚠️ 半坏 | ⚠️ 半坏（Store 内残留） | scheduler 直连本地 | 3 |
| 启动 FTS 索引空 | ✅ 坏 | ✅ 坏 | `indexAllFromWorkspace` 直连本地 | 3 |
| 运行时增量 FTS | ✅ 正常 | ✅ 正常 | 走 filesystem | — |
| Session 搜索 | ✅ 正常（dual-write） | ✅ 正常（dual-write） | SessionTree dual-write | 4（可清理） |
| 工具链（Shell/Filesystem tool） | ✅ 正常 | ✅ 正常 | 已经只走 filesystem | — |

---

**最后一句话总结**：  
OpenAI 用"单 workspace + 单 I/O 网关 + LLM 做全局合并"把整类问题消灭在源头；我们的目标是保留 sandbox / Store / Local 三种后端选择，同时用同一种纪律——**memory 领域的一切 I/O 只走 `AbstractFilesystem`**——来等效达成"单网关"效果。把这条纪律立起来，sandbox 模式和 Store 模式的共性顽疾会一次性消失。
