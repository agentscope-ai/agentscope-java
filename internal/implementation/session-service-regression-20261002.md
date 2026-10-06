# Session / Event / Agent as a Service 集中回归（2026-10-02）

本轮对照 [Session/Event 历史回归清单](session-event-log-regression-backlog.md) 和 [Service API 集成清单](../../agentscope-service/docs/service-api/regression-checklist.md)，执行自动化测试、实际数据库契约测试、浏览器交互及跨进程恢复实验。发现的问题在当前分支修复后复验。本文是内部测试记录，不属于用户 API 文档。

代码实际位置为 `/Users/ken/agentscope-3/agentscope-java`，分支 `harness-context-redesign`。保留原有未提交改动，没有新建 worktree、提交或部署到用户服务。所有新建数据库、服务端口和工作目录均为本轮隔离环境；模型与外部 Agent 使用确定性实现，未调用付费模型。

## 自动化结果

| 范围 | 最终结果 | 详细记录 |
| --- | --- | --- |
| Java Core / Harness | Core 2,505 项、Harness 1,099 项，无失败；分别有 9 / 14 项默认跳过 | [Java 报告](session-java-regression-20261002.md) |
| Java Service、依赖与扩展 | Common 33、DP 135、Gateway 3、Redis 5、JDBC 102、JEV 184 项通过；其他依赖结果见分报告；补跑 9 项 Docker 文件传输及 25,000 次 structured-output 并发压力用例通过 | [Java 报告](session-java-regression-20261002.md) |
| Go Service | 38 个测试包、827 条测试/子测试通过；2 条真实 Codex/Qoder CLI 调用未启用，13 包无测试 | [Go 报告](service-go-regression-20261002.md) |
| Go 并发与构建 | 相关 ASDP、调度、HTTPAPI、存储包 race 通过；三个 Go 命令构建成功；实际 etcd/apiserver 的 envtest 通过 | [Go 报告](service-go-regression-20261002.md) |
| Python SDK | 103 项通过，包括启动屏障和临时连接故障恢复 | [客户端报告](client-regression-20261002.md) |
| Console | 150 项单元测试、61 项 Playwright 通过；TypeScript/Vite 生产构建成功 | [客户端报告](client-regression-20261002.md) |
| 用户文档 | 460 页、504 个重定向的导航/链接/语法/资源检查通过，12 项文档测试通过 | [客户端报告](client-regression-20261002.md) |

不同测试框架的计数口径不同，Go 包含子测试，Java 总数包含跳过项，不将它们相加为一个“全部通过”的数字。首次收集失败时的 `maven.test.failure.ignore=true` 不作为通过证据；后续正常测试或明确的模块补跑才是最终结论。

真实数据库覆盖包括 PostgreSQL 17 的控制面/DP、JDBC PostgreSQL 16 与 MySQL 8.0 共 10 项、MongoDB 7 的 22 项，以及 Redis 本地容器和 H2/SQLite。OSS/COS、独立 PostgreSQL/MySQL 扩展中的 mock 测试与真实容器测试分别记录，不能互相替代。

## 实际运行链路

实际 HTTP 流量通过 Gateway（28080）、Go Control Plane（28081）、Java Data Plane（28082），使用专用 PostgreSQL 数据库。Python worker 使用真实 HTTP 注册、长轮询、任务操作和事件 ACK。浏览器测试同时区分 repository Playwright 的 mock fixtures 与实际服务访问。

| 场景 | 已验证的结果 | 原始证据 |
| --- | --- | --- |
| Agent / Team / Workflow | 三类 Job 成功，Team 实际派发成员任务，Workflow 调用 Team；24 项公共 API 检查通过 | `/tmp/agentscope-regression-20261002/public-e2e.json`、`public-e2e-verified.log` |
| 消息与工具续传 | 四条 assistant 消息、三个 tool call/result 完整；分页历史、Last-Event-ID 精确后缀、生成期间断开后 snapshot + suffix 恢复，没有重复 item | 同上 |
| 身份与契约 | Endpoint/Application/read scope 隔离，同 Application 换 key 仍可读取及幂等重放；冲突输入拒绝；轮换重叠后显式吊销旧 key | 同上 |
| 配额与交互 | 跨 Endpoint 的 Application 并发配额；满额时原请求重放；指定用户审批、过期版本拒绝、重复命令同 ID；JSON Pointer 转义/数组映射与输出 schema；禁用 Application | `/tmp/agentscope-regression-20261002/platform-contracts.json`，12 项 |
| 运行中 CP 强制重启 | 生成第一段内容后 SIGKILL Control Plane；重启后原 Invocation 继续，幂等请求不创建重复任务；最终四条消息、三个工具均完整，旧游标返回正确后缀 | `/tmp/agentscope-regression-20261002/restart-regression.json`、`restart-regression-verified.log` |
| 保留期与 410 | 临时将终态事件保留期设为 2 秒，历史清理后旧 cursor 返回 410；snapshot 仍完整，以新的 as_of 继续读取成功；实验后恢复默认配置 | 同上 |
| 产物 | 真实 worker 上传含零字节及非 UTF-8 字节的二进制；公开 snapshot 列出产物，Invocation 下载 API 逐字节一致 | `/tmp/agentscope-regression-20261002/artifact-regression.json` |
| Managed Job / Conversation | Java 真实运行链路均完成，公开 result 返回实际最终回复；额外同时提交三个快速 Job，三次 `task.complete` 都在同步启动确认后成功，工具无错误 | `/tmp/agentscope-regression-20261002/managed-smoke-barrier.log`、`managed-fast-jobs.json`、`managed-fast-jobs-verified.log` |
| Managed 外部工具与续聊 | 待答请求断开观察后仍存在，同会话并行新 turn 拒绝；回答后同 Invocation 完成，命令重复幂等、旧问题新命令拒绝；后续 turn 继承 session 且拥有新 Invocation/turn，取消到 cancelled 后还能发起并完成第三个 turn；8 项通过 | `/tmp/agentscope-regression-20261002/managed-actions-result.json`、`managed-actions-final.log` |
| Harness Chat | 多次推理/三个工具、部分参数生成时刷新、断流补齐、session 排序、HITL、interrupt/resume；重启 Java 后完整历史、待答请求、checkpoint 均恢复，继续原 turn 并产生新 run | [客户端报告中的七项实际浏览器验证](client-regression-20261002.md#real-browser-native-harness-session-history) |
| Console | 真实提交/SSE，刷新保留 Invocation 选择；审批草稿在后台更新期间保持，刷新恢复同一待办；取消到 cancelled 后再刷新，页面无异常 | [客户端报告](client-regression-20261002.md#real-browser-service-invocation-api) |
| DP outbox 重启 | 停止 DP 时 1,449 行尚未投递；重启后总计 2,987 行全部送达，pending=0，最大投递尝试计数为 1 | [Java 报告](session-java-regression-20261002.md) |

可重复执行的公共 HTTP 测试已保存在 [service_api_regression.py](../../agentscope-service/aistio/test/e2e/service_api_regression.py)，启动条件见同目录 [README](../../agentscope-service/aistio/test/e2e/README.md)。故障实验及 Managed 测试的临时 driver、JSON、日志保留在 `/tmp/agentscope-regression-20261002`。早期失败日志保留用于说明缺陷，不与最终复验混算。

## 数据一致性、并发和 webhook

- PostgreSQL 从空库并发迁移、0111 种子数据升级至 0112/0113、迁移中途失败回滚后重试、down/up 均通过。DP 手工 SQL 重复应用后，以 Hibernate `validate` 启动成功，没有对用户数据库迁移。
- 两个独立数据库连接池/服务对象共享 PostgreSQL，覆盖小连接池、并发 admission、Application 配额/用量累积、worker 到期任务 CAS、取消命令持久化后重建服务，以及 1,005 条事件的多写入者分页与跨 checkpoint 重建。
- 注入提交头、retention snapshot、webhook cursor 写入失败，验证原提交边界、去重和另一个副本继续。Java 实际 PostgreSQL 覆盖 native writer 互斥、不可变批次重放、sealed run 拒绝、同 session 新 run 和 namespace 隔离。
- Go Invocation webhook 使用真实本地 TLS 接收器验证原始 body HMAC、timestamp、重复投递、400/503/超时/302、12 次失败停止及人工 retry；两个 PostgreSQL 副本争抢与确认写入失败后的重投通过。生产 SSRF client 拒绝私网地址且不跟随重定向。测试注入的 TLS client 仅供本地接收器，不构成公开配置。
- Java Session webhook 的 8 次失败策略与 Go Invocation 的 12 次分别记录；本轮没有将 Go 接收器测试算作 Java Session webhook 的真实网络测试。

## 本轮确认并修复的问题

1. PostgreSQL 等待迁移 advisory lock 时持有 MVCC snapshot，与 concurrent index 建立互相等待。改为可取消的 try-lock 轮询。
2. 小连接池中遍历任务 rows 再读 inputs，以及持有 session 锁连接后嵌套读库，可能耗尽连接。前者先关闭 rows，后者统一使用独立锁连接，保留锁语义。
3. External worker capabilities 数组直接进入 registry，无法匹配策略要求的对象。统一在注册/连接入口归一化，实际 PostgreSQL 选择实例与冻结 attempt 已复验。
4. Python worker 与 Managed DP 都可能在异步 start 事件到达前完成快速任务，得到 409。现在进入执行前同步确认启动，拒绝旧 generation/终态 attempt；异步事件仍走原日志。
5. Control Plane 短暂不可用时，Python context GET 直接使任务失败。只读调用现在在原超时预算内重试临时网络错误及特定 5xx，业务错误和写请求不自动重试；SIGKILL 实验已复验。
6. Managed Conversation 的 native 消息 role 为大写，最终结果提取遗漏；现在兼容 role 大小写并优先本 turn 的 final_output，排除用户/工具及其他 turn。
7. 同 Conversation 的后续 Invocation 没有继承已有 sessionId，导致 native turn 未派发。统一在发送前保存 Conversation/Session/Turn 身份；新增同会话续聊回归。
8. SessionEventLog 将 event ID 重复误当作 sequence 竞争，可能反复尝试 16 次。现在先判断已提交事件，遵循幂等与内容冲突校验。
9. Sandbox 被复制的 RuntimeContext 在结束后仍引用旧绑定，可能回退使用其他执行的 sandbox。共享释放标志、清理只执行一次，拒绝使用已释放绑定；取消及并发隔离回归通过。
10. 示例 bootstrap 预建未绑定 Agent、复用旧 worker 身份文件，以及可空列表处理不当，分别导致注册失败或执行错误。改为注册时创建、每轮独立 identity/outbox 目录、规范空值。
11. Console 刷新丢失正在观察的 Invocation；现在 URL 保存 ID，并修正无消息的完成态提示。API key 不写入 URL 或该功能的持久存储。

其余修改主要是让测试遵循现有 native journal、AgentSession、execution context、生命周期关闭和新契约，未把失败断言简单禁用。具体修改和原始失败原因见三个分报告。

## 仍需独立环境或专项实验的范围

以下项目没有被本轮“通过”结论覆盖，历史清单不能整体标为完成：

| 保留项目 | 本轮已覆盖的边界与缺口 |
| --- | --- |
| 长期容量和慢消费者 | 1,005 事件、多页回放、2,987 outbox 及并发测试已做；百万 chunk、超长会话、超大多媒体、GC/冷重建、慢 PostgreSQL、多日 soak 尚未量化 |
| 云与跨主机故障 | 本地真实 PG/MySQL/Mongo/Redis 已做；OSS/COS/E2B 真实服务、NAS、Redis 主从切换、网络分区、时钟偏移尚未覆盖 |
| 每个 crash 边界 | CP SIGKILL、DP outbox 和 Chat 重启以及指定写入失败注入已做；没有穷举每个 blob/head/inbox/action/外部副作用前后的崩溃，或长工具运行中的跨主机 takeover |
| 真实 provider 与框架版本 | 使用确定性模型/runner；真实模型的交错工具参数、fallback、usage/计费、多媒体及所有 LangChain/AgentScope Python 版本，真实 Codex/Qoder CLI 未执行 |
| External worker 崩溃 | HTTP ACK/取消顺序有自动化覆盖；实际 worker 被 kill 后从原 outbox 恢复、丢最终 ACK、旧 generation 重投的全进程矩阵未完成 |
| 完整编排图与预算故障 | Agent、Team、包含 Team 的 Workflow 已实际成功；深层 Team/child Workflow 的失败/部分成功/取消/产物组合，以及预算预留崩溃和迟到 usage 的所有组合尚未穷举 |
| SSE 每个断点和副本通知 | 实际刷新、tool 参数中断、snapshot+suffix、410 已做；每一个 token/tool 边界的 golden 对比、多副本通知丢失及长期背压尚未穷举 |
| Webhook 外部网络 | Go 本地 TLS/真实数据库/SSRF 拒绝已做；真实公网 DNS 变化、网络分区、Java Session receiver/lease takeover/删除会话组合仍需验证 |
| 历史版本和部署 | 新库、0111 升级种子、DP DDL validate 及真实 envtest 已做；所有旧版本数据库/codec、用户已有历史迁移、真实 Kubernetes/云上部署未执行 |

## 环境与证据保留

本轮临时 Control Plane、Data Plane、Gateway、Chat 和 Python worker 已停止；专用 PostgreSQL/Redis 及 Testcontainers 容器已清理，28080–28085、54780 端口确认释放，用户原有服务未操作。清理证据保留在 `/tmp/agentscope-regression-20261002/cleanup.json`。临时文件位于 `/tmp/agentscope-regression-20261002`、`/tmp/agentscope-java-regression-20261002` 及三个分报告列出的日志路径。测试凭据只放在受限权限的临时文件；仓库内新增的 driver 和报告不需要用户凭据。
