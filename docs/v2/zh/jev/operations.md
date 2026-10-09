---
title: "配置预算与消费决策记录"
---

JEV 增加的是判断步骤。为它分配独立预算，并把建议、实际执行和最终业务结果分开观测，才能判断是否改善了你的 Harness。

## 场景：查明退款助手慢在哪里

一次客服请求先选择工具，再检查退款调用，最后审核回答。整个请求变慢可能来自生成模型、订单接口，也可能来自 JEV 重试。应分别记录每个判断的版本、预算和耗时，并关联同一 run 的工具执行结果，不能只看整次 Agent 的总时间。

## 给判断设置预算并接入观测

下面用有界内存队列展示观察器的接入位置；生产应用可由异步消费者定期读取并写入自己的观测系统。

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;

var decisions = new ArrayBlockingQueue<JevExecution.Record>(256);
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "orders-v1",
    (ctx, record) -> decisions.offer(record));
```

队列满时 `offer` 返回 false，当前示例会丢弃该条观测；不阻塞 Agent，也不提供可靠审计保证。应用可按需增加丢弃计数，并在入队前附上当前 run/session 的身份。每项逻辑判断的预算包含该项所有批次、重试和退避；不自动限制整个 Agent。响应中间件的 roundBudget 还包括原模型生成；浏览器工具预算包括页面操作与验证；含 Source 的具体重载是否覆盖数据读取，见对应 API。

观察器应快速返回。RuntimeException 被隔离不影响 Agent，但同步阻塞回调不会自动获得后台线程或额外超时保护。HTTP 请求、数据源和存储分别设置自身超时。

## 应记录哪些字段

| 字段 | 用途 |
|---|---|
| purpose、version、mode | 区分用途、定义版本和是否接管 |
| status、reason | 区分有效判断、不确定、跳过、取消和错误 |
| elapsed | 该判断环节的附加耗时 |
| recommendation | 各用途的工具名、模型 ID、动作或建议摘要 |
| RuntimeContext 身份 | 关联你的 run/session，隔离并发调用 |

不要用 SHADOW 的推荐模型统计实际派发模型，也不要用“建议拒绝”统计已阻止的工具。阶段路由的 CallRecord 记录派发与模型用量；轨迹、评审、检索等报告的 calls 记录判断请求。没有返回 usage 的请求成本为未知，不是零。

## 取消与失败

取消向在途请求和数据读取传播，组件停止后续派发。取消不一定撤销已经完成的远端请求或工具操作；需要结合执行收据处理未知结果。

根据用途读取失败行为：工具选择与路由保留原输入，受保护工具预检失败拒绝执行，发布前审核失败不发布。事后轨迹评估与长任务监督只记录结果，不重放原 Agent。

## 从影子模式切换

先固定金标、模式和定义版本，记录分场景错误与弃权，再按[评测指南](/v2/zh/jev/evaluation)选择阈值。切换某一用途到 ENFORCE 时，确认其数据源、权限和失败处理已经接入。线上仍保留独立的工具执行结果和业务质量指标。

Service 的配置与事件查询见 [Service 接入](/v2/zh/jev/guides/service-api)。

## 验证回退是否符合用途

[工具防护对比用例](/examples/jev/tool-guard/README.md.txt)同时记录检查耗时与实际派发，包含离线超时、取消、观察器异常和并发隔离测试。先跑这些固定分支，再对你的业务数据测量质量与延迟；不要用离线模拟耗时作为线上容量估计。
