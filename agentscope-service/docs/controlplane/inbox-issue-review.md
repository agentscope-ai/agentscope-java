# Inbox 内的 Issue 验收

## 交互

Inbox 的 `review_request` 消息显示 **Review result →** 提示。打开消息后，详情顶部固定显示验收操作区，交付结果、子任务和活动记录仍在下方原位查看，无需跳转 Issue 或展开 Properties。

- **Accept result**：以当前展示的 Issue version 调用 `/api/v1/issues/{id}/accept`。验收条件由后端校验，通过后标记 Done。
- **Request changes**：展开修改意见输入框；意见必填，点击 **Send review** 调用 `/reject`。Issue 回到 In progress，意见保存在状态变化活动中并直接显示。此接口不启动新的执行；继续执行仍使用现有工作分配/评论流程。
- 打开提醒、填写意见、点击 Cancel 不提交验收决定。草稿在当前 Inbox 内切换消息时保留，切换 namespace 时清除。
- 验收成功后刷新 Issue、Activity、Inbox 列表、详情和计数。后端将原 review_request 设为已处理并归档，前端保留当前选中的结果，不自动跳到下一条。
- 审批工具调用的 `approval` 与交付验收分开：审批仍使用原 ApprovalDetail；评论 Resolve 仅处理评论线程，不代表接受交付。
- 打开子 Issue 预览时隐藏父 Issue 的验收按钮，返回被通知的 Issue 后再操作。

## 边界

只有消息仍需处理、未解决/归档，且当前 Issue 为未归档的 In review 时才提供决策按钮。加载失败、历史提醒和其他 Issue 状态不提供验收操作。权限由现有工作资源授权中间件检查，拒绝时在详情内显示原因。

服务端 accept/reject 要求正数 expectedVersion，reject 要求非空 reason，仅接受 In review 的未归档 Issue。重复提交、过期版本、已处理或归档 Issue 均拒绝。继续复用原有子任务、待审批、交付物和 acceptance criteria 检查；未通过时保留待办，展示错误，不自动尝试更新版本后再次验收。未修改 reopen 兼容行为。

## 验证与交付

代码位于主目录 `/Users/ken/agentscope-2/agentscope-java` 的 `agentscope-service-v5` 分支，未使用独立 worktree。前端构建更新 `aistio/ui`，控制面验证二进制位于 `/tmp/aistiod-inbox-review`；没有重启/部署运行中的服务。

- Inbox Playwright 12 项通过，涵盖原审批/已读回归、直接验收、退回草稿和意见展示、400/403/409 错误、历史提醒、移动端取消。
- Go `internal/httpapi`、`internal/collaboration`、`internal/store/memory` 测试通过。新增接口测试验证必要字段、版本冲突、验收条件、重复决定、提醒关闭、反馈留痕，以及操作不会自动分派 Agent 任务。
- 测试使用模拟 API、内存 Store，不修改用户提供的实际 Issue 或 Inbox 状态。

证据见 测试记录（历史本地验收记录，保存在发布前备份中）。
