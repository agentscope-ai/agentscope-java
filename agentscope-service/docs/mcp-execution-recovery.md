# MCP 执行接管与结果恢复

[English](mcp-execution-recovery.en.md)

AgentScope Service 恢复扩展 v1 通过现有 tools/list、tools/call 和 _meta，让新 Worker 查询原执行。
它不修改 AgentScope Java 二进制依赖。第三方 MCP 服务须实现相同约定；普通工具仍不会重放不确定操作。

## MCP 调用批次与统一完成通知

模型一次返回的调用列表经过 Harness 和 Core 权限检查后，如果全部是可用且参数合法的托管 MCP 工具，
Runtime 将它们冻结成一个 `mcp.batch`，通过一次 `TOOL_EXECUTE` 提交。批次最多 128 条调用，Worker 按列表顺序执行，
保留每次调用的独立 ID、描述哈希、参数、恢复声明和原始结果。混合本地工具、非法参数、未启用工具组、外部工具覆盖、
带预设参数或自定义参数扩展的 MCP 工具继续使用 SDK 原路径，避免提前执行未通过校验的调用或绕过参数注入。
技能等辅助工具经 SDK Builder 注册时，不影响 MCP 批次；隔离的只读元数据探针逐工具检查预设和参数扩展，
SDK 元数据不可识别时回退原路径，并由兼容性回归测试阻止静默降级。托管 Harness 设置模型选项 `parallel_tool_calls=true`，
允许模型同轮提出多个调用；Worker 仍按原列表顺序执行。模型分多轮返回时，各轮保持独立批次。

批次对应一条 `ma.tool_task` 和一条可调度的 `ma.hands_execution`。Runtime 通过 `ma.mcp_batch_call` 维护
逐调用的身份、终态和工具列表分页索引，运行时工具视图继续逐调用展示；该索引不会产生新的 ToolTask 或通知。Worker 在首次处理批次时，事务性创建带
`batch_parent_id` / `batch_ordinal` 的内部子调用行；这些行不进入独立调度、结果补发或 MQ 消费流程。
每次调用的完成结果先独立持久化，再继续下一条；父批次只在所有调用结果完整时生成一个有序的结果集合。
即使某次 MCP 调用返回错误，也保留该次原始错误结果并继续汇总后续调用。父批次 `succeeded` 表示结果集合完整，
每次调用仍保留独立的成功或错误状态。

接管始终以父批次租约为单位。新 Worker 复用完成的子调用，按原执行 ID 查询执行中的子调用，按原顺序启动未执行调用。
只有恢复合约返回可信 `not_found` 时才按原幂等 ID 补交。普通不支持恢复查询的 MCP 调用仍保留不确定状态，
不能因批次接管而重放。取消通过父批次 `TOOL_CONTROL` 生效：不再启动后续调用，已有结果保留，执行中的调用须等原结果确认。
关闭 MCP 连接不被视为物理操作已取消；未启动项生成独立的取消结果。

Worker 只通过现有 `TOOL_EVENTS` 发布父批次完成事件，完整结果可沿用大消息对象引用。Runtime 在同一事务内
保存结果、确认全部待处理任务已结束并建立 `dispatch_mode=direct` 的持久恢复意图；这条链路不再向 `TURN_RESUMES`
发送第二次通知。结果消费回调唤醒有界恢复执行池，500ms 的数据库扫描只作通知丢失和副本故障的补偿。
恢复执行取得原 Session 的 Redis 执行租约和新的数据库 execution epoch，并从共享 checkpoint 与批次原始结果恢复，
不依赖最初收到结果的 Runtime 内存。恢复意图在 Turn 终态或下一次持久挂起后才标记 applied；领取后崩溃仍可重试。
输入等待和旧版单调用继续保留原 `TURN_RESUMES` 兼容路径。

原始 TURNS 的 60 秒 Suspend、终态后的主动 ACK、Session settled sequence 和前驱校验保持不变。
批次执行完成、完成消息 ACK 或恢复意图领取均不能提前放行 T2，必须等 T1 终态提交。
部署先通过 schema job 追加 `managed-mcp-batch.sql`，为 Session Portal 与 Runtime 授予新增 `ma.mcp_batch_call` 表的 SELECT/INSERT/UPDATE/DELETE 权限，
再更新使用严格 schema 校验的 Java 服务；Worker 仍只访问私有执行状态。先完成 Worker 更新，再放行会提交批次的 Runtime 流量，
升级期间避免旧 Worker/Runtime 接收新批次。
本地回归覆盖双 MCP 调用的真实 Harness 恢复、跨 Runtime、部分结果接管、取消、结果先于/晚于挂起、无额外 Resume 消息、
Runtime 领取后恢复意图保留以及 T1/T2 顺序；远端验证需另外检查实际 Pod 日志和服务端物理调用次数。

## 声明与调用

可恢复工具在 Tool._meta 声明：

```json
{"io.agentscope/execution-recovery":{"version":1,"queryTool":"managed_execution_get"}}
```

同一服务必须提供 queryTool，声明 readOnlyHint=true。查询工具不加入模型工具目录。
恢复描述冻结在工具描述哈希和持久化 mcp.call 参数中；旧描述省略新增空字段，保留原哈希。
首次调用的 _meta 由平台添加如下内容，用户元数据不能覆盖：

```json
{"io.agentscope/execution-recovery":{"version":1,"execution_id":"mcp_exec_..."}}
```

标识由 Session 作用域和原 task.execution_id 派生，跨 Worker、连接和重投递不变。
关闭元数据传播时，可恢复调用在执行外部操作之前失败，不会降级成无标识调用。

## 服务端约定

- 在启动物理操作前原子持久受理 execution_id、工具名和参数指纹；并发提交同一标识、同一参数只关联原执行。
- 同一标识、不同参数必须拒绝，不能覆盖原执行。
- 调用方断开后，执行及结果持久化独立继续；跨连接、跨服务副本查询同一份事实。
- 返回最终结果前保存完整原始 CallToolResult；鉴权同样保护查询接口。
- 仍可能接管时不能删除执行事实。过期或不确定记录返回 unknown；not_found 必须确实表示从未受理。

查询通过 tools/call 调用冻结的 queryTool，参数为 `{"execution_id":"mcp_exec_..."}`。
返回普通 CallToolResult，其 structuredContent 示例：

```json
{"version":1,"execution_id":"mcp_exec_...","state":"succeeded","result":{"content":[{"type":"text","text":"original result"}],"isError":false}}
```

| state | 接管行为 |
| --- | --- |
| running | 保留原任务，由持有租约的执行器查询原执行 |
| succeeded / failed / cancelled | 校验标识和完整 result，落库后继续可靠发布工具事件及 Resume |
| not_found | 仅在声明上述原子幂等约定时，用同一标识补发原调用 |
| unknown、查询失败、响应或标识不合法 | 保留 unknown，不重放外部操作 |

查询本身不能启动、重试或取消操作。已取消的任务即使查询到 not_found 也不能再次启动。
原始回包和查询回包按稳定对象键顺序序列化，避免 JSONB 或跨副本键顺序造成结果哈希冲突。
工具结果结清后才有效 Resume；T1 完成后 T2 才运行。

## Runtime 直接发现工具

Brain 在推理前直接初始化 MCP 客户端并读取 `tools/list`，将描述固定在每 Turn 的
`ma.mcp_catalog` 快照中。发现使用有界的阻塞线程和连接容量，遵守初始化/请求超时，等待响应时检查取消，
连接在结束后关闭；不阻塞 Reactor I/O 线程。必需服务不可用仍拒绝运行，可选服务降级记录只在该 Turn 内固定，
下一 Turn 重新发现。工具过滤、只读恢复查询声明、描述 hash 和显式 local 环境下的 stdio 限制保持一致。

新请求不再创建 `mcp.describe` Worker 命令，也不通过 `TOOL_EVENTS` 或 500ms Turn 重投等待目录。
真实 MCP 调用仍走下面的 Worker 持久受理、执行和恢复流程，符合条件的一轮 MCP 调用合并为一个批次。已有版本的在途目录命令及结果继续接收，
仅用于滚动升级排空；历史序列化格式保持兼容。

## 持久受理与执行所有权

`TOOL_EXECUTE` 的成功回执表示不可变任务与封存配置已写入 `hands_execution`，不表示工具已经完成。
回调只做持久受理与本地唤醒；数据库不可用时仍返回 Suspend。唤醒丢失、ACK 后退出或执行容量耗尽时，
独立的有界 `ManagedHandsLoop` 从数据库恢复任务，不依赖执行命令的周期性 MQ 重投。

执行器在整个后台执行期间持有 Redis 租约（默认 60 秒），每 15 秒续租，并以相同 worker_token
刷新 `last_dispatched_at`。该时间同时用于恢复调度；旧 token 不能刷新新 owner 的恢复时间。
每个副本每 500ms 对现有索引执行有界到期扫描，正常 owner 的任务不会到期，也不会被其他副本反复领取。
`ready`/`unknown` 的重试间隔默认 30 秒；`starting`/`running`/`result_pending` 在所有权心跳停止并到期后恢复。
Redis 租约是最终互斥依据，数据库时间只决定何时尝试接管。领取前和领取后均可能发生崩溃，扫描仍会保留原身份恢复。

后台执行容量默认每副本 16 个；容量已满时新命令仍可可靠受理，任务保留在数据库，执行池没有无限内存队列。
MCP HTTP 调用通过异步完成信号收取响应；连接准备、JDBC 结果捕获和任务所有权检查运行在有界后台线程，
不在 MQ 回调或 Reactor I/O 线程中等待长 HTTP 响应。后台 owner 保留受控的等待槽位，查询/取消检查默认间隔一秒。
原结果一经确认即可靠捕获，结果 Outbox 独立补发 `TOOL_EVENTS`；租约失效不会否定已确认的原执行事实。

`TOOL_CONTROL` 的取消与重放仍走独立消费者。接管只查询原 execution_id；只有恢复合约确认原执行从未受理，
才允许以相同幂等身份补发。普通不可核实调用仍保持 unknown。原始 `TURNS` 的 FIFO 和持久挂起/恢复条件不变：
工具命令 ACK 不会确认 Turn 完成，T2 仍须等待 T1 的终态。

## 实现与范围

[HTTP 测试 MCP](../tests/http-mcp-long-tool/README.md) 的两个副本通过独立 mcp_fixture schema 共享执行标识和结果，
不引入 Sandbox 或 NAS。普通账号仅有表 SELECT、INSERT 及状态/结果 UPDATE 权限，初始化由 owner 单独执行。
验证覆盖断开调用方、跨副本查询、并发去重、参数冲突、错误执行标识、查询失败、原失败/取消状态、键顺序及真实 Worker Pod 故障。

测试 MCP 仅执行计时等待；本轮覆盖 Worker 故障，不承诺 MCP 服务自身丢失执行进程后自动完成。
此前未携带恢复标识的旧任务不会自动重放或伪造结果。

## Runtime Turn 的长挂起与主动 ACK

等待工具或输入时，原始 `TURNS` 消息继续占据 Session 的 MQ FIFO 队首，默认返回
60 秒 Suspend（`builder.managed.turn-suspend-seconds`）。工具结果仍经
`TOOL_EVENTS` 持久化，MCP 批次由持久恢复意图直接恢复；旧工具任务和输入等待仍通过独立 `TURN_RESUMES` 通道恢复；工具结果到达本身不会 ACK 原始 Turn。
执行容量或租约竞争仍采用短重试，避免让短暂竞争变成长时间等待。

SDK 确认同一缓存批次中的所有挂起 RPC 后，runtime 在有界阻塞线程上把投递凭据写入私有
`ma.turn_ack_receipt`。Turn 终态/checkpoint 与 ACK 就绪标记在同一个 journal 事务内提交。
凭据写入与终态提交取得相同的 Session 行锁；因此完成早于凭据写入也不会丢失 ACK 意图。
每个 runtime 每 200ms 有界扫描已就绪的 ACK 记录，而不是对每个等待 Turn 向 MQ 重投轮询。
ACK RPC 不持有数据库事务，也不阻塞 Reactor 或 SDK RPC 线程。

任一 runtime 副本都可以使用原队列路由和有效 receipt handle，在 T1 终态持久化后先把队列挂起缩短到 50ms，再 ACK T1；缩短响应返回的新 handle 用于 ACK。这样同一缓存批次的后继消息也能及时恢复可见，随后由 MQ 放行 T2。两次 RPC 共用 6 秒预算；若期间重投抢先更新 handle，终态重投会正常返回 SUCCESS。
ACK 领取窗口为 10 秒，响应丢失或副本退出后可重试；过期 handle 不再重试，超时重投会取得新凭据。
每代凭据使用独立标识，旧响应不会覆盖或删除新凭据。数据库不可用、凭据保存失败或 ACK 通道不可用时，
原消息仍保留，60 秒超时重投继续提供恢复路径。消息处理和 ACK 均按至少一次语义设计。

该能力使用隔离的 RocketMQ Java SDK 5.2.2 内部适配层；升级 SDK 时需重跑缓存批次与 ACK 状态契约测试。
部署前通过已有 schema job 追加 `managed-turn-ack.sql` 并重新授予业务角色权限，再部署兼容此迁移的服务版本。
凭据只用于内部短期恢复，不进入公共 API 或日志。回归入口为 `ManagedTurnAcknowledgementsTest`、
`ManagedPostgresTurnAcknowledgementsIntegrationTest`、`ManagedLitePushConsumerTest` 和
`RocketMqTurnAcknowledgementsIntegrationTest`。

## Runtime 命令 Outbox 发布租约

Execute、Cancel、Replay 保留各自不可变的 Outbox 身份。Runtime 副本通过数据库条件更新，
每次只领取一条可立即发送的命令，同时检查 broker 回执、重试时间、租约过期时间和读到的尝试代次。
发布所有权采用私有随机 token、30 秒租约和数据库时间，不修改命令内容及 Worker/MCP 执行标识。
其他发布者确认前读到的旧候选不能继续发送，也不会预先领取整批命令等待串行发送。

MQ 发送在发布线程上进行，不持有数据库事务；独立定时器每 5 秒续租。发送预算为 30 秒，
超时后停止续租并中断发送线程；即使 SDK 忽略中断，迟到回执也不能提交，其他 Runtime 可在租约
到期后接管。确认必须匹配同一 token 且租约未过期，续租、失败退避和释放也校验所有权，
旧发布者不能覆盖接管者。传输失败时仅释放自己的领取并退避 1 秒；数据库不可用时保留持久记录，
通过租约到期恢复。`outbox.command.*` 结构化日志记录命令、尝试代次、发布实例和 broker 回执，
不记录私有 token 或工具参数。

MQ 已接收但回执尚未落库时发生崩溃，恢复仍可能产生物理重复消息，必须保留 Worker 的持久受理、
执行租约与 fencing、逐调用结果和稳定 MCP 幂等身份。原 Turn FIFO、挂起/恢复及 T1/T2 终态顺序
协议保持不变。回归入口为 `ManagedToolTransportTest` 和 `ManagedPostgresToolTransportIntegrationTest`。

部署时通过已有 schema job 追加 `managed-tool-publication-lease.sql`。迁移前停止所有旧 Runtime
发布器，再启动兼容镜像；旧发布器不认识新租约字段，不能与新发布器混跑。已有待发送命令和成功
回执均保留。Session Portal、Hands 的共享 schema 校验器也需要部署包含新迁移的兼容镜像。
