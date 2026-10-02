---
title: "长任务监督：持续观察、验证证据与建议"
---

R3 参考 [Foreman 固定提交 ba91849](https://github.com/thruwire/foreman/tree/ba91849e7088f072db491c739e4c81fd9a4c8154)，在 AgentScope 的事件流中增加独立、有限的观察循环。监督结果只有建议：不终止任务、不重放工具、不自行执行验证、不宣布交付完成。已有 `application.JevSupervisor` 的显式调用 API 保留兼容；持续观察使用本页的新组件。

## 源码依据和适配边界

| 参考实现 | 保留的机制 | AgentScope 适配 |
| --- | --- | --- |
| [observation.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/observation.py) | 有界任务、输出尾部、变更、仓库约束、验证与近期事件 | 事件由 Middleware 捕获；产物及验证由宿主提供，不自动扫描用户目录或执行 Git |
| [foreman/jev.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/foreman/jev.py) 与 [检查定义](https://github.com/thruwire/foreman/tree/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/responsibilities/definitions) | 每项职责有独立 Noul，单请求批量评估；没有全局完成概率 | 保留 10 个固定检查和可选文档检查；定义在 `checks()` 复用，按宿主明确的 documentationRequired 开启文档检查，不增加语义路由请求 |
| [builtin.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/responsibilities/builtin.py)、[policy.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/policy.py) | 每职责提出建议；按优先级、置信度、稳定注册顺序仲裁；运行中的 worker 才产生方向干预 | 转为无执行权的 Advice；缺少当前版本验证时优先请求验证。进展分数只作观察，不能因其不确定而阻止完成复核 |
| [runtime.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/src/foreman/runtime.py) | 独立观察、合并事件、最小间隔、周期检查、终止边界绕过去抖、连续故障计数 | 每次订阅独立状态；只对整体正常结束绕过去抖。合并为至多一个待处理 tick，不排队保存完整轨迹；取消与 Agent 调用绑定 |
| [test_policy.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/tests/test_policy.py)、[test_runtime.py](https://github.com/thruwire/foreman/blob/ba91849e7088f072db491c739e4c81fd9a4c8154/tests/test_runtime.py) | 人工优先、文档缺失、验证、失败容忍与取消测试 | 增加不修改事件、不重放、并发隔离、过期观察、当前验证版本和实际 Service 构建测试 |

Foreman 会启动、停止或重试 worker，并有终止、冷却期和委派预算。本项没有接管这些行为，也没有移植其 CLI、模型 worker 进程、配置插件注册、跨进程恢复和团队调度。原项目使用 verification_completed 参与完成判断；这里增加通过状态、产物版本及作用域验证，是明确的 AgentScope 适配。参考来源为 MIT，许可证已随模块发布。

## 基本 API

依赖 `io.agentscope:agentscope-extensions-jev`，版本随当前项目 BOM。新增类位于 `io.agentscope.extensions.judge.jev.supervision`：`JevTaskSupervisor`、`JevSupervisionMiddleware`、`SupervisionEvidence`。不修改核心 Model 接口。

```java
var client = JevClient.builder().build();
var supervisor = new JevTaskSupervisor(
    client::systemOne,
    new JevTaskSupervisor.Thresholds(0.2, 0.8), // 演示值，未校准
    JevTaskSupervisor.Limits.defaults(),
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(3), "supervision-v1",
        (ctx, record) -> recordExecutionMetadata(ctx, record)));
var middleware = new JevSupervisionMiddleware(
    supervisor,
    JevSupervisionMiddleware.Schedule.defaults(),
    (ctx, observation) -> recordAdvice(ctx, observation));

var agent = HarnessAgent.builder()
    .name("engineering-assistant")
    .model(model)
    .middleware(middleware)
    .build();
```

示例中的 recordExecutionMetadata/recordAdvice 是宿主非阻塞记录函数；model 是宿主已配置模型。中间件也可以注册到 ReActAgent。OFF 不捕获或调用模型；SHADOW 保持输入、工具执行与输出事件原样；ENFORCE 构造时拒绝。Service 缺省为 OFF，API 构造要求显式指定模式。

也可独立调用 `supervisor.observe(ctx, () -> Mono.just(snapshot))`，使用完全相同的检查定义与仲裁逻辑进行离线轨迹观察。Snapshot 包含 Scope、观察序号、任务、输出尾部、近期事件、运行/失败状态、截断标志、耗时和宿主证据。证据获取与模型请求共享一次 JevExecution 预算。

## 检查、建议与状态

检查按职责命名：

| 职责 | 检查 |
| --- | --- |
| core.completion | implementation_complete、requirements_satisfied、ready_to_finish |
| core.verification | tests_sufficient、needs_verification |
| core.worker-health | meaningful_progress、worker_stuck、work_off_track |
| repository.instructions | agents_md_drift |
| core.human-escalation | needs_human |
| quality.documentation | documentation_sufficient，仅在明确要求文档时启用 |

每项返回原始概率及 YES / NO / UNKNOWN；UNKNOWN 不是否定。0.2 / 0.8 只用于本轮离线案例和对照，不作为已校准生产阈值。仅影响当前决策分支的不确定项触发人工复核，信息性的 meaningful_progress 独立保留。

`Decision<Report>` 沿用 DECIDED / INCONCLUSIVE / ERROR / SKIPPED。Report 保留所有 Findings、全部 Proposal、选中建议、截断标志、验证状态、后端版本与用量。DECIDED 表示建议能够确定，不能解释为全部检查通过。错误或超限可以没有 Report。

建议仲裁顺序为：人工需求 → 运行异常 → 活动 worker 的方向问题 → 证据不完整 → 当前验证缺失/不足 → 必需文档不完整 → 完成复核 → 与当前分支相关的不确定 → 继续工作。相同优先级比较概率，再按稳定顺序选择；仓库约束在相同置信度的健康问题之前。

返回 CONTINUE、REQUEST_VERIFICATION、REVIEW_COMPLETION、REVIEW_DOCUMENTATION、REVIEW_DIRECTION 或 MANUAL_REVIEW。它们都不附带执行权。即使 REVIEW_COMPLETION，也不更改 Agent 或业务任务的完成状态。离线 Snapshot 的 workerFailed，以及事件流中的迭代上限、请求停止、全部工具被拒绝，会阻止完成复核建议；异常退出原样传播。

## 可信验证证据

宿主可在每次调用的 RuntimeContext 注册 `JevSupervisionMiddleware.EvidenceSource`。回调收到当前 context、Scope、观察序号和 workerActive，返回不可变 `SupervisionEvidence`。Scope 中 runId 是本次观察调用生成的 UUID，避免复用之前调用的验证收据。

```java
var source = new JevSupervisionMiddleware.EvidenceSource(request ->
    evidenceRepository.readAuthorizedSnapshot(
        request.context(), request.scope(), request.sequence()));
var context = RuntimeContext.builder()
    .userId(userId)
    .sessionId(sessionId)
    .put(JevSupervisionMiddleware.EvidenceSource.class, source)
    .build();
```

`evidenceRepository.readAuthorizedSnapshot` 是宿主自己的实现，返回 `Mono<SupervisionEvidence>`。仓库、工单、检索或其他应用均可以提供有版本的产物和验证证据，框架不限定业务领域。

Evidence 包含 revision、repositoryStatus、diff、changedFiles、instructions、documentationRequired 与可空 Verification。Verification 包含 userId、sessionId、runId、revision、source、passed、summary。只有 passed=true、source 非空、版本与作用域全部匹配才是当前有效验证。

revision 必须代表完整待交付产物，包括未提交修改；只用未变化的 Git HEAD 不够。宿主负责证据快照一致性、ACL、内容脱敏和验证来源真实性。不能把工具或 worker 声称“测试通过”直接包装成可信收据。中间件不替宿主执行测试；没有 EvidenceSource 时证据明确缺失，无法得到完成复核建议。

请求包含宿主明确提供的任务、可见输出、变更和验证摘要。不会自动包含模型思考、事件 metadata、工具参数或工具结果正文。工具结果用于验证时应由宿主从可信执行记录构造证据。

## 预算、并发与取消

默认最小间隔 5 秒，周期 30 秒，每次调用最多 60 次评估，连续错误最多 3 次。新事件只使观察变脏；密集输出不会逐条发模型请求。没有新事件时仍按周期检查。每次调用仅一个判断在途，待处理 tick 保留最新一个。

Agent 事件继续流向下游，不等待语义结果。正常结束会等待在途判断，并在额度允许时再作一次终局判断，因而完成信号最多增加两次预算的等待；回调必须非阻塞。观察期间若有更新，Observation 标为 superseded，旧建议在消费接口降为 CONTINUE，分数保留供审计。该检查只覆盖观察到的运行事件；外部产物变化仍由宿主快照版本负责。

取消会取消 tick、证据读取与在途判断，不追加终局观察；不能据此保证远端停止计费。Agent 异常原样传播，不重试整个 Agent。瞬时观察错误只记录，连续失败达到上限后停止该调用后续观察并建议人工复核，worker 继续原流程。总评估次数达到上限记录 ASSESSMENT_LIMIT；额度不会因长任务持续输出而无限增加。

默认文本上限 12,000 字符、diff 30,000、文件数 100、整体 state 100,000 字符。任务和仓库约束超限时直接弃权，不裁掉关键约束。输出与 diff 可以保留尾部，但会标记截断并禁止完成复核；非文本输入或最终结果同样标为证据不完整。近期事件最多 32 个类型标签，不是完整轨迹归档。

缺题、错误类型、非法概率、空响应、获取证据失败或超时均不构造完成结论。观察器 RuntimeException 被隔离；Service 复用有界异步记录队列，队列满或持久化失败会有日志，不阻断 worker。

## Service 配置与追踪

```json
{
  "jev": {
    "supervision": {
      "mode": "SHADOW",
      "version": "supervision-v1",
      "threshold": 0.8,
      "rejectionThreshold": 0.2,
      "budgetMillis": 3000,
      "minIntervalMillis": 5000,
      "periodicIntervalMillis": 30000,
      "maxAssessments": 60,
      "maxConsecutiveFailures": 3
    }
  }
}
```

沿现有 HarnessAgentBuildService 装配，配置进入 SessionAgentBuildSpec 缓存身份，变更后重新构建。启用必须提供两个阈值。预算最多 30 秒、次数最多 120；Service 最小观察间隔不少于 1 秒。可调整 maxTextChars/maxDiffChars/maxFiles/maxStateChars，但不能超过本页默认上限。未知字段、密钥和 endpoint 覆盖拒绝。

`task_supervision` 记录模型请求状态、耗时和用量；`supervision.advice` 记录实际可消费建议、观察次数和 superseded；`supervision.<检查名>` 记录每项概率和状态。后二者的 elapsed 为零，表示没有额外模型请求，不能纳入请求耗时分布。均沿现有 `jev.decision` 事件关联 Service run/session；recommendation.runId 是观察调用 UUID，与外层 Service run_id 区分。

HTTP Session overrides 不接收任意证据路径或“验证成功”声明。默认 Service 集成可以观察运行输出，但没有接入真实业务验证器；没有宿主收据时不能完成复核。生产证据存储、服务完整部署及跨进程恢复仍待验证。

## 可运行离线案例

按[轨迹案例构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)安装模块并生成 classpath，运行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevSupervisionExample
```

案例使用真实 ReActAgent、脚本模型、模拟订单工具与合成 JEV 响应。宿主检查工具恰好执行一次，再提供与当前观察 UUID 匹配的验证记录。预期输出包含 `Tool executions: 1, advice: REVIEW_COMPLETION, verified revision: true`。该例验证执行边界，不代表模型语义准确率。

源码下载：[监督器](/examples/jev/source/JevTaskSupervisor.java.txt)、[中间件](/examples/jev/source/JevSupervisionMiddleware.java.txt)、[证据类型](/examples/jev/source/SupervisionEvidence.java.txt)、[离线案例](/examples/jev/source/JevSupervisionExample.java.txt)。真实模型固定输入与结果见[监督对照记录](/v2/zh/jev/guides/supervision-benchmark)。

## 旧显式 API

`new JevSupervisor(judge).assess(task, boundedEvidence, verificationSucceeded)` 继续返回原 Report/Advice。它只有两项检查，没有观察调度或版本验证，不等价于本页的新接口。需要持续监督时注册新中间件；旧接口的调用方无需立即迁移。

## 研发验收记录

2026-09-26，实际代码位于 `/Users/ken/agentscope-3/agentscope-java` 的 `harness-context-redesign` 分支。

- JEV 168 项、Harness 相关 26 项、Service 相关 30 项测试通过；本项新增 22 项监督行为测试和 4 项 Service 配置/构建验证。
- 相关 21 个模块构建及本地安装成功：`mvn -o -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-service/service-dataplane -am install -Dtest='Jev*Test,CompactionMiddlewareTest,ConversationCompactorTest,ContextPipelineV2Test,HarnessAgentBuildServiceCacheKeyTest,ToolConfirmationMiddlewareTest' -Dsurefire.failIfNoSpecifiedTests=false`。
- 按文档 classpath 连续运行两次离线 main 均通过；没有额外派发工具。正式文档导航、链接、语法及站点构建校验通过。
- 真实 JEV/Qwen 两轮固定输入已运行并保留报告；生产配置未开启，未部署 Service，也未声称实际长任务收益得到验证。

R3 的源码适配与研发验收完成；下一项为 R4 代码审查与交付证据。
