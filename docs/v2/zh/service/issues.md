---
title: "Issues：交付与验收工作"
---

[English](/v2/en/service/issues)

Issue 保存一项工作的目标、负责人、讨论、执行记录和交付物。即使执行失败或服务重启，工作本身仍有可追踪的记录。用 Issue 管理“整理报告”“修复缺陷”这类需要完成和验收的任务。

## 创建第一项工作

打开 **WORK → Issues → New issue**。填写有明确结果的标题，例如“分析示例日志并输出错误分类报告”，在说明中给出输入位置、任务边界和期望交付物。

选择 **Sharing**：Private 仅自己可见；Namespace members 面向空间成员。创建后可添加单独协作者。共享范围包含执行记录和附件，因此应在提交资料前确认。

负责人可选 Agent、Team 或 Human，也可选择 Workflow 作为执行目标。选择 Workflow 会使用最新已发布 revision，并把实际版本记录到执行中；没有发布版本时应先完成发布。负责人可以稍后补充。创建后检查 **Executions**，确认是否已产生执行，而不是以 Issue 创建成功推断 Agent 已开始工作。

## 把验收标准写清楚

在详情中补充 Acceptance criteria，例如：

```text
- 读取指定的 sample.log，不访问生产日志。
- 交付 report.md，包含错误类型、数量与三条带行号的证据。
- 对无法确认的原因标记“待确认”，不要当作事实。
```

优先级表达重要程度，Due date 表达截止时间；它们不能代替任务说明。大型任务可创建子 Issue，分别明确负责人和交付物，在父 Issue 汇总验收。

## 讨论、文件与跟进

在评论中补充信息、回复线程或提及需要参与的 Agent。检查路由结果或执行记录，确认消息是否产生后续工作。解决一个评论线程表示该讨论已处理，不代表整个 Issue 验收通过。

上传文件作为 Artifact，便于其他参与者查看交付物。Subscribe 用于接收更新；订阅不会扩大对私有工作的访问权限。来源区域记录关联的 Chat、Channel 或其他入口。

## 理解状态

| 状态 | 用户应关注什么 |
| --- | --- |
| Backlog / Todo | 需求是否完整，负责人是否已安排 |
| In progress | 最新执行、讨论和产物是否在推进目标 |
| Blocked | 缺少的信息、授权或依赖是什么 |
| In review | 按验收标准检查结果及子 Issue |
| Done | 已按该 Issue 的完成策略完成 |
| Cancelled | 工作已取消，保留原因供后续查询 |

一次 Run 成功不等于 Issue 已验收。`review` 策略需要人工确认；`automatic` 策略允许按自动完成规则收敛；`external` 由相应外部流程管理。以详情中的 Policy 和 Issue 当前状态为准。

## 验收或要求修改

在 **Inbox** 的 Review result 中查看最新结果、文件和子 Issue，点击 **Accept result** 将 Issue 完成；或 **Request changes** 写明缺失项，让 Issue 回到 In progress。退回会记录反馈，但不会自动启动下一次执行，需要继续派发或跟进。详见 [Inbox](/v2/zh/service/inbox)。

运行失败时先查看对应 Execution 的节点、Attempt 和错误，再决定重试。重新执行创建新的执行记录，不抹掉失败证据。已经 Done 的工作可按需要重新打开；归档用于收纳历史。

下一步：[Team 协作](/v2/zh/service/team-collaboration) · [执行与会话参考](/v2/zh/service/sessions)。
