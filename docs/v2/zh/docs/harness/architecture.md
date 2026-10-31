---
title: Harness 架构
description: HarnessAgent 是什么、如何选择调用方式、组合业务能力并保存会话状态
en_link: /v2/en/docs/harness/architecture
---

`HarnessAgent` 将推理、工具与上下文管理，和工作区、长期记忆、技能、子 Agent、沙箱等能力组合在一起。先用 [Quick Start](/v2/zh/docs/quickstart) 跑通问答，再按业务需要增加能力。

`ReActAgent` 提供推理循环、工具、权限和会话上下文等基础 API；Harness 在这些能力上提供开箱即用的组合与默认配置。两者都支持 `call` 和 `streamEvents`。

推荐在应用启动时配置共享 `HarnessAgent.Builder`，每次直接调用前用 `builder.build()` 创建新实例，执行结束后关闭。会话通过稳定身份与同一日志后端延续；具体代码和共享实例的适用方式见[实例生命周期](/v2/zh/docs/building-blocks/agent#实例生命周期)。后台 `AgentSession` 的实例则由会话管理器持有到后台执行结束。

## 核心工作原理

一次执行会读取当前会话状态，构建模型输入，按需调用工具，再保存结果。工作区、记忆和压缩等能力在这些步骤中生效；业务通过 builder、工具和 middleware 定制行为。

## 选择调用方式

- **获取回复或把 Agent 接入工作流**：使用 `call(input, ctx)`。
- **在当前请求内展示实时文本和工具进度**：使用 `streamEvents(input, ctx)`。
- **让任务在后台继续，支持排队、补充要求和中断恢复**：使用 `agent.session(ctx)` 提供的 `AgentSession`。

直接调用也支持多轮会话，并使用默认的 Session Log 和 checkpoint 保存状态。AgentSession 进一步负责持久接收任务、安排执行和关联待办；业务无需为这些操作自行管理执行订阅。两种方式使用同一个 Agent 的模型、工具和中间件配置。

调用示例见[智能体](/v2/zh/docs/building-blocks/agent)，完整的后台会话流程见[会话操作、事件与恢复](/v2/zh/docs/harness/session-log)。已有工作流调度器的应用可继续直接调用；托管 HTTP 接入使用 [Service Agent API](/v2/zh/service/session-event-log)。

## 核心组件

按应用需要配置能力，或调用会话操作。

| 能力 | 解决什么问题 | 配置或操作 | 详细文档 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | [工作区](/v2/zh/docs/harness/workspace) |
| 状态持久化与执行日志 | 完整事实、checkpoint、跨请求和跨节点恢复 | 默认 EVENT_LOG；`.sessionLogStore(...)` 替换 | [会话日志](/v2/zh/docs/harness/session-log) |
| 会话任务管理 | 后台执行、持久排队、补充信息和恢复 | `agent.session(ctx)` | [会话操作](/v2/zh/docs/harness/session-log) |
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认开启；`.memory(...)` 定制 prompt / 触发策略 | [记忆](/v2/zh/docs/harness/memory) |
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | [上下文管理](/v2/zh/docs/harness/context) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [上下文管理](/v2/zh/docs/harness/context) |
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [子 Agent](/v2/zh/docs/harness/subagent) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | [文件系统](/v2/zh/docs/harness/filesystem) |
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | [沙箱](/v2/zh/docs/harness/sandbox) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [计划模式](/v2/zh/docs/harness/plan-mode) |
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [技能](/v2/zh/docs/harness/skill) |
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | [工作区](/v2/zh/docs/harness/workspace) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/zh/docs/harness/channel) |

## 既有 Session 的持久 Team 归属

`LocalTeamClient.sessionMembership(...)` 显式启用既有 Leader 和手工提供的 **BYO** 成员 Session 的接入、登记、查询与安全解除。同一定义的不同 Session 可以加入不同 Team；同一完整身份的 Session 在配置的关系域中只能属于一个 Team，包括跨 Team namespace 的情况。这是编程 API，复用已有 `BaseStore` 能力：`supportsAtomicSessionStorage()` 必须返回 `true`，提供正数记录版本，以及共享后端所有写入者之间原子的 `putIfVersion`。比较失败不得写入，真实存储错误必须传播。已有 `InMemoryStore` 和 JDBC `JdbcStore` 已声明该能力；自定义 provider 使用相同合同，默认仍为 `false`。其他 `BaseStore` 和 `TeamClient` 仍可通过原有 API 使用。

先通过已有 `createTeam` 建立 Team，再用已持久化的 Leader Session 显式接入。只能绑定已在该 Team 目录中声明的成员。下面示例使用旧 `AgentStateStore` Session；原生 Session 日志使用后文的配置。示例在应用入口使用 `.block()`；响应式请求路径应组合返回的 `Mono`。

```java
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import io.agentscope.harness.agent.team.TeamMemberSpec;
import io.agentscope.harness.agent.team.TeamSessionMembership.MemberSession;
import io.agentscope.harness.agent.team.TeamSessionMembership.Role;
import io.agentscope.harness.agent.team.TeamSessionMembership.SessionKey;
import io.agentscope.harness.agent.team.TeamSessionMembership.TeamAddress;
import java.util.List;
import java.util.Map;

var stateStore = new InMemoryAgentStateStore();
stateStore.save("alice", "leader-session", "agent_state",
        AgentState.builder().userId("alice").sessionId("leader-session").build());
stateStore.save("alice", "worker-session", "agent_state",
        AgentState.builder().userId("alice").sessionId("worker-session").build());

var client = new LocalTeamClient(new InMemoryStore());
client.createTeam(new TeamCreateSpec(
        "research-address", "my-app", "Research a topic", "leader-agent", "",
        List.of(new TeamMemberSpec("researcher", "research-agent", "", "byo"))))
        .block();

var membership = client.sessionMembership("app-relations", Map.of("sessions", stateStore));
var address = new TeamAddress("my-app", "research-address");
var leader = new MemberSession("lead", "alice", "leader-agent", Role.LEADER,
        new SessionKey("sessions", "alice", "leader-session"));
var worker = new MemberSession("researcher", "alice", "research-agent", Role.WORKER,
        new SessionKey("sessions", "alice", "worker-session"));

var team = membership.adoptTeam("alice", address, "Research", List.of(leader)).block();
var binding = membership.bindMember("alice", address, worker).block();
var current = membership.findMembership(worker.session()).block();
var members = membership.listMemberships("alice", address).block();
boolean removed = membership.unbindMember("alice", address, worker.session(), binding.token()).block();
// The existing worker Session remains; findMembership now completes empty.
boolean stateKept = stateStore.exists("alice", "worker-session");
```

内存示例不需要模型或 Worker 运行，展示的是实际归属操作。需要跨进程重启持久保存时，使用已有 JDBC `JdbcStore` 与持久数据库，并在所有应用实例使用相同关系域和 Session 存储域映射。归属操作不写入或删除 Session 状态及日志；其配置的后端独立保留已有对话。

### 既有原生 Session

原生历史使用三参数 `sessionMembership` 重载，传入实际的 `SessionLogStore`。将上例中的旧状态存储配置和成员身份替换为下面配置，`createTeam`、`adoptTeam`、绑定、查询及解除操作保持一致。这里两个既有 Agent 均通过 `.sessionLogStore(logs)` 使用同一个 `logs`，各自的上下文指向已完成初始化、owner 为 `alice` 的 Session。

```java
var membership = client.sessionMembership(
        "app-relations", Map.of(), Map.of("native-logs", logs));
var leader = new MemberSession("lead", "alice", "leader-agent", Role.LEADER,
        SessionKey.nativeSession("native-logs", leaderAgent.sessionKey(leaderContext)));
var worker = new MemberSession("researcher", "alice", "research-agent", Role.WORKER,
        SessionKey.nativeSession("native-logs", workerAgent.sessionKey(workerContext)));
```

原生登记读取 Session 日志中已提交的 baseline。每次存在性校验都会扫描已提交的日志历史，因此接入和绑定的成本随会话长度增长。接入或绑定之前，应通过普通 Agent 调用或 `AgentSession.submit` 任务初始化 Session。仅打开 Session 句柄或接受排队任务，不表示 baseline 已提交。缺少 baseline 时拒绝登记，日志读取错误直接传播；归属操作不写入占位 `AgentState`，也不修复日志。日志后来不可用时，仍可查询归属和使用 token 解除关联。

[NativeTeamMembershipExample](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/harness/teams/NativeTeamMembershipExample.java) 通过离线脚本模型执行两个真实原生会话，再使用编程 API 登记、重开客户端及日志后端，最后解除一个关联并检查其 Session 日志仍然保留。在仓库根目录运行：

```bash
mvn -B -T1C -pl agentscope-examples/documentation -am install -DskipTests
mvn -B -pl agentscope-examples/documentation exec:exec \
  -Dexec.executable=java \
  -Dexec.args="-classpath %classpath io.agentscope.examples.documentation2.harness.teams.NativeTeamMembershipExample"
```

示例不需要模型服务凭证，Session 日志保存在临时 workspace 中，并在同一进程内重开。示例的 Team 记录使用 `InMemoryStore`，进程退出后不会保留归属；需要跨进程恢复时，应使用持久化 `JdbcStore`。

### 身份与所有权

- `TeamAddress(namespace, teamName)` 是已有存储/路由地址。接入时显式提供，拒绝 null/空白 namespace，避免静默迁移历史记录；非空白字符串原样保留。若 `createTeam` 收到 null namespace，Team 实际保存在 `"default"` 下；构造接入地址时应使用该 namespace，也可从返回的 `TeamInfo` 读取。逻辑 `teamId` 和 `displayName` 独立；不同地址的 Team 可以使用相同展示名。同一地址只能由一个 owner 和关系域接入。
- `SessionKey(stateStoreDomain, owner, sessionId)` 保留旧状态槽身份及 JSON 形状。四参数形式增加运行 `agentId`；`nativeSession(logDomain, coreSessionKey)` 保留原生 `(userId, agentId, sessionId)` 身份。运行 `agentId` 与声明定义的 `agentRef` 独立。用户、Agent、Session 或实际存储域不同的原生会话，是不同的 Session。
- 域 ID 标识实际后端及分区路由，与关系域、Team namespace 分开。同一物理分区在所有客户端使用相同稳定 ID，独立分区使用不同 ID；legacy 与 native 域名不能重叠。将同一底层分区配置成两个域 ID 会绕过 Session 唯一性。应用须处理 provider 别名；额外 tenant 属性参与存储路由时，应配置可信 adapter，将这些属性固定到声明的分区。归属操作打开日志时传入 Session 的 user 和 session ID，不推断额外路由上下文。
- Session owner 必须等于 Team owner。定义 owner 独立记录，可以不同；可信应用编程 API 不提供定义访问或邀请授权。旧存储的存在校验接受已持久化状态，包括旧 state keys；原生存在校验要求已提交的日志 baseline。两种校验都不授予定义访问权限。
- null owner 支持匿名 Session。该 API 拒绝空串、空白 owner、控制字符和保留字面值 `__anon__`。从 Session 初始化起就应一致使用受支持的身份；将已有空 user ID 改成 null，在某些后端可能指向不同 Session。旧 API 的已接受输入不变。标识和地址拒绝空白值及控制字符。

### 查询、重试与兼容

`getTeam(owner, address)` 返回稳定逻辑 ID、展示名、原有 `TeamInfo`、接入状态和当前关系。`findMembership(session)` 在未关联时以 empty 完成；`listMemberships` 返回含 Leader 的不可变当前列表。这些是已关联的 Session，与声明成员目录不同，也不证明成员正在运行。

写接入标记前，接入操作检查全部初始 Session 尚未关联，并通过 CAS 确保空 owner 记录可以存储。初始 Session 已关联其他 Team 时会直接拒绝，不修改 Team 元数据或关系；可以换用其他 Session 修正请求。并发的相同接入请求复用同一个逻辑 Team ID 和已提交的绑定，仍受下述 CAS 重试上限约束。此阶段存储失败时 Team 尚未接入，旧查询仍可用；空记录可以保留供重试。关系地址保留已有短编码，过长 owner 或关系域改用定长 SHA-256 编码，满足 JDBC 键及 namespace 的长度限制。

随后在原 Team meta 写标记，再在单个 owner 关系记录中原子提交初始关系和回执。这两次写入中间发生故障或 Session 被并发抢占，仍可能留下 `PENDING`：`getTeam` 返回该状态和空的已提交成员列表，`listMemberships` 明确报错。旧 `listMembers` 及 `broadcastMessage` 继续使用原声明目录，包括原 Session 字段；这不代表新归属已经提交。提交后 `listMembers` 才切换到关系投影。用相同完整 `adoptTeam` 请求重试，包含展示名和初始成员；冲突的初始请求不能覆盖标记。已经提交的接入请求重放只返回当前关系，不恢复后来已解除的成员。

恢复要求原始初始 Session 仍然可用且可以关联。遇到临时存储故障，应先恢复后端可用性，再重试原请求。若 Session 已被其他 Team 占用，应通过有权执行的应用流程处理该关联后再重试。已删除的 Session 必须通过其受支持的存储/恢复路径还原为原来的持久会话；写入占位状态不属于恢复。若无法恢复这些条件，Team 会保持 `PENDING`：目前没有受支持的取消/放弃接入 API。不要通过删除或改写 marker、owner 记录绕过该状态。

完全相同的绑定保留 token。解除需要预期 token；实际解除返回 `true`，已无关联返回 `false`。观察到绑定存在时，错误 Team 或过期 token 报错；真正解除后重绑会生成新 token。`false` 表示操作期间观察到无关联，不保证调用返回时仍未绑定：与解除重叠的新绑定可能随后完成，该新关系会被保留。CAS 竞争最多重试十次，之后抛 `TeamConflictException`；存储错误直接传播，不降级为无条件写。提交后响应失败时，可查询或用相同请求重试确认结果；取消订阅不回滚已经提交的关系。

已接入 Team 的旧 `listMembers` 读取同一关系并投影 `sessionId`，未关联的声明成员投影为空。旧 record 结构、声明的 `isLead`、`phase` 和 `deployMode` 不变。已有 `TeamTool.listMembers` 会序列化这份名册，因此模型可见的 `sessionId` 来自实际关联的 Session，解绑后为空；输出不包含 binding token、关系记录或完整原生存储身份。旧数据已有非空 Session 字段时，初始接入必须显式提供对应完整身份。`completeTeam` 仍只将原 phase 改为 `Completed`，保留关系及接入标记。成功调用 `sessionMembership(...)` 后，当前 client 启用严格完成 CAS，包括尚未接入的 Team；新实例观察到接入标记时也使用严格 CAS。持续竞争时报冲突，避免覆盖其他写入方。未启用 client 对未接入 Team 保留旧完成 fallback，包括版本存储。

接入 Team 前，先停止该 Team 的旧版本及未启用写入方，等待其在途写操作结束，再升级所有参与写入的实例，并在每个 client 上调用 `sessionMembership(...)` 启用保护。启用 client 不会停止已经进入旧路径的写操作。旧二进制不理解归属投影，其无条件完成 fallback 可能清掉并发接入标记。已接入 Team 不支持新旧版本混写。回滚需使用理解现有存储格式的版本，或停止全部写入方后恢复接入前的备份。

纯 legacy 关系保留 schema 1，可以读取早期包含 `source: "BYO"` 的记录，以及曾省略该字段的草案记录。原生身份使用 schema 2；owner 记录升级到 schema 2 后，即使解除全部原生关联也不降级。登记原生 Session 前，须停止并排空旧写入者，将所有参与客户端升级到理解 schema 2 的版本；旧读取器无法读取原生记录。缺省来源只表示手工提供的既有资源，不授予删除权。新关系写入在内部存储格式中保留 BYO，与公开 `Membership` record 分开。未知来源、字段或格式版本明确拒绝，不重置或覆盖原记录。

Team 元数据、phase 和声明定义继续使用原记录；每个关系域、每个 owner 的单条记录只保存关联和接入回执，读写成本与竞争随该 owner 的关联数增加。目前没有基于压测的容量上限；大规模使用前，应结合实际后端评估预期关联数和并发写入量。已接入记录的所有写入者须遵守版本能力；不支持直接原始修改或删除。损坏或未知关系格式会报错，不重置为空。Session 存在校验与关系提交是独立操作，外部删除 Session 可能留下关联，应用须显式解除。

JDBC adapter 仅将已识别的重复键诊断视为 CAS 竞争。其他或未知 SQL 诊断保留原因传播，包括诊断码尚未识别的驱动重复键错误。这是对旧版宽泛完整性约束分类的有意收紧；新增后端时应验证实际驱动。JDBC、MySQL 和 PostgreSQL 扩展分别在自己的独立模块及支持方言内维护分类。

解除保留 Agent 定义、Session 状态、原生日志和 workspace，不停止运行、撤销工具权限、更新已构造的静态 `TeamContext` 或注销 `bindSession` 通知。任务板、mailbox 和静态 `teamsMode` 保留原运行语义。该能力不创建模型、Worker、邀请、调度或 Service Team/Run 资源。

## 状态怎么流转

状态分三层，框架自动在层之间搬数据。

- **调用内状态** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）加上 `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用状态** —— 默认从 Session Log 的 checkpoint 及后续可应用事实恢复；完整历史保留，JSONL 仅按需导出。LEGACY 使用 AgentStateStore；文件、子任务及 sandbox 元数据仍由各自后端保存。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务把它周期合并到 `MEMORY.md`；`MEMORY.md` 每次 call 加载为参考上下文，不作为 System 指令。

三个值得记住的规律：

- 最终请求每轮重新构建，但工作区文件按 call 加载；修改 AGENTS.md 或 MEMORY.md 后下一次 call 生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 的持久化由 Core 执行生命周期管理：EVENT_LOG 写 checkpoint，LEGACY 写旧状态存储。Harness 配置默认日志后端。

## 自己加 middleware 时要注意什么

要在不绕过 Harness 内置链路的前提下插入自定义行为：

- 用 `.middleware(...)`：你的 middleware 会跑在所有 Harness 内置之前。
- 通过 agent 上的 `RuntimeContext` 读当前调用的身份（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`，它会按当前文件系统模式（本机 / 沙箱 / 远端）正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## 相关文档

- [会话操作、事件与恢复](/v2/zh/docs/harness/session-log) — 后台任务、排队、交互和续做
- [可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat) — 从提交任务到前端断线续传的完整应用
- [工作区](/v2/zh/docs/harness/workspace) — 目录结构、指令与参考材料的来源、`tools.json`
- [上下文与 AgentState](/v2/zh/docs/building-blocks/context) — `AgentState`、`RuntimeContext`、`AgentStateStore` 持久化、多用户隔离
- [记忆](/v2/zh/docs/harness/memory) — 两层记忆
- [上下文管理](/v2/zh/docs/harness/context) — 构建模型输入、跟进长程任务、压缩对话和卸载大结果
- [文件系统](/v2/zh/docs/harness/filesystem) — 本机 + shell / 共享存储 / 沙箱
- [沙箱](/v2/zh/docs/harness/sandbox) — 隔离执行、跨调用恢复、分布式
- [子 Agent](/v2/zh/docs/harness/subagent) — 声明、同步/后台、流式转发
- [技能](/v2/zh/docs/harness/skill) — 四层合成、自学习闭环
- [计划模式](/v2/zh/docs/harness/plan-mode) — 只读阶段 + HITL 退出
- [Channel](/v2/zh/docs/harness/channel) — 会话管理、多 agent 路由、流式 SSE

## 模型输入的统一构建

Harness 在最终模型调用边界统一组织 System、对话、任务状态和参考材料；动态业务信息通过 contextSource 接入。配置、默认行为和限制见 [上下文管理](/v2/zh/docs/harness/context)。
