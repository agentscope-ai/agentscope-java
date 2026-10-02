---
title: "浏览器只读执行：API、守卫与 Service"
---

本模块把 [jev-ultrafast 1231850](https://github.com/browser-use/jev-ultrafast/tree/1231850a0bf1a0c0341fe408ef1668dbbfdfac46) 的可见候选、操作/目标共判、执行守卫和独立完成验证接入 AgentScope 工具链。首个范围是宿主授权页面上的只读信息查询：链接导航、滚动和等待。旧 JevBrowserPlanner 建议接口保留；真实循环由 JevBrowserNavigator、JevBrowserSession 和 read_browser 工具承担。

未经校准先使用 SHADOW。这里只读的依据是宿主授权的页面和操作契约；HTTP GET、工具 readOnly 标记或 JEV 允许都不能证明任意网站没有副作用。当前真实浏览器验收针对本地受控页面，未接入生产网站或账户。

## 基本 API

依赖当前 BOM 的 `io.agentscope:agentscope-extensions-jev`。以下省略 imports，Source、Scope、Snapshot、Verification 等类型属于 JevBrowserSession：

```java
var navigator = new JevBrowserNavigator(
    client::systemOne, 0.8, JevBrowserNavigator.Limits.defaults(),
    new JevExecution.Options(JevExecution.Mode.SHADOW,
        Duration.ofSeconds(10), "browser-v1", (ctx, record) -> observe(ctx, record)));
var toolkit = new Toolkit();
toolkit.registerTool(new JevBrowserReadTool(navigator));

var context = RuntimeContext.builder().userId(userId).sessionId(sessionId)
    .put(Source.class, new Source(
        request -> browserSessions.newAuthorizedSession(request.context()),
        (goal, page) -> independentlyVerify(goal, page)))
    .build();
```

client、browserSessions、independentlyVerify、observe 和用户/会话由宿主实现。注册 toolkit 后使用现有 ReActAgent / HarnessAgent 执行；工具仍经过 PermissionEngine。模型只提供 goal，不能指定 URL、脚本、选择器、浏览器配置或验证结果。

Source.open 必须是非阻塞的工厂，每次调用创建独占会话；真正的启动与 I/O 放在 session 的响应式方法中。宿主先解析用户 ACL 和授权页面，再返回 Source，不能把模型参数当授权目录。session.scope 必须匹配运行上下文，后续 Snapshot.scope 必须匹配该 session 的 user/session/tab。跨调用不共享浏览器会话。

Session 契约：

- `observe()` 返回不可变 Snapshot：范围、页面版本、URL、标题、可见文本、带稳定 ID 的链接、滚动状态及截断数量。
- `execute(permit, expected, action)` 消费一次性 Permit，并原子复核页面/目标再派发。STALE 必须保证未派发；已经派发但无法确认结果返回 UNKNOWN，不允许以 STALE 重试。
- `close()` 立即禁止后续派发并返回异步清理 Mono。应幂等；取消和错误也执行清理。
- Source.verifier 根据当前页面和业务要求独立返回 Verification，绑定 goalDigest、pageVersion、passed 和证据列表。禁止用 JEV 的 DONE 自证成功。

直接调用 `navigator.navigate(context, goal, source)` 可取得完整 Report。它含最后页面、每步原页面版本、Proposal、动作收据及独立验证；包含页面文本，宿主如要保存须自行脱敏。Agent 工具输出仅为 status、url、version、visibleText、evidence，不把影子建议或内部轨迹交给后续模型。

## 判断与执行边界

一次请求包含 operation 和可选 click_target 两个 Choice。共享 state 同时包含完整的授权可见候选，避免一个问题只能看到另一个问题中的目标选项。只有实际提供的操作进入选项；CLICK 目标为当前授权可见链接，另加 none。即使只有一个链接，也保留 none，满足现有 Choice 的最少两个选项约束。最多 128 个链接；本轮不引入 TYPE_TEXT、SELECT、表单、上传、下载或购买。

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

`JevPlaywrightSession` 使用可选依赖 `com.microsoft.playwright:playwright:1.45.0`，这也是本次实际验证的本地版本。仅使用 Session 接口和离线案例的消费者不需要引入它。使用真实驱动的应用应显式加入该依赖并提供浏览器绝对路径；示例不会自动安装浏览器或使用个人配置目录。

```java
var session = new JevPlaywrightSession(
    new Scope(userId, sessionId, tabId), Path.of(chromeExecutable),
    authorizedStartUrl, Set.of(authorizedStartUrl, authorizedPolicyUrl),
    Duration.ofSeconds(10));
```

每个 session 创建隔离的 headless 浏览器上下文，所有 Playwright API 在同一个专属线程执行，符合其[线程约束](https://playwright.dev/java/docs/multithreading)。禁止 service worker 和下载，请求仅允许宿主枚举 URL 的 GET；含凭据的 URL 配置拒绝。宿主仍需确认 GET 和页面脚本的业务语义只读。

原子 DOM 快照使用隐藏在 JSHandle 闭包里的节点身份表，模型不能写入选择器或脚本。只枚举视口内、可见、未禁用、非表单的授权链接，排除 hidden/inert/aria-hidden、下载、新窗口和内联点击处理器。执行时同一段固定脚本先重算页面/表单属性守卫，再解析当前节点、检查遮挡并点击或滚动；移动后的坐标重新计算，不复用模型生成坐标。

页面版本包含文档时间标识、URL、视口、可见文本、候选身份及安全表单属性。安全表单属性仅用于本地守卫，不进入模型 state；密码、隐藏和文件字段排除。本实现采用整个可见页面守卫，比参考项目动作局部守卫更保守。跨导航重新创建快照控制器；异常派发保留 UNKNOWN。暂不支持跨 frame、shadow DOM、弹窗和通用交互式网站。

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

已验证实际 Service 构建链、工具调用/结果配对、关闭恢复和缺少权限策略时 DENIED 且无 JEV 请求。工具 readOnly 声明不绕过权限；应用仍需为只读页面配置明确策略。未部署 Service，也未接入生产浏览器池。

## 可运行案例

在项目根目录准备编译输出和 classpath：

```bash
module=agentscope-examples/jev
mvn -pl "$module" -am install -DskipTests
mvn -pl "$module" dependency:build-classpath -Dmdep.outputFile=/tmp/jev-browser-cp.txt
browser_cp="$module/target/classes:$(cat /tmp/jev-browser-cp.txt)"
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserExample
```

默认无需密钥、浏览器或网络。真实 ReActAgent 调用 read_browser，脚本浏览器导航一次，独立验证通过后生成答案。结果 judgments=2、modelCalls=2、browserActions=1、closedSessions=1。脚本判断只证明执行链，不代表语义准确率。

显式启用真实浏览器，仍不调用外部模型或网站：

```bash
java -cp "$browser_cp" io.agentscope.examples.jev.JevBrowserLocalExample \
  --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

若需使用真实 JEV 或 Qwen 判断，在命令后增加显式后端参数，见[对照页](/v2/zh/jev/guides/browser-benchmark#复现)。

案例启动 loopback HTTP 页面，使用新浏览器上下文打开首页、点击保修政策、检查实际页面文本，再关闭页面与 HTTP 服务。实际运行得到相同计数。可在支持 Chromium 的其他环境提供相应可执行路径；当前只在本机 Chrome 验证。

真实 DOM 守卫测试显式启用：

```bash
mvn -pl agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev test -Dtest=JevBrowserDriverTest \
  -Djev.browser="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

默认该测试跳过；其余离线测试无浏览器依赖。驱动测试覆盖移动目标、一次性派发、屏幕外变化、可见语义变化、属性值、替换节点、隐藏链接、遮挡、伪造目标及关闭后调用。测试通过反射在驱动线程修改自有页面，生产工具不暴露脚本执行接口。

## 参考源码映射与差异

| 上游入口 | 已采用的机制 | AgentScope 适配 |
| --- | --- | --- |
| snapshot.js / browser.py | 可见控件、稳定身份、页面/目标守卫、当前几何与遮挡 | 限授权链接，独立 Playwright 适配；全页面守卫；每次工具独占上下文 |
| model.py / questions.py | 同次 operation 与目标选择，只执行匹配目标 | 复用现有 JevClient；单候选加 none；所有返回头均需合法；显式阈值弃权 |
| agent.py / tests/test_agent.py | 先消费决策、先记录输入再观察、过期重判、有限无进展 | 总预算及关闭信号；UNKNOWN 不重放；OFF/SHADOW/ENFORCE 与工具结果配对 |
| examples/flights.py | 使用实际页面独立检查完成条件 | 宿主 Verifier 绑定 goal/版本/证据，验证后复核页面；本地保修信息查询 |
| scripts/check_guards.py | 在真实 DOM 上验证变化与遮挡 | 显式 Chrome 守卫测试，不执行下载的上游脚本 |

固定来源许可证为 MIT，源码机制经阅读后按本项目接口独立实现。上游文本填写、select、已有浏览器个人配置和航班真实网站案例未移植；也不将 README 的延迟数字当成本实现性能。真实模型固定输入结果见[动作判断对照](/v2/zh/jev/guides/browser-benchmark)。

源码：[Navigator](/examples/jev/source/JevBrowserNavigator.java.txt)、[Session 契约](/examples/jev/source/JevBrowserSession.java.txt)、[Playwright](/examples/jev/source/JevPlaywrightSession.java.txt)、[工具](/examples/jev/source/JevBrowserReadTool.java.txt)、[离线 Agent](/examples/jev/source/JevBrowserExample.java.txt)、[本地浏览器](/examples/jev/source/JevBrowserLocalExample.java.txt)、[行为测试](/examples/jev/source/JevBrowserTest.java.txt)、[DOM 守卫测试](/examples/jev/source/JevBrowserDriverTest.java.txt)。

## 本轮验收记录

2026-09-26：JEV 247 项、Harness 26 项、Service 45 项通过，21 个相关模块 install 成功；包含显式启用的真实 Chrome 守卫测试。随后补充禁用祖先节点的候选过滤，20 项浏览器专项及模块 install 再次通过。离线 Agent、真实本地浏览器、真实 JEV/Qwen 后端均运行通过；固定动作数据与首轮输入缺口记录完整保留。

正式文档 467 页、479 个重定向的导航/语法/链接/锚点/资源检查及 build validation 通过。本次代码位于 `/Users/ken/agentscope-3/agentscope-java` 的 `harness-context-redesign`，本轮改动尚未提交，未部署 Service。源码快照包括[原子 DOM 脚本](/examples/jev/source/browser-snapshot.js.txt)；生产页面、授权目录、独立业务金标和阈值校准另行验收。
