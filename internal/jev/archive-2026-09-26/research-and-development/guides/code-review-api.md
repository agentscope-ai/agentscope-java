---
title: "代码评审：分阶段判断与证据定位"
---

R4 参考 [jev-review 固定提交 31f8960](https://github.com/devagrawal09/jev-review/tree/31f89602797fb7bea007f8a480bf368bf564954e)，提供变更评审和代码快照评审。宿主给出经过授权的不可变证据，JEV 筛选风险、选择证据区域并生成评审建议。结果用于人工复核，不代表编译、测试、安全验证或合并许可。

## 源码依据与适配

| 来源 | 参考机制 | AgentScope 实现与差异 |
| --- | --- | --- |
| [workflow.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/review/workflow.ts) | 五维筛选；按最大风险选前 5 个文件画像；按信号概率选前 8 项追查；按严重度排序 | JevCodeReviewer 每次订阅独立 Run；初版串行执行，所有阶段共用一次总预算。保留未追查信号，不把截断列表当作完整评审 |
| [judgments.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/review/judgments.ts) | 变更筛选 → 文件画像 → diff hunk 定位 → 机制 → 严重度 → 评审角色 | 使用同类 Noul、Choice、Score；保留 noMatch、noIssue；角色只是建议，没有人员分派或发评论行为 |
| [codebase-judgments.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/review/codebase-judgments.ts) | 每 160 行筛选后各维取最大值；80 行证据区域；关联测试上下文 | CODEBASE 使用相同分块尺寸；测试由宿主显式关联，最多 4 份，每份 1,800 字符，截断会标注上下文受限 |
| [patch.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/domain/patch.ts) | 从统一 diff 推导 hunk 和起始行 | 严格检查行数、单文件格式和整数边界；纯删除使用 OLD 行号。模型只能选择已有区域，不能生成任意行号 |
| [config.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/domain/config.ts) | 五维风险、机制、角色与 0–3 严重度，阈值 .7 / .55 / 1.5 / 2 | 保留参考结构；新增低风险 .2 阈值和弃权区间；置信度检查扩展到机制、严重度、角色及画像。所有演示阈值均未校准 |
| [repository-files.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/adapters/repository-files.ts)、[git.ts](https://github.com/devagrawal09/jev-review/blob/31f89602797fb7bea007f8a480bf368bf564954e/src/adapters/git.ts) | 本地发现文件、读取 Git 变更、启发式关联测试 | 不移植隐式仓库扫描。由应用提供有版本且经过 ACL 的快照，不限制代码语言，扩展本身不读取路径、不执行 Git |

参考仓库该提交没有独立单元测试套件，其 check 主要检查类型、依赖方向和 dashboard 语法。本项行为测试由 AgentScope 新增。没有移植 dashboard、CLI、报告文件存储、原项目并发数 3 或自动全仓扫描；源码对应机制不等于这些外围功能已经实现。MIT 许可证随 JEV 模块的 `META-INF/jev-references` 发布。

## 基本 API

依赖 `io.agentscope:agentscope-extensions-jev`，使用当前项目 BOM。新增类型位于 `io.agentscope.extensions.judge.jev.review`：

- `JevReviewInput`：revision、CHANGES / CODEBASE、文件及测试证据。
- `JevCodeReviewer`：分阶段评审，返回 `Mono<Decision<Report>>`。
- `ReviewRegions`：确定性证据区域解析。
- `JevCodeReviewTool`：供 Agent 显式调用的只读工具。

```java
var client = JevClient.builder().build();
var reviewer = new JevCodeReviewer(
    client::systemOne,
    JevCodeReviewer.Config.referencePolicy(), // 演示值，未校准
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(10), "review-v1",
        (ctx, record) -> recordMetadata(ctx, record)));

var input = new JevReviewInput(
    "snapshot-revision", JevReviewInput.Mode.CODEBASE,
    List.of(new JevReviewInput.File(
        "src/read.py", sourceText, List.of("tests/read_test.py"))),
    List.of(new JevReviewInput.TestEvidence("tests/read_test.py", testText)));

Mono<JevExecution.Decision<JevCodeReviewer.Report>> result =
    reviewer.review(context, input);
```

sourceText / testText 是宿主已授权的证据内容，recordMetadata 是非阻塞元数据记录函数。需要将读取证据也纳入预算时，使用 `reviewer.review(context, () -> loadAuthorizedSnapshot(context))`，返回 `Mono<JevReviewInput>`。

CHANGES 的每个 File.text 是一个文件的统一 diff；CODEBASE 是当前完整源文件。relative path 仅用于定位标识，拒绝绝对路径、反斜杠、`..`、控制字符和重复路径。关联测试必须存在于同一输入，类型构造时复制集合；这些检查不能替代 ACL。revision 应标识包含未提交内容在内的完整快照，由宿主保证源文件、diff 和测试的一致性。

## 分阶段输出与弃权

五个筛选维度为 correctness、security、reliability、compatibility、testGap。testGap 表示提供的测试证据是否不足，不能据此断言整个项目没有测试。CODEBASE 的分块最大值提高风险召回机会，但并不是经过校准的文件级风险概率。

每个风险信号记录 path、dimension 和 probability。只有达到 screenThreshold 的信号进入有限追查；处于 lowRiskThreshold 与 screenThreshold 之间时明确保留不确定性。文件画像的类别和评审优先级独立于问题成立与否；低风险文件也可进入前 5 个画像。

FollowUp 分别返回 LOCATED、NO_MATCH、NO_ISSUE、LOW_CONFIDENCE、DEFERRED、ERROR。前两种“没有支持证据”与调用失败不同，不返回虚构发现。Finding 包含选中的真实区域、OLD / NEW、区域起止行、定位置信度、机制、严重度、可空角色和建议。行号是证据区域范围，不承诺精确缺陷行。严重度至少 1.5 才请求角色；达到 2 时建议 REQUEST_CHANGES_REVIEW，否则 COMMENT，均不发布评审或更改合并状态。

角色判断失败或不确定时，已形成的证据和严重度仍保留，owner 为空；整体报告标记不完整。严重度或机制不确定时不生成 Finding。缺题、类型错误、非法概率及分布由现有 JevClient 契约检查拒绝。

`Report` 同时保留 matrix、profiles、followUps、unscreenedFiles、unprofiledFiles、complete 和逐请求 calls。complete 只表示本次配置范围内的流程完成且没有已识别的缺口；画像数量仍受 maxProfiles 限制，未画像数量单独报告。complete / DECIDED 都不是“代码没有缺陷”，更不是测试通过。没有输入文件为 SKIPPED；部分证据受限、信号待查或判断不确定为 INCONCLUSIVE；全筛选失败或阶段异常可为 ERROR。异常发生前的部分报告尽量保留。

## Agent 接入与权限

```java
var toolkit = new Toolkit();
toolkit.registerTool(new JevCodeReviewTool(reviewer));
var agent = HarnessAgent.builder().model(model).toolkit(toolkit).build();

var context = RuntimeContext.builder()
    .userId(userId).sessionId(sessionId)
    .put(JevCodeReviewTool.Source.class,
        new JevCodeReviewTool.Source((ctx, snapshotId) ->
            snapshots.loadAuthorized(ctx, snapshotId)))
    .build();
```

model 和 snapshots 是应用自己的模型及快照存储。工具名为 `review_code_snapshot`，模型仅传 snapshotId；Source 必须在宿主侧用当前用户和会话检查访问权限，不能把 snapshotId 直接当路径读取。Source 为空、用户或会话缺失、非法标识时返回错误判断，不尝试自动发现仓库。源代码、diff 和测试都作为不可信证据，提示模型忽略嵌入命令；确定性工具权限不依赖该提示。

在构建前注册工具，使其进入 Harness 工具视图和过滤规则。SHADOW 只生成可供 Agent / 人工阅读的建议，不自动执行建议。显式工具接入会增加工具 schema，Agent 选择调用后会增加一对工具调用/结果；它与“不修改原事件的旁路中间件”是不同接入方式。现有 PermissionEngine、Service 确认链及工具过滤仍适用，readOnly 不绕过授权。

## 预算、取消与错误

Config.referencePolicy 的上限为 50 个文件、每文件 100,000 字符、每请求 state 100,000 字符、每文件 128 个证据区域、5 个画像、8 个风险追查和 64 次模型请求。超过文件总数时不开始评审；单文件超限、二进制/合并 diff、非法 hunk 或空区域会作为明确跳过项，不能视为通过。关联测试被截断或超过 4 份时标注上下文受限。其他源代码不静默截断。

所有阶段与证据获取共用 JevExecution 总预算。默认串行，无自动重试整个评审或 Agent；生产 JevClient 的单次重试行为由客户端策略决定，本轮 benchmark 明确关闭重试。请求额度耗尽留下 DEFERRED；超时返回 TIMEOUT 和已有部分报告；取消会取消读取或在途请求并停止后续阶段，但不能保证远端停止计费。

OFF 不获取证据、不请求模型，Service 也不注册工具。SHADOW 必须显式选择；ENFORCE 构造时拒绝。观察器 RuntimeException 被隔离。Call 记录阶段、后端版本、耗时、状态和可知用量；未知用量保留 null，不记为零。文本后端返回非法结构时保留能解析到的计费用量。API 在拿到可解析响应前失败的用量可能未知。

## Service 配置

```json
{
  "jev": {
    "review": {
      "mode": "SHADOW",
      "version": "review-v1",
      "rejectionThreshold": 0.2,
      "threshold": 0.7,
      "minConfidence": 0.55,
      "routeSeverity": 1.5,
      "blockingSeverity": 2,
      "budgetMillis": 10000,
      "maxProfiles": 5,
      "maxFollowUps": 8,
      "maxRequests": 64
    }
  }
}
```

`HarnessAgentBuildService` 将工具与托管记忆工具一同注册到构建前的 Toolkit，沿用既有 Harness 构建流程。review 是应用工具配置，不出现在 `middlewares()` 返回值中；配置进入 Agent 构建缓存身份。省略或 OFF 不注册，变化后重新构建。

开启必须显式提供上述五个阈值。Service 总预算上限 30 秒，maxRequests 上限 128，maxProfiles 上限 10，maxFollowUps 上限 16，maxFiles 上限 100，maxRegions 上限 254；字符上限不超过 100,000。未知字段、密钥和 endpoint 覆盖拒绝。

`code_review` 元数据记录用途、版本、状态、耗时、文件数、发现数、调用数、后端版本及已知 token 汇总，沿既有 `jev.decision` 追踪关联 run/session。不在该事件保存代码、路径、diff、完整报告或凭据；工具结果本身会含证据区域，宿主需要按代码敏感性控制 Agent 消息及完整报告的存储和展示。总预算异常时观察事件可能没有阶段用量汇总，已有逐请求用量可从返回的部分 Report 获取。

当前提供 Source 扩展点和真实构建链测试，尚未给生产 Service 接入代码快照存储、ACL 后端、报告持久化或专用 UI。HTTP 配置不能注入 Source 或任意文件路径。开启配置后还必须由宿主在每个 run 的 RuntimeContext 提供 Source；缺少它会返回 REVIEW_SOURCE_REQUIRED 对应的错误状态，不产生评审结论。

## 离线案例与验收

按[轨迹案例构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)安装模块并生成 classpath，运行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevCodeReviewExample
```

案例默认离线：真实 ReActAgent、脚本模型、宿主快照 Source 与合成类型化判断。预期包含 `agentModelCalls=2, reviewRequests=6, snapshotReads=1`，并校验一个 SUCCESS 工具结果。它验证工具恰好执行一次，不是语义准确率测试。真实后端必须通过 benchmark 的显式参数开启，见[固定输入对照](/v2/zh/jev/guides/code-review-benchmark)。

源码下载：[评审器](/examples/jev/source/JevCodeReviewer.java.txt)、[输入](/examples/jev/source/JevReviewInput.java.txt)、[区域解析](/examples/jev/source/ReviewRegions.java.txt)、[Agent 工具](/examples/jev/source/JevCodeReviewTool.java.txt)、[离线案例](/examples/jev/source/JevCodeReviewExample.java.txt)。

本项测试覆盖 noMatch / noIssue / 低置信度、全源码与 diff 定位、纯删除行号、非法路径和 hunk、输入/状态/次数上限、部分结果保留、后端错误及用量、超时、在途取消、并发隔离、OFF、真实 Agent 工具调用以及 Service 开关/缓存/权限边界。2026-09-26：新增 23 项扩展行为测试及 5 项 Service 配置/构建/权限测试通过；累计 JEV 191 项、Harness 26 项、Service 35 项相关测试通过，21 个模块 install 成功，离线 CLI 运行通过。正式站点语法、导航、资源与 build validation 通过，完整记录见[推进页](/v2/zh/jev/reference-roadmap)。生产部署、真实仓库金标和阈值校准尚未完成。
