---
hide-toc: true
---

# 首个Harness Framework发布 -- 把OpenClaw的「持续进化」体验，装进企业级的安全边界。

书接上回，我在之前的一篇文章中深入分析了 OpenClaw 及其背后的 Harness Engineering 实践，同时构想了一套 “Harness Framework” 来讲解如何将这套理念应用到企业级智能体开发中。

好消息是，AgentScope Java 1.1.0 版本正式发布了，在这个里程碑版本中，我们完整的实现了这套 “Harness Framework” 规划。开发者可以基于 1.1 版本快速实践 Harness，开发面向个人提效的 XxxClaw、Coding Agent 等本地应用，也可以开发面向分布式场景的 DataAgent、SRE Agent 等企业级应用。

AgentScope Java 1.1.0 在这个版本中交付了四项核心能力：

+ **工作区驱动的 Agent 运行环境**：Agent 的人格、知识、技能、记忆、子 Agent 规格统一沉淀在一个结构化工作区里，每次运行自动从工作区加载上下文、结束后自动回写记忆，Agent 的能力随时间持续演化。
+ **可插拔的抽象文件系统**：工作区的物理存储可以自由切换——本机磁盘、远端共享存储、隔离沙箱均通过同一套接口操作，同一份 Agent 逻辑无需修改即可适配个人开发环境与企业分布式部署。
+ **开箱即用的上下文管理**：内置对话压缩、双层记忆沉淀与全文检索，解决长对话上下文膨胀和跨会话记忆丢失两个顽固问题，并通过后台维护机制保证记忆库不随时间失控增长。
+ **子 Agent 编排与隔离执行**：支持声明式定义子 Agent、同步或异步委派子任务；工具执行可配置在隔离沙箱内完成，并在多轮对话间保持沙箱状态可恢复，兼顾多租户场景的会话与用户维度隔离。

## OpenClaw/Hermes 很好，但在企业级智能体场景却用不起来？
过去一年，OpenClaw、Hermes、Claude Code 等智能体产品掀起了一波热潮，也带火了这些产品背后的 Harness Engineering 理念——用结构化的工作区、上下文管理与工具约定，替代"每次对话各自为战"的原始使用方式。越来越多的团队开始把这套思路搬进自己的 Agent 开发中。

然而，真正动手落地的人往往会发现，这条路走到"企业级"就开始卡壳。我们梳理了来自一线开发者最常提到的五个障碍：

1. **多用户、多副本，工作区怎么办？**  OpenClaw 用一个本地目录做工作区，单机单用户完全没问题。但服务要对外，多个用户的工作空间要隔离，Agent 水平扩容到多台机器后，同一用户的工作区又要在副本间共享——本地目录这套假设直接崩掉了。
2. **Tool 和 Skill Script 不能在宿主机上跑，怎么隔离执行？**  Agent 调用 Shell 或运行用户提供的代码，放在本地可信开发机上无所谓，一旦上服务，把任意用户输入的命令直接在宿主机上执行就是安全漏洞。沙箱是必须的，但"有沙箱"只是第一步：沙箱里的 Tool 还需要看到完整的上下文，多轮对话中同一个沙箱实例要可恢复，而不是每次都从零开始。
3. **"workspace + 文件系统"的组合如何搬到分布式环境？**  文件系统驱动的工作区是 Harness Engineering 里最直觉、也最有效的模式，但这套模式的前提是"文件系统"。分布式场景下没有统一的本地磁盘，远端存储、KV 服务、对象存储各有各的接口，重写一遍等于把 Agent 逻辑和基础设施耦合死了。
4. **Multi-Agent 怎么做才对？**  子任务分发、上下文隔离、异步执行、结果回收、超时取消——每一项单独做都不难，但要拼成一个可管理的编排层，代码复杂度会快速上升，而且大多数框架只提供原语，工程上的"怎么声明子 Agent、什么时候 spawn、怎么管理状态"全靠自己摸索。
5. **上下文压缩和分层记忆有没有开箱即用的实现？**  Harness Engineering 把这两件事讲得很清楚，但真正做起来要处理的细节非常多：压缩时机、压缩策略、压缩前的事实提取、历史的可检索性、跨进程重启后的恢复……大多数框架只给了 short/long memory 的抽象接口，具体实现还是要自己来。

这五个问题的根源是同一件事：**个人助手型 Agent 和企业级 Agent 是两种不同的工程形态**，用同一套假设去应对两种场景必然碰壁。

从**部署形态**看：个人助手是单用户单进程，所有状态都可以放在一台机器上；企业级 Agent 要水平扩容、要多租户、要服务不中断，状态必须能分布式存储和恢复。从**安全边界**看：本机工具执行没有风险，生产环境上任意 Shell 执行则是一个严重的攻击面，沙箱和权限边界不是"可选的优化"而是"上线的前提"。从**运维可观测性**看：个人工具出了问题自己看日志就行，企业服务要求记忆落盘、会话可审计、状态变更可追踪。从**Token 经济**看：个人用户对延迟和费用不敏感，企业场景每一次无效的上下文重推都是真实的成本开销。

那么，有没有一款框架能让你"写一套逻辑，按需切换形态"？AgentScope Java 1.1.0 的 Harness 模块（入口类 `HarnessAgent`）就是围绕这个目标设计的：它不替换 `ReActAgent` 的推理循环，而是在循环的关键时机插入 Hook，补齐一组工具与工作区约定，把上面五个问题的工程答案打包进来，让你专注于 Agent 的业务逻辑，而不是基础设施。

## AgentScope Harness 设计理念：凭什么它能解决以上问题？
AgentScope Harness 的设计哲学可以用一句话概括：**把"下一轮怎么办、下一天怎么办、上下文爆了怎么办、状态丢了怎么办"的工程答案打包进来，而不是让每个 Agent 项目各自发明一遍。**

具体到实现层面，有两个核心支柱支撑起整个框架。

### 核心支柱一：Workspace 作为唯一事实来源
Harness 为每个 Agent 引入了 **workspace 工作空间**的概念——一个结构化目录，用于承载 Agent 运行所需的一切持久化内容：人格定义（`AGENTS.md`）、长期记忆（`MEMORY.md`）、领域知识（`knowledge/`）、可复用技能（`skills/`）、子 Agent 规格（`subagents/`）以及会话历史（`agents/<agentId>/`）。

这并不是一个新想法——OpenClaw、Hermes 在实践中都发现，让 Agent 有一个稳定的"工作台"比每次重新初始化有效得多。Harness 把这个直觉系统化了：工作区是 Agent 的唯一事实来源（Source of Truth），所有状态的读写都围绕工作区展开，而不是散落在代码、数据库和内存的各个角落。

实际运行中，每次推理开始前，`WorkspaceContextHook` 会把 `AGENTS.md`、`MEMORY.md`、`knowledge/` 等关键文件自动注入到 system prompt 里，确保 Agent 的人格和知识在每一轮都完整呈现。Agent 运行结束后，`MemoryFlushHook` 会提炼本次对话的新事实写入记忆文件，后台的 `MemoryConsolidator` 再周期性地把流水账合并成精炼的长期记忆。工作区在对话中持续演化，每一次运行都比上一次"更了解"用户和任务。

<!-- 这是一张图片，ocr 内容为： -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1777338565508-2d485103-d3b6-4c8f-830b-7ee6e783cda3.png)

### 核心支柱二：AbstractFilesystem 让工作区可以运行在任何环境
工作区的理念很美好，但有一个现实约束：本地磁盘目录在分布式场景下行不通。多个 Pod 各有一块本地磁盘，`MEMORY.md` 写到哪里？哪个副本的版本才是"真"的？

AgentScope Harness 用 **AbstractFilesystem** 抽象层来解决这个问题。对上层而言，Agent 只需要调用统一的 `read/write/ls/grep` 等接口，不关心"文件"实际落在哪；对下层而言，可以适配到本机磁盘、远端对象存储（OSS）、KV 数据库（Redis）、沙箱文件系统等任意介质，甚至通过 `CompositeFilesystem` 把不同路径路由到不同后端。

<!-- 这是一张图片，ocr 内容为：ABSTRACTFILESYTEM 继承 继承 继承 继承 继承 SANDBOXFILESYSTEM REMOTEFILESYSTEM LOCALFILESYSTEM LOCALFILESYSTEMWITHSHELL COMPOSITEFILESYSTEM -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1778218615934-eec5c4c7-4a9c-44c2-84cb-56688f64d7f0.png)

如上图所示，基于 AbstractFilesystem 接口，AgentScope 内置提供了三种拓展实现，对应三种使用模式。

> 待详细展开三种实现与模式。
>

在 AgentScope 1.1 版本中，workspace 是 agent 的核心抽象，我们 AbstractFilesystem 作为 workspace 的物理实现载体，所有文件操作、命令执行、记忆管理工具都以 AbstractFilesystem 为标准操作入口。

<!-- 这是一张图片，ocr 内容为：FILESYSTEMTOOL SHELLEXECUTETOOL MEMORY 命令 记忆 读写 搜索 执行 管理 WORKSPACE BASED ON ABSTRACTFILESYSTEM -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1778218989236-658ff65d-94ae-42e6-a004-4fe7b223a52a.png)



基于这一层文件系统抽象，AgentScope 框架直接为智能体开发带来了三大工程能力：

**安全与隔离**

+ Shell/Code/Skill 的执行通过沙箱后端隔离，用户输入驱动的命令不再直接在宿主机上运行
+ 工作区本身也可以运行在沙箱内，实现文件读写层面的隔离
+ 工具的注册与暴露由框架统一管理，`execute` 工具仅在后端实现了沙箱接口时才出现

**分布式部署**

+ Agent 可以多副本对等部署，`MEMORY.md`、会话日志等关键文件通过 Remote 后端路由到共享存储，天然实现跨节点同步
+ 通过 `IsolationScope`（SESSION / USER / AGENT / GLOBAL）与 `RuntimeContext` 组合，在代码不变的前提下实现 session 级隔离、用户级共享等多种租户策略

**Subagent 与异步任务**

+ 子 Agent 的工作区、文件系统、会话状态都从父 Agent 继承或独立配置，编排策略由规格声明，不需要手工拼装
+ 异步任务的状态机（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED）与结果回收机制开箱即用，支持替换为跨进程实现

## AgentScope Harness 典型使用场景：快速映射到你的应用场景
下面三个场景覆盖了从个人到企业的典型开发形态。它们并不是非此即彼的选项，而是代表了三条不同的复杂度路径——你可以从最简单的一条开始，随着需求演化逐步迁移。

### 个人代理 Agent — 典型如 OpenClaw 类应用
**这类场景的特点**：单用户、本机运行、需要操作本地文件或执行脚本，典型产品是个人助理、笔记机器人、本地 Coding Agent。

这类场景的核心诉求是"让 Agent 真正了解我、记住我"，而不只是一个无状态的问答机器。Harness 在这里的价值是：工作区里的 `AGENTS.md` 定义了 Agent 的人格和行为偏好，对话结束后会自动提炼新的事实写入记忆，下次打开时 Agent 依然认识你、记得上次的进度。技能（skills）和领域知识也都住在工作区里，随时可以编辑调整，不需要动代码。

本机部署下还可以开放 Shell 执行能力，让 Agent 直接运行脚本、操作文件系统，这也是 OpenClaw 类产品最有吸引力的地方。而 Harness 在此之上补上了"持续演化"的那一层：工作区就像 Agent 的大脑，随着每次对话变得更有经验。

**AgentScope Harness 在此场景提供的核心能力：**

+ **持续记忆**：对话结束后自动将新事实提炼写入工作区，下次启动无需重新"告知"Agent 背景，长期记忆随使用积累。
+ **本地 Shell 执行**：在本机可信环境下，Agent 可直接运行脚本、操作文件，复现 OpenClaw 类产品的核心体验。
+ **工作区即配置**：修改 `AGENTS.md` 调整人格，在 `skills/` 目录里新增技能，改一个文件等于升级一次 Agent，不需要重新编译部署。
+ **会话跨进程恢复**：关闭再打开，只要 sessionId 不变，上次对话的状态全部还原，不是从零开始。

### 企业级数据服务 — 典型如 DataAgent
**这类场景的特点**：服务多个用户、需要执行 SQL / Python / Shell、任务耗时较长、输入来自不可信的外部用户，同时要求多轮对话状态可恢复、多副本部署时用户体验一致。

这类场景最大的风险是**执行安全**——用户驱动的代码不能在服务器上无限制地跑。Harness 的沙箱机制把 Agent 的文件操作和命令执行都限定在隔离环境里，服务器进程本身不受影响。更关键的是，沙箱不是"用完即毁"的，每轮对话结束后沙箱的状态会被持久化，下一轮拿回来继续，用户不会因为服务重启或切换节点就丢失工作进度。

多副本部署时，用户的长期记忆（Agent 对这个用户积累的了解）可以存放在共享存储里，无论请求落到哪个节点，Agent 看到的都是同一份记忆。长分析任务可以拆成多个子 Agent 并行执行，主 Agent 只负责协调和汇总，不必一直阻塞等待。

**AgentScope Harness 在此场景提供的核心能力：**

+ **隔离沙箱执行**：所有代码与命令在隔离环境内运行，宿主服务进程不受用户输入影响，安全边界清晰。
+ **多轮沙箱状态恢复**：每轮对话结束后自动保存沙箱状态，下轮或下次服务启动时原位恢复，用户的工作现场不丢失。
+ **分布式记忆共享**：用户的长期记忆存放在共享存储，多节点部署下所有副本读到同一份"对这个用户的了解"，体验一致。
+ **子 Agent 并行编排**：长任务可拆解为多个子 Agent 并发执行，主 Agent 只做协调，整体效率更高，也更易管理超时与失败。
+ **多租户隔离**：按会话或用户维度隔离工作区与执行环境，多用户同时在线互不干扰。

### 企业在线服务 — 典型如淘天交易 Agent
**这类场景的特点**：主要通过调用业务 API 完成任务（下单、查询、审批等），不需要在服务器上执行 Shell，但需要多实例运行、会话状态可持久、跨用户的知识共享。

这类场景的核心诉求是**稳定与安全**——在线服务不能因为 Agent 调用了一个不该调用的 Shell 命令而出事。Harness 在这里的价值是：不配置沙箱执行能力时，框架默认就不会暴露 Shell 工具，Agent 只能通过明确定义的业务工具与外部交互，安全边界由配置决定，而不是靠开发者自律。

会话状态和记忆可以落到远端存储，多个服务实例共享同一套用户记忆，用户换一个入口重新对话，Agent 仍然能接续上次的上下文。需要并行处理多个子任务时（比如同时查库存、计算优惠、生成摘要），子 Agent 机制同样适用，可以对接外部任务队列实现跨进程的任务管理。

**AgentScope Harness 在此场景提供的核心能力：**

+ **默认安全边界**：不开启沙箱执行时框架不暴露 Shell 工具，Agent 只能通过你明确注册的业务工具与外部交互，安全策略由配置决定。
+ **多实例共享记忆**：会话状态与用户记忆落到远端存储，任意服务实例都能读到同一份上下文，用户无感知地在多实例间切换。
+ **会话跨请求连续**：每次请求携带相同的用户标识，Agent 自动恢复上次的对话状态，实现真正的多轮连续对话体验。
+ **并行子任务支持**：需要同时处理多个业务步骤时，可将子任务委派给子 Agent 并行执行，结果汇总后统一回复，不影响主流程响应速度。

## AgentScope Harness 详解，花点时间了解更多框架详情吧
本节将从**使用者视角**讲清楚 AgentScope Harness 的核心能力：它是什么、怎么工作、配置时应该怎么想。

### 快速开始 - Quick Start
上手 Harness 只需三步：引入依赖、准备工作区、构建并调用 Agent。

**1. 引入依赖**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>

```

**2. 准备工作区**

在磁盘上选一个目录作为 `workspace`，并在其中创建 `AGENTS.md`。这不是"可选的初始化步骤"，而是 Harness 的核心入口——Agent 的人格、记忆、技能、子 Agent 规格全部围绕这个目录展开。`AGENTS.md` 内容简单写几行约定就够，后续随使用不断演化。

**3. 构建 **`HarnessAgent`** 并调用**

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("my-agent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .compaction(CompactionConfig.builder()     // 建议一开始就配，避免线上 context overflow
        .triggerMessages(50)
        .keepMessages(20)
        .build())
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("user-session-001")   // 相同 sessionId 的多次 call 自动续接上下文
    .userId("alice")                 // 多用户场景必传，用于命名空间隔离
    .build();

Msg reply = agent.call(userMessage, ctx).block();
```

运行后检查工作区目录：`AGENTS.md`、`memory/`、`agents/<agentId>/` 三个路径都应该存在，这说明 Agent 已经在正常写入记忆和持久化会话状态了。

完整可运行示例见 `agentscope-examples/harness-example` 中的 `QuickstartExample`。

---

### 核心概念 - Concepts
理解下面六个概念，基本就掌握了 Harness 的运行逻辑。

| 概念 | 定义 | 解决的问题 | 使用建议 |
| --- | --- | --- | --- |
| `HarnessAgent` | 基于 `ReActAgent` 的工程化封装入口，`build()` 时装配 Hook、内置工具、技能与会话持久化 | "不想从零拼装压缩、记忆、会话、子任务、文件系统" | 业务代码只与 `HarnessAgent.builder()` 和 `agent.call(msg, ctx)` 打交道 |
| `workspace` | Agent 的工作目录，承载 `AGENTS.md`、`MEMORY.md`、`skills/`、`subagents/`、会话历史等全部持久化内容 | "人格、知识、记忆、状态放哪、如何持续演化" | 先规划工作区结构再写 prompt；把工作区当作可版本化的资产 |
| `filesystem` | 文件读写的统一接口，是 Agent 工具层与物理存储之间的抽象层，支持本地磁盘、远端存储、沙箱等多种后端 | "同一套 Agent 逻辑如何在本地、共享存储、沙箱间切换" | 优先从三种声明式模式选型（Local / Remote / Sandbox） |
| `RuntimeContext` | 单次 `call()` 的身份上下文，包含 `sessionId`、`userId` 等，每次调用重新传入，不持久化 | "这一轮是谁、状态读写到哪、多租户如何隔离" | 必须稳定传 `sessionId`；多租户场景必须传 `userId` |
| `sandbox` | 隔离执行环境，文件操作与命令在沙箱侧运行，每轮对话结束后持久化状态、下轮恢复 | "如何在不信任输入下安全执行工具与脚本，并保持多轮状态连续" | 有代码执行需求时优先启用；根据业务选择隔离粒度 |
| `memory` | 双层记忆系统：每轮对话后自动提炼写入流水账，后台周期性合并成可注入的长期记忆，配合全文检索 | "长对话不丢事实、上下文不爆、历史可检索" | 开启对话压缩并观察记忆文件变化；旧事实用搜索工具回捞 |


**总纲**：`HarnessAgent` 负责编排，`workspace` 负责沉淀，`filesystem` 负责落点，`RuntimeContext` 负责身份，`sandbox` 负责边界，`memory` 负责长期演化。

---

### 功能详情 - Features
#### 工作区（Workspace）：Agent 的唯一事实来源
工作区是 Harness 区别于普通 Agent 框架最重要的设计。它不是一个临时存储目录，而是 Agent 的"大脑外化"——所有需要跨会话保留的内容都住在这里。

工作区的标准目录结构如下：

```plain
workspace/
├── AGENTS.md              ← Agent 人格与行为约定，每次推理前自动注入 system prompt
├── MEMORY.md              ← 精炼的长期记忆，由后台自动维护，随使用积累
├── knowledge/             ← 领域知识，随 AGENTS.md 一起注入
├── skills/                ← 可复用技能，自动装配到 Agent 的工具集
├── subagents/             ← 子 Agent 规格声明，自动被发现和加载
└── agents/<agentId>/
    ├── context/           ← 会话状态快照（进程重启后恢复用）
    ├── sessions/          ← 对话 JSONL 与压缩上下文，供审计与检索
    └── memory/            ← 每日记忆流水账
```

**工作区在每次推理中如何工作**：推理开始前，Harness 把 `AGENTS.md`、`MEMORY.md`、`knowledge/` 等关键文件拼入 system prompt；推理结束后，把本次对话中出现的新事实提炼出来追加到当日的记忆流水账。工作区随每次对话持续演化，Agent 随时间变得"更了解"它面对的业务和用户。

**为什么工作区优于把 prompt 写死在代码里**：人格、知识、技能和子 Agent 规格都在工作区的文件里，调整行为只需要改文件，不需要重新编译和部署。对于有复杂业务知识的 Agent，这一点尤其关键——业务规则随时在变，更新应该轻量。

---

#### 会话持久化（Session）：跨请求、跨进程的状态连续
Harness 把会话状态落盘分成**两条并行的路径**，它们各自解决不同的问题：

+ **状态快照（**`context/`**）**：每次 `call()` 结束后，Agent 的运行状态（当前的对话记忆、工具执行上下文等）序列化为 JSON 文件，存到工作区的 `agents/<agentId>/context/<sessionId>/` 下。下次用相同 `sessionId` 发起调用时，框架在推理开始前自动加载这份快照，恢复到上次结束的位置。这是"关掉再打开仍然记得上次"的技术保障。
+ **对话日志（**`sessions/`**）**：完整的对话历史以 JSONL 格式追加写入 `<sessionId>.log.jsonl`，这个文件永远不会被压缩，供审计和 `session_search` 工具使用。另有一份 `<sessionId>.jsonl` 存放压缩后的 LLM 上下文，是模型实际"看到"的版本。

两条路径都由框架自动维护，开发者唯一需要做的是**每次调用时稳定传入相同的 **`sessionId`。

---

#### 记忆管理（Memory）：从对话到长期知识的自动沉淀
这是 Harness 最有工程价值的能力之一。很多 Agent 框架的"记忆"本质是把历史消息堆进上下文，迟早会撑爆；AgentScope 当前版本的做法是**双层分离**：

**第一层——每日流水账**：每次对话结束后，框架用 LLM 从当次对话中提炼"新增事实"，以 bullet point 形式追加到当日的记忆文件（`memory/YYYY-MM-DD.md`）。这一层只追加、不修改，保证任何新事实都不会丢失。

**第二层——长期记忆**：后台有一个调度器，会周期性地读取近期的日流水账文件，用 LLM 把它们与现有的 `MEMORY.md` 合并、去重、精炼，输出一份在 Token 预算内的"可注入版"写回 `MEMORY.md`。这一层是被每轮推理注入到 system prompt 的"事实摘要"，质量高、体积受控。

两层之间的关系：第一层保证**不丢**，第二层保证**可用**。新事实先落在流水账，等积累够了由后台搬进长期记忆，推理时模型优先看长期记忆，找不到时用 `memory_search` 工具做全文检索（基于 SQLite FTS5）。

**对话压缩**是记忆管理的另一面：当对话消息数或 Token 数超过阈值，Harness 用 LLM 把之前的对话压缩成一段摘要，保留最近的若干条消息，其余的卸载到 JSONL 文件。压缩会在提炼长期记忆之后进行，确保有价值的信息先沉淀再压缩。如果模型返回了 context overflow 错误，框架还会捕获异常、强制压缩、自动重试，整个过程对调用方透明。

配置建议：

```java
.compaction(CompactionConfig.builder()
    .triggerMessages(50)    // 消息数超过 50 触发压缩
    .keepMessages(20)       // 保留最近 20 条
    .flushBeforeCompact(true) // 压缩前先提炼记忆（默认已开启）
    .build())
```

---

#### 子 Agent 编排（Subagent）：复杂任务的分解与委派
当主 Agent 遇到耗时长、上下文重或可并行的子任务时，可以把它委派给子 Agent 执行。子 Agent 是独立的 Agent 实例，有自己的 system prompt 和 Memory，不共享主 Agent 的对话历史，执行结果作为一条工具结果返回给主 Agent。

**子 Agent 的声明方式**有四种，灵活度从低到高：

1. **内置的 **`general-purpose`** Agent**：镜像主 Agent 的配置，适合临时委派任意子任务；
2. **工作区文件驱动**：在 `workspace/subagents/` 下放 Markdown 文件（YAML front matter 定义名称、描述、工具；body 是 system prompt），框架自动发现并加载；
3. **代码声明**：用 `builder.subagent(spec)` 编程式指定；
4. **自定义工厂**：完全控制子 Agent 的构建逻辑。

工作区驱动的方式是最推荐的——子 Agent 的定义随工作区版本化，不需要动代码就能调整委派策略。

**调用方式**分同步和异步两种：

+ **同步调用**：主 Agent 阻塞等待子 Agent 完成再继续，适合必须拿到结果才能下一步的场景；
+ **异步调用**：主 Agent 提交任务后立即拿到一个任务 ID，可以继续做其他事，后续用 `task_output` 工具轮询结果。对于耗时超过几秒的任务，强烈建议用异步，避免主 Agent 白白阻塞消耗时间与 Token。

**防无限递归**：子 Agent 默认是"叶子"形态，本身不能再 spawn 子 Agent，框架也有最大深度限制作为兜底。

---

#### 内置工具（Builtin Tools）
`HarnessAgent` 构建时会自动注册一套覆盖"闭环所需"的工具，无需手动配置：

| 工具类别 | 工具列表 | 说明 |
| --- | --- | --- |
| 文件操作 | `read_file`、`write_file`、`edit_file`、`grep_files`、`glob_files`、`list_files` | 操作工作区文件，路径在文件系统后端范围内 |
| 记忆检索 | `memory_search`、`memory_get` | `memory_search` 走 SQLite 全文检索；`memory_get` 按行号读取记忆文件 |
| 会话查询 | `session_search`、`session_list`、`session_history` | 检索历史对话内容，供 Agent 主动回顾 |
| 子任务管理 | `agent_spawn`、`agent_send`、`agent_list`、`task_output`、`task_list`、`task_cancel` | 委派、查询、管理子 Agent 任务 |
| Shell 执行 | `execute` | **条件性注册**：仅在文件系统后端支持隔离执行时才出现（本机 Shell 模式或沙箱模式） |


值得注意的是：在"远端共享存储"模式下，框架**默认不注册** Shell 工具——这是一个有意的安全设计，不是遗漏。如果你的业务 Agent 不需要执行命令，用这个模式可以消除一整类执行安全风险。

---

#### 文件系统（Filesystem）：三种模式，按需选型
文件系统是 Harness 连通"Agent 逻辑"与"基础设施"的关键一层。框架提供三种声明式模式，选型时从业务约束出发：

**模式一：本机 + Shell（默认）**

不配置 `filesystem` 或显式写 `filesystem(new LocalFilesystemSpec())`，工作区就是本机上的一个目录，可以执行 Shell 命令。适合个人本机应用和开发测试环境，最简单，没有任何额外依赖。

**模式二：远端共享存储**

配置 `filesystem(new RemoteFilesystemSpec(store))`，记忆、会话日志等关键数据路由到远端 KV（如 Redis），本地文件系统只存放不需要共享的内容。**默认不注册 Shell 工具**，适合多副本在线服务、需要跨节点共享用户记忆但不需要代码执行的场景。

**模式三：沙箱执行**

配置 `filesystem(sandboxSpec)`，文件读写和命令执行全部在隔离的沙箱环境里完成，宿主进程不受影响。适合需要执行不可信代码的场景，如 DataAgent、Coding Agent。

三种模式的核心区别在于：**谁来执行命令、数据落在哪、隔离粒度是多少**。同一套 Agent 代码逻辑，切换 `filesystem` 配置就能在三种模式间迁移。

---

#### 沙箱（Sandbox）：隔离执行 + 状态可恢复
沙箱模式解决的不只是"隔离执行"，更是"多轮对话中隔离环境的连续性"——这两点合在一起才真正有价值。

**执行边界**：在沙箱模式下，Agent 调用的 Shell 命令、文件读写都发生在沙箱侧，宿主进程只起协调作用。用户输入的任意命令不会直接影响服务器。

**状态可恢复**：每次 `call()` 结束，沙箱当前的文件系统状态会被持久化（快照机制）。下次调用开始时，框架按`sessionId` 或 `userId` 找到对应的快照，把沙箱恢复到上次结束的位置。用户不会因为服务重启或请求漂移到其他节点而丢失工作进度。

**工作区投影**：`AGENTS.md`、`skills/`、`subagents/`、`knowledge/` 等宿主工作区内容，在每次 `call()` 开始时会被同步到沙箱内，保证沙箱里的 Agent 能看到完整的配置和技能定义。

**隔离粒度**（按需选择）：

+ **会话级**：每个会话有独立的沙箱状态，互不干扰，适合多用户 SaaS；
+ **用户级**：同一用户的多个会话共享同一沙箱状态，适合"用户长期工作台"类场景；
+ **全局共享**：整个 Agent 共用一个沙箱，适合工具型、只读型 Agent。



真正应用于生产环境中的 Sandbox 沙箱，还有更多要考虑的因素，可以参考官网文档了解更多：

+ 沙箱生命周期如何管理：agent 内置管理、用户自行管理
+ 哪些流程需要运行在沙箱中：Tool In Sandbox、Subagent in Sandbox
+ 沙箱内部状态如何管理：state、snapshot 恢复

---

#### Skills：工作区驱动的可复用技能
Skills 是把"可复用的操作流程"结构化的方式。在工作区的 `skills/<skill-name>/` 目录下放一个 `SKILL.md`，框架启动时自动发现并装配进 Agent 的能力库。Agent 在推理时可以调用这些技能，技能本身描述了"做这件事的步骤和规范"。

这种设计的工程价值在于：技能是文件，可以和代码一起进 Git 版本控制、可以 Code Review、可以在不重新部署的情况下更新。当团队有大量 SOP 和操作规范需要注入 Agent 时，这比把所有内容堆进 system prompt 要清晰得多。

在沙箱模式下，技能文件会随工作区投影同步到沙箱内，技能中涉及的命令在隔离环境执行，不会影响宿主。

## 总结
AgentScope Java 1.1 把 Harness Engineering 里大家最想要、却最难自己拼装的一组能力，收敛成了 `HarnessAgent`** + 工作区约定 + 可插拔文件系统 + Hook 管线`**：个人场景下，它是“带记忆、带压缩、带子任务”的加强版 ReAct Agent；企业场景下，它是能把**隔离、多租户、分布式记忆与子 Agent 编排**变成配置项的基础设施。

若你正在评估从个人助手原型演进到可上线的企业智能体，建议从 [Harness 概览](../overview.md) 的快速开始跑通，再按 [Filesystem](../filesystem.md) 选择一种声明式模式，然后按需打开压缩、沙箱与子 Agent——每一步都有对应文档与示例模块可对照，而不必从零发明一套“工作区即真理”的运行时。



![画板](https://intranetproxy.alipay.com/skylark/lark/0/2026/jpeg/54037/1778221664765-d534ffa1-1649-4444-ad8c-046c936e40e7.jpeg)

