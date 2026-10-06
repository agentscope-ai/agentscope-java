---
title: "Team：概览与创建"
en_link: /v2/en/service/teams
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 把多个已注册 Agent 组织成一个可分派工作、可发布为 API 的团队。Lead 负责理解目标、选择成员和汇总结果；成员提供专项能力。需要固定顺序和分支规则时选择 [Workflow](/v2/zh/service/workflows)。

首次使用请先按[操作指南](/v2/zh/service/create-team)完成创建或接入。本分类集中提供详细配置、支持能力和工作原理。

## 本章节

- [协作用法：委派与交付](/v2/zh/service/team-collaboration)
- [角色、成员与策略参数](/v2/zh/service/team-configuration)
- [工作原理与结果收敛](/v2/zh/service/team-execution)

## API 与资源关系

`POST /api/v1/teams` 创建团队，`GET /api/v1/teams/{teamId}` 读取定义，`GET /api/v1/teams/{teamId}/overview` 查看概览。列表查询 `GET /api/v1/teams` 要传入 `tenant` 和 `namespace`。这些管理操作使用用户 Bearer token 和空间授权，返回的 `team.id` 用于分派和发布。

给 Team 分派工作使用 `POST /api/v1/issues` 的 `assigneeType:"team"` 与 `assigneeRef`；Automation 使用相同负责人类型。Workflow 的 team 节点通过 `teamRef` 引用团队。为应用发布服务时，Endpoint 使用 `targetType:"team"`、`targetRef` 与 job 模式。字段名字属于各自资源的协议，不应互相替换。

团队配置定义可用能力，并不是固定执行图。以报告团队为例，Researcher 提供带来源的事实，Reviewer 检查证据，Leader 决定何时使用成员并交付统一报告。每个角色都应有独立可验证的输出。

创建与调用的完整请求见 [Team API 指南](/v2/zh/service/create-team)，修改字段与并发控制见[配置参数](/v2/zh/service/team-configuration)。图形操作移至[控制台：团队与编排](/v2/zh/service/console/orchestration)。

## 检查就绪度

| 状态 | 含义与处理 |
| --- | --- |
| Ready | 当前配置与成员能力满足就绪检查，可进行小任务验证 |
| Degraded | 部分成员或能力不可用，阅读每个成员的原因 |
| Unavailable | 当前无法开始有效协作，优先修复 Lead 或运行时依赖 |

成员可能使用不同运行方式。配置 Runtime policy 或成员覆盖前，确认所需能力、目标运行时及安全约束都能满足；更多候选运行时不意味着无损迁移会话。

## 试运行与发布

通过 Issue API 创建一个范围很小的任务并指定该 Team。读取工作讨论、Run graph 和各执行结果，确认 Lead 能交付汇总，且失败或缺少信息时能解释原因。需要复核的工作保留人工验收。

Team 可以作为 Issue 和 Automation 的执行目标，也可以通过 Endpoint 向应用提供 job 能力。更新团队后验证新执行使用的成员配置；旧执行保留其团队快照供追溯。

深入教程：[Team 协作、委派与扩展](/v2/zh/service/team-collaboration) · [Endpoint](/v2/zh/service/endpoints)。
