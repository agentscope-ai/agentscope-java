# 阿里云 RocketMQ 接入

Session Portal、Brain 和 Hands 的 workload Secret 使用相同接入点和授权凭据：

```yaml
stringData:
  BUILDER_MANAGED_MQ_ENDPOINTS: "<VPC endpoint>:8080"
  BUILDER_MANAGED_MQ_ACCESS_KEY: "<instance username>"
  BUILDER_MANAGED_MQ_SECRET_KEY: "<instance password>"
```

凭据不要放进 Git 或 Helm values；两个凭据字段必须一起配置。均未配置时保留自建无认证 Broker 模式。TLS 按实例接入点要求设置 Helm `managed.mq.ssl`。

本实现要求 5 个 LITE 父 Topic 及其消费组：

| Topic | Group |
| --- | --- |
| ma_turns_v3 | ma_brain_v3 |
| ma_tool_execute_v1 | ma_hands_execute_v1 |
| ma_tool_control_v1 | ma_hands_control_v1 |
| ma_tool_events_v3 | ma_brain_tool_events_v3 |
| ma_turn_resumes_v3 | ma_brain_resumes_v3 |

消费组还必须支持 LiteTopic Shared、wildcard 订阅以及 FIFO Suspend/恢复。普通 NORMAL/FIFO Topic 不能直接替代这些 LITE Topic。

阿里云实例须开通 LiteTopic 能力，已有实例按官方说明通过工单确认或升级。云产品管理 API 与自建 Broker 的 mqadmin 初始化脚本不同，不要直接复用 `managed-mq-init.sh`；云端也不需要本项目创建 Proxy 内部心跳 Topic。

切换前先在独立测试资源验证发送、消费、挂起恢复、多副本接管和鉴权；停止接入新任务并排空旧 MQ 及数据库 outbox，再统一切换三个角色，避免旧消息滞留。验证文本 Turn、工具调用、取消、子 Agent 和故障恢复后，才停用旧 MQ，保留其数据以便回退。

官方说明：[LiteTopic](https://help.aliyun.com/zh/apsaramq-for-rocketmq/cloud-message-queue-rocketmq-5-x-series/developer-reference/litetopic)。

## 显式启用可选配置

默认继续使用自建部署。`managed.mq.existingSecret`、`data.existingSecret` 与 `schema.dataExistingSecret` 均为空；没有预设云资源、接入点、命名空间、账号或镜像。

如果使用独立托管的 MQ，复制 `deploy/cloud-mq.env.example` 到私有文件，填写自己的接入点和凭据，在选定命名空间创建 Secret，再显式设置 `managed.mq.existingSecret=<你的 Secret>`。只有 Portal、Brain 和 Hands 加载它。迁移凭据继续使用独立 Secret。

可选初始化脚本需要显式传入自己的实例和地域：

```sh
python3 agentscope-service/deploy/managed-cloud-mq-init.py --instance <你的实例> --region <你的地域>
```

## Execute 先发 MQ、提交后执行

普通工具调用采用以下顺序：保存完整但未授权的提案（`authorized=false`）→ 发送 Execute 并取得 Broker ACK → 在 Session 行锁事务中提交 checkpoint、`tool_task` 与授权回执（`authorized=true`）。`ma.tool_command_outbox` 保留历史名称，当前作为提案和回执账本；没有后台 Execute 发布扫描或发布租约。提案保存失败或 MQ 响应未知都不能提交工具任务。

Worker 只读取 Runtime 的受理事实，不读取 checkpoint 内容，也不协调推理。它在同一个 Session 行锁下检查授权回执与任务：已提交则持久化受理并 ACK；尚未提交且原执行仍有效则返回短 Suspend；没有提交且旧 epoch/token 已被替换，或 Turn 已终结时才 ACK 废弃。不能仅凭任务暂时不存在、租约过期或数据库异常丢弃消息。数据库不可用时继续 Suspend。已提交任务优先于 epoch 比较，Runtime 换代不会废弃有效调用。

TURNS 原消息在 Turn 完成前保留。R1 在提交前崩溃后，R2 由原 TURNS 重投接管，按当前 Turn 定位未授权提案，保留完整批次、调用身份、参数和配置，取得自己的 Execute ACK 后再提交任务。R1 提交后的崩溃直接恢复原任务；数据库提交成功但响应丢失也不会授权第二次物理执行。Worker 原有持久执行、租约接管、结果事件及幂等机制继续生效。Worker 确认受理时同时检查已提交取消输入，先落取消墓碑再唤醒执行；独立的取消意图转发继续运行，覆盖已受理任务及后续取消。T1 的未完成状态与 Session 顺序检查继续阻止 T2 越过。

新增 `managed-tool-submission.sql` 为历史命令默认保留已授权状态。升级前必须停止入口并用旧版本排空未发送的命令；此版本不会自动扫描补发旧 Execute。先迁移数据库，再统一切换 Runtime 和 Worker；旧 Worker 不支持新的提交确认协议，不能混用新生产者。此方案要求 Worker 能读取共享 Runtime 授权表并锁定 Session 行。

本地协议回归：`ManagedToolSubmissionTest` 与 `ManagedPostgresToolSubmissionIntegrationTest`；实际 HTTP MCP 提交前/后崩溃恢复：`ManagedMcpHttpIntegrationTest`。PostgreSQL 用例通过专用本地 `MANAGED_DATABASE_TEST_URI` 启用，不应指向业务库。
