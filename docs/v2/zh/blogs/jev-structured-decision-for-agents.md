---
title: 让 Agent 学会判断：Jev 与结构化决策
---

一个 Agent 从收到请求到完成任务，看起来是在"推理"和"执行"之间循环。但仔细拆开每一步，你会发现真正密集发生的是另一件事：判断。

这个请求该用哪个模型处理？当前上下文里哪些工具应该暴露给模型？这个 bash 命令能不能自动放行？这个回答的置信度够不够直接返回？这些判断每一个都不复杂，但它们出现在 Agent 运行时的每一个角落，加起来就变成了一个被长期低估的系统性开销。

AgentScope Java 最近集成了 [TypeSafe AI 的 Jev](https://docs.typesafe.ai/)。这篇文章不只介绍集成本身——更重要的是借这个机会聊清楚：为什么 Agent 系统需要一种独立于"推理"的"判断"能力，以及这种能力应该怎么用。

---

## Agent 运行时里的高频小决策

先看一个典型场景。

一个 ReAct Agent 正在处理用户请求。模型推理完成，产生了三个工具调用：一个文件读取、一个命令执行、一个代码搜索。在工具真正执行之前，系统需要回答：

| 判断点 | 需要什么 |
| --- | --- |
| 该用哪个模型处理下一轮推理？ | 请求复杂度评估 |
| 三个工具调用中哪个有风险？ | 命令内容 + 上下文分析 |
| 文件读取的结果是否需要截断？ | 输出大小预估 |
| 这个回答可以直接返回吗？ | 置信度评估 |

这些判断有三个共同特点：**高频、低复杂度、需要确定性输出**。它们不需要模型展开长链路推理，但需要系统快速得到一个可以做分支的结论。

目前的做法通常有两种，各有问题。

**第一种：让主模型顺便做判断。** 在 prompt 里加一句"先判断该用哪个模型，再回答"。这确实能工作，但每次判断都要走完整的推理路径，返回的是一段自由文本，系统还要再做一次解析。更麻烦的是，主模型的输出格式不受控——你让它返回 JSON，它可能在 JSON 前面加一段"好的，我来分析一下"。

**第二种：写硬编码规则。** "包含 `rm -rf` 的命令一律拒绝"、"超过 100 个字符的请求用大模型"。这解决了确定性问题，但覆盖不了长尾。"列出这个目录下的所有文件"和"删掉这个目录"在规则层面很难区分，在语义层面一目了然。

问题的本质是：Agent 系统里大量需要的不是"更聪明的推理"，而是"更便宜、更可靠的判断"。

---

## Jev 是什么：一种新的模型形态

[Jev](https://docs.typesafe.ai/) 是 TypeSafe AI 推出的结构化决策模型。它不是聊天模型，不生成文本，也不做工具调用。它做的事情只有一件：接收上下文和一组结构化问题，返回类型化的答案和校准概率。

具体来说，它支持三种问题类型，恰好对应 Agent 系统里三类判断需求：

| 问题类型 | 判断模式 | Agent 场景 |
| --- | --- | --- |
| `NoulQuestion` | 是/否的概率 | "这个操作安全吗？" |
| `ChoiceQuestion` | 选项集合的概率分布 | "该用哪个模型？哪个工具？" |
| `ScoreQuestion` | 等级评分 | "这段回答质量如何？" |

与传统 LLM 的关键差异在于返回形式。你问"这个 bash 命令安全吗"，Jev 不会返回一段分析文本，而是直接返回 `noul: 0.92`——P(安全) = 92%。你问"该用哪个模型"，Jev 返回每个模型的概率分布和整体置信度，而不是一段建议文字。

| 维度 | 聊天模型 | Jev |
| --- | --- | --- |
| 输出 | 自由文本 / 工具调用 | 类型化答案 + 概率 |
| 延迟 | 秒级到十秒级 | 亚秒级 |
| 格式可靠性 | 依赖 prompt engineering 和后解析 | 结构化协议保证 |
| 成本 | 按推理路径计费 | 按决策请求计费 |

就像你不需要为“今天星期几”这个问题启动一个深度研究流程——有些问题只需要一个快速的、确定性的回答。Jev 做的事情类似：为 Agent 里高频、结构化的判断提供一种专门优化过的计算路径。

---

## 在 AgentScope Java 里怎么接入

AgentScope 没有把 Jev 注册成 `Model` provider。原因很简单：`Model` 接口面向的是聊天推理，返回 `ChatResponse`；Jev 返回的是结构化答案，两者在调用频率、超时预期和类型系统上都不匹配。硬塞进同一个接口只会让配置语义变得混乱。所以 Jev 以独立扩展模块的方式存在，应用代码直接注入 `JevClient` 做判断，或者通过 middleware 挂到 Agent 循环里。

依赖引入：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jev</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Spring Boot 项目可以直接用 starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-jev-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  jev:
    api-key: ${TYPESAFE_API_KEY:}
    base-url: https://api.typesafe.ai
    model: jev-latest
    timeout: 5s
```

如果想在业务代码里直接调用 `JevClient` 做 `Noul`、`Choice` 或 `Score` 判断（不经过 middleware），完整的快速上手示例见 [Jev 集成文档](/v2/zh/jev/index)。

---

## 三个中间件：覆盖 Agent 循环的关键判断点

除了 `JevClient`，扩展模块还提供了三个参考 middleware，分别覆盖 Agent 循环的三个阶段。它们不只是简单的示例——每一个都对应一个真实的工程问题。

| 中间件 | 拦截点 | 判断类型 |
| --- | --- | --- |
| `JevModelRouterMiddleware` | `onAgent` / `onModelCall` | Choice：选模型 |
| `JevToolSelectionMiddleware` | `onReasoning` | Choice：选工具 |
| `JevAutoModeMiddleware` | `onActing` | Noul：判断安全性 |

三个拦截点——Agent 入口、推理前、行动前——恰好覆盖了 Agent 生命周期中最需要做判断的三个位置。这不是巧合，而是"判断"作为一类系统关注点的自然分布。

### 模型路由：规划资源分配

`JevModelRouterMiddleware` 在 Agent 调用入口做一次模型选择，整轮调用复用这个决策。

```java
JevModelRouterMiddleware modelRouter =
        JevModelRouterMiddleware.builder(client)
                .choice("fast", fastModel, "Direct lookups, extraction, and localized changes.")
                .choice("powerful", powerfulModel, "Architecture and high-stakes decisions.")
                .instructions("Choose the least costly model that can complete the task.")
                .confidenceThreshold(0.75)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(fallbackModel)
                .middleware(modelRouter)
                .build();
```

每个候选模型有自己的路由标准。Jev 根据用户请求在候选中做 Choice 判断，返回概率和置信度。置信度不够就退回默认模型——**判断失败不是让 Agent 停摆，而是降级到安全路径**。

### 工具选择：规划上下文可见性

`JevToolSelectionMiddleware` 在每轮推理前对工具列表做过滤和排序，只把相关的工具 schema 发给主模型。

```java
JevToolSelectionMiddleware toolSelection =
        JevToolSelectionMiddleware.builder(client)
                .alwaysIncludeTools(Set.of("load_skill_through_path", "reset_tools"))
                .maxTools(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();
```

当一个 Agent 注册了 50 个工具，每轮推理的 tool schema 就是一笔不小的 token 开销。更重要的是，工具太多会稀释模型的注意力。让 Jev 先做一轮筛选，只把当前任务相关的工具暴露给模型，既省 token 又提升准确率。

### 执行守卫：判断行为风险

`JevAutoModeMiddleware` 在工具执行前做风险判断。这是三个中间件里最能体现"判断"价值的一个。

```java
JevAutoModeMiddleware autoMode =
        JevAutoModeMiddleware.builder(client)
                .guardedTool("bash")
                .safetyThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(autoMode)
                .build();
```

当一个 `bash` 工具调用产生时，middleware 会把完整对话历史和命令内容发给 Jev，问一个 Noul 问题："这个调用安全吗？" `noul: 0.92` 就放行，`noul: 0.3` 就拒绝——拒绝的调用不会执行，而是写入一个 `DENIED` 的工具结果，让模型知道这个操作被拦截了。

`failOpen(true)` 意味着 Jev 不可用时放行，`failOpen(false)` 则会拒绝。这是生产系统里必须考虑的降级策略——判断服务的可用性不应该成为 Agent 的单点故障。

---

## 什么时候不该用

Jev 不是万能的，也不是"更便宜的大模型"。以下场景仍然需要主模型的深度推理：

- **需要多步逻辑推导的判断**：比如"这段代码为什么会并发死锁"，需要理解上下文、追踪调用链、做因果分析。这类问题的答案不是概率，是推理过程。
- **需要理解复杂代码或长文本的判断**：比如"这个 PR 是否可以合并"，需要阅读完整 diff、理解架构约束、评估测试覆盖。Jev 的 state 可以携带上下文，但它做不了深度推理。
- **输出本身是文本的场景**：Jev 返回的是概率和选项，不是分析文本。如果你需要一段解释、一个代码补丁、或一份报告，那是主模型的工作。

一个简单的判断标准：如果一个决策可以表达成"是/否"、"从 N 个选项里选一个"、或"打一个分"，且系统会根据这个结果做分支（if/else、路由、阈值判断），那它适合 Jev。如果这个决策需要先理解、再分析、再推导，那它属于推理模型的领地。

换句话说：Jev 回答的是"可以吗"和"选哪个"，不是"为什么"。

---

## 判断与规划：比集成本身更重要的东西

回过头看上面三个中间件，它们解决的问题可以归纳为两个词。

**规划**：在行动之前决定"做什么、用什么做"。选模型是规划资源分配，选工具是规划上下文可见性。这类决策决定了 Agent 的效率和成本。

**判断**：在执行之前决定"能不能做、值不值得做"。风险守卫是判断行为边界，置信度评估是判断输出质量。这类决策决定了 Agent 的安全性和可靠性。

这两个词覆盖了 Agent 系统里大量非推理类的计算。目前这些计算大多被塞进了主模型的推理路径，或者被硬编码进了业务逻辑。Jev 提供了第三条路：把这些判断抽出来，交给一个专门优化的决策模型，用类型化接口和校准概率返回结果。

顺着这个思路，还有很多地方可以用同样的模式优化：

- **RAG rerank**：检索结果先做一轮相关度评分，只把高相关的片段送进主模型。
- **意图分类**：请求入口先做一次路由分类，不同意图走不同的处理链路。
- **质量守卫**：模型输出后做一次评分，低质量结果触发重试或升级。
- **任务分解**：复杂任务先做优先级排序，再分发给 subagent。

这些场景的共同特征是：判断本身不复杂，但频率高、需要确定性、失败时有明确的降级路径。这恰好是 Jev 这类结构化决策模型的适用范围。

Agent 系统的下一步不只是"更强的推理"，还需要"更高效的判断"。推理决定 Agent 能走多远，判断决定它能不能走得又快又稳。
