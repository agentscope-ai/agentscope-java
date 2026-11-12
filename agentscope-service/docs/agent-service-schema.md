# Agent Service 数据库初始化与升级

> 下文保留阶段性历史。2026-10-16 起数据面仅使用重写后的 V0001，不再创建旧 Managed
> Runtime 表；旧迁移集合要求新数据空间。最新状态以 [MQ / Redis 实施记录](managed-mq-redis-refactoring-plan.md) 为准。

新架构的普通 PostgreSQL 服务副本只校验 schema。建表和升级由独立任务执行，
任务完成后退出，不启动 HTTP、Harness、编排 Worker 或用户初始化。这里的 schema
用于明确组件的表和写权限，与服务副本数量无关。

当前默认行为：

| 组件 | 初始化入口 | 普通启动 |
| --- | --- | --- |
| 控制面 Product | `control-plane --migrate-only` | `--schema-mode=validate`，校验 Product 版本及 SQL 校验和 |
| Go 编排运行时 | `managed-agents-orchestration-runtime --migrate-only` | `--schema-mode=validate`，校验全部已发布迁移版本 |
| Agent Service | Agent Service jar 中的 `ServiceSchemaMain --component=agent-service` | SQL init 不在 PostgreSQL 自动执行；校验版本、校验和及 JPA 表结构 |
| Harness 数据面 | Dataplane jar 中的 `ServiceSchemaMain --component=agent-harness-runtime` | JPA 默认为 validate，JdbcStore 不自动建表；校验版本及校验和 |
| Tool Worker | 无数据库初始化 | 通过 Agent Service 内部 ToolTask 协议认领、续租及提交结果 |

`store.Open` 和 `product.Open` 的空 schema mode 仍允许已有单元测试和显式开发调用
初始化数据库；两个 Go 服务入口都默认 validate。数据面嵌入式开发 fixture 显式使用
`managed-agents.schema.initialize=true` 与 JPA update，Agent Service 的嵌入式 fixture 使用自己的
schema.sql。这些开发配置不作为普通 PostgreSQL 多副本启动配置。

## 初始化顺序

1. 用数据库 owner 创建数据库及 `cp`、`rt`、`portal`、`dp` schema。Go Store 的 DSN
   search_path 需要指向已存在的 `rt`；Java 初始化入口可以创建自己的 schema。
2. 执行四个 schema 的初始化任务。控制面 `--migrate-only` 可以一起初始化 rt 和 cp；
   只初始化 rt 时使用编排运行时入口。
3. 给普通服务账号授予自己业务表需要的 DML 权限、schema USAGE 和必要的 sequence
   权限；给迁移元数据表授予 SELECT。普通账号不拥有 schema、表或 CREATE 权限。
4. 移除普通副本中的数据库 owner 凭据，再启动服务。

从仓库根目录构建后，示例命令如下。示例中的 DSN/数据库环境变量使用数据库 owner
凭据；普通服务启动时切换为对应业务账号。不要将真实凭据提交到仓库。

```bash
managed-agents/control-plane/bin/control-plane \
  --storage-driver=postgres --migrate-only

java -Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain \
  -cp managed-agents/agent-service/target/managed-agents-agent-service-2.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --component=agent-service

java -Dloader.main=io.agentcore.managed.server.web.persistence.ServiceSchemaMain \
  -cp managed-agents/agent-harness-runtime/java/brain-agentscope/target/managed-agents-agent-harness-runtime-2.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --component=agent-harness-runtime
```

控制面读取 `CONTROL_PLANE_STORAGE_DSN`、`CONTROL_PLANE_PRODUCT_DSN`。
Agent Service 初始化读取 `MANAGED_AGENTS_AGENT_SERVICE_DB_URL/USER/PASSWORD`，数据面读取
`MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_DB_URL/USER/PASSWORD`；可用 `--url`、`--username`、`--schema` 覆盖。
迁移入口不需要模型、Vault、JWT 或服务内部凭据。普通编排运行时仍需要稳定的内部
token、任务签名 secret 和 Vault key；控制面现在也把 Vault key 传给运行 Store。

Java job 默认 schema 为 portal 或 dp；普通服务的 JDBC `currentSchema` 与 Hibernate
default_schema 必须指向相同位置。初始 dataplane SQL 是当前 JPA 映射和 JDBC dialect
的固定基线，供新环境使用。已有旧 dataplane 表但没有新迁移记录的数据库不自动
认领为新基线；按本轮允许不兼容重建的约定使用新的运行数据空间。

## 版本与失败行为

Product 的四个初始迁移、Agent Service 的 `V0001`–`V0006` 与 Dataplane 的 `V0001`–`V0004` 有已应用版本和 SHA-256
校验和。后续修改追加新迁移并登记到清单，不能改写已发布 SQL。Go runtime 继续
使用原有版本化 up/down 迁移和 schema_migrations，普通启动校验完整版本清单。

Go `0126_terminal_issue_cleanup` 增加运行结束后仍待处理的 Issue 收尾标志与
`created_at/id` 部分索引，以及子 Issue 的 `parent_issue_id/id` 游标索引。失败根 Run
提交终态时同时保留收尾义务；Runtime 按有界批次处理，进度写入既有 `dp_kv`，完成后
CAS 清除标志。部署先执行新版 Go migration job，再启动普通 DML 副本；下行迁移
移除索引和标志，属于具体回退时需要结合排空决定的操作。

Go `0127_graph_consistency_cursor` 增加完整节点 UUID 游标、按 Run 排序的边索引，
以及按 Run/Node/Issue 读取 Task 历史的 UUID 游标索引。运行副本和容量检查使用这些索引，
不依赖进程内数量估算。先执行 migration job，再启动需要新版清单的普通副本；下行只移除
本轮新增索引，不删除节点、边或 Task 数据。

Agent Service `V0002__artifact_publication.sql` 新增显式产物发布的不可变幂等收据表，
发布收据、公开事件及查询投影在同一业务事务中提交。升级先执行 Agent Service migration
job，再对新表补充 Agent Service 业务账号的 DML 授权，最后启动新版副本；原 `V0001`
SQL 及校验和保持不变。

初始化由 PostgreSQL advisory lock 串行化，每个 Java/Product 迁移的 DDL 和版本
记录在同一个事务中提交。Go runtime 保留原有单迁移事务及明确标记的无事务索引
创建规则。失败不会记录成功版本；缺失或未来版本会拒绝普通启动，Product/Java
的校验和不匹配也会拒绝。校验不会修复数据库。Operator 应修复初始化任务的错误，
完成后再启动服务。

升级前保留数据库、Workspace/Artifact 归档以及原 Vault key。初始化任务完成之后
才替换服务版本；旧二进制不应在新增版本的数据库上重新启动。新旧协议版本混跑、
生产切流及回滚仍按具体版本的兼容范围和排空方案安排。

## 已验证边界与后续实现

Managed 的动态资源配置使用 Agent Service V0004__configuration_overlay 增量迁移，增加独立
加密字段，不新增表。共享 policy 的 configuration_overlay 仅保存 Environment、
vaultCredentials 和 memoryMounts，并随策略版本原子更新。普通鉴权、Session 列表和
SSE 复核只选择权限列，不加载或解密资源配置。数据面 configuration 保留冻结定义
及 bootstrap 摘要；在激活事务中更新
这三个字段，附加加密的 runtimeConfigurationOverlay scope_id/revision/digest 元数据。
权限续租不改变当前执行装配。源版本较旧不回退，同版本不同内容及绑定 Environment
变化明确拒绝；每份覆盖配置限制 1 MiB。资源引用的 idle PATCH 仍单独实施。

Go Runtime、Product、Agent Service 的真实 PostgreSQL 测试已经覆盖只读元数据账号校验、
拒绝缺失/未来版本，以及 Product/Java 拒绝校验和变化。Product 和 Agent Service 的并发
初始化通过，初始化不创建 Product 用户。真实 Java jar 的迁移入口和 Managed
执行闭环已使用不同 Agent Service/Data 业务账号跑通，两个账号都没有自己 schema 的
CREATE 权限，重启后的 SSE、快照和幂等结果保持一致。

两套 Agent Service/数据面副本共用 PostgreSQL 的跨副本受理、幂等 Turn 与事件读取通过，
没有增加预期模型调用。Tool Worker 实际进程也通过了同一 PostgreSQL 配置下的工具
结果、Worker 重启与 Sandbox 复用。当前验证入口为 `managed-agents/tests/service_deployment_smoke.py` 和
`managed-agents/tests/managed-mq-redis/replicas.py`，参数以各自 `--help` 为准。
前者验证独立迁移与业务账号，后者验证真实多副本 MQ/Redis 协作；两者都需要专用
测试基础设施。原 `session_portal_postgres_smoke.py` 已移除，不再作为可运行命令。

当前 Compose 与源码启动脚本已经按“初始化、业务授权、启动副本”的顺序接入。
release.py 提供七个组件镜像映射，Go 运行角色有独立 Dockerfile。Helm 提供三个
迁移 Job、独立迁移 Secret、schema.only 准备流程和逐组件业务 Secret；授权在
schema.only 完成后、普通服务部署前由数据库 owner 或数据库操作方执行。新增表
升级也按这个顺序处理。完整命令见部署与 Chart README。

新增 service_deployment_smoke.py 实际执行 Control/Agent Service/Data 迁移入口和部署授权
脚本，随后以五个业务账号启动 Control、Go Runtime、Agent Service、Data、Scheduler、
Gateway。登录、内部路由屏蔽、Go readyz 探测通过；各账号无 CREATE 权限，迁移
元数据只读，Agent Service/Data 相互没有 schema USAGE。授权脚本拒绝把迁移 owner 当成
业务账号；所有授权和密码变更在有超时的单事务中提交。默认检查没有调用模型。
`--managed-turn` 进一步用真实控制面账号创建 Environment 与 Agent，通过 Gateway、
Agent Service、Data 执行并核对去重、公开事件及用量；模型为本地替身。

Dataplane `V0004__indexed_native_logs.sql` 增加原生日志头、事件、批次提交收据、
写者封禁与导出游标五张表。事件按 `(log_id, seq)` 唯一索引读取；一个提交在独立
数据库事务中原子写入事件、批次收据及 head，并检查写者租约。Service 不再使用
Workspace Journal 的提交链来定位历史；Core/Harness 的 SessionLog SPI 和 standalone
存储约定保持不变。原生日志与业务事务使用两个连接池，同属 dp 业务账号，默认
日志池最多 8 连接；按所有副本累加到数据库预算。

新创建配置标记 `native_log_format=sql-v1`。迁移前的配置标记为 workspace-v1，
访问时明确返回 incompatible_native_journal_rebuild_required，不把旧历史当成空日志。
本轮允许不兼容重建，应排空旧运行并使用新的 Agent Service/Data 运行数据空间；该迁移
只提供新表，不自动转换旧日志或支持两种格式混跑。显式嵌入式开发初始化会执行
幂等 runtime-schema.sql，普通 PostgreSQL 副本仍不建表。

Go 的 rt schema 仍混合资源、协作和编排表；Control 与 Go Runtime 的 rt DML 范围
以及 Scheduler 的 dp 表/列白名单随 P5/P8 继续收敛。普通服务凭据不再使用 owner，
但不能将当前 schema 级 DML 授权称为最终的逐表最小权限。完整容器镜像、集群安装、
滚动升级和容量检查尚未完成，源码进程启动不能代替这些部署验证。

Agent Service 的 `V0003__webhooks.sql` 新增 `builder_portal_webhook`（加密原始身份/签名密钥、Session/游标及 due 索引）和 `builder_portal_webhook_delivery`（稳定投递 ID、最新 fence 和有限回执元数据）。Scheduler 只通过内部 Agent Service API 访问，不具备 Agent Service schema 权限；embedded fixture 的初始化保持幂等。


Go `0128_portal_owned_run_scan` 为 Run 增加内部 `portal_owned` 调度标记，创建时从
根 Issue 的来源或最多 256 层 parent 祖先解析原始 Invocation 的 `portal_command_id`。
空来源、循环、非法 UUID 与非字符串 command ID 不造成 SQL cast 失败或得到授权。
已有活跃 Run 和仍有终态收尾义务的 Run 在 migration job 中补齐；不扫描已收尾的历史。

`0129_portal_runtime_outbox` 为 outbox 保存从 Task/Approval/Attempt 记录派生的相同
调度归属，并补齐未投递、未进入死信的事件。索引只包含待处理 Agent Service 运行事件；
Runtime 使用独立认领方法，控制面审计和旧任务不占用这个消费者的批次。两种标记
均不对外序列化，忽略 API 调用方的标记值，也不能替代运行前的 live admission。
SQL 辅助函数使用调用账号权限，不是 SECURITY DEFINER；后续逐表授权必须包含其
Issue、Invocation、Task、Run、Approval 和 Attempt 查询依赖。

`0130_tool_approval_scan` 与 `0131_runtime_lease_scan` 增加 pending 审批、queued Task、
活跃 Attempt 的 UUID 扫描索引。新 Runtime 的 Task 排队超时、各 Attempt 租约状态、
取消等待和工具审批分别使用既有 KV 的版本化游标，每类每轮最多 500 条；副作用
完成后 CAS 推进，页尾回到起点。副本重启可继续游标，竞争可能重放有限页。

这些迁移继续按 migration job → 业务账号授权 → 新版服务启动执行；下行先移除
0131/0130 的索引，再移除 0129/0128 的标记与辅助函数。应用账号不能执行下行。
不兼容重建允许新建环境，但没有触发旧数据删除。普通权限、旧版本混跑、索引吞吐
及生产回退的完整矩阵仍需按环境完成。


Go `0132_retryable_input_scan` 增加 retrying 输入的 `next_attempt_at/id` 部分索引。
输入重试每轮按 due 顺序认领最多 500 个输入 ID，事务只更新这些输入，并对涉及的
Task 去重；不再按 Task ID 扩大到同任务的全部重试历史。内存开发存储使用相同的
输入写入预算。下行只移除该索引，不改变输入内容或状态。


Go `0133_coordinator_issue_barrier` 为 Team 节点增加 `(issue_id,run_id)` 部分索引，
只包含 type=team，定位无执行者的 Adaptive 子 Issue 所属活动 coordinator。下行只
移除该索引。业务 Runtime 使用普通 Run 行锁和 Task CAS；不新增 region/shard。

完成意图使用既有 `dp_kv`，tenant 为 Task tenant，ns_path 为
`orchestration/coordinator-completion/<namespace>`，item_key 为 Task UUID。
载荷版本为 1，包含 output、actor 和 accepted。Task 行锁把意图修改与物理 Task
完成串行化；协调完成在同一 Task 事务中验证输出并接受意图，普通 Task 完成不接受。
节点屏障在独立 Run 事务中复核并同时写节点成功和事件；Runtime 恢复读取 accepted
意图重试。意图使用现有运行数据的保留权限和生命周期，后续 retention 联调需包含
此命名空间。单项输出最多 16 MiB，意图包最多多 4 KiB；聚合历史容量仍需完善。


Go `0134_comment_delivery_scan` 增加
`comments(issue_id,source_task_id,created_at,id)` 部分索引，仅覆盖 type=result 且
未删除的记录。摘要在相关 Task 范围取最新一条；全评论历史使用既有 issue/time/id
索引的 keyset。下行只移除该索引。完整历史视图最多 32,768 评论 / 16 MiB，普通
有限页最多 16 MiB。详情摘要超过 16 MiB 时改为明确标注的详情引用，不修改或删除
原始记录。mention/route 原始集合的容量与写入预算仍待实施。


### 0135 Task 输入顺序与容量

`idx_agent_task_inputs_sequence(task_id,sequence,id)` 支持末尾序列分配及 LIMIT 8,193 的完整性检查。Task/route 锁是数量准入的权威边界，不使用本机计数器。单 Task 上限 8,192；每项新输入预留所有生命周期元数据后最多 2,046 JSON 字节，完整数组（包含分隔符）不超过 16 MiB。旧记录也按数量及实际序列化总量检查，超限不会输出一个被截断的完整数组。LastError 只保存带显式标记和 SHA-256 的诊断预览，最多 1,024 JSON 字节；没有把该预览承诺为完整故障日志。

取消查询跳过输入；claim/ack/failure 响应预算检查在提交前完成。输入容量路由失败回滚 SAVEPOINT 后保存评论和 `task_input_capacity`，图容量继续使用 `run_graph_capacity`。重试和手工重放连续编号，清除旧 delivery 元数据；failed Task 作为 parent/delegatedFrom，活动 Run 的重试不逃出 Team 子树。


### 0136 评论关系和校正输入索引

mentions/routes 采用 `(comment_id,created_at,id)` 索引，读取各自最多 4,097 行，合计 4,096 条和 4 MiB 后明确拒绝。基础评论 JSON 保留 12 MiB 减 4 KiB，整页继续 16 MiB。单次 mention/route target 各 128；校正的已有路由历史也占同一个总预算，不能通过增加版本绕过。`(comment_id,comment_version,task_id)` 支持最多 129 个校正接收者的有限目标读取。Comment 行锁串行校正，完整新关系预算在正文/Task 写入之前校验，容量拒绝不写部分输入。


Managed 预算增加 Agent Service V0005__session_budgets：Session 保存 budget_limits 和
budget_revision，不读取数据面逐调用收据。Dataplane V0002__session_budgets 新增
builder_runtime_budget 汇总与 builder_runtime_budget_call 收据，两组索引支持根
Session 的有界修复及到期根查询。配置、准入、结算使用共享 root configuration 行锁
保持首次创建和最后一格竞争一致；修复 claim token、下一到期时间与原生扫描位置
均持久化。先迁移两侧 schema，再为各侧业务账号授权新表，普通副本不需要 DDL。
