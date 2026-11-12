# 阿里云 RocketMQ 接入

Agent Service、Agent Harness Runtime 和 Tool Worker 的 workload Secret 使用相同接入点和授权凭据：

```yaml
stringData:
  MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS: "<VPC endpoint>:8080"
  MANAGED_AGENTS_RUNTIME_MQ_ACCESS_KEY: "<instance username>"
  MANAGED_AGENTS_RUNTIME_MQ_SECRET_KEY: "<instance password>"
```

凭据不要放进 Git 或 Helm values；两个凭据字段必须一起配置。均未配置时保留自建无认证 Broker 模式。TLS 按实例接入点要求设置 Helm `runtime.mq.ssl`。

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

## 2026-10-20 ACS 实测

测试 Pod `agencore-ma/managed-agents-cloud-mq-probe` 使用业务 Java SDK 5.2.0、VPC 接入点及 Secret 凭据。五个父 Topic 的 QueryRoute 均收到云端 `40100`，明确报错 `the topic of <topic> is not found`。这证明请求已到达云端，但尚不能证明消费权限或 LiteTopic 能力通过。需要在控制台创建上述 LITE Topic 并配置消费组、授权，再继续验证。业务部署尚未切换。

## 云端 wildcard 配置（2026-10-21 实测）

实例 `rmq-cn-mhb50f8n501` 的五个业务父 Topic 均为 `LITE`，消费组必须使用 `messageModel=CLUSTERING` 并绑定 `topicName`，顺序模式为 `deliveryOrderType=Orderly`。不要使用 `LITE_SELECTIVE`：该模式需要客户端逐个 `subscribeLite`，不能满足本项目自动消费所有 Session LiteTopic 的要求。`CLUSTERING` 模式不要传 `exclusive`，否则云端返回 `InvalidExclusive`。

顺序消费需配置 `FixedRetryPolicy`（本次为间隔 1 秒、最大重试 16 次）。父 Topic `liteTopicExpiration=-1`，避免误用自建 Broker 的过期参数范围。

在独立测试 Topic 上已验证：不调用 `subscribeLite` 也能收到新 LiteTopic 消息；返回 `ConsumeResultSuspend` 后再次投递成功；两个独立 LitePushConsumer 之间挂起消息接管成功，后续消息保持 A、B 完成顺序。基础收发凭据与配置只存 Kubernetes Secret。

本次迁移镜像基于 ACS 现有镜像固定 digest，仅替换公共 JAR 中的 `ManagedInfrastructureConfiguration.class`，接入凭据。逐项比较确认其他 JAR/class 内容不变，以保留当前集群已部署的 ACK 与工具发现改动。改造最初在独立 `codex/cloud-rocketmq` 分支完成，现已整合至主目录 master 工作区。

初始化脚本：`python3 managed-agents/deploy/managed-cloud-mq-init.py --instance <instance-id> --region cn-hangzhou`。脚本可重复执行，遇到已有不兼容资源会停止，不会自动删除。Helm 设置 `runtime.mq.existingSecret=managed-agents-cloud-mq`，该 Secret 只追加到三个 MQ 使用角色，且覆盖 workload Secret 中原有的 MQ 环境变量。

## ACS 切换结果

已将 Agent Service、Agent Harness Runtime、Tool Worker 的 6 个业务 Pod 切换到 `rmq-cn-mhb50f8n501`，均为双副本健康；Gateway 已滚动重启，清理切换期间的旧后端连接。

验收 Session：`1fe4a633-9444-5463-9327-e1cc35b9ce1f`。文本 Turn 完成；真实 90 秒 MCP 调用仅执行一次（generation=1），产生一次 suspension 和一次 resume；后续 T2 保持排队，T1 完成后执行成功。取消测试确认 Worker 收到控制消息且记录取消事实、Turn 终态为 cancelled；已在执行的 MCP 调用仍返回真实 succeeded，不能将此解读为外部操作被立即中止。

按用户选择保留旧 MQ 及会话 `ae0f0db1-1c84-55de-8fce-8a259ea618b2`：一个 waiting_tool、一个 accepted、工具 unknown，不取消、不重跑，也不将其计为迁移验收成功。

凭据测试 3 项、发布测试 46 项、格式检查、Helm lint/render 与 6 Pod 接入点/凭据存在性/JAR SHA256/健康检查通过。全量 Java verify 有一个沙箱 workspace 恢复时间断言失败；单独复跑该测试类及凭据测试通过，未修改沙箱逻辑。

本地私有验收证据位于 `managed-agents/release/dist/cloud-mq-cutover-20261021/`，包括 `acceptance-report.json`、`pods-verification.json`、`cloud-resources.json`、`image-publication.json` 与 Helm 切换前配置。源码已整合至主目录 master 工作区，尚未创建 Git 提交。

源码现已合入主目录 master 工作区（尚未创建 Git 提交）。Helm 默认使用 `managed-agents-cloud-mq`；后续打包后部署无需额外指定 MQ Secret。Secret 必须预先存在，缺失时 Pod 将启动失败，不会静默回退到自部署 MQ。

新环境部署时，复制 `deploy/cloud-mq.env.example` 到受限权限的私有文件，填入实例用户名/密码，再执行 `kubectl -n agencore-ma create secret generic managed-agents-cloud-mq --from-env-file=<私有文件>`。已有集群的该 Secret 已配置，无需重新录入凭据。打包镜像不内置接入点或密码，目标实例由该 Secret 决定。

master 整合还更新了 Kubernetes 发布包：包含 cloud-mq.env.example、云端初始化脚本与中英文配置说明；安装文档要求先创建 MQ Secret。主目录验证：凭据测试 3 项、发布测试 47 项、格式检查和 Helm lint 通过；全量 clean verify 在 common 模块发现现存 ManagedSessionProtocolTest.inputNotificationsDeduplicateAcrossSourcesInEitherOrder 事件顺序断言失败（506 项中 1 项失败、150 项跳过），不属于本次 MQ 配置改动。

## Execute 先发 MQ、提交后执行

普通工具调用采用以下顺序：保存完整但未授权的提案（`authorized=false`）→ 发送 Execute 并取得 Broker ACK → 在 Session 行锁事务中提交 checkpoint、`tool_task` 与授权回执（`authorized=true`）。`ma.tool_command_outbox` 保留历史名称，当前作为提案和回执账本；没有后台 Execute 发布扫描或发布租约。提案保存失败或 MQ 响应未知都不能提交工具任务。

Worker 只读取 Runtime 的受理事实，不读取 checkpoint 内容，也不协调推理。它在同一个 Session 行锁下检查授权回执与任务：已提交则持久化受理并 ACK；尚未提交且原执行仍有效则返回短 Suspend；没有提交且旧 epoch/token 已被替换，或 Turn 已终结时才 ACK 废弃。不能仅凭任务暂时不存在、租约过期或数据库异常丢弃消息。数据库不可用时继续 Suspend。已提交任务优先于 epoch 比较，Runtime 换代不会废弃有效调用。

TURNS 原消息在 Turn 完成前保留。R1 在提交前崩溃后，R2 由原 TURNS 重投接管，按当前 Turn 定位未授权提案，保留完整批次、调用身份、参数和配置，取得自己的 Execute ACK 后再提交任务。R1 提交后的崩溃直接恢复原任务；数据库提交成功但响应丢失也不会授权第二次物理执行。Worker 原有持久执行、租约接管、结果事件及幂等机制继续生效。Worker 确认受理时同时检查已提交取消输入，先落取消墓碑再唤醒执行；独立的取消意图转发继续运行，覆盖已受理任务及后续取消。T1 的未完成状态与 Session 顺序检查继续阻止 T2 越过。

新增 `managed-tool-submission.sql` 为历史命令默认保留已授权状态。升级前必须停止入口并用旧版本排空未发送的命令；此版本不会自动扫描补发旧 Execute。先迁移数据库，再统一切换 Runtime 和 Worker；旧 Worker 不支持新的提交确认协议，不能混用新生产者。此方案要求 Worker 能读取共享 Runtime 授权表并锁定 Session 行。

本地协议回归：`ManagedToolSubmissionTest` 与 `ManagedPostgresToolSubmissionIntegrationTest`；实际 HTTP MCP 提交前/后崩溃恢复：`ManagedMcpHttpIntegrationTest`。PostgreSQL 用例通过专用本地 `MANAGED_DATABASE_TEST_URI` 启用，不应指向业务库。
