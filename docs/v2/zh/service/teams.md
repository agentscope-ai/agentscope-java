---
title: "Team：概览与创建"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

**DESIGN → Teams** 把多个 Agent 组织成一个可分派工作的团队。Lead 负责理解目标、选择成员和汇总结果；成员提供专项能力。需要固定顺序和分支规则时选择 [Workflow](/v2/zh/service/workflows)。

首次使用请先按[操作指南](/v2/zh/service/create-team)完成创建或接入。本分类集中提供详细配置、支持能力和工作原理。

## 本章节

- [协作用法：委派与交付](/v2/zh/service/team-collaboration)
- [角色、成员与策略参数](/v2/zh/service/team-configuration)
- [工作原理与结果收敛](/v2/zh/service/team-execution)

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/teams.png" alt="创建 Team 时选择 Leader 和成员" />
</Frame>

填写职责后，在 **Leader Agent** 中选择统一接收任务的 Agent，在 **Additional members** 中选择可协作成员。需要进一步约束委派行为时，再展开 **Advanced coordination instructions**。

## 建立第一个团队

先分别验证成员 Agent 可以完成小任务，再创建 Team。以报告团队为例，选择一个能协调任务的 Agent 作为 Lead，添加 Researcher 和 Reviewer 两个成员。

在 **Roles & members** 中写清每个角色的职责与输出：Researcher 提供带来源的事实，Reviewer 检查证据与不确定性。在团队 Instructions 中写清共同目标、协作边界和最终交付格式。不要把相同的宽泛指令复制给所有成员。

Lead 决定一项请求需要哪些成员，并不保证每次都会运行整个名单。团队配置描述可用能力，不是固定执行图。

## 检查就绪度

| 状态 | 含义与处理 |
| --- | --- |
| Ready | 当前配置与成员能力满足就绪检查，可进行小任务验证 |
| Degraded | 部分成员或能力不可用，阅读每个成员的原因 |
| Unavailable | 当前无法开始有效协作，优先修复 Lead 或运行时依赖 |

成员可能使用不同运行方式。配置 Runtime policy 或成员覆盖前，确认所需能力、目标运行时及安全约束都能满足；更多候选运行时不意味着无损迁移会话。

## 试运行与发布

在 Issues 创建一个范围很小的任务并选择该 Team。查看工作讨论、Task map 和各执行结果，确认 Lead 能交付汇总，且失败或缺少信息时能解释原因。需要复核的工作保留人工验收。

Team 可以作为 Issue 和 Automation 的执行目标，也可以通过 Endpoint 向应用提供 job 能力。更新团队后验证新执行使用的成员配置；旧执行保留其团队快照供追溯。

深入教程：[Team 协作、委派与扩展](/v2/zh/service/team-collaboration) · [Endpoint](/v2/zh/service/endpoints)。
