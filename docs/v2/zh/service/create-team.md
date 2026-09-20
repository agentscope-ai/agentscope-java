---
title: "Team：创建与派发协作任务"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

使用 Team 让 Leader 根据工作目标选择成员、委派任务并汇总结果。先分别验证成员能独立完成小任务，再组成团队。

## 创建一个复核团队

准备两个具备任务能力的 Agent：资料助手负责整理材料，复核助手负责检查证据与缺失信息。在 **DESIGN → Teams** 新建团队，选择资料助手为 **Leader Agent**，在 **Additional members** 中添加复核助手。

<Frame caption="Team 创建界面，使用固定演示数据。">
  <img src="/imgs/service/teams.png" alt="选择团队 Leader 与协作成员" />
</Frame>

在协作指令中写明：

```text
Leader 先整理材料，再委派复核助手检查事实和缺失项。
复核助手返回问题与修订建议；Leader 修订后交付统一结果。
说明仍未确认的信息，并在成员任务处理完成后结束团队工作。
```

保存后检查就绪信息。需要调整角色或策略时查看参考手册的[Team 配置](/v2/zh/service/team-configuration)。

## 从 Issue 派发

在[控制台创建 Issue](/v2/zh/service/issues)，填写材料、预期输出和验收标准，选择该 Team。观察 Task map 与 Executions，检查 Leader 是否委派、成员是否提交结果，以及最终是否形成一份统一交付。

有人工验收时在[信箱](/v2/zh/service/inbox)检查结果并作出决定。成员的单次执行完成后，仍需确认 Leader 完成汇总和团队工作。

## 提供给应用

在 Team 的 **Connections → Publish as API** 发布 Job Endpoint。应用按[Endpoint 指南](/v2/zh/service/endpoints)提交请求，再按[SSE 反馈指南](/v2/zh/service/sse-events)接收过程和查询结果。

固定步骤、分支或审批关口使用[Workflow](/v2/zh/service/workflows)。成员能力、委派策略与协作原理统一收录于[Team 参考手册](/v2/zh/service/teams)。

完整团队可参考[全 Hosted 研发协作](/v2/zh/service/cases/sdlc-team)或[Managed 售前方案](/v2/zh/service/cases/presales-team)，分别验证代码交付与知识型工作的委派、复核和汇总。
