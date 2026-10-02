---
title: "模型与显式阶段路由：API、用量与 Service"
---

基于 Jevonian 固定提交 [75e980a](https://github.com/xinyao27/jevonian/tree/75e980a8b05cf931b3f7f0de364fea905ab4ca01) 的候选预筛、route 有序模型池、显式阶段、同次 effort 判断及用量记录机制，使用 AgentScope 现有模型和中间件接口独立实现。路由由应用明确开启，默认 OFF；未经校准先 SHADOW。

原有 JevModelRouterMiddleware 类名、builder 和调用级接口保留。新增 `.phaseRouting(...)` 配置由 JevPhaseRouting 管理模式、预算、目录和观察器，与旧 `.choice(...)` 互斥。旧的单模型候选接口仍见[模型路由 API](/v2/zh/jev/guides/model-routing-api)。没有改变核心 Model 接口。

## API 与宿主目录

依赖当前 BOM 的 `io.agentscope:agentscope-extensions-jev`。以下省略 imports，routing 包中的 Route、Candidate、Snapshot、Source、Effort、Quota、Price 是 JevRouteCatalog 的嵌套类型：

```java
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "routes-v1",
    (ctx, record) -> recordDecision(ctx, record));
var policy = new JevPhaseRouting(
    client::systemOne,
    List.of(
        new Route("plan", "设计与复杂推理", List.of("strong", "fast")),
        new Route("execute", "已明确步骤的执行", List.of("fast", "strong"))),
    Set.of("strong", "fast"), 0.8, true, options,
    (ctx, record) -> recordModelCall(ctx, record));
var middleware = JevModelRouterMiddleware.builder(client)
    .phaseRouting(policy).build();

var context = RuntimeContext.builder().userId(userId).sessionId(sessionId)
    .put(Source.class, new Source((ctx, input) -> directory.snapshot(ctx, input)))
    .build();
```

client、recordDecision、recordModelCall、directory、userId 和 sessionId 为宿主依赖。将 middleware 注册到 ReActAgent / HarnessAgent 构建链，再使用该运行上下文。模型对象和密钥在宿主解析，JEV 只能选择配置内的 route ID。

目录返回不可变 Snapshot：候选列表、实际模型输入 token 估计、输出预留、可选 minimumEffort、可选 pinnedModel、连续失败计数。连续失败是宿主信号，不通过匹配工具输出里的单词猜测。目录每次调用必须按当前用户/会话提供授权、可用模型；不能把未经授权的会话字段直接当作目录。模型绑定在一次调用内需保持对象身份稳定，配置换绑会触发原模型回退。

```java
var candidate = new Candidate(
    "strong", registeredModel, "复杂推理并支持工具调用",
    64000L, 8000, true,
    Set.of(Effort.LOW, Effort.HIGH), Quota.AVAILABLE,
    null); // 没有定价数据，成本保持未知
var snapshot = new Snapshot(
    List.of(candidate), estimatedInputTokens, outputReserve,
    Effort.HIGH, null, consecutiveFailures);
```

候选最多 128 个、route 最多 64 个、每条 route 最多 64 个不重复模型 ID。可用性以 AVAILABLE / LOW / EXHAUSTED / UNKNOWN 表达；EXHAUSTED 排除，LOW 和 UNKNOWN 保留并交给判断参考。工具需求先按实际 ModelCallInput.tools 检查，支持工具必须明确声明。

上下文容量未知时排除候选；输入估计加输出预留不能超过容量，输出上限必须容纳预留、maxTokens 和 maxCompletionTokens 的最大值。这里采用宿主估计和显式预留；参考实现是估算后保留 10% 空间、未知容量仍接纳，两者不同。本实现当前只路由文本与文本工具结果；图片/其他非文本内容保留原模型。

## 选择、思考等级与回退

首次 onModelCall 才读取实际输入并选择；同一 Agent 调用后续不再次问 JEV。每次实际派发前重新读取目录并检查容量、工具能力、配额、绑定、固定模型要求和最低思考等级。失效后整个调用持续使用原模型，恢复目录也不在同一工具循环中重选。

选择优先级：

1. GenerateOptions 已显式指定 modelName、baseUrl、endpointPath 或 apiKey 时保留整个原请求，避免把其他模型的请求级覆盖带给被选模型。
2. Snapshot.pinnedModel 指定且符合约束时直接使用，不问 JEV。
3. 显式阶段匹配配置 route，使用该 route 中第一个合格模型，不问 JEV。
4. 自动模式只在合格 route 中选择；同次请求可以选择 effort，模型顺序仍由配置负责。

明确 none、低置信度、非法响应、空目录、目录失败、总预算耗尽均回退原模型。没有合格候选不请求 JEV。Report 区分 route/model、应用的 effort、建议的 suggestedEffort、概率/置信度、排除原因、判断模型与用量。不要把语义弃权理解为业务没有答案，也不要把低置信度选择当作已执行。

思考等级为 NONE / MINIMAL / LOW / MEDIUM / HIGH / XHIGH / MAX / ULTRA。宿主声明哪些等级能通过实际模型适配器执行。客户端已有 reasoningEffort 保持原值；候选不支持时不接管。模型选择的等级按照声明的等级向上匹配，超出最深等级时使用其最深值，同时保留建议与实际配置差异；minimumEffort 是确定性下限，不能被降低。只有一个可选等级时直接应用，不再增加无意义的 Choice。若 route 或 effort 任一需要的判断不确定，整体使用原模型。

## 显式阶段

```java
var stages = new JevStageRouter(middleware);
var phase = stages.begin(parentContext, "plan", "分析设计和风险").block();
agent.streamEvents(messages, phase.context());
```

也可将 phase 和 ModelCallInput 交给 `stages.onModelCall(...)`，由应用提供实际下游调用函数。begin 创建新的路由上下文，当前先进阶策略在首次模型调用时读取目录；goal 用于旧接口兼容，实际判断以模型输入中的用户消息为依据。旧配置没有 phaseRouting 时继续走原有语义模型选择。阶段名称必须在 route 配置中存在，未知阶段回退原模型；阶段切换只能由应用发起，不根据中间工具结果自行切换。

新阶段共享宿主传入的业务上下文引用，只重置路由范围；不隐式复制业务状态或重新执行旧阶段。正常 Agent 流程使用订阅范围隔离，两个并发调用不会互相覆盖路由缓存。显式 Phase handle 应按一次阶段执行使用，取消后不得复用。

## 预算、观测与用量

配置预算覆盖一个模型 hook 的路由工作：首次目录读取、判断及派发前复核共用剩余预算；后续模型调用仅做有界目录复核。生成模型耗时不计入路由判断预算。取消关闭当前路由范围、取消在途读取/判断，并停止后续派发；不保证远端停止计费。生成模型错误沿原执行链传播，不重新选择模型、不重跑 Agent 或工具。

OFF 直接委托原输入，不读目录、不判断。SHADOW 记录建议，实际 ModelCallInput 对象、模型和 options 保持原样。观察器异常不影响 Agent。JevExecution 记录 phase-routing 的版本、状态、额外耗时及判断用量；CallRecord 则记录每次派发模型名、传给 SDK 的 reasoningEffort、完成/失败/取消、耗时、ModelCallEndEvent 的 ChatUsage。

CallRecord.dispatchedModel 是框架配置的 Model.getModelName，requestedEffort 是传入模型适配器的值；不是供应商响应确认的实际模型快照或最终 wire 参数。quality 中间件额外草稿修订等未经过此 hook 的调用也不自动包含在其用量里。

可由宿主提供 Price(version,input,output,cached,creation)，单位为 USD / 百万 token。估算按 SDK 的 cached / cacheCreation 为 input 子集计算，输出独立计费；缺价格、缺用量、非法计数或溢出时成本为 null。价格版本与估算分开保存，不用零冒充未知，不把合成案例价格当真实资费。不内建跨会话缓存收益推断或配额探测。

## AgentScope Service

使用现有 overrides 配置：

```json
{
  "jev": {
    "routing": {
      "mode": "SHADOW",
      "version": "routes-v1",
      "budgetMillis": 2000,
      "threshold": 0.8,
      "picksEffort": true,
      "routes": [
        {"id": "plan", "description": "设计与复杂推理", "models": ["strong", "fast"]},
        {"id": "execute", "description": "按已定步骤执行", "models": ["fast", "strong"]}
      ]
    }
  }
}
```

模型 ID 必须位于现有 Service 运维 allowlist。包含 routes 时启用新装配路径，旧 models 数组接口继续兼容，两者不能混用。开启时要求显式 threshold，预算默认 2 秒、上限 30 秒；拒绝未知字段、重复 route 和越权模型，Agent 配置不能放入密钥或 endpoint。配置参与原有构建缓存，改 OFF 会重建并恢复原模型。

宿主在每个运行上下文注入 Source，提供当前注册模型对象、能力、估计与配额；未提供时记录错误并使用原模型。本次没有接入实际生产模型目录或配额服务。既有 TraceSink 按运行/会话关联 phase-routing 与 routing-call 元数据，不保存完整用户请求。SDK 原始 Report 可供应用显式读取；同一个 RuntimeContext 被并发复用时不要用 `policy.report(ctx)` 作为每次调用的审计归属，应使用调用观测接口并提供独立运行上下文。

## 离线案例与验收

按[构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)准备 classpath，在项目根目录运行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevPhaseRoutingExample
```

无需密钥。真实 ReActAgent 依次执行 auto、plan、execute 三次独立调用；每次一次只读工具、两次模型调用。实际结果：judgeRequests=1、strongCalls=4、fastCalls=2、originalCalls=0、toolCalls=3、usageRecords=6。模型与 JEV 返回均为脚本，只证明路由/执行一致性。

2026-09-26 验收：累计 JEV 227 项、Harness 26 项、Service 41 项通过，21 个相关模块 install 成功。R6 17 项扩展测试覆盖候选能力、空候选、显式阶段/固定模型、两种模式、none/低置信度/非法响应/错误、超时、取消、调用隔离、候选换绑失效、客户思考等级、预算、执行错误不重试、真实 Agent 和成本未知。Service 另验证配置/allowlist、元数据、实际构建/缓存与 OFF 恢复。概率保留与剩余预算细节修改后，17 项专项回归及模块 install 再次通过。

[固定输入对照](/v2/zh/jev/guides/phase-routing-benchmark)单列路由标签和耗时，不代表真实生成模型任务质量、节省成本、缓存收益或生产 SLA；这些仍待独立验证。代码在项目主目录当前分支，未部署 Service。

## 源码依据与适配差异

| 固定源码与测试 | 对应机制 | AgentScope 适配 |
| --- | --- | --- |
| routing.ts / routing.test.ts | route 内有序模型池、显式阶段与固定模型优先、候选资格 | 一次调用选择一次，显式边界才可重选；失效持续回退原模型 |
| capabilities.ts / capability-routing.test.ts | context/effort 先过滤、思考等级匹配 | 宿主真实输入估计与输出预留；未知窗口保守排除；客户 options 优先 |
| brain.ts / brain.test.ts | 同次 route / effort 问题及完整分布 | 统一 JevClient 契约；低置信度弃权，不据此发起第二后端判断 |
| quota-routing.test.ts | 运行前按配额状态限定候选 | 使用宿主快照，不内建账户查询；全部耗尽仍回退原模型 |
| ledger.ts / effort-ledger.test.ts | 判断与实际执行记录、用量/成本未知 | 复用事件与 TraceSink；框架派发选项不冒充 wire 确认或账单 |

固定源码在所有判断通道失败后已有启发式路线，与其 README 描述的直接 502 不同。本项目继续保留原模型；不会照搬代理的供应商重试、自动压缩后再路由或会话指纹逻辑。参考代码采用 AGPL-3.0，本文按代码行为做设计对照，模块实现基于 AgentScope 接口独立编写。

源码：[目录契约](/examples/jev/source/JevRouteCatalog.java.txt)、[阶段策略](/examples/jev/source/JevPhaseRouting.java.txt)、[原中间件入口](/examples/jev/source/JevModelRouterMiddleware.java.txt)、[离线案例](/examples/jev/source/JevPhaseRoutingExample.java.txt)。
