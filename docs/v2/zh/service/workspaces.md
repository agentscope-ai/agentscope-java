# Workspace 与 Environment

Workspace 管理工作资料，Environment 决定托管 Agent 在哪里使用工具。Namespace 则决定资源归属和权限。

## Workspace

为 Agent 建立 Workspace，添加任务所需文件或技能，再在 Agent 配置中绑定。避免把开发者电脑上的绝对路径直接作为服务器路径。

发布部署中，控制面、Dataplane 与 Scheduler 使用同一个 `/data/workspaces`。Docker Compose 通过共享卷提供它；Kubernetes 需要可共享挂载的持久卷。只备份数据库无法恢复这些文件。

## Environment

| 类型 | 工具执行位置 | 部署要求 |
| --- | --- | --- |
| Local | Dataplane 进程所在的容器/主机 | 显式启用 `BUILDER_ALLOW_LOCAL_ENVIRONMENT` |
| Sandbox | 配置的沙箱服务 | 有效凭据、模板与网络连接 |
| Self-hosted | 你运行的 Hands Worker | Worker 连接、Environment 凭据与心跳 |

Local 的容器文件系统不是用户电脑的文件系统。Self-hosted Hands Worker 与运行 Coding Agent 的 Runtime Host 也有不同职责，不要互换凭据。

## 验证绑定

创建 Environment 后，确认 Agent 绑定到它。创建一个新 Session，执行读取小文件等简单任务，检查实际文件位置和错误信息。再验证持久文件在容器或 Pod 重启后仍存在。

相关说明：[Runtime Host](runtime-host.md)、[备份恢复](operations.md)。
