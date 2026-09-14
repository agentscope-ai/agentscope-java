---
title: "Team 角色、成员与策略参数"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 由一个 Leader 和可委派的成员组成。先按[创建团队](/v2/zh/service/teams)准备成员，再在 Roles & members、协作策略和 Runtime policy 中细化配置。

## 团队和成员字段

| 层次 | 字段 | 用途 |
| --- | --- | --- |
| Team | `name`, `description` | 团队名称与适合处理的工作 |
| Team | `leaderAgentId` | 统一接收任务、协调并汇总的 Agent |
| Team | `instructions` | 团队目标、委派规则、失败处理与交付要求 |
| Team | `status` | `active` 或 `disabled` |
| Member | `agentId` | 同一范围内的 Agent 身份 |
| Member | `role` | 职责标签，例如 researcher、reviewer |
| Member | `instructions` | 该成员在团队中的具体职责 |
| Member | `capabilityRequirements` | 派发所需能力要求 |
| Member | `runtimeBindingPolicy` | 该团队成员的运行候选及回退策略 |

例如 Researcher 必须交付来源和结论，Reviewer 必须指出证据缺口；Leader 负责消除冲突、说明未完成项并提交一份最终结果。成员角色不能代替 Agent 自己的模型、工具和资源配置。

## 协作限制

| `policy` 字段 | 控制内容 | 零值语义 |
| --- | --- | --- |
| `maxActiveTasks` | 并发任务上限 | 使用默认值 32 |
| `maxHops` | 委派跳数 | 使用默认值 8 |
| `maxFanout` | 单次委派接收者数量 | 不增加 Team 专属限制 |
| `maxChildDepth` | 子 Issue 深度 | 使用默认值 4 |
| `maxChildIssues` | 每个父 Issue 的子 Issue 数量 | 不增加 Team 专属限制 |
| `maxTaskRetries` | 每个任务的重试限制 | 不增加 Team 专属限制 |
| `allowExternalDelegation` | 是否允许委派给名单外 Agent/Team | false |
| `allowMentionAll` | 是否允许面向全体成员的委派 | false |
| `requireReview` | 按执行策略要求人工复核 | false |

“名单外委派”不是指 External Agent 运行模式。一个在成员名单中的 External Agent 仍然是本团队成员。

当前创建表单会写入显式策略，例如并发 32、fanout 8、hops 8、child depth 8、child issues 64；这些初始值与上表的零值处理不同。保存前检查详情页实际值。不要把零值统一解释为无限制或禁用。

下面是一个小团队的策略片段，可用于理解字段组合：

```json
{
  "policy": {
    "maxActiveTasks": 4,
    "maxFanout": 3,
    "maxHops": 4,
    "maxChildDepth": 2,
    "maxChildIssues": 8,
    "allowExternalDelegation": false,
    "allowMentionAll": false,
    "requireReview": true
  }
}
```

修改 Team 时 API 使用 `expectedVersion` 做版本检查。并发、层级和人数限制不构成外部模型账单的硬上限；还需按实际 provider 的用量与账户策略管理预算。

## 可以混用哪些成员

| 模式 | 加入前验证 |
| --- | --- |
| Managed | 模型可用，Environment/Memory/Vault 绑定正确，可完成一次任务 |
| Hosted | Host 在线、provider 可用、定义映射通过，任务协作 MCP/CLI 可用 |
| External | 适配器具备任务入口与真实完成/失败回报；仅有观测注册不够 |

Leader 必须具备团队协调能力，包括委派、查看结果和节点完成/失败。普通成员只需完成其承担的角色；并非所有能进行 Chat 的 Agent 都适合当 Leader。

## Runtime policy

`runtimeBindingPolicy` 包含有序 `candidates`、`selectionMode`、`fallbackMode` 和可选 `retryPolicy`。候选中的 `binding` 指定执行后端，`requiredCapabilities` 与 `securityConstraints` 限制选择条件。配置时使用控制台/API 提供的有效绑定标识，不填写主机进程号或任意 URL 代替。

选择遵循节点覆盖、成员覆盖、Agent 策略的层次。显式 fresh fallback 是新执行上下文，保留的工作证据必须在 Issue、评论与 Artifact 中。先验证单候选，再增加回退；扩容与回退都不会自动解决共享文件冲突。

下一步：[协作用法](/v2/zh/service/team-collaboration) · [工作原理](/v2/zh/service/team-execution)。
