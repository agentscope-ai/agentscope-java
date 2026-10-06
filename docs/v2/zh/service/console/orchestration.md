---
title: "团队与流程编排"
en_link: /v2/en/service/console/orchestration
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

当一项工作需要多个 Agent 时，先确定分工是否固定。Team 由 Leader 根据目标委派、协调并汇总成员结果；Workflow 把已知步骤、依赖和人工关口明确写成流程。两者都能从控制台配置，也能通过 API 创建和运行。

本页以“整理材料并复核”为例介绍操作。定义字段和应用调用见 [Team API](/v2/zh/service/create-team)、[Workflow API](/v2/zh/service/workflows)和[编排执行参考](/v2/zh/service/sessions)。

## 先组成一个 Team

准备“资料助手”和“复核助手”两个具备任务能力的 Agent，分别验证一个小任务。打开 **Design → Teams → New team**，填写团队名称，Leader Agent 选择资料助手，Additional members 添加复核助手。

<Frame caption="Team 创建示例，使用固定演示数据。">
  <img src="/imgs/service/teams.png" alt="Team Leader 与成员配置" />
</Frame>

展开 **Advanced coordination instructions**，把协作要求写清楚：

```text
Leader 先整理材料，再委派复核助手检查事实和缺失项。
复核助手返回问题与修订建议；Leader 修订后交付统一结果。
标明仍未确认的信息，并在成员任务处理完成后结束团队工作。
```

点击 **Create team**，检查团队及成员是否就绪。成员可以来自不同运行方式，但都必须具备本次工作所需的任务能力。需要调整 Leader、成员和协作要求时，进入 **Team orchestration**；参数含义见 [Team 配置](/v2/zh/service/team-configuration)。

随后在 **Work → Issues → New issue** 填写资料和验收要求，负责人选择刚创建的 Team。打开 Issue 的执行记录和 Team 的 Activity，检查 Leader 是否委派、成员是否返回结果、Leader 是否完成最终汇总。成员完成自己的任务后，团队工作仍可能需要 Leader 继续处理。

## 把固定步骤写成 Workflow

如果每次都必须“整理 → 人工审批 → 最终复核”，使用 Workflow 更容易固定关口。打开 **Design → Workflows** 创建流程，填写名称并选择 Initial Workflow Agent，先得到一个可验证的 Agent 步骤。

1. 进入 **Workflow design**，确认起始节点 key 和目标 Agent。
2. 使用 **Add node** 添加审批和复核节点，再在连接配置中设置依赖。
3. 按需要填写节点输入映射。例如从运行输入读取资料时，可以使用 CEL 表达式 `run.input.request`；下游字段应与上游实际输出一致。
4. 点击 **Save draft**，执行 **Validate**，修复缺少目标、未知节点引用或环路等错误。
5. 点击 **Publish saved draft** 生成发布版本，然后用 **Run Workflow** 选择版本、关联新建或已有 Issue，填写 JSON input 并启动。

<Frame caption="Workflow 设计页面，使用固定演示数据。">
  <img src="/imgs/service/workflows.png" alt="Workflow 节点、连接与发布操作" />
</Frame>

设计器支持 Agent、Team、条件、汇合、审批、定时等待、外部信号和子流程节点。按实际目标逐步添加；例如等待外部系统回传结果时，使用 signal 节点，并由应用调用信号 API，或在执行详情可用的信号操作中提交数据。

草稿用于编辑，发布版本固定流程拓扑。修改草稿不会改变已有运行，也不会自动切换已经发布给应用的版本。高级字段可以使用页面的 JSON 编辑入口，最终仍需服务端校验。

## 跟进并控制运行

从 Workflow 的运行历史或 Issue 的 Executions 打开执行，查看节点状态、输出、文件和失败原因。需要人工审批时，在对应请求或 Inbox 中决定；批准只放行该关口，后续操作仍由目标 Agent 按自身权限执行。

运行中的 **Pause** 阻止新节点调度，已经执行中的节点仍可返回。**Resume** 恢复调度；**Cancel** 请求取消执行中的任务与子运行。终态后 **Rerun** 创建新的执行记录，保留与来源的关联。重新运行前应确认是否会重复写文件、发送消息或产生其他业务操作。

运行完成后，回到 Issue 检查交付与验收状态。取消编排不等于删除 Issue，节点成功也不等于人工验收通过。

## 发布给业务应用

在 Team 的 **Connections → Publish as API** 创建 Job Endpoint。Workflow 需先发布 revision，再在 **Connections** 中为选定版本发布 Job Endpoint。填写 Name 和 Slug，点击 **Create & publish**。

发布后进入 **Manage API → Security**，在 **Calling application** 选择自己管理的 Application，或新建一个。勾选调用所需的 scope，再填写凭据名称并点击 **Create**。保存 API key 后，用 **Test API** 验证；创建或发布 Endpoint 本身不会签发凭据。完整的 [Application 与凭据 API 流程](/v2/zh/service/endpoints#给调用应用签发凭据)与这些页面操作使用同一套资源。

应用提交 Job 后，通过返回的状态地址读取结果，通过事件地址订阅执行过程。Team 内部如何委派、Workflow 如何调度节点，由编排配置决定；应用使用发布的调用契约跟进工作。

后续发布新 Workflow 版本时，需要明确更新 Endpoint release 才会切换应用入口的目标版本。完整流程见 [Endpoint](/v2/zh/service/endpoints)与 [SSE 事件](/v2/zh/service/sse-events)。需要让工作定时或由外部事件发起时，继续阅读[自动化与消息渠道](/v2/zh/service/console/automation)。
