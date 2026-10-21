# master 逻辑回迁记录（2026-10-21）

[English](master-sync-20261021.en.md)

目标为 `/Users/ken/agentscope-3/agentscope-java` 的 `codex/automatic-acr-release` 分支。来源为独立 Service 仓库 master（起始提交 `2714354`，工具提交门改动在同步期间提交为 `3c6bd55`）和本次同步时的未提交实现。迁出基线为原项目 `fbd32ca602647141d96d50ecfe9a3554cdca0cdd` 的工作区；通过迁出时的 2,224 项 SHA-256 清单及重命名记录核对。

原项目基线后的 5 个文件变更已保留，涉及 Helm values/planes、Gateway 配置、集群终端脚本和集群指南。原有 Core、Harness、扩展、工作流及其他无关改动未覆盖。所有修改直接位于目标主目录，没有创建 worktree，没有提交、推送或部署。

Redis/MQ 的完整组件交互参见 [Redis 与 Turn 生命周期](redis-usage-and-turn-lifecycle.md)和 [RocketMQ 与 Turn 生命周期](rocketmq-topics-and-turn-lifecycle.md)。

## 同步范围

- Session 事件协议、Turn/Input 语义、分页历史与独立结果读取，以及 Java、Python、TypeScript 客户端和 Console。
- 持久化 MCP 批次、工具发现与恢复、运行时续跑、执行结果捕获、工作进程租约和取消处理。
- 单一 Turn FIFO 路由、可恢复确认、MQ Suspend/ACK 顺序、副本切换与 outbox 发布协调。
- Execute 的 Broker ACK 先于事务授权，Worker 提交门检查、执行栅栏与 Session 行锁协调，以及提交前/后崩溃恢复。
- Gateway 幂等创建重试、上游连接错误恢复，以及 Portal/Gateway 滚动退出。

组件目录、Java 包、Go module、SDK 分发名、CLI、镜像名和 `BUILDER_*` 配置继续采用本项目的 AgentScope 标识。公开作用域 Header 保留 `X-AgentScope-Tenant/Namespace`，内部 Header 保留 `X-Builder-*`。新增事件扩展使用 `AgentScope-Extensions: turn-inputs-v1` 和 `agentscope_service`。

## 自建部署与数据库升级

默认继续自建部署，未迁入源环境的实例地址、资源 ID、命名空间、镜像摘要、云账号、凭据、本地环境文件或集群验收报告。Helm 的可选 `managed.mq.existingSecret`、`data.existingSecret`、`schema.dataExistingSecret` 均为空，`localDev=false`；原有资源配额、副本数和 Gateway 连接回收配置保留。

Service 已有 283 个 SQL 文件按原迁出哈希保留。数据库 migration 账本仍使用 `portal`、`dataplane`、`managed`；已有 `managed-hands.sql` 文件名和校验和不变。按注册顺序新增：

1. `managed-session-protocol.sql`
2. `managed-turn-ack.sql`
3. `managed-mcp-batch.sql`
4. `managed-tool-publication-lease.sql`
5. `managed-tool-submission.sql`

升级前停止新增受理，并用旧版本排空按深度拆分的旧 Turn Topic 以及尚未发送的 Execute/outbox。以独立 owner 运行 schema Jobs，再运行 `deploy/postgres-roles.sh` 更新业务账号权限，之后一起升级 Brain 和 Hands。旧 Worker 不支持新的授权门，不能消费新生产者命令。新版不扫描并重发历史 Execute；不要直接跳过排空步骤。

Hands 与运行时需要访问同一 `ma` 授权数据空间。Hands 仅获准读取提交门所需列，并获得 Session 的 `UPDATE(execution_epoch)` 列权限，以执行 PostgreSQL 的 `SELECT FOR UPDATE`；提交门自身不会修改该列。checkpoint、原生日志、Session 配置和授权写入仍不可访问。Brain/Portal 继续不能读取 Hands 物理执行账本。该权限边界有实际 PostgreSQL 回归测试。

## 后续增量核对（2026-10-21）

再次按同步清单核对当前 master `3c6bd55` 及工作区：清单已记录的源文件哈希均未改变，没有新的提交或运行时代码差异。工具提交门、HTTP MCP 持久执行与连接池测试、Redis/MQ 时序说明已在上次收尾时纳入。剩余源侧新增文件仅为已明确排除的云数据配置生成器及其测试。

本轮补齐中英文 README 的 MQ-first 工具授权顺序、Brain 侧 MCP 发现，以及持久 direct continuation / MQ Resume 两条恢复路径和时序文档入口。目标目录已有代码与自建部署配置保留；此前 MQ 自然过期重投验证缺口仍然存在。本次仅变更文档并刷新同步清单，执行相对链接、清单哈希和差异范围检查，没有重跑未改动的 Java/Go/前端测试。

## 验证

| 检查 | 结果 |
| --- | --- |
| 根目录 Maven 检查（`-T1 clean verify`，修复适配后按模块续跑） | 99 个模块分段验证通过；首轮 clean 构建后，修复适配并续跑完成 |
| 根目录 `mvn -B -ntp -T1 spotless:check` | 通过 |
| PostgreSQL / Redis 真实集成回归 | Common 550 项，0 失败，7 项可选夹具跳过；使用独立数据库 |
| HTTP MCP 双进程与持久恢复 | 21 项通过，包含跨进程去重、断线查询及连接池 |
| PostgreSQL 权限与发布/部署脚本 | 49 项通过，实际运行权限脚本验证提交门与私有状态隔离 |
| Console | 189 项通过；lint 0 错误、97 条现有警告；构建通过 |
| Control Plane | `make build`、`make test`、`go vet ./...` 通过 |
| Python SDK / DSH | 120 / 32 项通过；DSH TypeScript 构建通过 |
| Helm / 文档 | Helm lint 通过；文档 16 项测试、站点构建校验与断链检查通过 |

完整构建中修正了 Portal 调度器 Bean 名称和共享测试夹具路径的回迁适配。Gateway 回收测试显式使用 1s/1s 的测试周期；目标项目实际 10s/5s 的空闲连接策略保持原值，测试仍要求观察到连接关闭。

### MQ 验证边界

原版 RocketMQ 5.5.1 的初始位点负例仍保留。正向回归采用仓库已有的 MIN 位点测试镜像；单回调槽位的 16 层子调用及 FIFO/重试共 5 项通过。仅 `ma_push_bus_*` 压力测试组使用 100ms 重试与 1000 次预算，避免 Broker 退避耗尽原有 40 秒深度测试窗口；没有放宽完成、顺序、容量或超时断言，生产初始化配置未改动。

真实 Turn ACK 测试已按新协议接入 Execute 发布器；跨副本主动 ACK、原消费者退出后的接管通过，终态到 T2 分别约 709ms、716ms。自然过期场景没有在 65 秒内收到新回执，因此该项未通过。临时关闭 Proxy 自动续期的实验也未能收到初始 Turn，实验配置未保留。上述结果不能替代部署环境的 MQ 验收。

本次未执行业务集群部署、LLM 端到端验收或业务数据库升级。测试使用的独立本地容器在完成后清理，用户原有容器未变更。源码同步不代表已经替换运行中的旧版本。

### 后续同步基线

[逐文件同步清单](master-sync-20261021.files.json)记录本次增量文件的来源路径、源文件哈希、同步前后目标哈希以及两个仓库的提交标识。源工作区包含未提交内容，不能只用 master 的提交号还原此次输入；清单不包含凭据或云资源配置内容。清单自身不参与自身哈希。
