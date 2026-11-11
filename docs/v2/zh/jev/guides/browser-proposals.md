---
title: "只生成浏览器动作建议"
---

`JevBrowserPlanner` 为宿主已经观察到的浏览器动作候选生成下一步建议，并检查页面版本是否仍然一致。适合已有浏览器驱动、只想接入语义选择的应用。

## 示例场景：读退款条款，但页面在判断期间刷新了

用户希望了解退款期限，驱动已经看到页面上的退款条款区域。JEV 可以建议读取这一区域，但如果判断期间页面刷新，旧候选的位置或含义可能已经改变。

宿主为观察快照分配 `pageVersion`，选择前后核对版本。页面变更时返回 `STALE_PAGE`，应用应重新观察，而不是执行旧建议。

## 1. 把可见目标转换为候选

```java
import io.agentscope.extensions.judge.jev.application.JevBrowserPlanner;
import java.util.List;

var actions = List.of(new JevBrowserPlanner.Action(
    "policy", JevBrowserPlanner.Operation.READ, "当前可见的退款条款"));
String pageVersion = "page-v1";
```

候选必须来自当前实际观察，不能由模型凭空生成目标。这个接口只支持 READ、SCROLL、WAIT、DONE、BLOCKED；不执行点击、输入或提交。

## 2. 选择后重新检查版本

```java
var planner = new JevBrowserPlanner(selector);
var pending = planner.propose(ctx, "读取退款期限",
    pageVersion, actions,
    () -> browser.currentPageVersion(), false);
```

`selector` 配置见[候选分类](/v2/zh/jev/guides/application-api#初始化)。`browser.currentPageVersion()` 是你已有驱动的接口占位，不是框架内置方法。最后的 `false` 表示宿主尚未独立验证任务完成，所以即使模型推荐 DONE 也不能直接宣布完成。

## 3. 宿主决定是否执行建议

```java
var observed = pending.doOnNext(proposal -> {
    System.out.println("建议状态：" + proposal.reason());
    System.out.println("当前仍有效：" +
        proposal.validFor(browser.currentPageVersion()));
});
```

`validFor` 返回 true 只说明有动作且页面版本匹配。SHADOW 阶段仍只观察；宿主真正采用时，应把版本检查与执行绑定在同一个驱动操作中，避免检查之后再次发生页面变化。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios browser
```

离线用例依次演示：当前页面推荐 `policy`；页面变更后 STALE_PAGE；没有独立完成证据时 COMPLETION_NOT_VERIFIED。没有启动浏览器或执行动作，因此不能据此计算浏览器任务成功率。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 无建议与完整执行链

无选中候选、存在不确定项或判断失败时返回 ABSTAIN。重复动作 ID 或空页面版本为参数错误。预算和运行模式由 selector 提供，取消终止订阅。

希望由 AgentScope 驱动只读导航循环、记录实际动作并独立验证结果时，使用[浏览器执行工具](/v2/zh/jev/guides/browser-execution-api)。
