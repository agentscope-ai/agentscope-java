---
title: "在授权页面中只读导航并验证完成"
---

`JevBrowserNavigator` 从当前页面的可见候选中选择导航、滚动或等待，通过 `read_browser` 工具接入 Agent。适合在受控帮助中心、政策页面中查找信息。页面授权与只读操作范围由宿主定义，任务完成必须由独立验证器确认。

## 场景：在帮助中心找到保修期限

用户询问“产品保修多久？”，帮助中心首页只显示“保修政策”链接。Agent 需要打开政策页，读取其中的期限，再据此回答；不能因为模型选择了 DONE 就声称查询完成。

宿主限定可访问的首页与政策页，JEV 从当前可见操作中提出点击建议。执行前再次核对页面版本；到达政策页后，独立验证器确认实际文本包含所需信息，才返回 VERIFIED。

## 1. 注册只读浏览器工具

`client` 初始化见[客户端接入](/v2/zh/jev/guides/client)，`model` 是生成回答的模型。先使用 SHADOW 检查建议，浏览器停留在起始页，不实际点击：

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.*;
import io.agentscope.extensions.judge.jev.browser.JevPlaywrightSession;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Set;

var navigator = new JevBrowserNavigator(
    client::systemOne, 0.8, JevBrowserNavigator.Limits.defaults(),
    new JevExecution.Options(JevExecution.Mode.SHADOW,
        Duration.ofSeconds(10), "browser-v1",
        (current, record) -> System.out.println(record)));
var toolkit = new Toolkit();
toolkit.registerTool(new JevBrowserReadTool(navigator));
var agent = HarnessAgent.builder().name("help-center-reader")
    .model(model).toolkit(toolkit).build();
```

0.8 为待校准的选择阈值；10 秒覆盖整个浏览器工具调用的观察、判断、操作和验证。默认最多 8 步，避免页面循环。工具仍经过权限链，模型只提供 goal，不能提供任意 URL、脚本或选择器。

## 2. 为当前用户提供会话工厂与完成验证器

`openSession` 是宿主的 `Function<JevBrowserSession.Request, JevBrowserSession>`，每次调用返回一个已授权的独占会话；`verifier` 是 `JevBrowserSession.Verifier`，核验当前页面是否满足查询目标。两者的可运行实现见本页末尾案例。

```java
var context = RuntimeContext.builder().userId(userId).sessionId(sessionId)
    .put(Source.class, new Source(openSession, verifier))
    .build();
```

调用 Agent 时传入这个上下文。保修案例的验证器应基于实际政策页和目标判断是否拿到保修期限，并将验证结果绑定当前页面版本及目标摘要；不能复用模型的 DONE 作为验证结果。

要执行实际导航，将 options 的模式改为 ENFORCE 并重建组件。`VERIFIED` 才表示独立验证已通过；`OBSERVED` 只是读取了页面，`UNVERIFIED` 表示仍缺少完成证据。页面变化时旧建议不能直接执行，适配器必须先返回 STALE 再重新观察。

## 3. 实现会话的执行边界

Source.open 必须是非阻塞的工厂，每次调用创建独占会话；真正的启动与 I/O 放在 session 的响应式方法中。宿主先解析用户 ACL 和授权页面，再返回 Source，不能把模型参数当授权目录。session.scope 必须匹配运行上下文，后续 Snapshot.scope 必须匹配该 session 的 user/session/tab。跨调用不共享浏览器会话。

Session 契约：

- `observe()` 返回不可变 Snapshot：范围、页面版本、URL、标题、可见文本、带稳定 ID 的链接、滚动状态及截断数量。
- `execute(permit, expected, action)` 消费一次性 Permit，并原子复核页面/目标再派发。STALE 必须保证未派发；已经派发但无法确认结果返回 UNKNOWN，不允许以 STALE 重试。
- `close()` 立即禁止后续派发并返回异步清理 Mono。应幂等；取消和错误也执行清理。
- Source.verifier 根据当前页面和业务要求独立返回 Verification，绑定 goalDigest、pageVersion、passed 和证据列表。禁止用 JEV 的 DONE 自证成功。

直接调用 `navigator.navigate(context, goal, source)` 可取得完整 Report。它含最后页面、每步原页面版本、Proposal、动作收据及独立验证；包含页面文本，宿主如要保存须自行脱敏。Agent 工具输出仅为 status、url、version、visibleText、evidence，不把影子建议或内部轨迹交给后续模型。

## 判断与执行边界

一次请求包含 operation 和可选 click_target 两个 Choice。共享 state 同时包含完整的授权可见候选，避免一个问题只能看到另一个问题中的目标选项。只有实际提供的操作进入选项；CLICK 目标为当前授权可见链接，另加 none。即使只有一个链接，也保留 none，满足现有 Choice 的最少两个选项约束。最多 128 个链接；不支持 TYPE_TEXT、SELECT、表单、上传、下载或购买。

两个答案均先经过 JevClient 的响应校验，再按所选选项概率与显式阈值判断。低置信度或 CLICK/none 停止接管；只有匹配 operation 的目标会被执行，其他目标头没有执行能力。DONE/BLOCKED 先再次观察页面版本；DONE 还执行独立验证，再复核页面，只有绑定正确、通过且有证据才返回 VERIFIED。

执行前先记录派发意图，收据到达后记录结果，再观察下一页。后续观察失败仍保留 APPLIED 收据；派发错误、空收据或中途取消保留 UNKNOWN，不自动重放动作。STALE 可重新观察和判断，默认最多两次，第三次停止。非 WAIT 动作连续三次页面版本无变化停止；WAIT 受步数和总预算限制。

| 模式 | 实际浏览器行为 | 工具输出 |
| --- | --- | --- |
| OFF | 只读取宿主起始页，不调用 JEV | OBSERVED 和原页面 |
| SHADOW | 读取相同起始页，只判断一次建议，不执行动作和完成验证 | 与 OFF 相同；建议留在 Report/观察器 |
| ENFORCE | 在授权候选和守卫内执行有限循环 | VERIFIED、UNVERIFIED、BLOCKED、ABSTAIN 或具体停止原因 |

不能将 UNVERIFIED、OBSERVED、UNKNOWN、TIMEOUT、STEP_LIMIT、STALE_LIMIT、NO_PROGRESS 或错误解释为任务已完成。这里不生成用户答案；最终回答仍由 Agent 使用工具证据生成，可继续接入已有回答审核。

## 预算、取消与观测

默认上限为 8 个判断步骤、2 次过期重试、3 次无进展、32,000 个序列化状态字符；goal 最多 8,000 字符。每个步骤的判断、页面操作和独立验证共享一次工具调用的总预算。过大的状态直接停止，不把截断后不完整状态当完整判断；浏览器适配器只提供视口文本并明确候选截断数量。

取消停止订阅及后续派发，关闭独占会话。已经进入浏览器执行的请求可能结果未知，不声称取消能撤回已发生的输入。适配器监听关闭信号，关闭后调用立即失败，避免向已停止的工作线程排队。观察器异常不改变工具结果。

复用 JevExecution 记录 `browser_action` 的版本、模式、状态、额外判断耗时、建议操作及已知判断模型/token；`browser_task` 记录总耗时、步骤/收据数量和停止原因。Service TraceSink 关联运行上下文。元数据不含 URL、完整页面、goal、授权目录或凭据；没有已知价格时不计算货币成本。

## Playwright 适配器

`JevPlaywrightSession` 使用可选依赖 `com.microsoft.playwright:playwright:1.45.0`。仅使用 Session 接口和离线案例的消费者不需要引入它。使用真实驱动的应用应显式加入该依赖并提供浏览器绝对路径；示例不会自动安装浏览器或使用个人配置目录。

```java
var session = new JevPlaywrightSession(
    new Scope(userId, sessionId, tabId), Path.of(chromeExecutable),
    authorizedStartUrl, Set.of(authorizedStartUrl, authorizedPolicyUrl),
    Duration.ofSeconds(10));
```

每个 session 创建隔离的 headless 浏览器上下文，所有 Playwright API 在同一个专属线程执行，符合其[线程约束](https://playwright.dev/java/docs/multithreading)。禁止 service worker 和下载，请求仅允许宿主枚举 URL 的 GET；含凭据的 URL 配置拒绝。宿主仍需确认 GET 和页面脚本的业务语义只读。

原子 DOM 快照使用隐藏在 JSHandle 闭包里的节点身份表，模型不能写入选择器或脚本。只枚举视口内、可见、未禁用、非表单的授权链接，排除 hidden/inert/aria-hidden、下载、新窗口和内联点击处理器。执行时同一段固定脚本先重算页面/表单属性守卫，再解析当前节点、检查遮挡并点击或滚动；移动后的坐标重新计算，不复用模型生成坐标。

页面版本包含文档时间标识、URL、视口、可见文本、候选身份及安全表单属性。安全表单属性仅用于本地守卫，不进入模型 state；密码、隐藏和文件字段排除。页面守卫覆盖整个可见页面。跨导航重新创建快照控制器；异常派发保留 UNKNOWN。暂不支持跨 frame、shadow DOM、弹窗和通用交互式网站。

## AgentScope Service

```json
{
  "jev": {
    "browser": {
      "mode": "SHADOW", "version": "browser-v1", "threshold": 0.8,
      "budgetMillis": 10000, "maxSteps": 8,
      "maxStale": 2, "maxNoProgress": 3, "maxStateChars": 32000
    }
  }
}
```

配置默认 OFF，开启要求显式 threshold。预算默认 10 秒、上限 30 秒；未知字段、越界参数、URL、endpoint 和 apiKey 不允许放入 Agent overrides。工具在 Harness 构建前注册，配置参与原缓存；OFF 重建后移除工具。每个运行上下文必须由宿主注入 Source，不配置任何通用浏览器账户或隐式起始页；缺失 Source 生成工具错误。

## 可运行案例

[JevBrowserExample](/examples/jev/source/JevBrowserExample.java.txt)提供脚本会话和独立验证器。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后运行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserExample
```

默认无需密钥、浏览器或网络。真实 ReActAgent 调用 read_browser，脚本浏览器导航一次，独立验证通过后生成答案。结果 judgments=2、modelCalls=2、browserActions=1、closedSessions=1。脚本判断只证明执行链，不代表语义准确率。

显式启用真实浏览器，仍不调用外部模型或网站：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserLocalExample \
  --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

真实判断可在浏览器参数后追加 `--live-jev MODEL` 或 `--live-qwen MODEL ENDPOINT`，并提供对应环境密钥。完整入口源码见 [JevBrowserLocalExample](/examples/jev/source/JevBrowserLocalExample.java.txt)，固定动作对照见[评测指南](/v2/zh/jev/evaluation)。

案例启动 loopback HTTP 页面，使用新浏览器上下文打开首页、点击保修政策、检查实际页面文本，再关闭页面与 HTTP 服务。浏览器可执行路径由调用者提供。
