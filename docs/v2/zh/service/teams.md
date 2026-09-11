---
title: "Teams：组织协作角色"
---

[English](/v2/en/service/teams)

**DESIGN → Teams** 把多个 Agent 组织成一个可分派工作的团队。Lead 负责理解目标、选择成员和汇总结果；成员提供专项能力。需要固定顺序和分支规则时选择 [Workflow](/v2/zh/service/workflows)。

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
