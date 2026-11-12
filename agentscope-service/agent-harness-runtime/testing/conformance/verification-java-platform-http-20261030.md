# Java Brain 平台接口改造：阶段实施与验证

[English](verification-java-platform-http-20261030.en.md)

日期：2026-10-30。工作目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。在当前主目录保留原有改动；未提交、推送或实际部署。本文记录[平台接口方案](../../../docs/design/brain-platform-interfaces.md)的首批实施结果，不代表所有内核功能等价或生产故障验收完成。

后续部署更新：本记录的“未部署”描述指实施时点；同日随后已完成 ACS 部署与基础实测，结果和未关闭 P1 见[部署验收记录](../../../../docs/acs-java-http-acceptance-20261030.md)。

## 已实施的基础闭环

| 能力 | 当前实现与边界 |
| --- | --- |
| 配置与实例组装 | 继续从 Session Service 读取已接纳的配置、模型绑定与定义材料，复用 AgentScope 工厂；模型供应商和用户配置的 MCP 仍由内核调用。记忆文档增加受 Session 挂载范围约束的 Service 转发。 |
| 执行权 | Java `ExecutionAccess` / `SessionExecutionAccess` 使用共同 `executions/claim`、`renew`、`release`；claim/renew 响应不确定时重试原身份；本地用单调时钟保守计算有效期。prepared 提交检查同一租约，旧 owner 不能写入或释放后继租约。 |
| 任务通知 | Service 订阅已有事务提交信号，向配置的稳定 Brain 服务地址发送有认证的任务提示。Brain 容量满返回 429，接纳提示返回 202；只有 claim 授予执行权。复用持久队列和每 30 秒修复扫描，不新增可靠投递账本或要求 Brain 自注册。 |
| 正常 API 接入 | Java HTTP 模式下，普通 Session 创建也选择内部持久队列；无需客户端添加 queue 扩展头。公共扩展能力开关仍独立控制。Java HTTP 模式不再发布旧 Brain Ready/Resume MQ 提示；工具 MQ 保留。 |
| 原生状态与日志 | 原生 SessionLog、checkpoint、模型结果、对象内容和私有 workspace 通过现有 prepared/对象接口访问 Service。装配不再创建 JDBC、JPA、Redis 或 MQ 客户端。原生 Session 以 journal 恢复，未使用的 JPA AgentStateStore 不装配，不增加第二份状态双写。原生临时目录可继续存在。 |
| 文件输入与工具 | 文件引用解析经 Service；工具意图通过 prepared 提交，Hands 结果由 Service 持久化，Brain 经 HTTP 读取后应用。未改变 taskId/executionId、工具结果幂等或恢复边界。 |
| 输入与取消 | 从 Service 读取已接受的输入、关闭截点和生命周期事实；运行中控制仍以有界周期读取为基础。**尚未实现 owner/incarnation 定向控制回调**，不能把任务负载均衡回调当成取消已送达。 |
| 公开事件与 delta | 持久事件仍由 Service 投影；Java 模型请求附带 purpose，使在线预览可使用共同 active-reasoning 校验。预览独立有界队列、无重试、按协议拆分 Unicode 文本；丢失预览不影响提交和最终事件。 |
| SDK 原生消息总线 | 明确装配 `ManagedSessionMessageBus`：原生 inbox 为空，输入走平台协议；未开放的原生多 Agent 消息写入明确拒绝。避免 SDK 默认 WorkspaceMessageBus 在没有执行上下文时访问远程工具文件系统。此项不宣称新增子 Agent 支持。 |

没有为了接口命名一次性重写所有语言。Java 新增执行能力接口，其余能力复用已有窄客户端与 adapter；TypeScript/Rust 原有提交、恢复与运行算法保持不变。共同 schema 新增可选字段/操作，旧向量保持有效。

## 部署接线

五个物理角色保持 Gateway、Control Plane、Agent Service（包含 Session Service）、Brain、Tool Worker。

- **Agent Service**：Java 模式设置 `MANAGED_AGENTS_BRAIN_HTTP_ENABLED=true`，`MANAGED_AGENTS_BRAIN_CALLBACK_URL=http://<brain-service>:8082/api/internal/brain/notify`。地址来自部署配置与服务发现，不来自任意请求 URL。Service 继续持有 DB、Redis、MQ、对象存储和 Control Plane 连接。
- **Java Brain**：设置 `MANAGED_AGENTS_SESSION_SERVICE_URL=http://<agent-service>:8084/internal/session-service/v0`，同一内部认证令牌、JWT/Vault 兼容配置及模型凭据；`MANAGED_AGENTS_RUNTIME_CONCURRENCY` 范围为 1..256。对象 backend-id 必须与 Service 一致，但不配置对象存储连接或凭据。
- Compose 与 Helm 已移除 Java Brain 的平台存储 envFrom、DB/Redis/MQ 配置与共享持久卷。Helm 只投影显式认证键及 `agent-harness-runtime.modelCredentialEnv` 中的模型键；定制 credentialEnv 时需将同名键加入该列表和对应 Secret。
- `scripts/dev-up.sh` 的 Java/Service 启动参数同步更新。PI/Codex Compose 覆盖显式关闭 Java 回调派发，继续其已有通知/领取实现；PI Helm 两种模式仍维持原部署。
- 镜像构建入口不变：根目录执行 `mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am package -DskipTests`；Java 镜像使用 `managed-agents/docker/Dockerfile.service --build-arg MODULE=agent-harness-runtime`，或 `agent-harness-runtime/images/agentscope-java/Dockerfile`。本轮未执行镜像构建、推送或启动真实服务。
- **兼容性切换**：先发布支持新协议的 Service，排空并停止旧 Java MQ Brain，再启用 HTTP 模式和新 Brain。禁止旧/new Brain 同时竞争工作。现有 `broker_fifo_v1` 会话、未绑定但已有原生日志的旧会话没有自动迁移；本阶段验收使用新建会话，旧会话迁移需单列方案。
- 历史 schema job、DB 角色、JPA 类和部分 JDBC jar 暂保留以支持既有迁移/测试。它们不再进入新 Brain 的 Spring 运行装配；本次没有完成镜像依赖瘦身或历史表删除。

## 定向验证

只运行受影响测试，没有执行 `clean verify`、全量 Java/TS/Rust 测试或真实设施压测。

| 验证 | 结果与证据范围 |
| --- | --- |
| `ManagedHttpPlatformTest` | 3 项通过：claim 响应丢失保持身份、过期续租不产生有效 handle、delta Unicode 拆分及顺序符合共同 schema。 |
| `HarnessProtocolStoreTest` | 26 个当前用例通过（整类 25 项后，原 HTTP 单例扩展为两模式并定向重跑），含原 PI/PI Durable/Codex 协议回归与新增 Java HTTP 测试。原生 Harness 在真实 loopback HTTP 上执行：服务侧 H2/文件对象存储，确定性模型与 Hands fixture；覆盖 prepared 提交丢响应找回回执、一次模型工具调用、工具挂起后由新 Agent 认领续跑、两轮重建 Agent 的历史恢复、取消事实读取、旧 owner fencing，以及 429 后任务提示重试并领取。 |
| `SessionApiContractTest#ordinaryCreationUsesDurableQueueForHttpBrainWithoutPublicExtension` | 1 项通过：普通 API 创建/发送消息进入内部持久队列，不依赖扩展头。 |
| `AgentHarnessRuntimeAppContextLoadTest` / `HarnessRuntimeConfigTest` | 2 项通过：配置不可达的 DB/Redis/MQ 地址仍可装配；无 DataSource、AgentStateStore、Redis、MQ、process-lease Bean，使用远程 Workspace/SessionLog。 |
| 协议检查 | 10 schemas、21 API operations、117 正例、50 反例，以及 Node 4 个 JCS 和 4 个 identity 向量通过。新增 release、callback 提示、resources、Java next 与可选 purpose。 |
| Helm / 启动脚本 | 4 项 Helm 定向用例通过（含 Java 无基础设施注入及 PI/PI Durable 保持部署）；`bash -n managed-agents/scripts/dev-up.sh` 通过。 |

复现 Java 定向检查：

```bash
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am   -Dspotless.skip=true   '-Dtest=ManagedHttpPlatformTest,AgentHarnessRuntimeAppContextLoadTest,HarnessRuntimeConfigTest,HarnessProtocolStoreTest,SessionApiContractTest#ordinaryCreationUsesDurableQueueForHttpBrainWithoutPublicExtension'   -Dsurefire.failIfNoSpecifiedTests=false test
```

改动文件的 Spotless 检查及限定范围 `git diff --check` 均通过；未格式化用户其他在途迁移。协议检查使用带 `rfc8785/jsonschema/openapi-spec-validator` 的既有 Python 环境；系统 Python 未安装该依赖不代表协议失败。Maven 测试使用本地已有 AgentScope SNAPSHOT，不能据此证明远端干净镜像可下载所有依赖。

## 剩余项及停止边界

| 优先级 | 后续事项 | 对当前基础使用的影响 |
| --- | --- | --- |
| 部署前必须 | 目标环境真实 PostgreSQL/MQ/Redis/Hands/模型/SSE 验收；先 Service 后 Brain 的发布与 drain；核对网络和 Secret | 本轮自动化不等于线上部署成功；未经此验收不宣称可生产切换。 |
| 旧环境切换前必须 | 老 broker 会话与原生格式绑定迁移、回滚步骤 | 不影响新会话；直接替换旧 Brain 会遗留旧任务，不允许静默迁移。 |
| 后续 P1 | owner/incarnation 寻址与定向控制提示；跨 Service 副本通知优化和大队列公平性 | 当前有 HTTP 事实读取及持久队列修复，基础取消/工具继续执行可用；大规模时延与控制请求量未验收。 |
| 后续 P1 | 真实模型取消、强制终止/网络分区/进程重启、长时间挂起、Service 重启修复 | 本次覆盖协议 fencing 与重建 Agent；没有伪称覆盖真实进程/容器故障。 |
| 后续 P2 | 原生/公开完整 SSE 对照、慢消费者与预览压力、多模态/大文件请求尺寸、记忆资源真实 Control Plane 联调、日志长周期清理 | 需要单独边界验收；预览允许丢失，持久事件和对象不以预览为准。 |
| 后续 P2 | Java 历史 MQ/JPA 装配代码及 jar 瘦身、其余语言接口形式统一 | 不妨碍当前 HTTP Brain；不为代码整洁扩大本阶段范围。 |

D0/D1/D2 的基础 Java 路径与 D3 部署模板已落地；D3 实际发布、owner 定向通知及完整故障验收仍开放。D4 本轮只保持增量 schema 和部署覆盖兼容，未重构 TS/Rust 内核。按阶段收敛要求，到此停止扩展。

## 基础功能补齐与镜像前收敛（2026-10-30，后续批次）

本批继续在主目录 `master` 工作，不提交、不推送、不部署；保留此前改动。范围为实际基础执行缺口，不展开 owner 注册、通知连接或完整故障矩阵。

| 项目 | 实施结果 | 验证边界 |
| --- | --- | --- |
| PI / PI Durable / Codex 普通会话 | 按已配置的 harness-type 自动选择内部持久队列；不再要求调用方启用 queue 扩展，也不依赖 Java HTTP callback 开关 | 三种模式分别用普通公共 API 创建并提交消息，验证持久队列可领取；显式 queue 创建扩展的禁用开关仍生效。未修改 TS/Rust 执行算法 |
| Java 回调补偿与队列推进 | 单个回调连接失败不再终止整批；失败或仅接纳但未 claim 的候选按协议延后；补齐原来遗漏的必填 `reason`，避免 defer 被 schema 拒绝 | 真实 loopback 回调分别覆盖 429 后领取、队首断连接但下一 Session 仍领取；claim 成功后的活跃任务不再被选出 |
| 文件输入解析请求尺寸 | `prepared/query` 允许 `max(公共请求上限, 1 MiB) + 64 KiB`，容纳已接受输入和内部封装；其余控制请求仍为 1 MiB | 声明长度和流式计数两种方式验证合法请求通过、超限拒绝；不是多模态或大文件全链路验收 |
| Java 运行中取消 | 增强原生 HTTP 回归：确定性模型保持流不结束，Service 接受 CANCEL，HTTP Brain 关闭模型流并提交 cancelled；再重建 Agent 执行下一轮成功 | 同时保留工具直接返回/挂起恢复两模式、提交丢响应回执恢复、上下文恢复。真实模型厂商取消、Pod kill、网络分区仍待集群验收 |

验证结果和日志：

- 定向基础修复 8 项通过：Session API 4、请求上限 2、回调 2。日志 `/tmp/managed-functional-retest.log`。首轮回归暴露了缺失 `reason`；修复后补偿持久化断言通过。参数化 API fixture 使用独立幂等键，避免跨用例复用旧会话。
- 原生取消/恢复 2 模式和 Brain 启动装配 2 项通过，共计本批 12 个定向用例。`package` reactor 成功，生成 Agent Service、Java Brain 和 Tool Worker 可执行 JAR，核对其 Start-Class 正确。日志 `/tmp/managed-functional-package.log`。这是本地兼容依赖上的构建，不是容器或真实设施验收。
- 本批 Java 文件的 Spotless 检查通过，日志 `/tmp/managed-functional-format-check.log`；限定文件 `git diff --check` 通过。不运行 `clean verify` 或全量 TS/Rust 测试。

本批打包及关键 HTTP 回归复现（不跑全量测试）：

```bash
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am \
  -Dspotless.skip=true \
  '-Dtest=HarnessProtocolStoreTest#javaNativeExecutionAndNextTurnRestoreUseHttpWithoutBrainDatabase,AgentHarnessRuntimeAppContextLoadTest,HarnessRuntimeConfigTest' \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

### 下一步镜像与集群验收

五个物理角色保持不变。Java Brain 只注入 Session Service 地址、内部认证及模型配置；DB、Redis、MQ、对象存储连接仍属于 Service/Hands 等对应角色。不得为了容器启动给新 Java Brain 加回基础设施凭据。

1. **构建前**：确定镜像仓库、标签、目标 CPU 架构和可达的 Maven 二进制仓库。当前 AgentScope `2.1.0-SNAPSHOT` 来自本机 Maven install；本地 package 成功不能证明干净 Docker builder 可下载它们。应提供兼容二进制依赖及构建认证，保持 `revision` 与 `agentscope.version` 分离，不复制旧源码到本项目。
2. **打包入口**：Java Brain 使用 `managed-agents/docker/Dockerfile.service --build-arg MODULE=agent-harness-runtime`；Agent Service 使用同一文件的 `MODULE=agent-service`。构建上下文为仓库根目录。PI 使用专用 Node 镜像，Codex 使用专用 Rust 镜像；Codex 完整 Helm 模板仍未补齐，不能把 Java/PI Chart 当作 Codex 已完成的部署入口。
3. **新环境验收**：先完成基础设施和 schema/grant，再启动 Service、Hands、Brain 等角色；新建 Session 验证文本回答、一次工具调用、统一 SSE/delta、运行中取消、取消后下一轮、工具挂起后新 Brain 恢复。加入 B1 配置拒绝/生效与 B2 工具声明/调用对照；保留 session/turn/task ID 和日志。
4. **旧环境切换**：仍需单列 broker 会话迁移和回滚，先 Service、排空并停止旧 MQ Brain；不自动修改旧 Session 的 scheduler/native format。新会话验收不以迁移完成为前提。

本机 Docker server 探测未在有界等待内返回，已结束本次探测；未构建或推送镜像、未启动任何集群发布。owner 定向通知、跨 Service 信号优化、大队列容量、真实 CP memory 代理、多模态和完整故障验收留在原优先级清单。完成上述定向检查即停止本批开发，下一阶段进入镜像与环境验收。
