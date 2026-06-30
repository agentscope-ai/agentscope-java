---
hide-toc: true
---

# Coding Agent 的下半场：从个人提效到组织级研发体系

当下还在古法手搓代码的开发者都是在奔着非遗传承人的目标去了，绝大多数都已经用上了 Claude Code、Cursor 这类 Coding Agent。方向对了，但场景不同，解法也不同——开发者自己在本地装个 AI 助手提效，和在组织内部搭起一套 AI 驱动的研发协作体系，是完全两个维度的事情。前者已经有成熟的产品了，后者才刚刚开始。本文聊的就是后者。

---

## 不是你一个人在想这件事

2025 年底到 2026 年初，一件有意思的事情发生了：Stripe、Ramp、Coinbase 这三家公司几乎同时公开了各自的内部 Coding Agent——Stripe 叫 [Minions](https://stripe.com/engineering)，Ramp 叫 Inspect，Coinbase 叫 Cloudbot。三家公司独立开发，没有互相参考，最终却收敛到了几乎相同的架构上。

这不是巧合。当你把 Coding Agent 从"一个人在终端里用"升级成"整个团队通过 Slack / GitHub Issue 随时触发"，你就会被同一组工程问题逼到同一条路上。LangChain 团队看到了这个规律，2026 年 3 月发布了 [Open SWE](https://github.com/langchain-ai/open-swe)——把 Stripe/Ramp/Coinbase 的共同模式提炼成开源框架。Open SWE 的 README 开头写得很直接：

> Elite engineering orgs like Stripe, Ramp, and Coinbase are building their own internal coding agents — Slackbots, CLIs, and web apps that meet engineers where they already work.

"Meet engineers where they already work"——不是让工程师学一个新工具，而是让 agent 钻进工程师已经在用的 Slack 频道、GitHub Issue、IM 对话里，变成团队工作流的一部分。

我们做 AgentScope Harness 的时候，走的也是这条路。本文以官方示例 `agentscope-codingagent` 为线索，讲清楚一个生产级 Coding Agent 在落地过程中会撞到哪些墙，我们是怎么翻过去的。

---

## 两个维度的 Coding Agent

先把定位理清楚。Claude Code 优化的是 **"我一个人写代码更快"**——你打字、它干活、你看着它干活、你随时打断纠正。状态在你本机，触发者就是你自己，信任边界就是你信你自己的机器。

我们要搭的东西解决的是另一个问题：**"团队里某个小任务我都不用自己看，扔给 agent 跑完开 PR 我 review 一下就行"**。触发者可能是任何一个 Issue 评论者，agent 在远端跑十几分钟到一小时，没人盯着。Stripe 的工程师在 Slack 里 @Minions 说句"帮我修这个 bug"，回头收到一个 draft PR——这就是组织级 Coding Agent 该有的样子。

两种形态的功能集有交集——都能写代码、跑命令、改文件——但工程约束完全不同。Claude Code 是你自己的私家车，你信任驾驶员（你自己），所以不需要安全气囊以外的防护。组织级 Coding Agent 是出租车公司的运营车辆——乘客不是车主，驾驶发生在远端，你需要行车记录仪、GPS 追踪、里程限制、紧急制动，还得保证一辆车坏了不影响整个车队。

Open SWE 把这个哲学总结成一句话：**"Isolate first, then give full permissions inside the boundary."** 先隔离，再放权。

事实上厂商也在往这个方向走。GitHub Copilot Coding Agent 已经可以在 Issue 上 assign 触发，在云端跑完自动开 draft PR；Claude Code 也有 headless 模式，能在 CI 里被程序化调用。理念上没有本质区别——沙箱隔离、异步触发、PR 驱动产出——厂商是把头部公司验证过的模式产品化了，做成了开箱即用的 SaaS 服务。而 Stripe、Ramp、Coinbase 选择自建，更多是出于自身工程体系的特殊性：内部系统的深度集成、数据合规的要求、工作流的定制程度。两条路不矛盾，哪条更合适，取决于组织自身的约束和需求。

AgentScope Harness 要做的事情是把这条路上共性的工程问题抽象成可组合的基础能力，让选择自建的团队不用从零开始。

---

## 先跑起来再说

最快的体验路径——一个环境变量、一个 Maven 命令，本地跑起来一个交互式 REPL。无需 Docker、无需 webhook、无需 GitHub App。

```bash
export DASHSCOPE_API_KEY=sk-...
cd agentscope-java
mvn install -pl agentscope-examples/agents/agentscope-codingagent -am -DskipTests -q
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

启动后到 `You>` 提示符，agent 工作在自己的 workspace `~/.agentscope/codingagent/workspace/`。什么都没配，跑起来就有完整的工作区、会话持久化和长期记忆。

```
You> write hello.txt with a haiku about Java
You> clone https://github.com/owner/repo into the workspace and tell me what it does
You> review https://github.com/owner/repo/pull/42
```

到这一步，本地 demo 就跑通了。但 demo 和生产之间的距离，比大多数人想的远得多。

---

## 第一面墙：执行隔离

你给 agent 装了 `execute` 工具，让它能跑 shell 命令。第一天很兴奋——它能 `git clone`、能 `mvn test`、能自己跑通整个构建流程。第二天你意识到一个问题：**触发者不是你自己了。** 任何一个 GitHub Issue 评论者、任何一个 Slack 用户都能让 agent 跑代码，你的宿主机直接暴露在模型决定的命令下面。

这就是所有做组织级 Coding Agent 的团队第一个撞到的墙。Coinbase 用自建的沙箱基础设施解决，Ramp 用 Modal 的云端容器，Open SWE 做了一层抽象支持 Modal、Daytona、Runloop 等多种后端。我们也做了同样的抽象——`FilesystemSpec` 是统一接口，Docker 容器、远端 KV、本机文件系统都是可插拔的实现。以 Docker 为例：

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

加了这一行，`read_file`、`write_file`、`execute` 等所有内置工具自动改走沙箱后端，agent 代码完全不用动。`IsolationScope.SESSION` 保证每个 GitHub Issue / PR / IM 对话各跑各的。

沙箱解决了"不能伤宿主"。但马上下一个问题来了。

---

## 第二面墙：状态续不上

用户在 PR 上又评了一条"再补个测试"。agent 得接着上一轮的环境继续干——重新 `git clone` + `npm install` 等五分钟，谁也受不了。

Open SWE 用"persistent sandbox"解决——同一个 thread 的 follow-up message 复用同一个沙箱。我们的方案更精细，沙箱在每次 `call()` 结束时把工作区状态打包成快照，下次按需恢复：容器还在就直接接着用，容器没了就拿快照重新起一个，没快照就全量初始化。快照后端可选本地文件、OSS、Redis，生产环境加一行配置：

```java
.snapshotSpec(new OssSnapshotSpec(ossClient, "my-bucket", "agentscope/"))
```

不光是沙箱的状态要续上。**对话历史、压缩摘要、Plan 状态、todo 列表、权限规则——整个 AgentState 每次 `call()` 结束自动落盘，下次同 `(userId, sessionId)` 的 `call()` 自动加载。** 默认存本地文件，多副本生产切 Redis 一行搞定。切到 Redis 之后，节点崩了会话漂到另一个节点，滚动发布新 pod 自动恢复，甚至 GitHub Issue 里聊到一半切到钉钉继续——只要 `sessionId` 一致，记忆都在。

---

## 第三面墙：上下文爆了

一个长 Issue 跑几十轮对话，`git diff` 输出上万字符，`mvn test` 日志几十 K——模型上下文窗口很快撑爆。这是所有做长任务 Coding Agent 的团队都会撞到的问题。Open SWE 底层的 Deep Agents 框架用 file-based memory 做卸载，把大结果写到文件而不是留在对话历史里。

我们的解法是四套独立可组合的机制：**对话摘要压缩**在消息条数过多时自动触发，保留尾部原文、前面压缩成摘要；**大工具结果卸载**把超长输出写到工作区文件，上下文里只留首尾各约 2K + 一个 `read_file` 路径提示；**参数截断**把 `write_file` 的大入参也截掉；**溢出兜底**在真撞到 `context_length_exceeded` 时做紧急压缩后重试。

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(50).keepMessages(20)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000).build())
    .build())
.toolResultEviction(ToolResultEvictionConfig.defaults())
```

**这不是可选项。** Coding Agent 一定会跑长会话，一定会输出大 diff，不开这两个早晚撞墙。

同时，`MEMORY.md` 会从每天的对话流水账里周期性合并出长期事实。跑久了，agent 自己学会了团队的规矩——"这个仓库的测试命令是 `mvn -pl module test`，根目录 `mvn test` 太慢不要用"——下次就不用再问了。

---

## 第四面墙：多人同时用

上面三面墙——隔离、续状态、管上下文——解决的是"一个 agent session 能跑住"。但组织级服务从第一天起就是多租户的：几十个 Issue、几十个 PR、几十个 IM 对话同时在跑，每个都有自己的代码仓库、依赖目录、对话历史和长期记忆，**绝不能串台**。

这时候才真正理解为什么 Open SWE 的 README 里把 "Multiple tasks run in parallel — each in its own sandbox, no queuing" 列为核心 feature。不是炫技，是刚需。

我们用 `IsolationScope` 控制隔离粒度。`SESSION` 让每个 sessionId 独立一个沙箱，`USER` 让同一用户的多个对话共享一份仓库克隆。隔离不只是沙箱层面的——会话状态、记忆、子 agent 任务也都按同样的粒度走，不用开发者自己操心。

并发控制也是这一层的事：`RunDispatcher` + `MessageQueueHook` 强制保证同一个 thread 同一时间只跑一个推理。用户在 agent 跑着的时候又评了一条，新消息不会打断当前推理，而是入队等下一轮开始前注入——和 Open SWE 的 `check_message_queue_before_model` middleware 是同一个思路。`ThreadBudgetHook` 管住每个 thread 的模型调用上限，`ModelCallLimitHook` 管住全局——**一个用户的失控循环不能把全公司的额度烧光**。

---

## 第五面墙：agent 要接得住各种入口

Stripe 的 Minions 走 Slack，Coinbase 的 Cloudbot 也走 Slack，Open SWE 同时接 Slack + Linear + GitHub。国内场景还要加钉钉和飞书。组织级 Coding Agent 的一个共识是：**不要让用户换到一个新界面去找 agent，让 agent 出现在用户已经在用的地方。**

我们在 Harness 之上加了一层通道适配器，把不同入口的事件统一映射到 `(threadId, message)`。`github:issue:owner/repo#42` 通过 SHA-256 收敛成 UUID，钉钉和飞书同理。这个确定性映射保证同一个 Issue 的所有评论都路由到同一个 agent session，对话历史自动恢复。

---

## 翻过这些墙之后，有几个认知

### Context Engineering 不是概念，是工程必需品

行业里现在把"怎么给 agent 喂上下文"这件事叫 Context Engineering。有意思的是，几乎所有主流 Coding Agent 都独立走到了同一个模式：Claude Code 有 `CLAUDE.md`，GitHub Copilot 有 `.github/copilot-instructions.md`，Open SWE 有 `AGENTS.md`。**repo 级别的规约不应该硬编码在 system prompt 里，而应该是文件——能版本化、能 CR、能独立更新。**

我们的 workspace 把这个思路推得更远：不只有 `AGENTS.md` 定义人格和行为约定，还有 `skills/` 放团队 SOP（提交规范、测试规范）、`subagents/` 声明子 agent、`knowledge/` 放领域知识、`MEMORY.md` 积累长期事实。workspace 当 Git 管理，CI 验证，部署时 hydrate 进所有副本。**频繁变化的应该是这些文件，而不是 Java 代码。**

### 工具精选比工具数量重要

Stripe 公开分享 Minions 经验时说过：他们的 agent 有约 500 个工具，但强调 "tool curation matters more than tool quantity"。Open SWE 也跟进了这个理念，只暴露约 15 个核心工具。我们的做法类似——内置工具集控制在文件操作 + shell 执行 + 记忆检索这个范围内，业务工具通过 `toolkit.register(...)` 按需注册。

### 关键步骤不能只靠 prompt

**不能只靠 prompt 告诉模型"记得跑测试"，关键步骤要用确定性逻辑保证。** GitHub Copilot Coding Agent 跑完后走 repo 现有的 CI pipeline 做验证；Open SWE 有一个 `open_pr_if_needed` middleware 作为兜底——agent 忘了开 PR，middleware 自动补上。Harness 的 middleware 机制（`MessageQueueHook`、`ThreadBudgetHook` 等）也是同一思路：**哪些事交给模型决定，哪些事用确定性代码保证，这条线要画清楚。**

Open SWE 博客里有一句提炼很准确：agentic（模型驱动）和 deterministic（middleware 驱动）的分离，是让 agent 从"demo 能跑"到"生产可靠"的关键。

还有一点：**Draft PR 作为输出契约。** 无论是 Copilot Coding Agent、Open SWE 还是 Stripe Minions，agent 的产出都是 draft PR，永远需要人类 review 后才能 merge。agent 不直接改生产代码——这是组织级 Coding Agent 的一个基本安全假设。

### 大改之前先想清楚

让 agent 直接上手做"重构整个鉴权模块"是高风险的——它可能边想边改、改坏一片。Harness 的 Plan Mode 把这件事固化成"先想 → 写计划 → 人确认 → 再动手"的流程。开启后 agent 进入只读阶段，退出 plan 需要人类确认。Coinbase Cloudbot 的"Agent Councils"是同一理念——在高风险操作前加入人类审批节点，**用流程约束代替祈祷模型别出错**。

### 子 agent 不是锦上添花

Open SWE 用 Deep Agents 的 `task` tool 做子 agent 派发，Stripe 用 Blueprints 编排，Ramp 用 Sessions + Child Sessions。Harness 的子 agent 用法很轻量——在 workspace 里写一个 markdown 文件声明职责和工具集，主 agent 调 `agent_spawn` 就能委派。后台调用加个 `timeout_seconds=0`，主 agent 不 block，跑完后框架自动把结果注入下一轮推理。

---

## 从单机到企业：一条演进路线

这些墙不需要一次全翻。Harness 的设计让你从最简的形态开始，按需升级：

**Stage 1：本机 CLI。** 什么都不配，`execute` 在宿主 `sh -c` 跑，状态存本地文件。只在你信任的本机环境用。

**Stage 2：加沙箱。** 一行 `.filesystem(new DockerFilesystemSpec()...)`，所有执行进容器。每个 Issue/PR 一个临时容器，宿主不暴露攻击面。

**Stage 3：多副本分布式。** `stateStore` 换 Redis，沙箱快照存 OSS，加 `executionGuard` 做并发控制。到这一步就能横向扩展——挂在负载均衡器后面跑 N 个副本，任何副本都能接住任何用户的任何对话。

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")
    .isolationScope(IsolationScope.USER)
    .snapshotSpec(new OssSnapshotSpec(ossClient, "bucket", "prefix/"))
    .executionGuard(RedisSandboxExecutionGuard.builder(jedis)
        .leaseTtl(Duration.ofMinutes(30)).build()))
.stateStore(RedisAgentStateStore.builder().lettuceClient(redisClient).build())
```

**Stage 4：可观测与限流。** Prometheus 指标、模型预算、上游限流重试——这些组合在一起就是一个"上线后能跑住"的系统该有的样子。

---

## 趋同说明了什么

回顾一下本文提到的这些项目——Stripe Minions、Ramp Inspect、Coinbase Cloudbot、LangChain Open SWE、GitHub Copilot Coding Agent、Claude Code，再加上 AgentScope Harness——它们在语言、生态、部署形态上各不相同，但在核心架构决策上高度一致：per-session 隔离沙箱、确定性的 thread ID 路由、middleware 拦截链、agent 运行时的 message queue 注入、repo 级指令文件、draft PR 作为输出契约。

这种趋同不是互相参考的结果。**它是被同一组问题逼出来的工程必然。**

---

## 写在最后

Coding Agent 的上半场是个人提效——模型更聪明、补全更准、本地工具更顺手。下半场的战场转到了工程：怎么把"能跑一次 demo"变成"7×24 稳定服务一整个团队"。从 Stripe 到 GitHub，从 LangChain 到 AgentScope，大家在不同的起点上走向了相同的架构。这种趋同本身就是最好的路标。

文中提到的 codingagent 是一个完整且可读的示例，建议直接 clone 下来跑一遍再翻源码——它把本文讲的这些工程问题都对应到了真实代码。

继续深入：[Harness 架构](../docs/harness/architecture.md) · [工作区](../docs/harness/workspace.md) · [沙箱](../docs/harness/sandbox.md) · [上下文压缩](../docs/harness/compaction.md) · [子 Agent](../docs/harness/subagent.md) · [技能](../docs/harness/skill.md) · [Plan Mode](../docs/harness/plan-mode.md)
