---
title: "运维、恢复与排障"
en_link: /v2/en/service/operations
---

<Note>
当前发布版本为 `2.1.0-BETA1`，属于预发布版本。用于生产前请验证实际部署。
</Note>

一份可恢复的备份包括数据库、Workspace、Artifact 和解密这些数据所需的密钥。

<span id="compose-operations"></span>

## 数据持久化

Compose 使用三个命名卷分别保存 PostgreSQL 数据、共享 Workspace 和 Artifact。可以通过 `docker volume ls` 找到本项目的卷，并将它们纳入备份策略。备份加密数据时，还必须妥善保留 `.env` 中的 Vault master key，因为恢复这些数据时仍需要原来的密钥。

如果工具需要读取宿主机上的业务资料，应显式配置目录挂载，并保证容器用户 `65532:65532` 具有所需权限。只在 Agent 指令中写出宿主路径，并不会使这个路径出现在容器里。准备资料时，应先确认所选 Environment 实际能够访问哪个目录，再将对应位置提供给 Agent。

## 更新配置和版本

修改 `.env` 后，需要重新执行 Compose 启动命令，让服务使用新的配置。随后检查组件状态，确认更新后仍能正常运行。

```bash
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

升级版本时，应先在 `.env` 中修改 `SERVICE_VERSION`，再拉取对应的新镜像并重新启动服务。初始化脚本会保留已有 `.env`，所以重新运行 `init-env.sh` 不会替你完成这次版本修改。如果需要变更密钥，还应协调使用该密钥的各个组件；尤其是 Vault master key，它关系到已有数据的解密，不能像普通登录密码一样直接替换。

完整 Compose 使用 standalone HTTP 运行方式。如果还要接入依赖 ASDP 的 External SDK，应先按[External 接入说明](/v2/zh/service/external-agent)准备对应的运行通道。生产环境的 Kubernetes 安装方式见[生产安装](/v2/zh/service/kubernetes)，无论采用哪种部署方式，都应在升级前完成[备份恢复演练](/v2/zh/service/operations)。

## 停止、继续与排错

需要暂时停止平台时，可以执行 `docker compose down`。这个命令会停止服务并保留数据卷，之后重新运行启动命令即可继续使用已有数据。日常停止时不要附加 `-v`，因为该参数会同时删除数据卷。

如果启动失败，先用 `docker compose ps -a` 确认哪个组件未正常运行，再通过 `docker compose logs --tail=100` 查看对应错误，判断问题是否发生在镜像拉取、数据库连接或组件启动阶段。如果宿主端口已被占用，可以修改 `.env` 中的 `GATEWAY_PORT`；若对外访问地址也随之变化，还需同步更新 `BUILDER_OAUTH_PUBLIC_URL`，然后重建容器。

## Docker 备份

安排维护窗口，先停止四个应用组件，保留数据库运行：

```bash
docker compose stop gateway scheduler data control
mkdir -p backup
chmod 700 backup
docker compose exec -T db pg_dump -U agentscope -d agentscope -Fc > backup/database.dump
```

使用你的卷备份工具为 `workspaces`、`artifacts` 命名卷制作快照，保存 `.env`，并记录 `SERVICE_VERSION`、镜像 digest 和快照时间。备份目录应位于安装目录之外的受保护持久存储中。确认数据库备份与文件快照属于同一维护窗口后再启动应用。

```bash
docker compose up -d --wait --wait-timeout 600
```

Kubernetes 同样需要数据库备份与 PVC 快照；根据存储提供程序选择快照方式，保存配置 Secret 的受保护副本。

## 升级

阅读新版本的迁移与兼容性说明，先在测试数据库副本上演练。完成备份后，Docker 修改 `.env` 中的 `SERVICE_VERSION`，拉取镜像并重建容器；Helm 使用指定版本的 Chart 执行 upgrade。

Go 在启动时执行产品与运行状态迁移，Java 当前通过 Hibernate 更新表结构。组件镜像回退不保证旧版本可以读取新 schema。

## 恢复

需要回退数据时，先停止应用写入。在独立空数据库中恢复备份，恢复同一批 Workspace / Artifact 快照与原 Vault 密钥，再用备份对应的组件版本启动。不要把 `pg_restore --clean` 指向仍在使用的业务数据库。

对新建的恢复数据库执行示例：

```bash
pg_restore --no-owner --no-acl --dbname="$RESTORE_DATABASE_URL" backup/database.dump
```

恢复到同一 PostgreSQL 实例中的新数据库后，在 Compose `.env` 设置 `POSTGRES_DB` 为恢复库名称，并使用原密钥和同批文件快照启动。数据库容器只在数据目录为空时初始化数据库；已有实例中的恢复库需要预先创建。

## 恢复验收

检查管理员登录、已有 Agent 与 Session 历史、Workspace 文件、Vault 凭据解密、运行时连接，以及一项新的小任务。Task/Issue 的验收状态应与备份时刻相符。

只有数据库、文件和密钥都能共同恢复，备份才算通过验证。升级按维护窗口执行；单副本安装不提供多副本 HA 或无停机升级保证。

## 用 API 检查恢复后的服务

恢复后先做读取，再提交一个明确的新测试任务：

| 核对内容 | API |
| --- | --- |
| Agent 与运行绑定 | `GET /api/v1/agents`、`GET /api/v1/agents/{id}/bindings` |
| 会话配置与版本 | `GET /api/v1/agent-sessions/{sessionId}`，读取固定 target |
| 已有业务结果与页面 | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}`、`GET .../{id}/snapshot` |
| 未完成编排与实际执行 | `GET /api/v1/orchestration-runs/{id}/graph`、`GET /api/v1/execution-attempts/{id}` |
| 通知与自动化 | 查询原 Webhook/Automation 的投递记录，核对回调去重与运行状态 |

读取使用原调用归属的凭据或已授权平台身份。Turn 数据、Managed 原生日志、Workspace 文件和凭据加密密钥都需恢复；只恢复其中一层不能保证任务可继续。过期事件 cursor 应重新获取 snapshot，不重新提交已完成工作。接口和认证见 [API 参考](/v2/zh/service/api-reference)。

## 恢复后重新开放服务

先保持定时规则和外部入口受控，在测试工作上验证登录、历史、文件和凭据。确认 Runtime Host 重新上线，再逐个恢复计划触发与业务流量。数据库快照恢复不会撤销备份之后已经发出的消息或外部写入；对照业务系统核对幂等记录和未完成工作后再重跑。

## 用固定案例做升级回归

在升级前保存[CRM 方案交付案例](/v2/zh/service/cases/in-product-delivery)的固定请求、来源版本与验收结果，在隔离的恢复环境中重复调用。模型措辞可以不同，下面的事实和持久化结果应可核对：

| 检查 | 验收依据 |
| --- | --- |
| 来源恢复 | 请求中的三份资料与版本一致；若生产接入了 Memory，另外验证实际读取 |
| 文件恢复 | 原 Artifact 可以下载，内容与备份记录一致 |
| 新任务 | 新 Turn / Run 完成，引用正确来源，未把未确认产品能力写成承诺 |
| 历史 | 升级前的 Turn、事件、产物与应用验收记录仍能查看 |
| Host 与调度（如果使用） | 在测试目标上运行代码修复或周期研究案例，检查关联记录 |

记录升级前后版本、备份批次、用例输入、执行 ID 和差异。只在需要对应集成时验证其凭据可用性；知识读取案例本身没有外部凭据，不能证明 Vault 解密路径也已验证。

<span id="troubleshooting"></span>

## 获取组件日志

```bash
docker compose logs --tail=200 control data scheduler gateway
kubectl -n agentscope logs deployment/service-agentscope-control --tail=200
kubectl -n agentscope describe pod POD_NAME
```

Gateway 正常不代表模型或工具执行正常。Managed 会话故障查看 Dataplane；渠道与 Worker 调度查看 Scheduler；产品 Automation、资源、账号和编排派发查看 Control。Hosted provider 故障还需对应主机的 daemon 日志。

## 重启没有重置管理员密码

这是预期行为。`CONTROL_PLANE_BOOTSTRAP_PASSWORD` 只用于空账号表。已有账号通过 Profile 或管理员账号管理修改密码，不通过重新生成 `.env` 重置。

## Vault 解密失败

检查恢复时是否保留了原 `BUILDER_VAULT_MASTER_KEY`，以及各组件是否一致。不要通过随意替换密钥来修复；先恢复匹配的配置与数据。

## 收到任务但没有最终结果

先从 Issue 的 Executions 判断 Run、Node 和最新 Attempt，而不是看最后一条文字。waiting 时查看依赖、approval 或 signal；blocked 时补充信息；failed 时检查错误和部分产物。Inbox 的 Request changes 不自动启动执行。External 接入应核对是否实现任务回报，Hosted 接入应核对 provider 是否退出并完成回传。

## Webhook 或 Session 重复请求

先查询已有 Delivery/Turn 的状态。保持同一逻辑请求的幂等键和内容，只有新的业务请求才使用新 key。事件被过滤看 trigger 的 event 配置；请求被拒绝看认证头和 schema。SSE 断线后优先查询返回的 statusUrl。

## 统一服务 API 调用排障

保存 Session ID、Turn ID、请求幂等键和错误码。先读取 `/api/v1/agent-sessions/{sessionId}/turns/{turnId}` 确认任务状态，再通过同一 Turn 的 `/snapshot` 和 `/capabilities` 了解已保存的结果与当前可用命令。

| 现象 | 核对方法 |
| --- | --- |
| 创建 Session 后没有 key | 凭据由 Application 单独签发，创建时显式提供 targets 和 scopes |
| 401/403 | 检查 凭据目标授权、Application 状态与 scope、待办指定审批人 |
| 409 | 区分资源版本冲突、幂等键内容不一致、目标能力不匹配或活动 Conversation 冲突 |
| 输入命令已接收但没生效 | 查询返回的 command 状态；持久接收不代表模型已消费 |
| SSE 返回 410/cursor_expired | 重新读取 snapshot 替换界面，再从新的 as_of 继续；不重发任务 |
| 某个成员完成但调用仍运行 | 检查 Turn 与 steps，不把成员结果当成根工作终态 |
| 恢复按钮不可用 | 按 available_commands 展示；并非所有后端支持 resume，公共 API 不支持 checkpoint restore |

`/api/v1/events` 是 WebSocket 刷新通知，不能替代持久 Turn SSE。具体请求和响应字段见[统一服务 API](/v2/zh/service/service-api)。

## Agent API 与 SSE

以下针对 Managed 原生会话。先保存 session ID、turn ID、最近事件 ID、HTTP 状态码和脱敏错误。区分页面连接、任务执行和上下文恢复：

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

具体请求见[会话与任务](/v2/zh/service/session-event-log)，刷新和断线恢复见 [SSE 文档](/v2/zh/service/sse-events)。应用只需要保存 Session、Turn 和游标，无需参与内部运行时记录的映射。

## 更新 Control Plane 名称

Go 组件位于 `agentscope-service/service-controlplane`，服务端二进制名为 `service-controlplane`。升级已有部署时，需要一起更新构建路径、启动命令、部署清单和环境变量。Control Plane 配置统一使用 `CONTROL_PLANE_` 前缀；HTTP 客户端使用 `CONTROL_PLANE_HTTP`，CLI 和 Runtime Host 使用 `CONTROL_PLANE_URL`。命令行工具使用 `as`，Runtime Host 可执行文件改为 `agentscope-runtime-host`。建议以同一 Service 版本附带的环境配置模板为准，避免新二进制加载旧配置。

Java 接入模块为 `agentscope-extensions-controlplane`，入口为 `io.agentscope.extensions.controlplane.ControlPlane` 和 `ControlPlaneConfig`。Python 分发包为 `agentscope-service-sdk`，代码中通过 `agentscope_service` 导入；DSH 插件为 `@agentscope-service/dsh-controlplane`。已有接入应用需要更新依赖和导入后再部署。如果使用了自定义日志目录、Helm 资源名或 Console 偏好设置，也需要在升级时迁移这些本地配置。数据库 schema 和 ASDP 的 `agentscope.protocol.v1` 线上协议标识沿用原值。
