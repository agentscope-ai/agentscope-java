---
title: "持续观察进展与交付状态"
---

`JevSupervisionMiddleware` 在 Agent 运行期间观察进展、偏航、验证需求和交付证据，向应用返回建议。适合研发任务、资料整理和需要多步工具执行的工作流。监督器不会自行停止任务、重放工具或宣布完成。

## 示例场景：Agent 在整理核查报告，如何判断该补证据还是交付

一个订单核查任务需要查询状态、汇总异常并生成有依据的报告。Agent 可能连续输出“正在处理”，也可能在没有完成验证时声称任务已结束。单凭输出活跃或完成措辞，不能判断是否有实质进展。

监督中间件在运行过程中结合当前输出和宿主证据观察任务，建议继续工作、补验证、复核方向或进入完成复核。它只提供建议，不自行停止 Agent，也不会把“模型认为完成”直接写成任务完成状态。

## 基本 API

依赖 `io.agentscope:agentscope-extensions-jev`，版本随当前项目 BOM。类型位于 `io.agentscope.extensions.judge.jev.supervision`：`JevTaskSupervisor`、`JevSupervisionMiddleware`、`SupervisionEvidence`。

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;

var client = JevClient.builder().build();
var supervisor = new JevTaskSupervisor(
    client::systemOne,
    new JevTaskSupervisor.Thresholds(0.2, 0.8), // 演示值，未校准
    JevTaskSupervisor.Limits.defaults(),
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(3), "supervision-v1",
        (ctx, record) -> System.out.println(record)));
var middleware = new JevSupervisionMiddleware(
    supervisor,
    JevSupervisionMiddleware.Schedule.defaults(),
    (ctx, observation) -> System.out.println(observation));

var agent = HarnessAgent.builder()
    .name("engineering-assistant")
    .model(model)
    .middleware(middleware)
    .build();
```

`model` 是已有生成模型；实际核查工具仍按原方式注册。第一层观察器记录检查是否成功、耗时和版本，第二层接收可供应用消费的监督建议。示例打印到终端，服务里应换成快速返回的元数据记录器。

`Schedule.defaults()` 限制检查频率和总次数；3 秒只约束一次证据读取与模型判断，不是限制任务必须在 3 秒内完成。先使用 SHADOW，可以确认哪些时刻建议补验证，而不改变现有任务流程。

中间件也可以注册到 ReActAgent。OFF 不捕获或调用模型；SHADOW 保持输入、工具执行与输出事件原样；ENFORCE 构造时拒绝。Service 缺省为 OFF，API 构造要求显式指定模式。

也可独立调用 `supervisor.observe(ctx, () -> Mono.just(snapshot))`，使用完全相同的检查定义与仲裁逻辑进行离线轨迹观察。Snapshot 包含 Scope、观察序号、任务、输出尾部、近期事件、运行/失败状态、截断标志、耗时和宿主证据。证据获取与模型请求共享一次 JevExecution 预算。

## 结合核查任务处理建议

检查按职责命名：

| 职责 | 检查 |
| --- | --- |
| core.completion | implementation_complete、requirements_satisfied、ready_to_finish |
| core.verification | tests_sufficient、needs_verification |
| core.worker-health | meaningful_progress、worker_stuck、work_off_track |
| repository.instructions | agents_md_drift |
| core.human-escalation | needs_human |
| quality.documentation | documentation_sufficient，仅在明确要求文档时启用 |

每项返回原始概率及 YES / NO / UNKNOWN；UNKNOWN 不是否定。0.2 / 0.8 是演示阈值，需要按业务样本校准。仅影响当前决策分支的不确定项触发人工复核，信息性的 meaningful_progress 独立保留。

`Decision<Report>` 沿用 DECIDED / INCONCLUSIVE / ERROR / SKIPPED。Report 保留所有 Findings、全部 Proposal、选中建议、截断标志、验证状态、后端版本与用量。DECIDED 表示建议能够确定，不能解释为全部检查通过。错误或超限可以没有 Report。

建议仲裁顺序为：人工需求 → 运行异常 → 活动 worker 的方向问题 → 证据不完整 → 当前验证缺失/不足 → 必需文档不完整 → 完成复核 → 与当前分支相关的不确定 → 继续工作。相同优先级比较概率，再按稳定顺序选择；仓库约束在相同置信度的健康问题之前。

例如任务仍在查订单时，应用可以记录 CONTINUE；报告看似完整但缺少宿主验证时，应请求补验证；有版本匹配的成功验证后，才可能建议 REVIEW_COMPLETION。

返回 CONTINUE、REQUEST_VERIFICATION、REVIEW_COMPLETION、REVIEW_DOCUMENTATION、REVIEW_DIRECTION 或 MANUAL_REVIEW。它们都不附带执行权。即使 REVIEW_COMPLETION，也不更改 Agent 或业务任务的完成状态。离线 Snapshot 的 workerFailed，以及事件流中的迭代上限、请求停止、全部工具被拒绝，会阻止完成复核建议；异常退出原样传播。

## 可信验证证据

在本次 RuntimeContext 注入 EvidenceSource，提供带 revision 和作用域的产物与验证记录。缺少有效证据时不能得到完成复核建议。[接入方法与有效性规则](/v2/zh/jev/guides/supervision-evidence)

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

HTTP Session overrides 不接收任意证据路径或“验证成功”声明。默认 Service 集成可以观察运行输出，但没有接入真实业务验证器；没有宿主收据时不能完成复核。证据存储和跨进程恢复由宿主实现。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后运行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevSupervisionExample
```

案例使用真实 ReActAgent、脚本模型、模拟订单工具与合成 JEV 响应。宿主检查工具恰好执行一次，再提供与当前观察 UUID 匹配的验证记录。预期输出包含 `Tool executions: 1, advice: REVIEW_COMPLETION, verified revision: true`。该例验证执行边界，不代表模型语义准确率。

[完整源码](/examples/jev/source/JevSupervisionExample.java.txt)。用例将核查任务缩小为一笔订单，以便直接检查实际工具次数与验证收据；长任务方向判断和完成建议的准确率需要另用真实任务轨迹评测。
