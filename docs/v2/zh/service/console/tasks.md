---
title: "对话、任务分派与反馈"
en_link: /v2/en/service/console/tasks
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

对话适合澄清需求，Issue 适合安排负责人、保留交付记录并验收结果。在控制台中，你可以先用 Chat 讨论，再把目标明确的工作交给 Agent 或 Team；也可以直接创建 Issue。应用集成相同流程时，使用 [Issue API](/v2/zh/service/issues)、[反馈与验收 API](/v2/zh/service/inbox)和[事件接口](/v2/zh/service/sse-events)。

## 先用 Chat 明确需求

打开 **Work → Chat → New chat**，选择一个可用 Agent，输入小请求并等待回复。例如先让资料助手整理会议待办，再追问缺少哪些信息。Conversation 用于阅读消息，Events 用于查看运行时提供的事件；遇到工具确认时，核对操作对象和参数后再决定。

<Frame caption="Chat 界面示例，使用固定演示数据。">
  <img src="/imgs/service/chat.png" alt="Chat 消息、事件与 Create issue 入口" />
</Frame>

刷新或暂时断线后，重新打开已有 Chat 查看状态和历史。运行可能仍在继续，重发相同请求可能产生另一轮工作。是否支持持续会话、恢复与工具确认，取决于所选 Agent 的运行时能力。

讨论形成目标后，点击 **Create issue**。检查预填内容，把关键结论、材料位置和验收要求写进工作说明；新 Issue 保存 Chat 来源引用，但不应假设协作者已经阅读了整段私人对话。

历史列表支持 Pin、Archive 和 Delete chat。删除后可在 Deleted 中使用 Restore chat；这些整理历史的操作不等于取消执行。

## 创建并分派一项工作

打开 **Work → Issues → New issue**，或者继续使用从 Chat 打开的创建窗口。

1. 填写目标明确的标题，例如“整理本周会议待办”，在 Description 中给出材料、工作边界和交付物。
2. 选择 Sharing。Private 仅自己可见，Namespace members 面向空间成员；共享范围包含执行记录和附件。创建后还可以添加单独协作者。
3. 选择 Agent、Team 或 Human 作为负责人。如果希望按固定流程执行，选择 Workflow 作为执行目标，它需要已有发布版本。
4. 创建后进入详情，补充 Acceptance criteria，并检查 Executions 是否已经产生执行。

例如，验收标准可以写成：

```text
交付一份清单，逐项列出任务、负责人和期限。
所有条目必须能在给定会议材料中找到依据。
时间或负责人缺失时标为待确认，不自行补全。
```

负责人可以稍后安排。在详情的 Assignee 中可以改派给 Agent、Team 或 Human；Workflow 执行可以从其 **Run Workflow** 入口关联已有 Issue。选择负责人后仍应检查 Executions，不能只凭 Issue 创建成功判断工作已经开始。

<Frame caption="Issue 列表，使用固定演示数据。">
  <img src="/imgs/service/issues.png" alt="Issue 的负责人、优先级与状态" />
</Frame>

## 跟进讨论与交付

在 Issue 详情中阅读评论，使用 Attach 上传材料或交付物。需要补充信息时发表评论；需要特定 Agent 参与时使用 Mention，并检查讨论中的反馈和后续执行。大型工作可以拆成子 Issue，分别约定负责人和结果，再在父 Issue 汇总。

Executions 展示实际执行记录。打开对应执行查看步骤、尝试和错误；Team 工作还可以结合任务图查看成员分工。执行失败时先确认失败发生在哪一步，再决定如何重新派发，避免重复已经完成的外部操作。

| 当前状态 | 接下来关注什么 |
| --- | --- |
| Backlog / Todo | 资料是否齐全，负责人是否明确 |
| In progress | 执行、讨论和产物是否在推进目标 |
| Blocked | 缺少什么信息、授权或依赖 |
| In review | 结果是否达到验收标准 |
| Done / Cancelled | 最终结果或取消原因是否完整 |

单次执行成功和整项工作完成是不同层次。详情中的 Policy 决定是否需要人工验收；解决评论线程也不等于完成 Issue。

## 在 Inbox 中验收与审批

打开 **Work → Inbox**，使用 Needs action 找到需要决定的事项，或用 Unread 查看尚未阅读的更新。选择消息后，在右侧检查关联工作；Open issue 可以打开完整记录。

<Frame caption="Inbox 结果验收界面，使用固定演示数据。">
  <img src="/imgs/service/inbox.png" alt="Inbox 中的关联 Issue 与结果验收" />
</Frame>

对于 Review result，先对照 Acceptance criteria 检查结果、附件和子 Issue。符合要求时选择 **Accept result**；需要补充时选择 **Request changes**，写明缺失项，再点击 **Send review**。要求修改会记录反馈并将 Issue 退回 In progress，后续仍需安排新的执行或跟进。如果页面提示工作已经变化，使用 **Refresh review** 重新核对后再决定。

审批条目则用于决定某项操作是否允许继续。核对请求者、目标、原因及关联工作，再批准或拒绝；这和验收最终交付是两类决策。Chat 内的交互式工具确认由对应会话处理，不保证都出现在 Inbox。

阅读或归档通知只影响信箱整理，不会代替验收、审批或取消。反馈的版本约束和自动化接入方式见 [Inbox API](/v2/zh/service/inbox)；需要多人协作或固定步骤时，继续阅读[团队与流程编排](/v2/zh/service/console/orchestration)。
