# Subagent Phase A 迁移指南

> 适用版本：`agentscope-core` 2.0.0-SNAPSHOT（含 Phase A 改造）
> 关联文档：[SUBAGENT_GAP_VS_OPENCODE.md](./SUBAGENT_GAP_VS_OPENCODE.md)、实施 plan `~/.claude/plans/deep-mixing-adleman.md`

Phase A 三项落地（编号对应 `SUBAGENT_GAP_VS_OPENCODE.md` 第 3 节）：

- **(6)** `SubagentDeclaration` 新增模型超参 `temperature` / `topP` / `variant` / `steps`
- **(9)/(10)** `SubagentDeclaration` 新增 `mode` (PRIMARY/SUBAGENT/ALL) 与 `hidden`
- **(7)** 新增 `SubagentSpecGenerator` + `AgentGenerateTool`，支持 LLM 反推 subagent 规格

不动 `HarnessAgent.builder()` 顶层启动路径、`WorkspaceMode`、`TaskRepository`、`SubagentEventBus`。

---

## 1. 破坏性变更（source-level）

### 1.1 `SubagentDeclaration.getMaxIters()` → `@Deprecated`

```java
@Deprecated
public int getMaxIters() { return steps; }   // 等价 getSteps()
```

- 不再有同名字段；`maxIters` 的值现在保存在 `steps` 字段。
- 行为不变 — 仍然返回相同的 int。
- 编译期警告：建议改用 `getSteps()`。

### 1.2 `SubagentDeclaration.Builder#maxIters(int)` → `@Deprecated`

```java
@Deprecated
public Builder maxIters(int maxIters) { this.steps = maxIters; return this; }

public Builder steps(int steps) { this.steps = steps; return this; }
```

- 现有调用方无需立刻迁移；编译通过 + 运行时等价。
- 新代码请用 `.steps(...)`。

### 1.3 `DefaultAgentManager.createAgentIfPresent(...)` 多了一个拒绝路径

```java
SubagentDeclaration decl = declarations.get(agentId);
if (decl != null && decl.getMode() == Mode.PRIMARY) {
    return Optional.empty();   // 新增：PRIMARY-only 不允许被 spawn
}
```

- 任何**显式**标 `mode: primary` 的 declaration 将不再可被 `agent_spawn` 调度，结果为 `Optional.empty()`。
- 之前 `mode` 字段不存在，所有 declaration 都视为 `ALL`，行为不变。
- 调用方 `AgentSpawnTool.agentSpawn` 错误文案变更：
  - 旧：`Error: Unknown agent_id: <name>`
  - 新（PRIMARY-only）：`Error: agent_id '<name>' is PRIMARY-only and cannot be spawned as a subagent.`

### 1.4 `SubagentsMiddleware.renderSubagentSection` 渲染会过滤

- `### Available agent ids` 列表会跳过 `hidden=true` 或 `mode=PRIMARY` 的 declaration。
- 编程式注册（`new SubagentEntry(name, desc, factory)`，无 declaration）行为不变 — 仍然全部展示。
- 若你的测试断言「某 hidden agent 出现在 system prompt 里」，请改成断言它**不**出现。

---

## 2. 新增 schema 字段（YAML frontmatter）

`subagents/*.md` 文件现在支持以下额外字段（全部可选；缺省走 builder/parent fallback）：

```yaml
---
description: A test agent              # 必填，原有
workspace:                              # 原有
  mode: isolated                        # 原有
  path: ./defs/agent                    # 原有
model: qwen3-max                        # 原有

# Phase A 新增 ↓
temperature: 0.3                        # 0..2，覆盖父 GenerateOptions.temperature
top_p: 0.9                              # 0..1（也接受 topP 拼写）
variant: thinking                       # 模型变体（schema 已保留，详见 §3）
steps: 12                               # 重命名自 maxIters，旧 maxIters 仍兼容
mode: subagent                          # primary | subagent | all（默认 all）
hidden: false                           # 默认 false；true 时不出现在 LLM 视野

tools: [read_file, grep_files]          # 原有 — 工具白名单
---
```

### Builder 写法

```java
SubagentDeclaration decl = SubagentDeclaration.builder()
        .name("code-reviewer")
        .description("Reviews code for security issues")
        .inlineAgentsBody("You are a code reviewer...")
        .temperature(0.2)
        .topP(0.9)
        .steps(8)
        .mode(SubagentDeclaration.Mode.SUBAGENT)
        .hidden(false)
        .build();
```

---

## 3. variant 字段当前状态（重要）

`variant` 在 `SubagentDeclaration` / `AgentSpecLoader` 两侧都支持解析与存储，**但 `ReActAgentBuilderSupport.buildDeclaredFactory` 暂不把它透传到 child 的 `GenerateOptions`**。原因：`io.agentscope.core.model.GenerateOptions` 当前没有 `variant` 字段，model 层尚无 variant 概念。

含义：
- 现在写 `variant: thinking` 不会报错，但也不会改变 child 运行行为。
- 该字段是 schema-forward 存储 —— 等后续 PR 在 model 层加上 variant 后，无需改 declaration / loader。

---

## 4. 新增工具：`agent_generate`（默认关闭）

`SubagentSpecGenerator` 用 LLM 生成 subagent markdown spec；`AgentGenerateTool` 把它暴露为 `agent_generate` 工具。**OOTB 不启用**。

### 4.1 启用

```java
SubagentsMiddleware mw = new SubagentsMiddleware(
        baseEntries, taskRepo, workspaceManager,
        filesystem, mainWorkspace, factoryBuilder);

mw.enableAgentGenerateTool(new SubagentSpecGenerator(modelToUse));

// mw.getTools() 现在返回 [subagentTool, taskTool, agentGenerateTool]
```

仅在默认（非 session）模式下生效；session 模式调用是 no-op（会写 debug 日志）。

### 4.2 工具签名

```
agent_generate(name, description, dry_run=false)
```

- `name`: 必填，kebab-case，必须不与已有 agent 重名（含运行时动态加载的）。
- `description`: 必填，自然语言描述这个 subagent 该做什么。
- `dry_run`: `true` 时只返回 markdown 不写盘；`false`（默认）时写到 `subagents/<name>.md`。

写盘后下一轮 reasoning 由 `DynamicSubagentsMiddleware` 自动扫描并注册新 agent — 不需要重启进程。

### 4.3 错误返回

| 触发条件 | 返回 |
|---|---|
| `name` 为空 | `Error: name is required` |
| `name` 非 kebab-case | `Error: name '<name>' is not a valid kebab-case identifier (lowercase, digits, '-')` |
| `description` 为空 | `Error: description is required` |
| `name` 已存在 | `Error: agent '<name>' already exists` |
| LLM 输出无法解析 | `Error: LLM produced a malformed subagent spec for '<name>'; could not parse frontmatter` |
| `filesystem` 未配置且非 dry_run | `Error: no filesystem configured for AgentGenerateTool — cannot persist spec. Use dry_run=true to preview.` |
| `filesystem.write` 失败 | `Error: write failed for subagents/<name>.md: <err>` + 附带生成的 markdown |

---

## 5. 不变之处

以下设计在 Phase A 没有动，沿用现有行为：

- **`HarnessAgent.builder()` 顶层启动**：主 agent 永远不经 `SubagentDeclaration`，`mode` 字段也不影响顶层启动入口（`CodingBootstrap` / `ClawBootstrap` / `BuilderBootstrap` 等）。
- **`WorkspaceMode` (ISOLATED / SHARED)**：保持原决策表，未引入新模式。
- **`TaskRepository` / `WorkspaceTaskRepository`**：任务持久化、heartbeat、孤儿扫描、跨节点 cancel 协调 — 完全不变。
- **`SubagentEventBus`**：流式事件聚合、`Reactor Context` 注入 — 完全不变。
- **`AgentSpawnTool.agentList()`**：只列已 spawn 的活体实例，与 `hidden` 概念正交，不变。
- **`SubagentsMiddleware.getTools()` 默认返回**：仍是 `[subagentTool, taskTool]`，只有显式 `enableAgentGenerateTool(...)` 后才会出现第 3 个工具。

---

## 6. 单元测试参考

新增了三个文件可作为 schema/行为参考：

- `agentscope-core/src/test/java/io/agentscope/harness/agent/subagent/SubagentDeclarationPhaseATest.java` — temperature/topP/variant/steps + maxIters 兼容
- `.../SubagentModeHiddenTest.java` — mode/hidden 解析、`renderSubagentSection` 过滤、`DefaultAgentManager` PRIMARY 拒绝
- `.../SubagentSpecGeneratorTest.java` — LLM 输出 round-trip 校验
- `agentscope-core/src/test/java/io/agentscope/harness/agent/tool/AgentGenerateToolTest.java` — name 校验、冲突、dry_run、写盘、malformed LLM 输出

跑 Phase A 集合：

```bash
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='SubagentDeclarationPhaseATest,SubagentModeHiddenTest,SubagentSpecGeneratorTest,AgentGenerateToolTest'
```

回归：

```bash
mvn -pl agentscope-core test -Dspotless.check.skip=true \
    -Dtest='HarnessAgentTest,HarnessAgentModelStringTest,HarnessAgentSubagentStreamTest,HarnessAgentDynamicHookBuilderTest'
```

两组都应全绿（开发期实测 17 + 9 + 24 = 50 cases pass）。

---

## 7. 下一步（Phase B/C 预告）

Phase A 把 declaration schema 与 LLM-authored spec 通道打通，为后续两步打底：

- **Phase B**：后台任务完成反向通知（取代轮询提示词）+ 子 agent 活体/对话历史持久化（让 `agent_send` 跨重启可用）。
- **Phase C**：Permission ruleset 三件套（allow/ask/deny + pattern + 父→子继承）+ 把 compaction / title / summary 包成 hidden subagent declaration（依赖本期 hidden + mode）。

详细差距与价值排序见 [`SUBAGENT_GAP_VS_OPENCODE.md`](./SUBAGENT_GAP_VS_OPENCODE.md) 第 3、4 节。
