---
title: "问题类型、评审状态与执行模式"
---

构建一个判断时，先决定你需要的是概率、单选结果还是分级评分；再决定应用如何使用它。结果符合结构并不保证语义正确。

## 先以条件性退款为例

用户要求“查订单，未发货再退款”。判断“退款工具是否适用”时，订单查询和退款都可能适用，应分别问是非题；判断“这次分析选哪个模型”时才用单选；评估一项风险的严重程度则用分级评分。

即使 JEV 认为退款操作合适，也要检查订单事实与执行权限。把业务输入、语义建议和执行结果分开，后面各项 API 的状态才不会被误读。

## 三种问题类型

| 类型 | API | 返回值 | 典型用途 |
|---|---|---|---|
| 是非判断 | `NoulQuestion` / `NoulAnswer` | 陈述为真的概率 | 工具是否适用、回答是否有依据 |
| 单选 | `ChoiceQuestion` / `ChoiceAnswer` | 选项、完整分布和置信度 | 模型路由、动作选择 |
| 评分 | `ScoreQuestion` / `ScoreAnswer` | 等级分布、加权分数和置信度 | 风险严重度、分级评估 |

一个 `SystemOneRequest` 包含共享 `state` 和按 ID 命名的多个问题。把业务材料放入 state，把判断标准写进问题；不要把金标答案或隐式授权混入待评输入。多选适用性应逐候选判断，不能用一次互斥 Choice 代替。

## 区分评审结果与执行决策

`JevJudge` 对 Noul 条件聚合：`PASS`、`FAIL`、`INCONCLUSIVE`、`ERROR`。它适合全部条件都必须满足的审核。期望为 false 时按 `1-p` 计算满足条件的概率。[定义与阈值](/v2/zh/jev/guides/judge-api)

`JevExecution.Decision` 表达组件是否形成有效建议：`DECIDED`、`INCONCLUSIVE`、`ERROR`、`CANCELLED`、`SKIPPED`。`DECIDED` 不等于任务成功，也不等于内容通过；应读取具体组件的结果。[模式与预算](/v2/zh/jev/guides/harness-runtime)

`JevMetricResult` 把状态、分类 label、分数 score 和可空 passed 分开。没有通过策略的分类指标不应被强制转成 PASS/FAIL。[轨迹指标](/v2/zh/jev/guides/metric-definitions)

## 判断不足时保留不确定性

低置信度、明确否定和请求失败是不同情况。工具选择可能回退原候选，执行防护可能拒绝受保护调用，事后评估则保留错误或弃权。按具体用途处理，不能把“没有有效判断”统一解释为允许。

概率和置信度需要按场景校准。文本模型模拟概率时尤其要保留其自报值属性，不把它当成测得的准确率。完整比较方法见[评测自己的 Harness](/v2/zh/jev/evaluation)。

## 从一个判断开始

先按[客户端示例](/v2/zh/jev/guides/client)提交“退款已完成”与“尚未发起退款”的记录，读取 Noul 概率；再按[条件评审](/v2/zh/jev/guides/judge-api)加入阈值和结果处理。可运行的 `JevApplicationScenarios judge` 用预设值展示 PASS、FAIL、INCONCLUSIVE 三种结果，帮助确认应用分支，而非验证模型准确率。
