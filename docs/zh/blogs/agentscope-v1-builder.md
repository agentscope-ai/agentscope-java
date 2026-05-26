---
hide-toc: true
---

# AgentScope Builder 正式发布 —— 把 OpenClaw 的「自我进化」，做成可被整个团队共用的平台

书接 [《首个 Harness Framework 发布》](agentscope-v1-harness.md) 那一篇 —— AgentScope Java 1.1 把 OpenClaw 那套「工作区即真理 + 自我进化」的体验，沉淀成了 `HarnessAgent` + `AbstractFilesystem` + 内置压缩与双层记忆的工程基础设施。当时我们留下了一个承诺：**写一套 Agent 逻辑，按需切换形态，从个人本机一路扩到企业分布式部署**。

承诺听起来好讲，但读者不傻 —— 看一遍设计文档，下一句一定是："示例呢？真能跑吗？"。

这一篇我们交出两个示例项目，把这个承诺的两端都做成可以 `mvn package` 直接运行的代码：

- **agentscope-claw** —— Harness 在「单人本机」一端的完整落地。**用 AgentScope Harness，我们已经做出了一个 Java 版本的 OpenClaw**。
- **agentscope-builder** —— Harness 在「多人企业」一端的完整落地，今天正式发布。**Builder 是 OpenClaw 的分布式版本**：在一个网页上，整个团队都能开发、运营、共享自己的自进化 Agent。

下面我们先把 claw 这个示例讲透 —— 因为 Builder 不是凭空出现的，它是被 claw 没能解决的那一组问题逼出来的。

---

## Claw —— 用 Harness 已经能做出一个 OpenClaw

### 它是什么

claw 是 [OpenClaw] 的 Java 版本：**一款装在你自己电脑上的个人助手**。它以你的身份、在你的文件系统和 shell 里干活，并且会随着使用慢慢"长大" —— 它学到的技能、孵化的子智能体、攒下的记忆，都是它自己在工作区里写的一堆文件。

claw 在仓库里的位置：

```
agentscope-examples/agents/agentscope-claw/
```

它不是一段示意代码，而是一个**完整的 Spring Boot 应用**：JDK 17、一条 `mvn package`、一条 `java -jar`，浏览器打开 <http://localhost:8080> 就能用。所有的状态都落到 `~/.agentscope/` 下，可以用 `CLAW_HOME` 环境变量改写；首次启动会自动生成一个内置的 `default` agent，让你不写一行代码就能开始对话。

### 三个核心能力

claw 真正有意思的不是"能聊天"，而是下面三件事 —— 它们也正是 Harness 的设计承诺第一次完整地体现在一个真实产品里：

**1. 工作区驱动的自我进化**

claw 的所有状态都不在数据库里，而是在 `~/.agentscope/agents/<agentId>/workspace/` 这个目录下：

- `AGENTS.md` —— Agent 的人格与行为约定，每次推理前自动注入 system prompt
- `skills/` —— 可复用技能，agent 自己写、自己用
- `subagents/` —— 子 Agent 规格声明，自动被发现和加载
- `MEMORY.md` + `memory/YYYY-MM-DD.md` —— 双层记忆，由后台 LLM 自动维护
- `agents/<subId>/sessions/` —— 完整的对话日志（JSONL）和压缩后的上下文

每一次对话结束，都会有新事实被提炼出来追加到当日的记忆流水账，后台调度器再周期性地把它们合并进 `MEMORY.md`。**调整 agent 的人格、知识、技能不需要动代码，只需要编辑工作区里的文件 —— 改一个文件就等于升级一次 Agent**。这件事 OpenClaw 做了，claw 现在也做了，且是同一套 Harness 运行时在背后撑。

**2. 直接以你的身份在本机文件系统和 Shell 里干活**

claw 用的文件系统后端是 `LocalFilesystemWithShell` —— 没有沙箱、没有远端，所有的读写和命令都直接落到本机操作系统。这在你自己机器上不是 bug，是 feature：你让它"帮我把 `~/Downloads` 下三个月前的文件挪到归档目录"，它真的能做到，因为它有 Shell。

Harness 的工具集是按"后端能力"条件性注册的 —— 在 claw 这种本机模式下，`execute` Shell 工具会自动出现在 Agent 的工具集里；换到不可信环境（如后面会讲的 Builder 远端模式），同一段 Agent 代码里的 Shell 工具会自动消失。**这是「同一份 Agent 逻辑、不同形态」的第一个具体体现**。

**3. 直接出现在你已经在用的地方**

claw 开箱内置 6 种通道：

| `type` | 传输 | 说明 |
| --- | --- | --- |
| `chatui` | 进程内 | 默认开启的本地 Web UI |
| `dingtalk` | Stream（WebSocket） | 钉钉企业内部应用，无需公网端口 |
| `wecom` | HTTP 回调 + REST API | 自建企业微信应用 |
| `feishu` | HTTP 事件回调 + REST API | 飞书自建应用 + 事件订阅 |
| `github` | Webhook + REST API | 监听 issue / PR review comment 事件 |
| `gitlab` | Webhook + REST API | 监听 Issue / MR Note Hook |

也就是说：你可以从一条钉钉 DM、或者一个 GitHub Issue 评论里 @ claw，它会带着完整的工作区上下文回应你。每个 agent 在 bootstrap 时还会自动注册 `outbound_send` 工具，让它可以**主动**往任意通道发消息 —— 子任务跑完后，`HarnessGateway.tryDispatchAnnounce` 会自动复用入站时的地址，让"完成通知"自然地回到当初触发它的那个钉钉/企微会话。

通道层还自带了一组默认的可靠性机制 —— 幂等去重、Bot-loop 防护、企微签名校验、AES-256-CBC 解密、access-token 续签，这些在企业 IM 集成里"不写就出事"的细节，框架都已经做好了。

### Claw 的边界

claw 的设计极度克制 —— **没有登录、没有多租户隔离、没有 Docker sandbox、不做横向扩展**。它故意不去做更多的事，因为这些事会破坏"装上就能用"的简单体验。

但只要你试着把这套东西放到一个团队里，问题就会接连出现：多个人怎么共享同一个进程？每个人自进化出来的工作区怎么互不污染？多副本部署时同一个用户的记忆怎么跨节点共享？让 agent 跑用户输入的代码怎么不出事？做出来的好 Agent 怎么分享给同事但又不让别人改坏？

这五个问题，**每一个单独看都不大，但合起来意味着 claw 必须被重新装进一个不一样的容器里**。这就是 Builder 的起点。

[OpenClaw]: https://github.com/agentscope-ai/openclaw

---

## 从 Claw 到 Builder —— OpenClaw 的下一个形态

claw 假设的是"一台机器、一个用户、一个工作区"。把这套假设直接套到团队场景，会同时崩掉五个地方 —— 每一个都不是"再开几个 claw 进程"能补上的：

1. **多人共用一个进程，但每个人要看到自己的视图。** claw 只认本机当前用户。多人登录、按 token 鉴权、按用户分会话 —— 这些都不在 claw 的范围内。
2. **每个用户的工作区必须互不污染。** Agent 自进化的副作用是它**会写文件** —— 学到的技能、生成的子 Agent、攒下的 `MEMORY.md`。Alice 调教过的 agent 不能让 Bob 看到他不该看到的东西，更不能让 Bob 的对话覆盖掉 Alice 的记忆。但 claw 用的是一个全局工作区。
3. **多副本部署时，同一个用户必须看到一致的工作区。** 把 claw 起两个进程在两台机器上，各自的本机磁盘是隔离的；同一个用户的请求落到不同副本上，看到的是两份不同的记忆。
4. **服务端跑用户输入的代码必须有 OS 级隔离。** claw 默认开了本机 Shell —— 这在你自己电脑上是核心体验，在多租户服务上就是直接的攻击面。
5. **做出来的好 Agent 要能被分享，但不能被改坏。** 团队里需要的不是"整份导出再让别人导入"，而是细粒度的"我授权给某个组用、但他们不能改"。

这五个问题归结到一件事：**「一个用户、一台机器、一个工作区」要被换成「多个用户、多台机器、多组被命名空间隔离的工作区」**。这不是给 claw 加几个补丁能解决的 —— 它需要在 Harness 这一层之上，重新搭一层运维外壳。

Builder 就是这层外壳。

---

## Builder —— OpenClaw 的多租户、可分布式版本

### 它在解决的场景

Builder 把 claw 的核心体验装进了一个**面向团队和企业**的 Web 平台。一句话定位：

> **Builder 是 OpenClaw 的分布式版本** —— 同样的自我进化、同样的工作区驱动、同样的 Harness 运行时；只是从"一个人"变成"一个组织"，从"一台笔记本"变成"一组横向扩容的服务"。

Builder 在仓库里的位置：

```
agentscope-examples/agents/agentscope-builder/
```

和 claw 一样，它也是一个完整的 Spring Boot 应用 —— `mvn package` + `java -jar` 就能在 8080 端口起来。开箱自带嵌入式 H2 数据库，并预置了 `admin/admin`、`bob/bob`、`alice/alice` 三个 demo 账号 —— 不用准备数据库、不用初始化用户表，登录进去就能体验完整的多用户共享场景。生产部署时激活 `jdbc` Spring Profile、覆盖 JDBC 连接，就能切到 MySQL / PostgreSQL，两个驱动都已经在 classpath 里。

### 用户视角：开浏览器、点几下、保存即得

claw 的开发者体验是"装上就能用"。Builder 把这个标准抬高到下一个层级：**用户什么都不用装，开浏览器、点几下、保存即得**。

具体来说，每一个登录用户都会得到一个**属于自己的工作台**。在这个工作台上：

- 创建一个 Agent 是 UI 上的几次点击 —— 挑技能、挑子智能体、挑工具和 MCP 服务、写一行系统提示，保存。**不是 `mvn package`，不是写代码，更不是改 YAML 重启服务**。
- 每个 Agent 都有自己独立的工作区，可以编辑 `AGENTS.md`、可以放新的 `skills/`、可以喂领域知识进 `knowledge/`。这个工作区会随对话不断长大，和 claw 在你自己机器上的体验完全一致 —— 区别只是它现在长在浏览器里、长在服务端、长在一个属于"你"这个用户的命名空间里。
- 对一个 Agent 满意了，**点一下分享**，就可以授权给某位同事、某个组、或者整个组织。

整个体验对终端用户而言是"零配置的 OpenClaw"。底层做了什么、怎么隔离、文件落到哪 —— 用户都不需要知道。

### 一切以 workspace 隔离与共享为基础

Builder 的产品设计有一条底层假设 —— **workspace 是 Agent 的资产**。所有的隔离、所有的共享、所有的多租户能力，全部围绕这一点展开。

- **隔离**：每一对 `(用户, Agent)` 都有自己**独立的 workspace 命名空间**。Alice 的 `agent-A` 和 Bob 的 `agent-A` 即使起点配置完全相同，他们各自的微调、记忆、技能演化都互不渗透。
- **共享**：当你想把自己调教好的 Agent 分享出去时，分享的就是这个 workspace 加上一份授权策略。Builder 把权限分成三档：
  - **可运行（run）** —— 别人能调它，但看不到工作区内部，也不能改技能或 prompt
  - **可编辑（edit）** —— 别人可以改它的配置和工作区文件，等于多人共用一个 Agent
  - **可 fork** —— 别人复制出一份属于自己的 workspace，之后两份独立演化
- **授权对象**：可以是某个具体的同事、某个用户组、也可以是整个组织。

这个模型直接来自团队协作的真实需求 —— "做出好东西后能分享、但不必交出控制权"。它也意味着：**只要 workspace 这个抽象设计得足够干净，所有上层产品能力（创建、编辑、分享、fork、计费、审计）都能以同一种方式表达**。这正是 Builder 的产品骨架。

接下来一节我们打开引擎盖，看看这个"workspace 即资产"的承诺，在底下到底是怎么落到代码里的。

---

## Builder 的核心机制：CompositeFilesystem

如果你只允许我用一句话讲清楚 Builder 的实现，那就是：

> **Builder 把每一个 Agent 都跑在一套 `HarnessAgent` + `CompositeFilesystem` 之上 —— 前者负责 Agent 的运行时编排，后者把工作区做成一个可命名空间隔离、可分布式落点、可投射到沙箱的资产。**

下面我们沿着一次具体的请求，把这两块拆开看。

### HarnessGateway —— 每对 (用户, Agent) 一个独立运行时

Web 层之下挂着一个 **HarnessGateway**。它的工作很简单：把每一对 `(userId, agentId)` 路由到一个独立的 `HarnessAgent` 实例。

- Alice 调用 `agent-A` → 落到 `Agent(alice, agent-A)`
- Alice 调用 `agent-B` → 落到 `Agent(alice, agent-B)`
- Bob 调用同名的 `agent-A` → 落到 `Agent(bob, agent-A)` —— 与 Alice 的完全独立

每个 `HarnessAgent` 实例都拿到的是一个被绑定到该用户、该 agent 命名空间的 `CompositeFilesystem`。换句话说 —— **HarnessGateway 不关心隔离怎么实现，它只负责"把对的 agent 实例交给对的请求"**；隔离的真正活儿，在下一层做。

### CompositeFilesystem —— 把工作区做成可隔离的资产

`CompositeFilesystem` 是 Builder 这一切能跑起来的关键。它的命名很直白 —— **它是一个组合（composite）出来的文件系统**：

```
┌──────────────────────────────────────────────────┐
│  CompositeFilesystem                             │
│                                                  │
│  ┌───────────────────────────────────────────┐   │
│  │  Layer 1: 命名空间分发                     │  │
│  │    把所有路径透明重写到                    │  │
│  │    users/{userId}/agents/{agentId}/...    │  │
│  └───────────────────┬───────────────────────┘   │
│                      ▼                           │
│  ┌───────────────────────────────────────────┐   │
│  │  Layer 2: 存储后端                         │  │
│  │    本机磁盘 / Docker 容器 / 远端 KV 三选一  │  │
│  └───────────────────────────────────────────┘   │
└──────────────────────────────────────────────────┘
```

- **上层是命名空间分发**：Agent 调 `read("AGENTS.md")` 时，CompositeFilesystem 会从当前的 `RuntimeContext` 拿到 `(userId, agentId)`，把路径透明地重写成 `users/{userId}/agents/{agentId}/AGENTS.md`。Agent 代码以为自己在操作"一整个文件系统"，**实际上看到的是被命名空间裁剪过的、只属于它自己的子树**。
- **下层是物理存储后端**：命名空间分发完之后，真正落到哪个介质上，取决于后端实现。默认是宿主机磁盘，对应 `LocalFilesystemWithShell`，路径直接落到 `~/.agentscope/builder/workspace/users/{userId}/agents/{agentId}/...`。

关键点：**Agent 代码完全不知道这两层的存在**。它使用的还是 Harness 那套统一的 `read / write / ls / grep` API，和在 claw 里一模一样。隔离是在 CompositeFilesystem 这一层实现的，不是靠业务代码"小心避开别人的目录"实现的。

### 一次写入的端到端 walkthrough

把这套抽象具体化 —— 当 Alice 在 UI 上让她的 `agent-A` 学一个新技能（写入 `skills/sql-helper/SKILL.md`），整个调用链如下：

1. **Web 层**：JWT 解析出 `userId=alice`、URL 路径解析出 `agentId=agent-A`，挂到 `RuntimeContext` 上。
2. **HarnessGateway**：路由到 `Agent(alice, agent-A)` 这个 HarnessAgent 实例。
3. **Agent 推理**：模型决定调用 `write_file("skills/sql-helper/SKILL.md", ...)` 工具。
4. **CompositeFilesystem 上层**：拦截调用，从 `RuntimeContext` 拿到 `(alice, agent-A)`，把路径重写为 `users/alice/agents/agent-A/skills/sql-helper/SKILL.md`。
5. **CompositeFilesystem 下层**：默认本地存储，把这个相对路径拼到 `~/.agentscope/builder/workspace/`，最终写到磁盘上的 `~/.agentscope/builder/workspace/users/alice/agents/agent-A/skills/sql-helper/SKILL.md`。
6. **下次推理**：当下一次对话开始时，`WorkspaceContextHook` 注入 system prompt 时同样会经过 CompositeFilesystem 读 `skills/`，自动定位到 alice/agent-A 自己的子树，新学的技能自动出现在工具集里。

Bob 的 `agent-A` 做同样的事，路径会被重写到 `users/bob/agents/agent-A/...` —— 物理上和 Alice 的完全不同的目录树。**没有任何"is alice or bob"的业务判断代码**，隔离是从抽象层挤出来的。

### 当你需要更强的隔离：在同一套 Composite 上加一层沙箱

claw 的本机模式下，Shell 命令直接打到宿主机。Builder 的默认本地模式继承了这一点 —— 适合可信团队、单节点部署。但只要你的场景里**会有不可信代码进入 Agent**（比如让 Agent 跑用户提交的 SQL、Python、Shell 脚本），宿主机就不能再直接挨这一刀。

Builder 在这种场景下走的不是"再造一套沙箱方案"，而是**在 CompositeFilesystem 上加一层 projection**：

- Agent 进入沙箱模式后，运行时整体迁到 Docker 容器内
- 宿主侧仍然是 workspace 的"主"，CompositeFilesystem 把宿主上的 `AGENTS.md`、`skills/`、`subagents/`、`knowledge/` 等关键文件**投射**进容器内的 `/workspace`
- 容器内的 Agent 看到的是和宿主完全一致的工作台 —— 它读的是同一份 `AGENTS.md`、用的是同一组 skills
- Shell 命令在容器内执行，宿主进程不会被任何用户输入直接影响

注意 —— **这不是"另一套文件系统"**，是同一个 CompositeFilesystem 在下层多套了一层"宿主 ↔ 容器"的物理映射。Agent 代码、工作区目录结构、UI 体验全部不变，变的只是 Shell 命令的边界落到了容器壁上。

沙箱的隔离粒度 —— 一个容器对应一个 session、一个用户、一个 agent，还是全局共享 —— 是个实际的部署决策，可以按业务场景配置；默认 `USER`（每个用户一个容器，多 session 共用）在大多数多租户 SaaS 里是合理的起点。

### 当一台机器不够：把存储层换成分布式后端

到这里 Builder 还是单节点的 —— 工作区落在一台机器的本机磁盘上（或者那台机器上的 Docker 容器里）。一旦你需要把 Builder 做成多副本、用户请求随机落到任意一台节点上都看到一致状态，问题就回到了文章开头说过的"多副本怎么共享工作区"。

CompositeFilesystem 的解法很直接：**把下层的存储后端从"本机磁盘"换成"分布式 KV"**。Builder 抽象出了 `BaseStore` 这个接口，实现可以是 Redis、对象存储（OSS / S3）、或者你自己接的 KV 服务。换上去之后：

- Agent 运行时的所有读写都走 `RemoteFilesystem`，落到 `BaseStore`
- Web 层管理用户工作区也走同一份 `BaseStore` —— Web 看到的、Agent 看到的是同一份数据
- 配合分布式 `Session`（典型实现是 `RedisSession`），Builder 进程本身可以多副本对等部署

整张图里"装命名空间分发的上层"完全没动 —— 命名空间分发是在 CompositeFilesystem 这一层完成的，存储后端无论是本机磁盘、Docker 容器、还是 Redis，都看不到它。**这正是当初 [Harness 那一篇](agentscope-v1-harness.md) 里讲的 `AbstractFilesystem` 真正发挥威力的地方** —— 业务代码一行不用改，部署侧换 Bean 就完成了从单机到分布式的迁移。

---

## 一图看懂 Builder 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  AgentScope Builder（Spring Boot，端口 8080）                       │
│                                                                     │
│   React SPA ──▶  REST API (JWT)                                     │
│                  │                                                  │
│                  ▼                                                  │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  HarnessGateway                                              │  │
│   │   ├─ Agent (alice, agent-A) ──┐                              │  │
│   │   ├─ Agent (alice, agent-B)   │ 每 (user,id) 一个 HarnessAgent│  │
│   │   └─ Agent (bob,   agent-A) ──┘                              │  │
│   └──────────────────────────────────┬───────────────────────────┘  │
│                                      ▼                              │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  CompositeFilesystem                                         │  │
│   │   ├─ 上层：命名空间分发  (userId, agentId) → 子树            │  │
│   │   └─ 下层：物理存储后端                                      │  │
│   │         · 默认：本机磁盘                                     │  │
│   │         · 沙箱模式：宿主 ⇄ 容器 projection                   │  │
│   │         · 分布式：BaseStore（Redis / OSS / 自定义）          │  │
│   └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│   用户与 agent 元数据（默认 H2；生产可切到 MySQL / PostgreSQL）     │
└─────────────────────────────────────────────────────────────────────┘
```

整张图里**真正"新"的只有最上面那一条** —— React SPA + JWT REST API + HarnessGateway 的路由。中间和底层全是把 Harness 的 `HarnessAgent` 与 `AbstractFilesystem` 直接拿来组合。

这正是 Builder 的设计哲学：**不重新发明 Agent 运行时，只补齐让它跑在多租户企业环境里所需要的运维外壳**。

---

## 快速开始

### Claw

```bash
# 1. 设置模型 API key（默认走 DashScope）
export DASHSCOPE_API_KEY=sk-xxx

# 2. 编译并运行
mvn -pl agentscope-examples/agents/agentscope-claw -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-claw/target/agentscope-claw-*.jar
```

打开 <http://localhost:8080>，默认主目录是 `~/.agentscope`。需要接钉钉 / 企微 / 飞书等通道的，编辑 `~/.agentscope/agentscope.json` 添加对应的 channel 条目即可。详见 [Claw README]。

### Builder

```bash
export DASHSCOPE_API_KEY=sk-xxx

mvn -pl agentscope-examples/agents/agentscope-builder -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-builder/target/agentscope-builder-*.jar
```

服务起在 8080 端口，用 `admin/admin`、`bob/bob` 或 `alice/alice` 登录就能进入完整的 UI。生产部署相关的内容（数据库切换、沙箱镜像、分布式后端）见 [Builder README]。

[Claw README]: https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agents/agentscope-claw
[Builder README]: https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agents/agentscope-builder

---

## Claw vs Builder —— 该选哪个

| | claw | Builder |
|---|---|---|
| **适用场景** | 自己笔记本 / 工作站上的个人助手 | 一个团队或一家公司共建并运营自进化 Agent |
| **用户数** | 1 人 | 多人 —— 每个登录用户都有自己的 workspace |
| **入口** | Web UI + 钉钉 / 企微 / 飞书 / GitHub / GitLab | React SPA + JWT REST API |
| **隔离** | 无 —— 直接以你的身份运行 | `(userId, agentId)` 命名空间；可选 Docker sandbox |
| **共享** | 没有 —— 一台机器一个人 | run / edit / fork 三档分级 |
| **分布式** | 单进程、单节点 | 切到 BaseStore 后端即可横向扩容 |
| **文件系统** | `LocalFilesystemWithShell` | `CompositeFilesystem` |

**两条路并不互斥** —— Harness 的工作区是文件，整个 `AGENTS.md / skills/ / subagents/` 子树就是一份可以版本化、可以 Code Review、可以从 claw 直接拷到 Builder 的资产。一个常见的工作流：开发者在自己机器上用 claw 把一个 Agent 调到自己满意，把工作区目录作为模板提交到仓库，再由运维侧通过 Builder 推给整个团队。

---

## 总结

[Harness 那一篇](agentscope-v1-harness.md) 我们交付了"自进化 Agent 运行时"的能力 —— `HarnessAgent` + 工作区约定 + 可插拔文件系统 + Hook 管线。

今天的这一篇把这套运行时**真正做成了两个可以直接跑起来的产品**：

- **claw 证明**：用 AgentScope Harness，我们已经能做出一个完整的 Java 版 OpenClaw —— 自我进化、本机 Shell、5 种 IM 通道接入，全部装进一个 `mvn package` 就能跑的 Spring Boot 应用里。
- **Builder 证明**：同一套 Harness 运行时，加上一层"workspace 隔离与共享"的运维外壳，就能直接演化成一个面向团队的多租户平台。从 claw 到 Builder，**Agent 业务逻辑一行没有改**，改的只是底层 CompositeFilesystem 在哪一层提供隔离、把数据落到哪个介质上。

这正是 Harness 设计之初就承诺要做到的：**写一套 Agent 逻辑，按需切换形态**。从今天起，这个承诺在仓库里就有两个跑得起来的实证。

如果你的需求是个人助手，从 [Claw README] 跑通 quick start 开始；如果是团队 / 公司平台，直接从 [Builder README] 起步。两条路汇聚到的是同一套 Harness —— 这也是我们做这件事的初衷。
