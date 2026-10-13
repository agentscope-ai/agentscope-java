---
title: "压缩工具历史并恢复归档"
---

长任务会积累大量工具调用和结果。`JevContextCompactor` 接入 Harness 的压缩入口，对历史工具材料提出保留、截短或成对移除决策；采用前保存归档。适合文件读取、检索等可重复阅读但不应重新执行的历史材料。

## 示例场景：保留当前测试证据，移开旧文件读取内容

修复任务已经读过一份过时文件，又取得当前测试失败信息；用户还要求“不要修改生成文件”。如果只按长度截掉旧消息，可能删掉关键约束，或只留下工具调用而丢掉对应结果。

应用先决定哪些工具历史可以处理，并固定必须保留的消息。JEV 在这些边界内判断旧材料是否仍有用，按调用与结果成对处理；采用候选前把当前历史归档，后续可读取恢复而不重放工具。

## 1. 定义可处理历史与保护范围

需要 `agentscope-extensions-jev` 与 `agentscope-harness` 同版本依赖。JEV 模块的 Harness 依赖为 optional，仅使用客户端或 Judge 的应用不必引入 Harness。

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextCompactor;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.Set;

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
    (ctx, record) -> System.out.println(record));
var strategy = new JevContextCompactor(client::systemOne, limits, execution, archive);

```

本例只允许处理 `read_file` 和 `search` 的历史，最近 6 条消息保留；最低缩减比例 10% 避免花一次判断只省很少内容。`truncateHeadChars=300` 是采用截短建议时保留的结果前缀，不代表自动生成摘要。所有 token 限额都是估计值，最终派发仍由 Harness 检查预算。

## 2. 接到 Harness 现有压缩入口

```java
var agent = HarnessAgent.builder()
    .name("research-agent")
    .model(model)
    .compaction(CompactionConfig.builder().strategy(strategy).build())
    .build();
```

`Config.defaults()` 的 eligibleTools 为空；单参数 `new JevContextCompactor(client)` 默认为 OFF。ENFORCE 构造时必须提供归档接口，运行上下文必须有 userId、sessionId，Harness 提供 agentId。工具是否可处理由宿主规则确定，不能由 JEV 判断工具是否只读或是否获授权。

宿主可以用 `jev.context.pinned=true` 的消息 metadata 或 `pinnedMessageIds` 额外保护内容。首条、最近消息、SYSTEM、包含 ThinkingBlock 的消息、错误/拒绝/挂起/未完成结果、非文本工具输出默认不处理。ThinkingBlock 不发送给 JEV；其他不支持的消息类型使本次语义压缩放弃。规则优先于模型分数。

## 3. 查看建议并决定是否采用

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

采用压缩前必须成功归档。文件与 BaseStore 两种实现、作用域和恢复代码见[持久归档与材料恢复](/v2/zh/jev/guides/context-archive)。恢复只读取已存消息，不重放工具。

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

Service 归档使用 `SharedWorkspacePaths.resolveSessionDataPath` 下的 `jev-context`，不接受用户控制的存储路径；记录复用 `jev.decision` 和现有 run/session 关联。ENFORCE 不借压缩触发长期记忆提取；应用的其他记忆策略仍独立配置。多副本运行时需要使用宿主共享的持久存储和一致的作用域。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后运行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextCompactionExample
```

案例使用真实 HarnessAgent、脚本模型和合成 JEV 响应：旧文件读取成对归档，当前测试证据及“不能修改生成文件”约束保留，模型调用一次，再恢复 6 条原始消息。显式使用 `InMemoryAgentStateStore`，重复运行不复用默认持久会话，不重新派发历史工具。

[完整源码](/examples/jev/source/JevContextCompactionExample.java.txt)。该例使用合成 JEV 判断，验证材料保护和归档恢复；实际压缩率、下游答案质量及模型耗时需在自己的轨迹上对照。
