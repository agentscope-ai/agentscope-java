---
title: "语义上下文压缩：Harness 接入、归档与恢复"
---

R2 参考 [fast-jev-compaction 固定提交 e3f262a](https://github.com/tamaratran/fast-jev-compaction/tree/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0)，将已有的成对建议 API 扩展为 Harness 实际压缩策略。JEV 判断哪些历史工具材料还需要；持久归档、消息配对、最终预算及状态提交由确定性代码完成。长期记忆准入独立，不从压缩结果推导事实写入许可。

## 源码依据与适配差异

| 参考源码 | 保留机制 | AgentScope 的适配 |
| --- | --- | --- |
| [state.ts](https://github.com/tamaratran/fast-jev-compaction/blob/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0/src/state.ts) | 按调用 ID 配对；首条和近期消息固定；状态省略工具结果全文，输入按 1000/200/60 字符逐步缩短 | 重复 ID、乱序/孤立结果拒绝压缩；未完成调用保留。用户及助手文本在判断状态中也不删除；三档输入缩短仍超限则回退，未移植原项目后续的旧文本省略和合并阶段 |
| [compact.ts](https://github.com/tamaratran/fast-jev-compaction/blob/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0/src/compact.ts) | 每个调用两道 Noul，分别问调用记录和完整结果；状态重复、题目分批；保留/截短结果/成对删除 | 分批串行、单次总预算、所有批次成功后才产生候选；增加弃权区间，阈值未校准时使用 SHADOW；不复制其单一 0.5 阈值 |
| 同上 `applyDecisions` | 非工具文本原文保留；调用和结果成对删除；不改写为摘要 | 仅显式 eligibleTools 的成功纯文本结果参与；错误、挂起、非文本结果及宿主固定项保留。截短保留工具状态、ID、元数据和执行详情 |
| [hooks/fast-jev.ts](https://github.com/tamaratran/fast-jev-compaction/blob/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0/hooks/fast-jev.ts) | 接入宿主压缩入口，按效果决定是否采用 | 在 `ConversationCompactor` 中通过 `CompactionConfig.strategy` 接入，复用 `HarnessContextBuilder` 的最终预算、配对和并发检查，不额外重放 Agent |
| [回归测试](https://github.com/tamaratran/fast-jev-compaction/blob/e3f262a7f4d42bd8dd32ced30d26176f7cb545b0/tests/fast-jev-compaction.test.ts) | 固定项、成对删除、短结果、分批、失败 | 补充归档失败、取消、跨会话、最终预算拒绝、并发历史变更和真实 Harness 运行测试 |

归档及恢复是 AgentScope 的适配能力，参考项目没有提供这里的持久恢复协议。不能照搬其“重新执行工具获取结果”的提示：本实现只恢复已存材料，绝不为恢复而重放写工具。借鉴代码随 JAR 附带 MIT 声明。

## 基本 API

需要 `agentscope-extensions-jev` 与 `agentscope-harness` 同版本依赖。JEV 模块的 Harness 依赖为 optional，仅使用客户端或 Judge 的应用不必引入 Harness。

```java
var archive = new FileJevContextArchive(archiveRoot, 20_000_000);
var limits = new JevContextCompactor.Config(
    0.2, 0.8,         // discardThreshold / keepThreshold：仅为演示，需校准
    6,                // preserveRecentMessages
    25_000, 30_000,    // maxStateTokens / maxRequestTokens：估计值
    128,              // maxQuestions：每对需要两道题
    300,              // truncateHeadChars
    0.10,             // minimumReduction：序列化字符数缩减比例
    5_000_000,        // maxTranscriptChars
    Set.of("read_file", "search"), // 由宿主确定可处理的工具
    Set.of());        // 固定消息 ID
var execution = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(3), "context-v1",
    (ctx, record) -> persistDecisionMetadata(ctx, record));
var strategy = new JevContextCompactor(client::systemOne, limits, execution, archive);

var agent = HarnessAgent.builder()
    .name("research-agent")
    .model(model)
    .compaction(CompactionConfig.builder().strategy(strategy).build())
    .build();
```

`Config.defaults()` 的 eligibleTools 为空；单参数 `new JevContextCompactor(client)` 默认为 OFF。ENFORCE 构造时必须提供归档接口，运行上下文必须有 userId、sessionId，Harness 提供 agentId。工具是否可处理由宿主规则确定，不能由 JEV 判断工具是否只读或是否获授权。

宿主可以用 `jev.context.pinned=true` 的消息 metadata 或 `pinnedMessageIds` 额外保护内容。首条、最近消息、SYSTEM、包含 ThinkingBlock 的消息、错误/拒绝/挂起/未完成结果、非文本工具输出默认不处理。ThinkingBlock 不发送给 JEV；其他不支持的消息类型使本次语义压缩放弃。规则优先于模型分数。

## 决策与模式

每对工具调用分别得到 keepCall / keepResult：

- 两者均不高于 discardThreshold：DROP_PAIR，调用和结果一起离开当前上下文。
- keepResult 不高于 discardThreshold，且 keepCall 不低于 keepThreshold：TRUNCATE_RESULT，保留调用与结果前缀；短结果不截短。
- keepResult 不低于 keepThreshold：KEEP。
- 其他情况：UNCERTAIN，保留整对，不将不确定解释为可删除。

`plan(request)` 返回 `JevExecution.Decision<Plan>`；Plan 有候选消息、每对动作、Scores、请求数、已知 token、状态适配阶段及归档引用。固定项的 Scores 为确定性占位值（reason=PINNED），不是模型概率。整体 `INSUFFICIENT_REDUCTION` 可以带每对判断，表示采用压缩不划算，不表示每个判断都失败。

| 模式 | 模型判断 | 归档 | 后续流程 |
| --- | --- | --- | --- |
| OFF | 无 | 无 | 沿用标准压缩流程 |
| SHADOW | 有候选时判断 | 无 | 只记录建议，标准压缩流程输入不变 |
| ENFORCE | 有候选时判断 | 采用前必须成功 | 成功产生候选；错误、不确定导致无足够缩减、超限或归档失败则保留当前历史，不额外调用摘要模型 |

策略只在现有压缩触发条件满足时运行。标准轻量裁剪、工具结果卸载等已有机制有各自的开关；本策略不接管它们。最终请求仍要经过 Harness 预算检查，超预算会停止本次模型派发，不因 JEV 判断成功而放行。

分组共享总预算；缺题、错类型、非法概率、空响应或任一批失败均不应用部分结果。取消向后端传播，不产生后续状态提交。存储 I/O 已启动时可能完成一份未采用的归档；宿主管理保留期和清理。不能据取消声明远端不再收费。

## 归档与恢复

`JevContextArchive` 是宿主授权的存储接口，有两个实现：

- `FileJevContextArchive(root, maxBytes)`：按 user/agent/session 的哈希划分目录，内容哈希引用、原子发布和恢复校验；支持 POSIX 的文件系统使用私有目录/文件权限。
- `StoreJevContextArchive(baseStore, maxBytes)`：复用 Harness 的 `BaseStore` 命名空间和 `putIfVersion(..., 0)` 创建语义；不支持 CAS 且没有相同已有快照时拒绝归档。内存 Store 仅适合测试；生产使用持久后端。

归档记录包含该压缩边界的原始消息快照，包括消息 ID、时间、工具参数、结果及元数据。因此宿主需要按原会话数据管理访问、保留期和存储保护。JEV 请求和观察事件不包含这些元数据。

```java
var scope = new JevContextArchive.Scope(userId, agentId, sessionId);
List<Msg> previous = archive.restore(scope, archiveReference).block();
// 查看或按宿主明确策略恢复需要的材料；不要覆盖此后新增的活动历史。
```

引用来自 Plan 或 `context_compaction` 决策记录。恢复时必须使用认证后的原作用域；不知道作用域或归档已损坏时失败，不能接受模型提供的任意文件路径。这个 API 返回旧快照供宿主使用，不直接覆盖 AgentState。

若在本策略之前已有工具卸载或摘要，快照保存的是当前边界收到的消息，不能声称恢复了更早已被其他组件改变的原始会话。完整历史仍以宿主原有 transcript/offload 记录为准。

## Service 配置

```json
{
  "jev": {
    "compaction": {
      "mode": "SHADOW",
      "version": "context-v1",
      "budgetMillis": 3000,
      "threshold": 0.8,
      "rejectionThreshold": 0.2,
      "eligibleTools": ["read_file", "search"],
      "preserveRecentMessages": 6,
      "maxStateTokens": 25000,
      "maxRequestTokens": 30000,
      "maxQuestions": 128,
      "truncateHeadChars": 300,
      "minimumReduction": 0.1
    }
  }
}
```

`HarnessAgentBuildService` 在现有 Agent 构建链装配策略；配置纳入现有 Session 构建身份。默认 OFF，不创建客户端或归档。启用必须显式提供两个阈值；密钥、endpoint、archivePath 等覆盖字段拒绝。预算最多 30 秒，状态/请求预算最多 25k/30k。

Service 归档使用 `SharedWorkspacePaths.resolveSessionDataPath` 下的 `jev-context`，不接受用户控制的存储路径；记录复用 `jev.decision` 和现有 run/session 关联。ENFORCE 不借压缩触发长期记忆提取；应用的其他记忆策略仍独立配置。完整部署、网关、持久事件和多副本恢复尚未验证。

## 可运行离线案例

按[轨迹案例构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)安装当前模块并生成 classpath，然后运行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevContextCompactionExample
```

案例使用真实 HarnessAgent、脚本模型和合成 JEV 响应：旧文件读取成对归档，当前测试证据及“不能修改生成文件”约束保留，模型调用一次，再恢复 6 条原始消息。显式使用 `InMemoryAgentStateStore`，重复运行不复用默认持久会话，不重新派发历史工具。

工程测试覆盖固定保护、混合消息文本、成对操作、未知/失败/非法响应、分批失败的原子性、超时、取消、归档失败、作用域隔离、损坏检测、预算拒绝及并发历史冲突。验证状态与真实效果见[压缩对照记录](/v2/zh/jev/guides/context-benchmark)。

## 研发验收记录

2026-09-26，`/Users/ken/agentscope-3/agentscope-java` 的 `harness-context-redesign` 分支：

- JEV **146 项**、Harness 压缩与上下文 **26 项**、Service 相关 **26 项**测试通过；包含本项新增 16 项扩展行为测试和 3 项 Service 配置/记录测试。
- 命令：`mvn -o -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-service/service-dataplane -am install -Dtest='Jev*Test,CompactionMiddlewareTest,ConversationCompactorTest,ContextPipelineV2Test,HarnessAgentBuildServiceCacheKeyTest,ToolConfirmationMiddlewareTest' -Dsurefire.failIfNoSpecifiedTests=false`，相关 21 个模块构建及本地安装成功。
- 正式文档 458 页与 479 条重定向的导航/链接检查及站点构建校验通过。
- 固定合成样本已完成两种真实后端对照；工程边界通过不等于 JEV 语义压缩质量已达生产标准。
- 尚未验证 Service 完整部署、跨副本恢复、真实长任务效果和业务阈值校准；未自动开启任何生产配置。

R2 的源码适配及研发验收已完成，下一项是 Foreman 长任务监督。
