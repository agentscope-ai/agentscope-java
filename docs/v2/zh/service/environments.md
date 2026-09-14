---
title: "Environments：配置执行位置"
---

[English](/v2/en/service/environments)

**Resources → Environments** 定义 Managed Agent 在哪里执行文件、Shell 等工具。它与保存定义的 Workspace 分工不同，也不是 Hosted Agent 的 Runtime Host。

## 选择类型

| 类型 | 适用方式 |
| --- | --- |
| local | 在 Dataplane 所在环境执行；管理员必须启用 Local |
| sandbox | 使用 E2B 云沙箱隔离 Shell/文件执行 |
| remote | 共享 BaseStore 文件系统，不提供 Shell |
| self_hosted | 通过自行运行的 Worker 提供执行能力 |

Docker 下 Local 指 Dataplane 容器内部，并不是宿主机的任意目录。生产环境按工具隔离和网络需求选择后端。

## 创建与配置

在 Environments 创建名称和类型，随后在详情配置该类型所需的 JSON 连接参数。类型创建后只读。后端所需凭据和能力必须与所选 provider 匹配；不要把 Runtime Host 的 enrollment 凭据用于 Worker。

在 Agent 的 Advanced settings 或环境绑定中选择这个 Environment。用只读文件操作验证连接、工作目录和权限，再启用需要写入或执行命令的工具。

## Self-hosted 接入

Self-hosted Worker 使用 Environment 的 API key 建立连接。创建 key 时安全保存，按照所用 Worker 的连接配置提供；重连后检查在线状态和一次实际工具调用。Runtime Host 运行 Coding Agent，而 Worker 承载 Managed Agent 的工具执行，两者不是可互换的进程。

## 无法执行时

依次检查环境是否存在且可用、网络和认证、目标目录挂载、工具二进制及权限。模型可以回复不代表文件工具也已配置完成。更新配置后用新任务验证，轮换 key 后更新所有使用者。

下一步：[Managed Agent](/v2/zh/service/managed-agent) · [配置参考](/v2/zh/service/configuration)。

## E2B sandbox 配置示例

管理员先在部署配置中提供 `BUILDER_E2B_API_KEY`。创建 sandbox 环境后，Config 可使用：

```json
{
  "templateId": "base",
  "isolationScope": "SESSION",
  "sandboxTimeoutSeconds": 300
}
```

需要额外程序时选择包含这些依赖的自定义 E2B template。`workspaceRoot` 设置沙箱工作路径；`persistenceMode` 可选择 `TAR` 或 `NATIVE_SNAPSHOT`，按模板与后端能力验证保存和恢复。remote 类型只有文件系统能力，不能作为远程 Shell Worker 使用。

## 从发布镜像运行 self-hosted Worker

创建 self_hosted Environment 并保存 API key。设置下列变量：`SCHEDULER_IMAGE` 为发布清单中的 scheduler 镜像全名，`BASE_URL` 为 Worker 可访问的 Gateway URL，`ENVIRONMENT_ID` 和 `ENVIRONMENT_KEY` 为刚创建环境的值。

```bash
docker run --rm \
  --name agentscope-hands \
  -v agentscope-hands:/data \
  --entrypoint java "$SCHEDULER_IMAGE" \
  -Dloader.main=io.agentscope.builder.worker.HandsWorkerMain \
  -cp /app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --base-url "$BASE_URL" \
  --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /data/hands \
  --worker-id hands-1
```

该进程只向 Gateway 发起出站请求，不需要暴露 Worker 端口。将 Managed Agent 绑定到此 Environment，发起一个读取并写回小文件的任务，观察工具挂起后由 Worker 回传结果并继续。Worker 工作目录位于命名卷中；任务需要的系统程序应预装到你的 Worker 镜像。

正式运行由你的进程或容器管理器负责重启，多个 Worker 使用不同 worker ID。停止前检查已领取的工作，避免将停止进程误认为已取消业务任务。
