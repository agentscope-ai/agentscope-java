---
title: Harness 架构
description: HarnessAgent 是什么、各能力如何协作、状态如何在一次 call() 中流转
en_link: /v2/en/docs/harness/architecture
---

`HarnessAgent` 是 `ReActAgent` 的一层薄包装，把长期运行 agent 必备的工程能力打包进单一 builder：工作区驱动的人格、长期记忆、子 agent 编排、沙箱隔离、技能装配、计划模式、Channel 路由。

裸的 `ReActAgent` 只解决"一次请求 → 推理 → 工具 → 回复"。Harness 要回答的是另一组问题：下一轮怎么接着上一轮、上下文如何保持有界、多用户如何隔离、危险操作如何先 review 再执行、可复用能力如何沉淀。

> 安装、依赖、跑通第一个 `HarnessAgent` 的端到端示例见 [快速开始](/v2/zh/docs/quickstart)。本页只讲架构。

## 核心工作原理

理解 Harness 只需要记住三件事：

**1. 能力是叠加在推理循环关键时机上的，不是改写循环。**
工作区注入、压缩、子 agent、沙箱、Plan Mode —— 每个能力都钩在 ReAct 循环的关键时机。core 的算法本身没动，Harness 只往里加东西。

**2. 能力之间互不依赖，只通过共享对象通信。**
每个能力只做自己的事，互相不感知。它们之间靠三个共享对象交流：

- **`RuntimeContext`** —— 这次 `call()` 是谁在说话：`sessionId`、`userId`、自定义 extra。不持久化。
- **工作区** —— 谁读写哪些文件。物理落到本机、沙箱还是 KV 存储是配置决定。
- **`AgentStateStore`** —— 跨调用怎么恢复运行时状态。

**3. 内置 middleware 注册顺序固定，你自己加的跑在最前面。**
Harness 在构建期按固定顺序串起所有内置 middleware。你通过 `.middleware(...)` 加的会跑在 Harness 内置之前。

## 核心组件

每个能力对应一个问题，按需在 builder 上打开。

| 能力 | 解决什么问题 | Builder 入口 | 详细文档 |
|---|---|---|---|
| 工作区驱动的人格 | 人格 / 知识 / 子 agent / 技能 / MCP 白名单都以文件形式存在 | `.workspace(path)` | [工作区](/v2/zh/docs/harness/workspace) |
| 状态持久化 | 同 `(userId, sessionId)` 跨请求、跨进程、跨副本恢复 | 默认开启；`.stateStore(...)` 替换实现 | [上下文与 AgentState](/v2/zh/docs/building-blocks/context) |
| 双层长期记忆 | 长会话里有价值的事实自动沉淀到 `MEMORY.md` | 默认开启；`.memory(...)` 定制 prompt / 触发策略 | [记忆](/v2/zh/docs/harness/memory) |
| 对话压缩 | 上下文有界；模型真的溢出时强制重试 | `.compaction(...)` | [上下文压缩](/v2/zh/docs/harness/compaction) |
| 大工具结果卸载 | 超 80K 字符的结果落盘 + 占位符 | `.toolResultEviction(...)` | [上下文压缩](/v2/zh/docs/harness/compaction) |
| 子 agent 编排 | 委派给子 agent，支持同步或后台，自动反向通知 | `.subagent(...)` 或 `workspace/subagents/` | [子 Agent](/v2/zh/docs/harness/subagent) |
| 可插拔文件系统 | 本机 + shell / 共享存储 / 沙箱，不改代码切换 | `.filesystem(...)` | [文件系统](/v2/zh/docs/harness/filesystem) |
| 沙箱隔离 | 文件与命令隔离，跨调用恢复，多副本部署 | `.filesystem(new DockerFilesystemSpec()...)` | [沙箱](/v2/zh/docs/harness/sandbox) |
| 计划模式 | 只读思考阶段 + HITL 退出 | `.enablePlanMode()` | [计划模式](/v2/zh/docs/harness/plan-mode) |
| 技能装配 | 来自 Git / Nacos / MySQL / classpath / 工作区 | `.skillRepository(...)` | [技能](/v2/zh/docs/harness/skill) |
| MCP 集成与工具白名单 | 声明式 MCP server + 工具粒度允许 / 拒绝 | `workspace/tools.json` | [工作区](/v2/zh/docs/harness/workspace) |
| Channel 路由 | 会话管理、per-session 并发控制、多 agent 路由、流式事件 | `agent.channel(...)` / `GatewayBootstrap` | [Channel](/v2/zh/docs/harness/channel) |

## 既有 Session 的持久 Team 归属

`LocalTeamClient.sessionMembership(...)` 显式启用既有 Leader 和手工提供的 **BYO** 成员 Session 的接入、登记、查询与安全解除。同一定义的不同 Session 可以加入不同 Team；同一完整身份的 Session 在配置的关系域中只能属于一个 Team，包括跨 Team namespace 的情况。这是编程 API，需要 `VersionedBaseStore`；已有 `InMemoryStore` 和 JDBC `JdbcStore` 实现了该能力。其他 `BaseStore` 和 `TeamClient` 仍可通过原有 API 使用。

先通过已有 `createTeam` 建立 Team，再用已持久化的 Leader Session 显式接入。只能绑定已在该 Team 目录中声明的成员。下面在应用入口使用 `.block()`；响应式请求路径应组合返回的 `Mono`。

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

内存示例不需要模型或 Worker 运行，展示的是实际归属操作。需要跨进程重启持久保存时，使用已有 JDBC `JdbcStore` 与持久数据库，并在所有应用实例使用相同关系域和 Session 存储域映射。归属操作不写入或删除 `AgentStateStore` 状态；文件或 JDBC 状态存储可以独立保留已有对话。

### 身份与所有权

- `TeamAddress(namespace, teamName)` 是已有存储/路由地址。接入时显式提供，拒绝 null/空白 namespace，避免静默迁移历史记录；非空白字符串原样保留。逻辑 `teamId` 和 `displayName` 独立；不同地址的 Team 可以使用相同展示名。同一地址只能由一个 owner 和关系域接入。
- `SessionKey(stateStoreDomain, owner, sessionId)` 指向真实 Session 存储槽。同一物理存储分区使用相同稳定域 ID，独立分区使用不同 ID。将同一底层存储配置成两个域 ID 会绕过这一身份合同。应用也须处理 provider 的别名；Agent 引用不能证明状态隔离。
- Session owner 必须等于 Team owner。定义 owner 独立记录，可以不同；可信应用编程 API 不提供定义访问或邀请授权。存在校验接受任何已持久化状态，包括旧 state keys；没有持久状态的 Session 不能登记。
- null owner 支持匿名 Session。新 API 拒绝空白 owner 和保留字面值 `__anon__`，避免与已有匿名状态槽混同；旧 API 的已接受输入不变。标识和地址拒绝空白值及控制字符。

### 查询、重试与兼容

`getTeam(owner, address)` 返回稳定逻辑 ID、展示名、原有 `TeamInfo`、接入状态和当前关系。`findMembership(session)` 在未关联时以 empty 完成；`listMemberships` 返回含 Leader 的不可变当前列表。这些是已关联的 Session，与声明成员目录不同，也不证明成员正在运行。

写接入标记前，接入操作检查全部初始 Session 尚未关联，并通过 CAS 确保空 owner 记录可以存储。初始 Session 已关联其他 Team 时会直接拒绝，不修改 Team 元数据或关系；可以换用其他 Session 修正请求。并发的相同接入请求复用同一个逻辑 Team ID 和已提交的绑定，仍受下述 CAS 重试上限约束。此阶段存储失败时 Team 尚未接入，旧查询仍可用；空记录可以保留供重试。关系地址保留已有短编码，过长 owner 或关系域改用定长 SHA-256 编码，满足 JDBC 键及 namespace 的长度限制。

随后在原 Team meta 写标记，再在单个 owner 关系记录中原子提交初始关系和回执。这两次写入中间发生故障或 Session 被并发抢占，仍可能留下 `PENDING`：`getTeam` 返回该状态和空的已提交成员列表，`listMemberships` 明确报错。旧 `listMembers` 及 `broadcastMessage` 继续使用原声明目录，包括原 Session 字段；这不代表新归属已经提交。提交后 `listMembers` 才切换到关系投影。用相同完整 `adoptTeam` 请求重试，包含展示名和初始成员；冲突的初始请求不能覆盖标记。已经提交的接入请求重放只返回当前关系，不恢复后来已解除的成员。

完全相同的绑定保留 token。解除需要预期 token；实际解除返回 `true`，已无关联返回 `false`。错误 Team 或过期 token 报错；真正解除后重绑会生成新 token。CAS 竞争最多重试十次，之后抛 `TeamConflictException`；存储错误直接传播，不降级为无条件写。提交后响应失败时，可查询或用相同请求重试确认结果；取消订阅不回滚已经提交的关系。

已接入 Team 的旧 `listMembers` 读取同一关系并投影 `sessionId`，未关联的声明成员投影为空。旧 record 结构、声明的 `isLead`、`phase` 和 `deployMode` 不变。旧数据已有非空 Session 字段时，初始接入必须显式提供对应完整身份。`completeTeam` 仍只将原 phase 改为 `Completed`，保留关系及接入标记。成功调用 `sessionMembership(...)` 后，当前 client 启用严格完成 CAS，包括尚未接入的 Team；新实例观察到接入标记时也使用严格 CAS。持续竞争时报冲突，避免覆盖其他写入方。未启用 client 对未接入 Team 保留旧完成 fallback，包括版本存储。

接入 Team 前，先停止该 Team 的旧版本及未启用写入方，等待其在途写操作结束，再升级所有参与写入的实例，并在每个 client 上调用 `sessionMembership(...)` 启用保护。启用 client 不会停止已经进入旧路径的写操作。旧二进制不理解归属投影，其无条件完成 fallback 可能清掉并发接入标记。已接入 Team 不支持新旧版本混写。回滚需使用理解现有存储格式的版本，或停止全部写入方后恢复接入前的备份。

关系 schema 1 可以读取早期包含 `source: "BYO"` 的记录，以及曾省略该字段的草案记录。缺省来源只表示手工提供的既有资源，不授予删除权。新关系写入在内部存储格式中保留 BYO，与公开 `Membership` record 分开。未知来源、字段或格式版本明确拒绝，不重置或覆盖原记录。

Team 元数据、phase 和声明定义继续使用原记录；每个关系域、每个 owner 的单条记录只保存关联和接入回执，读写成本与竞争随该 owner 的关联数增加。已接入记录的所有写入者须遵守版本能力；不支持直接原始修改或删除。损坏或未知关系格式会报错，不重置为空。Session 存在校验与关系提交是独立操作，外部删除 Session 可能留下关联，应用须显式解除。

解除保留 Agent 定义、Session 状态和 workspace，不停止运行、撤销工具权限、更新已构造的静态 `TeamContext` 或注销 `bindSession` 通知。任务板、mailbox 和静态 `teamsMode` 保留原运行语义。该能力不创建模型、Worker、邀请、调度或 Service Team/Run 资源。

## 状态怎么流转

状态分三层，框架自动在层之间搬数据。

- **调用内状态** —— `AgentState`（对话上下文、权限规则、Plan Mode 状态、工具状态）加上 `RuntimeContext`（`sessionId`、`userId`、沙箱句柄、extra）。
- **跨调用状态** —— 每次 `call()` 结束自动写盘、下次自动加载：存在 `AgentStateStore`（默认 `~/.agentscope/state/<agentId>/`，按 `(userId, sessionId)` 寻址）里的 `AgentState` 运行时快照、`sessions/<sessionId>.log.jsonl` 的永不压缩对话日志、子任务记录、沙箱元数据。
- **长期记忆** —— 跨 session 累积：`memory/YYYY-MM-DD.md` 只追加；后台节流任务把它周期合并到 `MEMORY.md`；`MEMORY.md` 每轮推理被注入 system prompt。

三个值得记住的规律：

- system prompt 每轮重新拼，所以你改 `AGENTS.md` 或 `MEMORY.md` 立刻生效，不需要重启。
- 压缩、记忆提炼、后台维护都被节流闸门管着，不会每轮都跑。
- `AgentState` 由 core 的 `ReActAgent` + `AgentStateStore` 自动持久化。Harness 不再额外做这件事。

## 自己加 middleware 时要注意什么

要在不绕过 Harness 内置链路的前提下插入自定义行为：

- 用 `.middleware(...)`：你的 middleware 会跑在所有 Harness 内置之前。
- 通过 agent 上的 `RuntimeContext` 读当前调用的身份（`userId` / `sessionId`）。
- 读写工作区用 `harnessAgent.getWorkspaceManager()`，它会按当前文件系统模式（本机 / 沙箱 / 远端）正确路由。直接 `java.nio.Files` 在沙箱或远端模式下会写错地方。

## 相关文档

- [工作区](/v2/zh/docs/harness/workspace) — 目录结构、注入到 system prompt 的内容、`tools.json`
- [上下文与 AgentState](/v2/zh/docs/building-blocks/context) — `AgentState`、`RuntimeContext`、`AgentStateStore` 持久化、多用户隔离
- [记忆](/v2/zh/docs/harness/memory) — 两层记忆
- [上下文压缩](/v2/zh/docs/harness/compaction) — 摘要压缩、大结果卸载、溢出兜底
- [文件系统](/v2/zh/docs/harness/filesystem) — 本机 + shell / 共享存储 / 沙箱
- [沙箱](/v2/zh/docs/harness/sandbox) — 隔离执行、跨调用恢复、分布式
- [子 Agent](/v2/zh/docs/harness/subagent) — 声明、同步/后台、流式转发
- [技能](/v2/zh/docs/harness/skill) — 四层合成、自学习闭环
- [计划模式](/v2/zh/docs/harness/plan-mode) — 只读阶段 + HITL 退出
- [Channel](/v2/zh/docs/harness/channel) — 会话管理、多 agent 路由、流式 SSE
