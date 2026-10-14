---
title: "显式规划工具交换对的保留与卸载"
---

`JevContextPlanner` 为应用维护的工具历史生成保留和卸载建议，并能按原顺序恢复完整交换对。适合已经自行组织上下文、希望在下次模型调用前减少旧材料的应用。

## 示例场景：修复当前问题，暂时移开旧搜索结果

一个修复任务已经读取过旧版本资料，随后又取得当前补丁的测试结果。继续把旧搜索全文放入每次请求会占用上下文，但单独删掉工具结果会留下没有结果的调用。

应用把一次调用和对应结果组成 `Exchange`。JEV 只判断未固定的完整交换对是否仍有必要；当前测试证据被应用标记为固定项，始终保留。

## 1. 准备完整交换对

```java
import io.agentscope.extensions.judge.jev.application.JevContextPlanner;
import java.util.List;

var exchanges = List.of(
    new JevContextPlanner.Exchange(
        "old-search", "search(old issue)", "旧版本资料", false),
    new JevContextPlanner.Exchange(
        "current-test", "test(current change)", "当前测试证据", true));
```

这里用字符串演示 call/result；应用中可传独立的消息快照。最后一个参数 `pinned=true` 表示宿主要求保留，不交由模型删除。每对必须同时有调用和结果，ID 不能重复。

## 2. 结合当前任务生成计划

```java
var planner = new JevContextPlanner(selector);
var pending = planner.plan(
    ctx, "根据当前测试证据修复问题", exchanges);
```

`selector` 使用[应用组件初始化](/v2/zh/jev/guides/application-api#初始化)，`ctx` 属于当前调用。模型明确判为不需要的交换对进入 `archive()`；不确定项仍在 `retained()` 中。

## 3. 查看精简建议，按需恢复

```java
var observed = pending.doOnNext(plan -> {
    System.out.println("下次输入建议保留：" + plan.retained());
    System.out.println("可移开：" + plan.archive().keySet());
    System.out.println("原顺序完整材料：" + plan.restore());
});
```

例如旧搜索被明确判为不需要时，`retained()` 只有当前测试，`archive()` 保存旧搜索，`restore()` 返回原来的两对。应用明确采用计划后，才能改变下次模型输入；SHADOW 期间仅观察。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios context
```

离线预设把 `old-search` 放入内存归档，保留 `current-test`，恢复后顺序仍为旧搜索、当前测试；没有重新执行任何工具。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 持久化与失败处理

这里的 `archive()` 是内存映射，不会保存文件、释放整个进程的内存或自动修改 AgentState。Plan 仍引用宿主 call/result，使用期间不要修改它们。判断失败或关闭时保留全部交换对。

需要由 Harness 自动触发压缩、采用前持久归档和跨请求恢复时，使用[上下文压缩](/v2/zh/jev/guides/context-compaction-api)与[归档恢复](/v2/zh/jev/guides/context-archive)。
