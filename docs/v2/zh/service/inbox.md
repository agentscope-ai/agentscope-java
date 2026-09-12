---
title: "Inbox：通知、审批与验收"
---

[English](/v2/en/service/inbox)

Inbox 汇集与你相关的工作更新、审批和结果验收。每天先处理需要你作出决定的事项，再阅读普通通知。

## 找到需要处理的消息

打开 **WORK → Inbox**，使用 Status 筛选：Needs attention、Needs action、Unread、All messages 或 Archived。Unread 表示尚未阅读，Needs action 表示仍需要决策；读过消息不等于处理完工作。

选择消息后，右侧显示关联 Issue 或审批详情。Open issue 可进入完整工作记录。消息为空时先检查筛选条件和账号/空间，不要直接认定执行没有产生事件。

## 验收工作结果

1. 打开 review request，确认正在验收的 Issue 标题。
2. 阅读结果、Acceptance criteria 和附件；有子 Issue 时查看其结果。
3. 满足标准时点击 **Accept result**，确认 Issue 变为 Done。
4. 不满足时点击 **Request changes**，填写具体缺失项和期望结果，再点击 **Send review**。

要求修改会把 Issue 退回 In progress 并记录反馈，**不会自动发起新的执行**。查看子 Issue 预览不会把当前验收对象切换为子 Issue。评论线程的 Resolve 也不会完成验收。

若页面提示工作已变化，点击 Refresh review 后重新核对结果再决策。系统不会把你针对旧结果的确认静默应用到新版本。

## 处理审批

审批详情说明请求者、操作目标、原因以及关联的工作。根据当前请求给出批准或拒绝决定，必要时附上原因。审批决定可能使等待中的执行继续或失败；它与“结果是否达到交付要求”的 Issue 验收是两个独立决策。

Chat 内的工具确认由相应会话与运行时处理，不要假设所有交互式确认都必然出现在 Inbox。

## 清理通知

阅读后可以归档不再需要操作的消息。仍然 Needs action 的消息不能用 Archive 代替决策。历史可从 Archived 查看；消息归档不会删除 Issue 或执行记录。

遇到无权限时联系该空间或工作的所有者。管理员账号也不应通过更换凭据绕过私有工作授权。

下一步：[Automations](/v2/zh/service/automation) · [账号与权限](/v2/zh/service/access)。
