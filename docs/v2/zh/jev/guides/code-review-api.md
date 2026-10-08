---
title: "评审变更与定位代码证据"
---

`JevCodeReviewer` 对变更 diff 或源码快照做分阶段评审：筛选风险、选择证据区域、判断机制与严重度。适合提交前检查、代码审查辅助和研发 Agent 的产物复核。输入代码不限定语言；结果用于复核，不能替代编译、测试或合并授权。

## 场景：发现变更删除了权限检查

研发 Agent 提交一段修改：原先读取记录前会检查 `authorized`，现在直接返回记录。编译可能正常，已有测试也可能没覆盖越权读取；评审需要指出风险机制，并定位到这次变更中的实际证据，供开发者核查。

下面复用离线案例的 Python diff。宿主提供已经授权、版本一致的快照，JEV 先筛选风险，再从解析出的真实区域选择证据；它不能凭空提供文件或行号，也不会自动提交评审。

## 1. 构造快照并发起评审

`client` 初始化见[客户端接入](/v2/zh/jev/guides/client)，`context` 为当前运行上下文：

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import java.time.Duration;
import java.util.List;

var reviewer = new JevCodeReviewer(
    client::systemOne,
    JevCodeReviewer.Config.referencePolicy(),
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(10), "review-v1",
        (current, record) -> System.out.println(record)));
var input = new JevReviewInput(
    "demo-revision", JevReviewInput.Mode.CHANGES,
    List.of(new JevReviewInput.File(
        "src/auth.py",
        "@@ -1,2 +1,1 @@\n"
            + "-if not authorized: raise Forbidden()\n"
            + "-return record\n"
            + "+return record",
        List.of())),
    List.of());
var decision = reviewer.review(context, input).block();
System.out.println(decision);
```

`CHANGES` 接收每个文件的统一 diff；`CODEBASE` 接收完整源码。这里未提供测试证据，不代表仓库没有测试。真实应用应通过 `TestEvidence` 提供同一快照下的相关测试，并在文件记录中引用它们。

`referencePolicy()` 是未校准的示例阈值及资源上限；10 秒限制整个分阶段评审。此能力只支持 OFF/SHADOW，生成建议供复核，不提供自动批准或阻断合并的 ENFORCE 模式。需要把读取快照也纳入预算时，可传 `Supplier<Mono<JevReviewInput>>`。

CHANGES 的每个 File.text 是一个文件的统一 diff；CODEBASE 是当前完整源文件。relative path 仅用于定位标识，拒绝绝对路径、反斜杠、`..`、控制字符和重复路径。关联测试必须存在于同一输入，类型构造时复制集合；这些检查不能替代 ACL。revision 应标识包含未提交内容在内的完整快照，由宿主保证源文件、diff 和测试的一致性。

## 2. 读取发现、证据与未完成项

对于这段 diff，可重点复核删除授权判断是否造成越权读取；只有机制、区域与严重度均得到足够支持，才形成 Finding。即使没有 Finding，也应检查是否存在未完成项，不能据此宣布代码安全。

五个筛选维度为 correctness、security、reliability、compatibility、testGap。testGap 表示提供的测试证据是否不足，不能据此断言整个项目没有测试。CODEBASE 的分块最大值提高风险召回机会，但并不是经过校准的文件级风险概率。

每个风险信号记录 path、dimension 和 probability。只有达到 screenThreshold 的信号进入有限追查；处于 lowRiskThreshold 与 screenThreshold 之间时明确保留不确定性。文件画像的类别和评审优先级独立于问题成立与否；低风险文件也可进入前 5 个画像。

FollowUp 分别返回 LOCATED、NO_MATCH、NO_ISSUE、LOW_CONFIDENCE、DEFERRED、ERROR。前两种“没有支持证据”与调用失败不同，不返回虚构发现。Finding 包含选中的真实区域、OLD / NEW、区域起止行、定位置信度、机制、严重度、可空角色和建议。行号是证据区域范围，不承诺精确缺陷行。严重度至少 1.5 才请求角色；达到 2 时建议 REQUEST_CHANGES_REVIEW，否则 COMMENT，均不发布评审或更改合并状态。

角色判断失败或不确定时，已形成的证据和严重度仍保留，owner 为空；整体报告标记不完整。严重度或机制不确定时不生成 Finding。缺题、类型错误、非法概率及分布由现有 JevClient 契约检查拒绝。

`Report` 同时保留 matrix、profiles、followUps、unscreenedFiles、unprofiledFiles、complete 和逐请求 calls。complete 只表示本次配置范围内的流程完成且没有已识别的缺口；画像数量仍受 maxProfiles 限制，未画像数量单独报告。complete / DECIDED 都不是“代码没有缺陷”，更不是测试通过。没有输入文件为 SKIPPED；部分证据受限、信号待查或判断不确定为 INCONCLUSIVE；全筛选失败或阶段异常可为 ERROR。异常发生前的部分报告尽量保留。

## 3. 让 Agent 显式请求评审

注册只读工具，再为每次调用提供快照 Source。下例只向指定演示用户和会话开放上面的固定快照；实际接入需替换为你的授权快照存储。

```java
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

var toolkit = new Toolkit();
toolkit.registerTool(new JevCodeReviewTool(reviewer));
var agent = HarnessAgent.builder().name("code-review-assistant")
    .model(model).toolkit(toolkit).build();
var runContext = RuntimeContext.builder()
    .userId("demo-user").sessionId("demo-session")
    .put(JevCodeReviewTool.Source.class, new JevCodeReviewTool.Source((current, snapshotId) -> {
        if (!"demo-user".equals(current.getUserId())
                || !"demo-session".equals(current.getSessionId())
                || !"demo-revision".equals(snapshotId)) {
            return Mono.error(new IllegalArgumentException("快照不可访问"));
        }
        return Mono.just(input);
    }))
    .build();
```

调用 Agent 时传入 `runContext`。模型只传 `snapshotId`，不能把它直接当文件路径读取。Source、用户或会话缺失时返回错误，不自动发现仓库。代码和测试中的文本只是评审材料，不能作为宿主执行命令的授权。

在构建前注册工具，使其进入 Harness 工具视图和过滤规则。SHADOW 只生成可供 Agent / 人工阅读的建议，不自动执行建议。显式工具接入会增加工具 schema，Agent 选择调用后会增加一对工具调用/结果；它与“不修改原事件的旁路中间件”是不同接入方式。现有 PermissionEngine、Service 确认链及工具过滤仍适用，readOnly 不绕过授权。

## 预算、取消与错误

Config.referencePolicy 的上限为 50 个文件、每文件 100,000 字符、每请求 state 100,000 字符、每文件 128 个证据区域、5 个画像、8 个风险追查和 64 次模型请求。超过文件总数时不开始评审；单文件超限、二进制/合并 diff、非法 hunk 或空区域会作为明确跳过项，不能视为通过。关联测试被截断或超过 4 份时标注上下文受限。其他源代码不静默截断。

所有阶段与证据获取共用 JevExecution 总预算。默认串行，无自动重试整个评审或 Agent；JevClient 的单次重试行为由客户端策略决定。请求额度耗尽留下 DEFERRED；超时返回 TIMEOUT 和已有部分报告；取消会取消读取或在途请求并停止后续阶段，但不能保证远端停止计费。

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

代码快照存储、ACL 后端和报告保存由宿主提供。HTTP 配置不能注入 Source 或任意文件路径。开启配置后还必须由宿主在每个 run 的 RuntimeContext 提供 Source；缺少它会返回 REVIEW_SOURCE_REQUIRED 对应的错误状态，不产生评审结论。

## 可运行案例

[JevCodeReviewExample](/examples/jev/source/JevCodeReviewExample.java.txt)提供上述权限检查删除的 diff，通过真实 Agent 调用只读评审工具。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后运行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevCodeReviewExample
```

默认不读取你的仓库，不使用密钥。案例使用脚本生成模型和预设评审响应，验证快照读取、分阶段评审及工具结果传递，不测量真实漏洞检出率。实际项目仍需运行测试并人工核查证据，再决定是否修改或合并。
