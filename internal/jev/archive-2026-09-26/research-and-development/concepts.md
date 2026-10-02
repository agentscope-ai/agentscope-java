---
title: "核心概念与决策契约"
---

# 核心概念与决策契约

## 三种题型

| 题型 | 适用问题 | 答案含义 | 常见误用 |
| --- | --- | --- | --- |
| Noul | 用户是否提出退款条件；证据是否支持声明 | 陈述为真的概率 | 把低概率当调用错误；混淆风险概率与安全概率 |
| Choice | 在已授权候选中选工具、部门、下一步 | 所选项、候选分布及confidence | 认为最高项就一定适用；跨不同候选集合直接比较概率 |
| Score | 按显式等级衡量覆盖程度或影响 | 加权分数、各等级概率、confidence | 当成任意连续回归值；忽略量表方向 |

confidence不是正确率保证，也不能与最大候选概率混用。Noul没有单独confidence字段。不同后端的概率与confidence不可直接继承阈值，Qwen文本生成概率应标记为`verbalized`，Jev原生概率标记为`native`。

## 原子问题如何组织

一次Judge可同时问“回答是否覆盖目标”“事实是否有证据”“是否包含越权承诺”；规则分别归约。多个问题共享同一个state才合并请求。不同文档对应不同state的调用属于批量调度，不等于一个System One请求天然处理多个独立state。

Choice应有明确弃权语义，或额外Noul判断任一候选是否适用。单一候选也需检查适用性。`none`、`unknown`、`needs_clarification`不是服务错误的替代标签。

## 应用决策信封（拟议，不是现有API）

```json
{
  "purpose": "support.intent",
  "definitionVersion": "v1",
  "policyVersion": "shadow-v1",
  "stateVersion": "ticket-42:7",
  "status": "ABSTAIN",
  "reasonCodes": ["EVIDENCE_INCOMPLETE"],
  "evidenceIds": ["message-9"],
  "action": "request_more_evidence",
  "probabilityKind": "native",
  "mode": "SHADOW"
}
```

状态至少区分`ACCEPT`、`REJECT`、`ABSTAIN`、`ERROR`；动作另设，避免`ACCEPT`被误解为资金操作已授权。原始模型输出与策略决策分开保存。`evidenceIds`只能引用输入白名单；需要自然语言解释时由规则模板或生成模型处理，Jev不生成自由文本证据说明。

## 四道校验

1. 请求前：主体权限、候选工具及数据访问范围已确定。
2. 响应后：题目ID、类型、分布键、有限数值、概率和完整合法。
3. 策略层：用途专属阈值、证据完整性、人工确认要求和错误回退。
4. 执行前：主体、对象、参数hash、状态版本与有效期仍一致；不一致则重新取证。

阈值通过校准集选择，再在独立测试集冻结验收；文档中的任何示例数值都不是生产推荐阈值。同一数据训练/调参/验收会夸大收益。重复采样一致不代表正确，给state添加随机字段也不能证明服务端产生独立样本。

将[决策配置示例](examples/decision-policy.json.txt)作为设计讨论输入；当前SDK不会自动加载该文件。
