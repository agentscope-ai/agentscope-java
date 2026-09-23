# Managed Agents 与 Issue 协作

Managed Agent 的正式执行入口是 AgentTask。Resolver 使用 `externalKey=agent-task|{taskId}` 查找或创建 Session，再投递包含 task locator 的 wake。Agent 必须刷新 Issue version 和 inputs；Session idle 不等于 Task completed。结果通过 task-scoped API 写为 Comment/Artifact，完成动作由统一 TaskService 收敛。
