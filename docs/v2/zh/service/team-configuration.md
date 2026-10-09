---
title: "Team 角色、成员与策略参数"
en_link: /v2/en/service/team-configuration
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 由一个 Leader 和可委派的成员组成。先按[Team API 指南](/v2/zh/service/create-team)创建团队，再用本页参数维护成员、协作策略与运行策略。

## 管理 API

所有路径相对 Service 地址，使用用户 Bearer token 和已授权空间。列表请求必须提供 `tenant`、`namespace`；创建时将两者写入 JSON。示例与认证函数见 [Team API 指南](/v2/zh/service/create-team)。

| 操作 | API | 请求与返回 |
| --- | --- | --- |
| 创建 | `POST /api/v1/teams` | 必填 `tenant`、`namespace`、`name`、`leaderAgentId`；可同时传 `members`、`policy`；返回 `{team}` |
| 列表 / 详情 | `GET /api/v1/teams`、`GET /api/v1/teams/{id}` | 分别返回 `{items}`、`{team}` |
| 更新 | `PATCH /api/v1/teams/{id}` | 必填 `name`、`leaderAgentId`，携带当前 `expectedVersion`，并保留希望沿用的 `policy` 与 `description`；返回 `{team}` |
| 新增成员 | `POST /api/v1/teams/{id}/members` | `agentId`、`role` 及可选成员字段；返回 `{member}` |
| 更新成员 | `PATCH /api/v1/teams/{id}/members/{memberId}` | 必填 `role`，携带 `expectedTeamVersion`；可更新职责、能力要求和运行策略；返回 `{member}` |
| 移除成员 | `DELETE /api/v1/teams/{id}/members/{memberId}` | 成功返回 204；不取消正在运行的任务 |

`PATCH Team` 目前要求提交名称和 Leader，并会应用请求中的 policy，不是任意字段的局部合并。先 GET 当前定义再改写需要的字段，避免遗漏原策略。更新成员时同样保留要沿用的配置；其 `agentId` 不可在该 PATCH 中更换，改换 Agent 应移除后重新添加。

Team 响应的 `id`、`version`、`members[].id` 用于后续操作；`members[].agentId` 是注册 Agent 的身份，不能拿成员记录 ID 当作 Agent ID。Leader 不再重复放入成员列表，成员 Agent 和 role 均须唯一。

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

API 可显式设置策略；控制台创建表单也可能传入自己的初始值。以读取到的 `team.policy` 为准，不要把所有零值都解释为无限制或禁用。所有数值限制不能为负；`maxHops` 最大 64、`maxChildDepth` 最大 32、`maxFanout` 最大 256，具体操作仍受平台约束。

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

其他策略参数用于限制交付与资源消耗：

| 字段 | 含义 |
| --- | --- |
| `maxArtifactBytes`、`allowedArtifactMediaTypes` | Artifact 大小与允许的 MIME 类型 |
| `maxIssueTokens`、`maxIssueCostMicros` | 根据已回报用量检查 Issue 预算；费用单位为 micro |
| `issueSlaSeconds`、`taskTimeoutSeconds` | 工作截止时间默认值与任务超时限制 |
| `secretPolicy`、`piiPolicy` | `allow` / `block`，按平台规则检查协作内容 |

预算依赖运行时如实回报用量，内容检查也不能代替工具与数据权限。不要将这些字段理解为外部提供商账单的实时硬限额或完整的数据防泄漏系统。

## 可以混用哪些成员

| 模式 | 加入前验证 |
| --- | --- |
| Managed | 模型可用，Environment/Memory/Vault 绑定正确，可完成一次任务 |
| Hosted | Host 在线、provider 可用、定义映射通过，任务协作 MCP/CLI 可用 |
| External | 适配器具备任务入口与真实完成/失败回报；仅有观测注册不够 |

Leader 必须具备团队协调能力，包括委派、查看结果和节点完成/失败。普通成员只需完成其承担的角色；并非所有能进行 Chat 的 Agent 都适合当 Leader。

## Runtime policy

`runtimeBindingPolicy` 包含有序 `candidates`、`selectionMode`、`fallbackMode` 和可选 `retryPolicy`。候选中的 `binding` 指定执行后端，`requiredCapabilities` 与 `securityConstraints` 限制选择条件。成员覆盖要求显式设置 `selectionMode:"ordered"`、`fallbackMode:"disabled"` 或 `"fresh"`，并提供至少一个有效 candidate。候选 binding 使用实际运行绑定结构，不填写主机进程号或任意 URL。

选择遵循节点覆盖、成员覆盖、Agent 策略的层次。显式 fresh fallback 是新执行上下文，保留的工作证据必须在 Issue、评论与 Artifact 中。先验证单候选，再增加回退；扩容与回退都不会自动解决共享文件冲突。

Agent 级默认策略通过 `GET/PUT /api/v1/agent-runtime-policies/{agentId}` 管理。PUT 提交 `tenant`、`namespace`、非空 `candidates`，每个 candidate 的 `binding.bindingId` 必须属于该 Agent 且可用；服务会解析实际后端。可设置 `selectionMode`、`fallbackMode`、`maxConcurrency`、`queueTimeoutSeconds`、`attemptTimeoutSeconds`、`retryPolicy`，响应为 `{policy}`。GET 查询仍需携带 tenant/namespace。

下一步：[协作用法](/v2/zh/service/create-team#team-collaboration) · [工作原理](/v2/zh/service/create-team#team-execution)。
