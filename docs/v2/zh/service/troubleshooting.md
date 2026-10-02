---
title: 排障
en_link: /v2/en/service/troubleshooting
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

从发生问题的工作记录开始，记录版本、时间、Session / Task / Attempt / Run ID，再检查相关组件。共享日志前移除令牌、密码和业务敏感内容。

| 现象 | 优先检查 |
| --- | --- |
| 镜像拉取失败 | 发布清单中的仓库命名空间、标签、登录与 CPU 架构 |
| 容器健康检查失败 | `docker compose ps`、数据库状态和失败组件日志 |
| Helm Pod Pending | PVC 是否 Bound、StorageClass 是否支持所需访问模式 |
| 登录失败 | 使用 bootstrap 密码还是旧账号密码、数据库是否已存在账号 |
| Local Environment 被拒绝 | 是否显式启用 Local；是否应改用 Sandbox / Self-hosted |
| Agent 不回复 | 模型凭据、环境绑定、工具确认、Dataplane 日志 |
| Team 等待不结束 | 未完成成员任务、缺少输入、待审批或交付评审 |
| 刷新后历史丢失 | 是否误用了 memory 存储、数据库与原卷是否一致 |
| OAuth 回调失败 | 公开 origin、平台回调 URL、HTTPS 可达性 |
| Runtime Host 离线 | provider 是否安装可运行、Host 凭据、网络和 daemon 日志 |
| SDK gRPC 连接失败 | 当前部署是否启用 Kubernetes-native ASDP，端口是否正确 |

## 获取组件日志

```bash
docker compose logs --tail=200 control data scheduler gateway
kubectl -n agentscope logs deployment/service-agentscope-control --tail=200
kubectl -n agentscope describe pod POD_NAME
```

Gateway 正常不代表模型或工具执行正常。Managed 会话故障查看 Dataplane；渠道与 Worker 调度查看 Scheduler；产品 Automation、资源、账号和编排派发查看 Control。Hosted provider 故障还需对应主机的 daemon 日志。

## 重启没有重置管理员密码

这是预期行为。`AISTIO_BOOTSTRAP_PASSWORD` 只用于空账号表。已有账号通过 Profile 或管理员账号管理修改密码，不通过重新生成 `.env` 重置。

## Vault 解密失败

检查恢复时是否保留了原 `BUILDER_VAULT_MASTER_KEY`，以及各组件是否一致。不要通过随意替换密钥来修复；先恢复匹配的配置与数据。

## 收到任务但没有最终结果

先从 Issue 的 Executions 判断 Run、Node 和最新 Attempt，而不是看最后一条文字。waiting 时查看依赖、approval 或 signal；blocked 时补充信息；failed 时检查错误和部分产物。Inbox 的 Request changes 不自动启动执行。External 接入应核对是否实现任务回报，Hosted 接入应核对 provider 是否退出并完成回传。

## Webhook 或 Endpoint 重复请求

先查询已有 Delivery/Invocation 的状态。保持同一逻辑请求的幂等键和内容，只有新的业务请求才使用新 key。事件被过滤看 trigger 的 event 配置；请求被拒绝看认证头和 schema。SSE 断线后优先查询返回的 statusUrl。

## Agent API 与 SSE

先保存 session ID、turn ID、最近事件 ID、HTTP 状态码和脱敏错误。区分页面连接、任务执行和上下文恢复：

| 现象 | 处理方式 |
| --- | --- |
| 刷新后只有后半段文字，或缺少离开期间的工具 | 先 GET snapshot 渲染 items/tools，再从 as_of 订阅；只恢复 cursor 不会重建 UI |
| SSE 连接关闭，任务是否停止不确定 | GET turns/{turn} 或 snapshot；断线不取消，也不重新 POST turns |
| run.ended / item.completed 后仍显示运行中 | 等目标 turn 的明确结果；执行、消息、工具完成不等于任务完成 |
| 400 / 409 cursor 错误 | 核对 session 范围，重新取快照；不要自行解析、递增 cursor |
| 410 资源分页过期 | 从资源第一页重新读取；资源分页 cursor 不可传给 SSE |
| 确认已提交但工具没有继续 | 查 required_actions 和 GET turns/{turn}/actions；accepted 仅接收，rejected 时先读 reason 和 pending |
| steer 返回 409 | 任务可能已结束或关闭输入；重新读取状态，新的独立问题提交新 turn |
| checkpoint 恢复返回 409 | 先处理未关闭任务、待办、未消费输入和未知工具结果；恢复不能撤销外部操作 |
| 费用不完整或预算拒绝执行 | 查 usage/budget 的未计价调用、模型用量和计价配置；调整限制后按任务状态显式 resume |
| Webhook 没收到 | 检查允许主机、注册时间、事件过滤与 deliveries；连续失败暂停后修复接收端再 retry |
| 有心跳却无文字 | 检查任务状态、工具和模型；它们可能不提供增量；若内容集中到达，检查代理缓冲 |

具体请求见 [Agent API](/v2/zh/service/session-event-log)，事件处理见 [SSE 文档](/v2/zh/service/sse-events)。已发布 Endpoint 继续使用它返回的 statusUrl/eventsUrl 和[独立事件协议](/v2/zh/service/sse-events#endpoint-协议范围)。
