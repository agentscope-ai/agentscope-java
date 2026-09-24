# Application SDK 与协作契约

Application SDK 同时承担运行观测和 AgentTask 接入：

- 注册稳定 AgentInstance，报告 capability、Session、Event、Context 和 inventory；
- 接收版本化 `ExecutionAttemptCommand`；
- 通过 task token 拉取统一 `ContextEnvelope`，ack inputs；
- 写 progress/result Comment、上传 Artifact、创建允许的 child Issue，并 complete/fail；
- 在控制面中断后按 task/event ID 幂等恢复。

Go connector、Python SDK、Java Extension 和 DSH TypeScript 插件共享相同领域语义。SDK 不读取数据库，不把本地路径当共享协议，也不通过 Session chat 模拟可靠 Agent 间协作。

task token 的对象级权限、Inbox 的 human-only 语义、Artifact 交换和 completion 用量格式见 [Issue 协作终态契约](./collaboration-contract.md)。运行时 SDK 不暴露需要 human/control-plane 身份的全局 Issue list/create/update/assign 方法；Agent 只能在当前 Task scope 内读取 discussion、写回结果和创建 child Issue。
