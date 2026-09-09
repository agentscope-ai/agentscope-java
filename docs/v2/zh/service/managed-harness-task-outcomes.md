# Managed Harness 的任务结果与子任务归属

本次增强用于处理 AgentTask 调研过程中出现的子任务无法查询、空完成事件被误读为仍在执行、普通文本被自动完成，以及部分报告未进入主 Issue 的问题。

## 实现边界

没有改写 `ReActAgent` 的推理循环，也没有修改 `HarnessAgent.java`。增强位于 Harness 的任务工具、完成事件中间件、文件工具，Paw 的会话接入，以及 aistio 的 AgentTask 适配器和控制面。普通聊天仍按原有模型回合结束规则返回。

## Paw 与 Harness

- 每个 Paw Agent 的 `sessions_spawn` / `sessions_send` 复用该 Harness 实例的 `TaskRepository`。创建、`task_output`、等待及完成投递使用同一父 sessionId；调用方的 RuntimeContext 随持久化操作传递。
- 子会话拥有新的 RuntimeContext、sessionId 和 sessionKey，只继承用户标识及 managed 生命周期标记，不复制父任务凭据或父 AgentState。
- managed AgentTask 的子任务完成不再通过 Paw 公告或 Harness 消息总线发起另一轮处理。适配器用 `TaskRepository.SUPPRESS_COMPLETION_CALLBACK` 声明自己持有续轮权，持久化结果和主动读取不受影响。父适配器保留当前执行，获取真实结果后在同一会话继续。
- `task_output` 查询不到任务时返回工具错误，明确标记 `not_found`。空仓库的等待返回 `no_tasks`，不会声称子代理仍在工作或已经完成。
- 子代理调用失败会持久化为失败。普通聊天的完成公告在持久化之后发出；取消事件也先持久化再通知监听器。
- 每轮完成提醒最多投递 10 条，只确认本轮实际展示的条目，剩余条目下一轮仍可投递。没有声明式 subagent 的场景也能接收外部会话工具产生的完成结果。
- `glob_files`、`grep_files`、`list_files` 的模型可见输出限制为最多 200 条、约 16,000 字符，超限明确提示缩小范围；这些工具不再排除在结果驱逐机制之外。

## AgentTask 完成协议

模型使用本地 `task.submit_result` 提交明确的 `succeeded`、`waiting`、`blocked` 或 `failed` 意图，适配器负责调用控制面：

- `succeeded` 必须包含实际交付；还有未结束的后台任务时不接受成功。
- `waiting` 必须提供真实任务 ID。适配器获取终态结果后继续同一会话；未知 ID 不会无限等待。Team lead 的控制面委派仍由控制面检查和安排后续执行。
- 单纯返回“稍后继续”“仍在等待”等文本不会自动成功。适配器补发一次纠正提示，再次缺少明确 outcome 时记为 blocked。
- 异常运行停止（例如达到最大迭代次数）不能提交成功。
- 本地继续处理有 16 轮和 10 分钟的预算；该时间预算覆盖续轮与依赖等待，不替代模型/工具本身的超时配置。退出时请求取消仍未结束的本会话后台任务；底层取消为尽力而为。
- `run.node.complete` / `run.node.fail` 已经完成控制面提交时，适配器不再重复提交物理任务完成。
- 下发给模型的结构化上下文去除任务令牌等凭据，并列出当前实际工具名称。spawn 不会自动给子代理增加网络检索能力。

这是执行协议与状态一致性的增强，不是对报告事实正确性或内容是否充分的自动证明；lead 的内容核验仍然必要。

## 控制面与验收

- REST completion 与 MCP 使用一致的 outcome 处理。
- `task.complete(outcome=blocked)` 的 Team lead 保留协调器，结束当前物理回合，将主 Issue 标为 blocked，并发布已保存的部分交付。新的人工输入可以沿原 run 恢复。
- 明确的 `failed` / `run.node.fail` 保持终止语义。失败任务和 execution attempt 原子保存部分 result；主总结仍展示部分成果及未完成原因。
- 主总结读取当前 run 的 worker 结果和子 Issue 中有来源的结果评论，包括 lead 后补的报告。评论和任务结果互补，去除明显重复；验收状态单独展示。
- 新增 `issue.acceptance.update(itemId, satisfied, evidence)`：只有当前子 Issue 的有效 Team lead 可以修改既有条目的满足状态、记录证据和来源任务，不可改写验收要求。随后调用 `issue.accept`，原有人工审核政策仍生效。
- 通用 CLI `issue update` 的人工鉴权未放宽。Agent 应使用上述任务权限入口，避免拿 task token 调用人工接口导致 401。

代码变更不会回填历史 Issue，也不会自动重启已失败的运行。服务和 Paw 使用新构建后，新执行才采用这些行为。

## 验证

回归测试覆盖仓库接线、父会话隔离、默认聊天不被 managed 子任务唤醒、真实依赖结果续轮、未知依赖、普通文本误完成、过早成功、异常停止、批量投递确认、取消持久化顺序、超长文件输出、REST/MCP blocked 一致性、人工沿原 run 恢复、清单权限及根 Issue 结果保留。

普通聊天兼容性通过 Paw bootstrap 的单 Agent、多 Agent 和 per-peer 会话测试验证。没有连接付费模型重新运行历史调研，也没有将改动部署到运行中的服务。

验证命令：

```sh
mvn -pl agentscope-examples/agents/agentscope-paw -am test \
  -Dtest=FilesystemToolTest,SessionsToolTest,HarnessAgentTaskOutcomeTest,AgentTaskCollaborationToolTest,SubagentDeliveryTest,ToolResultEvictionMiddlewareTest,WorkspaceTaskRepositoryTest,WorkspaceTaskRepositoryDeliveryTest,WaitAsyncResultsToolTest,BuilderBootstrapSmokeTest,TaskRepositoryContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.skip=true
go -C agentscope-service/aistio test ./...
go -C agentscope-service/aistio build ./cmd/...
```

Java 格式检查按本次修改的文件执行 Spotless，避免格式化同一工作区中的其他改动。Go 全量测试首次运行出现 Qoder `TestRunStopsChildWhenApprovalTransportFails` 时序失败；单独运行和后续全量运行均通过。
