---
hide-toc: true
---

# 用 AgentScope Harness 从零搭一个 Coding Agent：从本地 REPL 到企业级机器人

Coding Agent 是过去一年最热的智能体形态之一：从 Cursor、Windsurf 到 Devin、Claude Code，大家都在试图让模型"自己 clone 代码、自己跑测试、自己提 PR"。从开发者角度看，把这件事在自家组织里跑起来其实就两个核心问题：

- **怎么安全地让模型动你的代码？** 任何一次 `npm install`、`mvn test`、`rm -rf` 都不能动到宿主或构建机。
- **怎么让它持续对话？** GitHub Issue 评论、PR Review、IM @ 机器人——同一段任务可能横跨几小时、几天，状态要能续上。

### 不是另一个 Claude Code：先把定位说清楚

在往下读之前，先把"我们要做的"和"已经存在的本地工具"区分开，避免读者带着错的期待往下看。

Claude Code、Cursor、Cline 这类产品的形态是：**装在单个开发者机器上的 CLI/IDE 协作助手，人坐在终端前实时介入。** 它优化的是"我一个人写代码更快"——你打字、它干活、你看着它干活、你随时打断纠正。状态、记忆、文件全在你本机；触发者就是你自己；信任边界就是"你信你自己的机器"。

本文要搭的 codingagent 是另一种东西：**给整个工程组织部署的后台 Coding Agent 服务，通过 GitHub Webhook / IM @机器人 异步触发，每个任务在远端沙箱里跑、产出 draft PR。** 它优化的是"团队里某个小任务我都不用自己看，扔给 agent 跑完开 PR 我 review 一下就行"——触发者可能是任何一个 Issue 评论者，agent 在远端跑十几分钟到一小时，没人盯着。

这两种形态在功能集上有交集（都能写代码、跑命令、改文件），但**部署成本、安全模型、组织治理**完全不在一个量级。本文讲的是后者；前者用 Claude Code 这类成熟产品就够了，没必要自己造。两者更细的对比放在[第 3.3 节](#33-定位差异和-claude-code-这类本地工具比codingagent-是另一种东西)。

> 顺带说一句：Harness 设计上**两种形态都支持**——`HarnessAgent.builder()` 不配 `.filesystem(...)` 就是本机加强版（能装记忆、能装技能），加沙箱 + 分布式 Session 就升级成 codingagent 这种组织服务。同一份 agent 代码，按需切换部署形态。

### 本文做什么

AgentScope Java 2.0 的 Harness 模块就是为开头那两个核心问题准备的。本文以 `agentscope-examples/agents/agentscope-codingagent` 这个**官方完整示例**为线索，讲清楚一个生产级 Coding Agent 是如何用 Harness 拼出来的：它依赖哪些 Harness 能力、各自解决了什么问题、怎么从本地 CLI 一路演进到挂在 GitHub Webhook 后面的企业服务。

读完你会得到三件事：

1. 一份**可运行**的最小 Coding Agent（CLI 模式，5 分钟跑通）；
2. 一张**Harness 能力 → Coding Agent 工程问题**的对应表，知道每一行 `builder.xxx()` 解决了什么；
3. 一份**从单机到分布式**的演进路径，避免一上来就把架构做复杂。

> 完整文档与源码：
> - Harness 架构与各能力详解：`docs/v2/zh/docs/harness/`
> - codingagent 示例：`agentscope-examples/agents/agentscope-codingagent/`

---

## 一、Coding Agent 难在哪里？

在动手堆代码之前，先把这类应用的工程难点摆清楚——这样后面看 Harness 的能力地图才有锚点。

| 工程难点 | 具体表现 |
|---|---|
| **执行隔离** | `git clone` / `npm install` / `mvn test` / 任意 Shell 命令，绝不能在宿主上跑 |
| **跨调用恢复** | 同一段会话里，第二轮 `call()` 必须能复用第一轮的代码仓库、依赖目录、临时文件 |
| **多用户隔离** | 多个 issue / PR / IM 会话并发，状态、文件、记忆都不能串台 |
| **长会话状态** | 一个 Issue 可能跑几小时几十轮，对话历史超出模型上下文是常态 |
| **多通道触达** | GitHub Webhook、CLI、钉钉、飞书都要能接同一个 agent，路由规则要统一 |
| **可观测与限流** | 模型调用预算、限流重试、指标埋点缺一不可 |
| **可演化的能力** | 团队规范、技能脚本要能不动代码就更新 |

这七个难点单独解决都不难，但要拼成一个"上线后能跑住"的系统，工程复杂度会陡升。Harness 的设计目标就是把这些难点**变成几行 builder 配置**。

---

## 二、Harness 能力地图：每一行配置解决一个问题

`HarnessAgent` 是 `ReActAgent` 的工程化封装：核心推理循环没变，但在循环的关键时机插入了 middleware，把"长期运行的 agent 必备的工程能力"打包进单一 builder。下面这张表把 Coding Agent 关心的能力直接列出来：

| Harness 能力 | Builder 入口 | 在 Coding Agent 里解决的问题 |
|---|---|---|
| 工作区（Workspace） | `.workspace(path)` | 人格 `AGENTS.md`、技能 `skills/`、知识 `knowledge/` 都以文件形式版本化，改文件即升级 agent |
| 沙箱文件系统 | `.filesystem(new DockerFilesystemSpec()…)` | 把 `git clone`、`mvn test`、`execute` 全部关进容器，宿主无感 |
| 跨调用快照 | `.snapshotSpec(…)` | 第二轮 `call()` 能看到第一轮装好的 `node_modules` 和克隆下来的仓库 |
| 隔离粒度 | `.isolationScope(IsolationScope.SESSION)` | 每个 issue / PR / IM 对话一个独立沙箱，互不串台 |
| 状态持久化 | 默认开启 / `.stateStore(RedisAgentStateStore.…)` | 同 `(userId, sessionId)` 跨进程、跨副本恢复对话 |
| 双层记忆 | `.compaction(…)` | 长会话压缩 + 长期事实沉淀到 `MEMORY.md` |
| 大工具结果卸载 | `.toolResultEviction(…)` | `git diff` / `mvn test` 输出动辄几十 K 字符，自动落盘 + 占位符 |
| 子 agent | `workspace/subagents/` 或 `.subagent(…)` | 把 PR Review 这种独立任务委派出去，主 agent 不被淹没 |
| 技能装配 | `workspace/skills/` 或 `.skillRepository(…)` | 团队约定的 SOP（提交规范、测试规范）以 skill 形式自动生效 |
| Plan Mode | `.enablePlanMode()` | 大重构前强制"先写计划 → 人确认 → 再动手"，降低误操作 |

codingagent 示例几乎用到了上面所有能力。下面我们按它的真实结构展开。

---

## 三、codingagent 的整体架构

codingagent 跑在 **HarnessAgent + `SandboxFilesystem`** 之上，sandbox 生命周期由运行时自己管。任何通道送来的事件都会被 `ThreadIdFactory` 确定性映射成一个 `threadId`，dispatcher 按 thread 派发（忙时入队），agent 在 per-session 的 Docker 容器内执行。

```
   GitHub Webhook · CLI · 钉钉 · 飞书
                   │
                   ▼
        ┌───────────────────────┐
        │ Channel 适配器        │  HMAC 校验 · 去重 · 过滤自评
        └─────────┬─────────────┘
                  ▼
        ┌───────────────────────┐
        │ ThreadIdFactory       │  github:issue:owner/repo#42 → SHA-256 → UUID
        └─────────┬─────────────┘
                  ▼
        ┌───────────────────────┐
        │ RunDispatcher         │  立即派发 · thread 忙时入队
        │   ├ MessageQueueHook  │
        │   ├ ThreadBudgetHook  │
        │   └ ModelCallLimitHook│
        └─────────┬─────────────┘
                  ▼
        ┌──────────────────────────────────────────┐
        │ HarnessGateway                           │
        │   ├ CodingAgent  （issue / PR 迭代）     │
        │   └ ReviewerAgent（review_requested）    │
        └─────────┬────────────────────────────────┘
                  ▼
        ┌──────────────────────────────────────────┐
        │ SandboxFilesystem  （per-thread Docker） │
        │   agentscope/coding-sandbox:latest       │
        │   ├ git · shell · 构建工具集             │
        │   └ 运行时托管生命周期（自动）           │
        └─────────┬────────────────────────────────┘
                  ▼
            GitHub API · 目标仓库
```

这张图里每一层都对应 Harness 的一类能力。下面我们从下到上拆解。

### 3.1 两个 Agent 的分工

codingagent 用两套独立的 `HarnessAgent`：

| Agent ID   | 类                     | 职责                                       |
|------------|------------------------|--------------------------------------------|
| `coding`   | `CodingAgentFactory`   | 实现 issue、写代码、推 PR                  |
| `reviewer` | `ReviewerAgentFactory` | 评审 PR、记录 findings、发表一次性 Review  |

它俩共享同一套 Harness 装配模式（同样的 workspace、同样的沙箱、同样的会话持久化），但分别有自己的 system prompt 和工具集：

- **coding agent**：吃 Issue 评论 / PR 行评论，工具集偏"写"——`write_file`、`edit_file`、`execute`（在沙箱里跑 `git push`、`mvn test`）、`GitHubApiTool`（开 PR、评论）。
- **reviewer agent**：吃"review_requested"事件，工具集偏"读 + 结构化"——`read_file`、`grep_files`、`add_finding`（结构化记录问题）、`publish_review`（汇总后一次性发表）。

把 review 单独拆出来不是凑数——它的会话特征完全不同：一次性、无状态、产出固定（一篇 review），所以独立配额、独立 system prompt 反而更稳。

### 3.2 三类工具：内置 + 通道 + 业务

每个 agent 装的工具大致分三层：

1. **Harness 内置工具**（不用注册，`HarnessAgent` 构造时自动装）：`read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files` / `memory_search` / `memory_get` / `session_search` / `agent_spawn` 等。
2. **沙箱启用后追加的 shell 工具**：`execute`——只有 `filesystem` 配的是沙箱或本机模式时才暴露，这是有意的安全设计。
3. **codingagent 自己写的业务工具**：`GitHubApiTool`、`HttpRequestTool`、`FetchUrlTool`、`WebSearchTool`，外加 reviewer 专属的 `add_finding` / `publish_review`。

模型不感知工具来自哪一层，但你作为开发者要明白：内置工具靠 Harness 注册，业务工具靠你 `toolkit.register(...)`，**Shell 工具靠的是 filesystem 配置**——不配沙箱就没有 shell，这是默认安全策略。

### 3.3 定位差异：和 Claude Code 这类本地工具比，codingagent 是另一种东西

读到这里很多人会问：Claude Code、Cursor、Cline 不也是 Coding Agent 吗？为什么 codingagent 的架构看起来这么"重"——又是 dispatcher、又是 thread 路由、又是 Channel 适配器？

答案是它们是**两种不同形态的产品**，工程复杂度不在一个量级。下面这张表把核心定位差异列清楚：

| 维度 | Claude Code 等本地工具 | codingagent / Open SWE 等组织级服务 |
|---|---|---|
| **服务对象** | 单个开发者（"我自己"） | 整个组织（"团队 + 多用户"） |
| **运行位置** | 开发者本机 CLI / IDE | 自托管后端服务，多副本部署 |
| **触发方式** | 终端打字、IDE 交互（**同步**） | GitHub Webhook / IM `@bot` / PR review request（**异步**） |
| **典型任务时长** | 秒~分钟，强人机交互 | 分钟~小时，无人值守 |
| **执行位置** | 你的开发机 `sh -c`，文件直改 | 远端 per-session Docker/K8s 沙箱，产出 draft PR |
| **权限哲学** | "**Ask first**" —— 危险工具实时弹确认 | "**Isolate first**" —— 沙箱内拥有完全权限 |
| **状态归属** | `~/.claude/` 本地文件，单人单机 | 工作区 + Redis Session + 沙箱快照，跨节点共享 |
| **身份认证** | 一个人的 Anthropic 账户 | GitHub App + 可选 per-user OAuth + 组织白名单 |
| **多租户** | 不是多租户产品 | 从第一天就是多租户 |
| **定制方式** | 用户态扩展（hooks / plugins / MCP / CLAUDE.md） | fork 后端代码 / 工作区文件 / Channel 适配器 |

把这张表翻译成 codingagent 架构图里的具体设计决定：

- **为什么需要 ThreadIdFactory + RunDispatcher？** 因为同时可能有几十个 GitHub Issue / PR / IM 对话并发触发，每个都要稳定地路由到自己的 agent session，互不串台。Claude Code 是"一个人坐在终端前"，根本不需要这一层。
- **为什么强制 per-session Docker 沙箱？** 因为触发者不是机器主人——任何 Slack 用户、任何 GitHub Issue 评论者都能让 agent 跑代码，宿主必须假设输入不可信。Claude Code 跑在你自己机器上，你信任你自己。
- **为什么需要 Channel 适配器抽象？** 因为同一个 agent 要接得住 GitHub Webhook、钉钉 Stream、飞书 callback 多种入口。Claude Code 只有一个入口：你的终端。
- **为什么需要 ThreadBudgetHook + ModelCallLimitHook？** 因为多用户共享模型预算，一个用户的失控循环不能把全公司的额度烧光。Claude Code 烧的是你自己的额度，自己心疼自己控制。
- **为什么 Session 默认走 Redis、沙箱默认走 OSS 快照？** 因为生产服务要支持滚动发布、故障转移、横向扩展——任何节点都要能接住任何会话。Claude Code 重启就重启了，你重新打开就行。

**所以选型的判断标准很简单：你要做的是一个"开发者自己用的工具"，还是一个"组织里随便谁触发都能用的服务"？**

- 前者直接装 Claude Code / Cursor，或者用 Harness 跑[默认本机模式](../docs/harness/filesystem.md)（不配 `filesystem`），就是一个能装记忆、能装技能、能 spawn 子 agent 的"加强版本地 Coding Agent"。
- 后者就是 codingagent 这套范式：Harness + 沙箱 + 分布式 `AgentStateStore` + Channel 适配器，每一层都是为"多人、异步、安全"而存在的。

Harness 的设计目标恰好是让**同一份 agent 代码逻辑，能在两种形态间通过配置切换**——`HarnessAgent.builder()` 不写 `.filesystem(...)` 就是 Claude Code 那种本机形态，加一行 `.filesystem(new DockerFilesystemSpec()...)` + `.stateStore(RedisAgentStateStore.…)` 就升级成 codingagent 这种组织服务。本文剩下的部分都是后者的实现。

---

## 四、从 0 开始：5 分钟跑通本地 CLI

最快的体验路径——一个环境变量、一个 Maven 命令，本地文件系统上跑一个交互式 REPL。无需 Docker、无需 webhook、无需 GitHub App。

```bash
# 1. 设置模型 key（默认 DashScope；OpenAI / Anthropic 也支持）
export DASHSCOPE_API_KEY=sk-...

# 2. 在仓库根目录构建依赖（之后跑可以省略）
cd agentscope-java
mvn install -pl agentscope-examples/agents/agentscope-codingagent -am -DskipTests -q

# 3. 启动 CLI
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

启动后会出 banner，然后到 `You>` 提示符。**agent 工作在自己的 workspace** `~/.agentscope/codingagent/workspace/`（不是你当前的仓库目录）——标准玩法是把目标仓库 *克隆进* workspace 再操作。所以好的首条 prompt 是：

```
You> write hello.txt with a haiku about Java
You> fetch https://github.com/anthropics/anthropic-sdk-python/blob/main/README.md and summarize it
You> clone https://github.com/owner/repo into the workspace and tell me what it does
You> /exit
```

workspace、聊天记录、配置都和你的项目目录、其他 harness 应用相互隔离：

- **Workspace**（agent 读写的 skills/memory/sessions/files）：`~/.agentscope/codingagent/workspace/`
- **聊天记录**（per-thread SQLite）：`./.agentscope/codingagent.db`
- **配置**：硬编码在 `CodingChatCli` 里——CLI 会忽略项目根目录的 `.agentscope/agentscope.json`，避免别的 harness 应用留下的旧配置覆盖它。

这就是 Harness 价值的第一层：**默认模式下你什么都不用配，跑起来就有完整的工作区、会话持久化和长期记忆**。

### 进阶：让 agent 评审一个真实的 GitHub PR

```bash
export GITHUB_TOKEN=ghp_...          # 带 `repo` scope 的 PAT
# REPL 内:
You> review https://github.com/owner/repo/pull/42
```

这条会把请求路由到 **reviewer** agent —— 它会拉取 diff、记录结构化 findings，并发表一次完整的 GitHub Review。

### 进阶：把每个 session 隔离到 Docker sandbox

```bash
# 先构建 sandbox 镜像
docker build \
  -t agentscope/coding-sandbox:latest \
  agentscope-examples/agents/agentscope-codingagent/src/main/docker/coding-sandbox/

export SANDBOX_TYPE=docker
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

每个聊天 thread 都会拿到自己的临时容器——让 agent 跑任意 `execute` 命令时更安全。这就引出了 Harness 的核心能力之一——**沙箱**。

---

## 五、核心能力详解：用 Harness 解 Coding Agent 的工程难题

### 5.1 沙箱：让 agent 可以"放心 `rm -rf`"

Coding Agent 最大的工程矛盾是：要让模型有真正的执行能力（git、构建、测试），又不能让它误伤宿主。Harness 的答案是把**所有文件操作和命令执行**都收进一个 Docker 容器。

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("coding")
    .model(model)
    .workspace(workspace)
    .filesystem(new DockerFilesystemSpec()
        .image("agentscope/coding-sandbox:latest")
        .isolationScope(IsolationScope.SESSION))
    .build();
```

只要这一行 `.filesystem(...)`，发生四件事：

1. `read_file` / `write_file` / `execute` 等所有内置工具自动改走沙箱后端，agent 代码完全不用动；
2. `sessionId` 不同 → 沙箱不同；`sessionId` 相同 → 自动复用同一沙箱；
3. `IsolationScope.SESSION`（默认）保证每个 issue / PR / IM 对话各自一个独立容器；
4. 工作区里的 `AGENTS.md` / `skills/` / `subagents/` / `knowledge/` 会在沙箱启动时 hydrate 进容器（内容哈希增量），改了 `skills/` 里的脚本下次 `call()` 沙箱里就是新版。

#### 跨调用恢复 = 快照

Coding Agent 真正难做的地方是**第二轮 `call()`**：用户在 PR 上又评了一条 "再补个测试"，agent 必须能复用第一轮装好的 `node_modules` 和克隆下来的仓库——重新 `git clone` + `npm install` 一遍要等 5 分钟，谁也受不了。

沙箱在每次 `call()` 结束时把工作区状态打包成快照存起来；下次 `call()` 按情况恢复：

- 容器还在 + 工作区还在 → 直接接着用（最快）
- 容器没了 → 拿快照重新起一个，恢复工作区
- 没快照 → 按 `WorkspaceSpec` 全量初始化（冷启动）

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")
    .snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/")))
```

快照后端可选：`NoopSnapshotSpec`（默认，不持久化）、`LocalSnapshotSpec`（本地单机）、`OssSnapshotSpec`（S3 兼容，多副本）、`RedisSnapshotSpec`（低延迟，小工作区）。

#### IsolationScope —— 谁和谁共享同一沙箱

| Scope | 谁共享 | 在 Coding Agent 里的典型用法 |
|-------|--------|---------|
| `SESSION`（默认） | 每个 sessionId 独立 | 每个 GitHub Issue / PR / IM 对话各跑各的（codingagent 的默认） |
| `USER` | 同 `userId` 的多个 session 共享 | 一个开发者的"个人工作台"——多个对话共享同一份仓库克隆 |
| `AGENT` | 该 agent 所有用户共享 | 公共工具型，例如"全公司共享的代码搜索沙箱" |
| `GLOBAL` | 全局一份 | 谨慎使用 |

codingagent 用的是 `SESSION`——每个 GitHub Issue/PR/IM 对话独立沙箱，最自然也最安全。

### 5.2 工作区：人格、记忆、技能都是文件

Harness 把所有跨调用、跨重启需要保留的东西都组织成一个目录——`workspace`：

```
~/.agentscope/codingagent/workspace/
├── AGENTS.md                    ← 静态：人格 + 行为约定
├── MEMORY.md                    ← 长期记忆：策划后的事实
├── tools.json                   ← 静态：MCP server + 工具白名单（可选）
├── memory/YYYY-MM-DD.md         ← 长期记忆：每日流水账
├── knowledge/                   ← 静态：领域知识（API 文档、代码规范）
├── skills/                      ← 静态：可复用技能
│   └── pr-checklist/SKILL.md
├── subagents/                   ← 静态：子 agent 声明
│   └── researcher.md
├── plans/                       ← 运行时：Plan Mode 计划文件
└── agents/<agentId>/            ← 运行时：会话快照 + 日志 + 子任务
    ├── context/<sessionId>/agent_state.json
    ├── sessions/<sessionId>.log.jsonl
    └── tasks/<sessionId>.json
```

对 Coding Agent 而言，工作区的工程价值在三个地方：

**1. 团队规范以文件形式生效。** 比如你想让所有 PR 都遵循一套 commit message 规范，不需要塞进 system prompt，写成一份 skill 放进 `skills/commit-style/SKILL.md`，所有 agent 实例下次 `call()` 就生效，不用重启。

**2. agent 在用的过程中越来越懂团队。** 第一次它问"我们用哪个测试框架"，你告诉它"JUnit 5 + Mockito"。`MemoryFlushMiddleware` 自动把这条事实写到当日流水账，后台节流任务周期性合并进 `MEMORY.md`，下次 `call()` 它就记得了——所有用同一 workspace 的对话都受益。

**3. 工作区可以版本化。** 把 `AGENTS.md` + `skills/` + `subagents/` + `knowledge/` 当作 Agent 的"配置仓库"——用 Git 管理，CI 验证，部署时 hydrate 进所有副本。这是 Harness 区别于"prompt 写死在代码里"的最大优势。

### 5.3 会话持久化：跨进程、跨副本恢复

codingagent 是个**长生命周期**应用：一个 Issue 可能从早上聊到晚上，期间服务可能滚动发布、扩缩容、副本切换——但用户感知到的应该是"对话不会断"。

Harness 把这件事拆成两条互相配合的链路：

- **AgentStateStore** —— `AgentState`（对话历史、压缩摘要、Plan 状态、todo 列表、权限规则、工具状态）每次 `call()` 结束自动落盘；下次同 `(userId, sessionId)` 的 `call()` 自动加载。
- **永不压缩的对话日志** —— 原始 messages 永远以 `sessions/<sessionId>.log.jsonl` 追加保留，供审计和 `session_search` 查询。

```java
// 单机开发（默认）：本地 JsonFileAgentStateStore，落在 ~/.agentscope/state/<id>/
HarnessAgent.builder()
    .workspace(workspace)
    .build();

// 多副本生产：Redis
RedisClient client = RedisClient.create("redis://redis.prod:6379");
HarnessAgent.builder()
    .workspace(workspace)
    .stateStore(RedisAgentStateStore.builder().lettuceClient(client).build())
    .build();
```

切到 Redis 之后这一切是**自动**的：

- **故障转移**：节点崩了，会话漂到另一个节点，用户感知不到。
- **滚动发布**：旧 pod 退出前 `shutdownManager` 自动保存，新 pod 接到流量时自动从 Redis 还原，对话不会断。
- **跨场景接续**：在 GitHub Issue 里和 agent 聊到一半，切换到钉钉私聊继续聊——只要 `sessionId` 一致，记忆都在。

⚠ 一个强制约束：如果你已经在用 `filesystem(SandboxFilesystemSpec)`（沙箱）或 `filesystem(RemoteFilesystemSpec)`（远端 KV），Harness 会**强制要求** Session 也换成分布式后端（Redis / MySQL），否则 `build()` 直接抛 `IllegalStateException`——因为沙箱状态必须跨副本共享，否则就会丢。

### 5.4 双层记忆：长会话也不爆上下文

一个长 Issue 跑几十轮、上百条消息，模型上下文窗口很快就撑爆。Harness 的答案是双层记忆 + 多重压缩：

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(50)      // 50 条触发摘要压缩
        .keepMessages(20)         // 保留尾部 20 条原文
        .build())
    .toolResultEviction(ToolResultEvictionConfig.defaults())  // 大工具结果自动卸载
    .build();
```

四套机制独立可组合：

| 策略 | 解决的问题 | 触发时机 |
|------|----------|----------|
| **对话摘要压缩** | 上下文太"深"——消息条数太多 | 每次模型推理前 |
| **大工具结果卸载** | 上下文太"宽"——单条工具结果过大（`git diff`、`mvn test`） | 工具执行后 |
| **上下文溢出兜底** | 真的撞到模型 `context_length_exceeded` | `call()` 抛错时自动重试 |
| **预压缩参数截断** | `write_file` 的内容入参很大但后期没人看 | 摘要之前的轻量预处理 |

Coding Agent 里 `git diff` / `mvn test` 的输出经常上万字符，`ToolResultEvictionMiddleware` 把超过 80K 字符的结果写到工作区某个目录，上下文里只保留首尾各约 2K 字符 + 一个 `read_file` 路径提示——agent 想看全文自己再读一遍。这一条几乎是 Coding Agent 的标配。

同时，`MEMORY.md` 会从每天的流水账里周期性合并出"团队约定"、"已知陷阱"等长期事实，每轮推理时注入 system prompt。codingagent 跑久了，`MEMORY.md` 里可能就长出这样的内容：

```markdown
- 仓库 owner/repo 的测试命令是 `mvn -pl module test`，根目录 `mvn test` 太慢不要用
- main 分支受保护，必须通过 PR 合并；feature 分支命名约定为 `feat/`
- CI 用 GitHub Actions，配置文件在 .github/workflows/ci.yml
```

### 5.5 子 Agent：把 PR Review 委派出去

codingagent 的两个 agent（`coding` 和 `reviewer`）其实就是 Harness 子 agent 模式的一个变种——只不过它们走的是同级路由（按事件类型分发到不同 agent），而不是父→子 spawn 关系。

但 Harness 的子 agent 能力在 Coding Agent 里同样有用：

**典型用法 1：研究子任务**。"先去 GitHub 看下这个库的 README + 最近 3 个 PR，再回来告诉我怎么改"——这是个独立的、上下文重的子任务，spawn 出去让子 agent 专门跑：

`workspace/subagents/researcher.md`：

```markdown
---
description: 调研子 agent。当需要先了解一个外部仓库、文档或库再做修改时使用。
workspace:
  mode: isolated
tools: [read_file, grep_files, fetch_url, web_search]
---

你是调研助手。请按以下流程：
1. fetch_url / web_search 收集材料
2. 用 read_file / grep_files 看相关代码
3. 给主 agent 一份带要点和引用的简报
```

主 agent 就能调用：

```
agent_spawn agent_id="researcher" task="调研 ABC 库的 v2 升级要点和 breaking changes"
```

**典型用法 2：长任务后台跑**。一个大重构可能要跑几分钟甚至更久，主 agent 不应该一直 block：

```
agent_spawn agent_id="refactor-runner" task="..." timeout_seconds=0
```

`timeout_seconds=0` 是后台调用，立即返回一个 `task_id`，子 agent 在后台跑；跑完后**主 agent 不需要轮询**——下一次推理开始前，框架会把已完成的任务结果作为系统提醒注入对话末尾，主 agent 自然地继续。

子 agent 的状态默认写到 `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`，多副本场景下任意节点都能读到任务状态。

### 5.6 技能装配：团队 SOP 文件化

Coding Agent 经常要遵循一些"行业潜规则"或"团队约定"，比如：

- PR 模板该怎么写
- commit message 的 prefix 规范（`feat:` / `fix:` / `chore:`）
- 测试覆盖率门槛
- 哪些文件不应该改

把这些塞进 system prompt 会让 prompt 膨胀，而且每次推理都重复推理这些规则。更好的做法是用 **skill**：

`workspace/skills/pr-checklist/SKILL.md`：

```markdown
---
name: pr-checklist
description: 当用户要提 PR、要 review PR、要写 commit message 时使用，确保符合项目规范。
---

# PR Checklist

## Commit Message
- 必须以 `feat:` / `fix:` / `chore:` / `docs:` 之一开头
- 中文项目用中文描述，英文项目用英文
- 末尾不要带 emoji

## PR 要求
- 必须 link 对应 Issue
- 必须有测试（除非是纯文档改动）
- 跑 `scripts/run-checks.sh` 通过后再 push
```

每轮推理时 agent 会在 system prompt 里看到一个 `<available_skills>` 块，列出当前可见的所有 skill（只列 name + description，足够轻）。agent 觉得相关才会调 `load_skill_through_path` 拉详情。

技能可以来自四个层（按优先级从低到高）：

1. `projectGlobalSkillsDir(Path)` —— 项目全局，例如 `~/.agentscope/skills/`
2. `skillRepository(...)` —— 市场后端（Git / Nacos / MySQL / classpath）
3. `workspace/skills/` —— 工作区共用
4. `<userId>/skills/` —— 用户隔离

最实用的组合是：**团队通用 SOP 放 Git skill repo，项目特有规范放 workspace/skills/**：

```java
HarnessAgent.builder()
    .skillRepository(new GitSkillRepository("https://github.com/your-org/team-skills.git"))
    .workspace(workspace)   // workspace/skills/ 里放本项目专属
    .build();
```

### 5.7 Plan Mode：大重构前先想清楚

让 Coding Agent 直接动手做"重构整个鉴权模块"是高风险的——它可能边想边改，改坏一片。Harness 的 Plan Mode 把这件事固化成"先想 → 写计划 → 人确认 → 再动手"：

```java
HarnessAgent.builder()
    .enablePlanMode()
    .build();
```

开启后 agent 进入只读阶段：

- 只能调用**只读工具**和 4 个白名单工具：`plan_enter` / `plan_write` / `plan_exit` / `todo_write`；
- 其它工具调用一律被拒绝（agent 看到一条"plan 阶段拒绝"提示）；
- 退出 Plan Mode 走 HITL 确认（复用权限系统的 ASK），避免模型一意孤行直接进入执行。

典型工作流：

```
User: 帮我重构 X 模块
Agent: [plan_enter]
       [read_file / grep_files 调研]
       [plan_write 写到 plans/PLAN.md]
       [plan_exit → 触发 HITL]
Human: 确认 ✓
Agent: [进入执行阶段，工具解禁]
       [todo_write 把 PLAN 拆成 todos]
       [逐条 edit_file + execute]
```

`PLAN.md` 的内容会跟着 `AgentState` 自动持久化——进程重启、节点切换、跨副本恢复后 plan 阶段也会一起恢复。

---

## 六、通道适配器：让 agent 接得住任何入口

Harness 自己不管"消息从哪来"——codingagent 在 Harness 之上又加了一层**通道适配器**，把 GitHub Webhook、CLI、钉钉、飞书等不同入口的事件统一映射到 `(threadId, message)`，再交给 dispatcher 派发到正确的 agent session。

| Channel  | Transport                  | When to use                                  |
|----------|----------------------------|----------------------------------------------|
| CLI      | stdin/stdout (in-process)  | 本地开发、demo、单用户                       |
| GitHub   | HTTP webhook               | Issue/PR 驱动的编码与 review                 |
| DingTalk | Stream WebSocket           | IM 聊天（无需公网 URL）                      |
| Feishu   | HTTP callback              | IM 聊天（需公网 URL）                        |

通道适配器的核心抽象是 `ThreadIdFactory`——把不同入口的 ID 确定性映射成一个 `threadId`：

```
github:issue:owner/repo#42   → SHA-256 → UUID → coding agent thread
github:reviewer:owner/repo#7 → SHA-256 → UUID → reviewer agent thread
dingtalk:<appKey>:<staffId>  → SHA-256 → UUID → coding agent thread
feishu:<tenantKey>:<chatId>  → SHA-256 → UUID → coding agent thread
```

这个 `threadId` 就是传给 `RuntimeContext.sessionId(...)` 的值——同一个 Issue / PR / IM 对话上所有事件都路由到同一个 agent session，对话历史自动恢复。

dispatcher 还在通道和 agent 之间挂了几个保命的中间件：

| Middleware            | 作用                                       |
|-----------------------|--------------------------------------------|
| `MessageQueueHook`    | thread 忙时新事件入队，下一轮推理前注入    |
| `ThreadBudgetHook`    | per-thread 模型调用上限                    |
| `ModelCallLimitHook`  | 全局模型调用上限（跨所有 thread）          |

`FallbackModel` 包裹主模型，对限流 / 过载错误做透明重试。这些都是从生产经验里长出来的小工具——不一定每个项目都需要，但有一个示例可以抄非常方便。

---

## 七、从单机到企业部署：一条演进路线

Harness 让你**从最简的形态开始，按需切换部署形态**——同一份 agent 代码逻辑，配置不同就跑出不同的能力。

### Stage 1：本机 CLI（默认）

```java
HarnessAgent.builder()
    .name("coding")
    .model(model)
    .workspace(workspace)
    // .filesystem(...) 不写 = 本机 + shell
    // 状态存储默认是本地 JsonFileAgentStateStore，写到 ~/.agentscope/state/<id>/
    .build();
```

什么都不配，跑起来就有完整的工作区 + 会话持久化 + 长期记忆。`execute` 工具在宿主 `sh -c` 跑——只在你信任的本机环境用。

### Stage 2：本机 + Docker 沙箱

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")
    .isolationScope(IsolationScope.SESSION))
```

加这一行，所有 `execute` / 文件读写都进 Docker 容器。这是给 GitHub Webhook 模式用的——每个 Issue/PR 一个临时容器，宿主不暴露任何攻击面。

### Stage 3：多副本 + 分布式

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(new OssSnapshotSpec(ossClient, "bucket", "prefix/"))
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
        .leaseTtl(Duration.ofMinutes(30))
        .build()))
.stateStore(RedisAgentStateStore.builder().lettuceClient(redisClient).build())
```

三件事一起做：

1. `stateStore` 换 Redis：任意副本都能恢复任意会话，沙箱元数据也自动存在同一个 Redis 里；
2. 沙箱快照存 OSS：容器漂到其他节点能从快照恢复；
3. 用 `IsolationScope.USER` + `executionGuard`：同一用户的并发请求被锁串行化，避免两个副本同时写同一份状态。

到这一步，codingagent 就能横向扩展——挂在负载均衡器后面跑 N 个副本，任何副本都能接住任何用户的任何 Issue / PR / IM 对话。

### Stage 4：可观测与限流

Spring Boot Actuator 暴露：

- `GET /actuator/health` —— 存活探针
- `GET /actuator/prometheus` —— Prometheus 指标
- `GET /actuator/metrics` —— 指标浏览

codingagent 自己埋了一组关键指标（前缀 `coding_agent.*`）：

| 指标                            | 说明                                   |
|---------------------------------|----------------------------------------|
| `webhook.received`              | 总入站 webhook 数                      |
| `webhook.duplicate`             | 跳过的重复投递                         |
| `dispatch.total`                | 已发起的 agent 派发                    |
| `dispatch.errors`               | 派发失败                               |
| `model.calls`                   | 跨 thread 的 LLM 调用                  |
| `findings.added`                | reviewer 录入的 findings 数            |
| `review.published`              | 已发表的 GitHub PR review 数           |
| `dispatch.duration`             | 端到端派发耗时                         |

设置 `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` 即可打开分布式追踪。`ThreadBudgetHook` 和 `ModelCallLimitHook` 守住模型预算，`FallbackModel` 应对上游限流——这些组合在一起就是一个"上线后能跑住"的 Coding Agent 应该有的样子。

---

## 八、把 Harness 用对的几条建议

把 codingagent 的工程实践抽象出来，给你写自己的 Coding Agent 时的几条参考：

**1. 默认开压缩 + 大工具结果卸载。** 不是可选项。Coding Agent 一定会跑长会话，一定会输出大 diff，不开这两个早晚撞墙。

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(50).keepMessages(20)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000).build())   // write_file 入参也截一截
    .build())
.toolResultEviction(ToolResultEvictionConfig.defaults())
```

**2. 沙箱不是"再说"，是默认。** 哪怕只是给团队内部用，只要 agent 会跑 `execute`，就把它关进容器。codingagent 的设计原则是"不在宿主执行任何模型决定的命令"——这个原则一旦破了就难修复。

**3. 一个 thread 只跑一个 agent run。** codingagent 用 `RunDispatcher` + `MessageQueueHook` 强制保证同一个 thread（Issue/PR/IM 对话）同一时间只有一个推理在跑，新事件入队等下一轮。这能避免一大类并发 bug，强烈建议照搬。

**4. workspace 当 Git 管，agent 当部署单元。** `AGENTS.md` + `skills/` + `subagents/` + `knowledge/` 是 agent 的"配置"，应该有 PR、CR、版本号；agent 的代码（Java 这边）相对稳定，频繁变化的应该是 workspace。

**5. 把 `description` 写好。** skill / subagent 的 description 决定模型用不用它。"代码评审"远不如"当用户要 review PR、找代码风格问题时使用"有效。

**6. Plan Mode 不是必装，但大改之前应该手动 `enterPlanMode()`。** 程序化进出 plan：

```java
agent.enterPlanMode();
agent.exitPlanMode();
agent.isPlanModeActive();
```

**7. 一开始就规划好 `sessionId` 命名。** Coding Agent 的 `sessionId` 通常等于"对话所属的资源"——issue 号、PR 号、IM chat ID。codingagent 用 `ThreadIdFactory` 把这些异构 ID 通过 SHA-256 收敛成 UUID，既稳定又易于排查。这种确定性映射不要等系统跑起来才补。

---

## 九、写在最后

Coding Agent 是个看起来很酷、但工程化深度其实非常大的应用形态。从模型选型到沙箱、从会话恢复到通道适配，每一步都有自己的坑。**Harness 的价值不是"提供一个 SDK"，而是把这些坑变成几行 builder 配置**——你专注于 agent 的业务逻辑（system prompt 怎么写、工具集怎么搭、技能怎么沉淀），基础设施层面的事情都有默认答案。

文中提到的 codingagent 是一个**完整且可读**的示例，强烈建议直接 clone 下来跑一遍，再翻它的源码——它把 Harness 几乎所有能力都用了一遍，是从"概念文档"到"真实代码"之间最好的桥。

继续深入的话，推荐按这个顺序读：

- [Harness 架构](../docs/harness/architecture.md) —— 各能力怎么协作
- [工作区](../docs/harness/workspace.md) —— 目录结构、加载机制、隔离
- [沙箱](../docs/harness/sandbox.md) —— 跨调用恢复、分布式、并发控制
- [上下文与 AgentState](../docs/building-blocks/context.md) —— 跨进程恢复；[上下文压缩](../docs/harness/compaction.md) —— 压缩策略
- [子 Agent](../docs/harness/subagent.md) —— 委派、后台任务、流式转发
- [技能](../docs/harness/skill.md) —— 四层合成、市场后端、自学习闭环
- [Plan Mode](../docs/harness/plan-mode.md) —— 只读阶段 + HITL 退出

希望这篇能帮你少踩一些坑。Coding Agent 的下一个里程碑可能不在模型，而在工程——把"能跑一次"变成"7×24 稳定服务一整个团队"。
