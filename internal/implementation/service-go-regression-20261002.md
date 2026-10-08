# Go Service 集中回归记录（2026-10-02）

代码位置：`/Users/ken/agentscope-3/agentscope-java`，分支 `harness-context-redesign`。本轮直接在主目录执行，保留已有改动，未提交、未部署到用户服务。

已阅读并对照 `session-event-log-regression-backlog.md` 与 `agentscope-service/docs/service-api/regression-checklist.md`。此记录只说明本子任务真实执行的 Go/PostgreSQL 验证；HTTP 进程端到端、Java DP/Gateway、SDK/前端由并行任务另行记录。

## 隔离环境

- 新建 Docker 容器 `agentscope-regression-20261002-go`，镜像 `postgres:17`，仅绑定 `127.0.0.1:54780`，没有挂载用户数据目录。
- 新测试账户 `regression`，密码 `regression_only_20261002`，仅用于该临时容器。
- Go 测试 DSN：`postgres://regression:regression_only_20261002@127.0.0.1:54780/controlplane_go?sslmode=disable`。
- 并行 HTTP E2E 使用 `controlplane_e2e`；Java 使用 `agentscope_java`，三者数据库独立。新增测试还在各自唯一 schema 中执行并自动删除 schema。
- 本轮容器保留至三个任务完成；最后可运行 `/usr/local/bin/docker stop agentscope-regression-20261002-go` 清理该 `--rm` 容器。不会停止或重置用户现有数据库。

## 已执行命令及结果

工作目录均为 `agentscope-service/controlplane`。

| 验证 | 结果 | 日志 |
| --- | --- | --- |
| `go test -p 4 -count=1 ./...`，未设置 PG DSN | PASS：38 个测试包，13 个无测试包；PG 集成用例按设计跳过 | `/tmp/agentscope-go-regression-full-20261002.log` |
| 设置专用 PG DSN 后同命令首次全套 | FAIL：发现并发迁移死锁，保留原始证据 | `/tmp/agentscope-go-regression-postgres-full-20261002.log` |
| 修复后设置专用 PG DSN，同命令全套 | PASS：38 个测试包，13 个无测试包；所有启用的 PG 集成用例真实执行 | `/tmp/agentscope-go-regression-postgres-fixed-20261002.log` |
| `go test -p 2 -count=1 -v ./internal/store/postgres -run TestServiceMigrations` | PASS：2 项 | `/tmp/agentscope-go-regression-migrations-20261002.log` |
| `go test -p 2 -count=1 -v ./internal/httpapi -run TestServicePostgres` | PASS：3 项 | `/tmp/agentscope-go-regression-replicas-20261002.log` |
| `go build -p 4 ./cmd/service-controlplane ./cmd/as ./cmd/agentscope-runtime-host` | PASS | `/tmp/agentscope-go-regression-build-20261002.log` |
| `go test -race -p 2 -count=1 ./internal/asdp ./internal/controller ./internal/invocation ./internal/store/memory ./internal/store/postgres ./internal/httpapi`，专用 PG DSN | PASS：6 个包，无 race 报告 | `/tmp/agentscope-go-regression-race-20261002.log` |
| 设置本轮下载的 `KUBEBUILDER_ASSETS` 后 `go test -p 2 -count=1 -v ./internal/controller` | PASS：28 个顶层测试、32 个含子测试结果，0 skip；真实隔离 etcd/apiserver | `/tmp/agentscope-go-regression-envtest-20261002.log` |
| `go test -p 2 -count=1 -v ./internal/httpapi -run TestServicePostgresASDPCapability`，专用 PG DSN | PASS；包含池大小 1 的回归 | `/tmp/agentscope-go-regression-capabilities-pool-20261002.log` |
| 最终 PG + envtest 全套 `go test -p 4 -count=1 -json ./...` | PASS：38 个测试包、813 条测试/子测试通过；2 个 opt-in CLI smoke 子测试 skip，13 包无测试；0 fail | `/tmp/agentscope-go-regression-final-20261002.jsonl`；统计 `/tmp/agentscope-go-regression-final-summary-20261002.json` |
| 后续修复后 controller/httpapi/runtimebinding/postgres/model 五包 race 与三命令构建 | 全部 PASS；race 未报告竞争 | `/tmp/agentscope-go-regression-race-final-20261002.log`；`/tmp/agentscope-go-regression-build-final-20261002.log` |

## 新增回归覆盖

`internal/store/postgres/service_migration_test.go`：

- 三个独立 store 同时在全新 schema 初始化，真实执行全部迁移，含 `CREATE INDEX CONCURRENTLY`。
- 退至 0111 后种入 Endpoint、Release、Credential、Invocation，再升级 0112/0113。
- 人为冲突使 0113 在部分 DDL 之后失败，验证事务未泄漏新列或 migration version；移除冲突后重试成功。
- 旧 key 保持未绑定 Application，不会在迁移中隐式获权。
- 种入新 Application、ResultMapping、冻结 contract 和 partial_succeeded 数据后执行 0113/0112 down，再重新升级；基础调用记录保留，partial_succeeded 不计为 active。降级删除的新字段本身不承诺保留。

`internal/httpapi/service_postgres_regression_test.go`：

- 两个独立 store / server 共享专用 schema，查询连接池各限制为 1，验证嵌套服务锁不会耗尽查询池。
- 12 个并发请求跨两个 Endpoint，Application maxConcurrent=1：恰好 1 次新 admission、5 次相同请求重放、另一 Endpoint 6 次配额拒绝。
- 并发、重复、乱序 token 累计只计最大值；旧 Invocation 更新不会覆盖已记账用量；应用预算跨 Endpoint 生效。
- 两个副本争抢同一到期 poll，恰好 1 个 CAS 成功；中途 command wakeup 后，旧 poll 的完成 CAS 不会覆盖唤醒时间。
- 三个并发写入者产生 1005 个事件，跨副本按 61 条分页回放，序号连续、ID 无重复；跨 64 条 checkpoint 后快照包含完整文本前缀。
- 注入 event/source 已写入但 head 写入失败：另一副本先提交再重试原 source，既不丢失也不错误别名到其他事件。
- 注入 retention snapshot 写入失败：历史仍可读；随后跨副本分两批完成 >1000 条清理，旧 cursor 明确 expired，snapshot 与 source dedup 保留，从 snapshot cursor 读取新 suffix 成功。
- cancel 命令 HTTP 接受落盘后关闭原 store，另一副本执行 sweep；Invocation 进入 cancelled、没有 Run 被派发，terminal snapshot 可读。
- HTTP 注册 capability 名称数组、实际 ASDP Connect 回调、PostgreSQL 持久状态与 Resolver.DispatchCandidate 联动：缺失 required capability 拒绝，匹配后选中正确实例，Attempt 保留冻结要求；外部会话 cancel 能力也从同一对象读取。

## 发现并修复

`internal/store/postgres/migrate.go`：原 `SELECT pg_advisory_lock` 在等待迁移锁时保留 statement snapshot；持锁实例执行 `CREATE INDEX CONCURRENTLY` 又等待该 snapshot，产生 PostgreSQL `40P01` 死锁。真实日志明确显示 advisory lock 与 virtual transaction 双向等待。改用 `pg_try_advisory_lock`，未获得锁时在 Go 侧以可取消 timer 等待，数据库语句立即结束。新增并发迁移用例以及带 PG 全套重跑均通过。

能力契约：真实 API 端到端发现 External ASDP capability 名称数组直接存入 registry，而调度策略要求 JSON object，导致 requiredCapabilities 永远匹配不上。现在 External HTTP 注册/heartbeat 与 ASDP Connect 在入口将名称列表转换为 `{name:true}` 对象；policy 仍严格要求对象，继续使用统一的 JSON containment，不新增调度兼容分支。public service cancellation 能力读取同步改为对象。对应真实 PG 路径测试通过。

`internal/store/postgres/collaboration_tasks.go`：上述能力测试将连接池限制为 1 时，`ListAgentTasks` 遍历未关闭 rows 又从同池读取 inputs，造成连接池耗尽死锁。第一次诊断运行被 SIGQUIT 终止以保存栈（`/tmp/agentscope-go-regression-capabilities-20261002.log`）。局部修改为先扫描/关闭 rows 再查 inputs；同一池大小 1 的完整派发测试通过（`capabilities-pool` 日志）。

## 尚未宣称覆盖

- `test/e2e` 带 build tag 的 Kubernetes 部署场景未运行；不使用用户 kubeconfig 或用户集群。
- 真实 Codex/Qoder CLI 模型调用的 opt-in smoke test 未启用；本轮不会将 mock provider 测试称为真实模型测试。
- 外部公网 HTTPS webhook 接收器、真实 DNS rebind/网络分区、进程 kill -9 后外部工具副作用不确定性、长时间压测/大规模保留清理不属于上述通过结论。
- 新增双副本测试是两套独立 DB 连接池和服务对象；不等同于所有主机/网络故障组合。进程级 HTTP E2E 以主任务报告为准。

envtest 二进制位于 `/tmp/agentscope-envtest-20261002/k8s/1.32.0-darwin-arm64`；测试自行启动并停止临时 etcd/apiserver，不连接用户 Kubernetes 集群。最终全套中 Controller envtest 与全部设置了 DSN 的 PostgreSQL 用例均实际执行。唯一两个测试级 skip 是 `TestNativeSubagentDelegationSmoke/codex` 和 `/qoder`。

本轮检查还包括 `gofmt` 与针对改动路径的 `git diff --check`，均通过。Go 子任务完成时专用 PostgreSQL 容器仍运行，供并行 Java/HTTP E2E 使用；待它们结束后由主任务统一清理。

## HTTP E2E 后续联调：Python 执行启动顺序

主任务实际 Team/Workflow E2E 发现，Bridge 将 `ExecutionAttemptReport(start)` 异步入队后立即调用 runner，快速 leader 已同步提交 `task.complete(outcome=waiting)`，但 start 尚未送达 CP，因此 completion 得到 409。修复在 Python `ExecutableAdapter` 进入 context/runner 前，通过已有 task-scoped MCP `task.start` 同步确认；Bridge 对该 adapter 不再另发异步 start。未新增 Go API，也没有使用 sleep 或通用 409 retry。

在途 start 请求使用 shield 并在取消时等待线程请求结束，确保业务 runner 不会启动、cancelled 报告不会先于晚到的 start。新增测试验证快速 waiting + 重复 dispatch 的屏障顺序、启动被拒绝不进入 runner、在途取消等待请求落定。针对性 `test_executable.py`、`test_service_entrypoints.py`、`test_bridge.py` 共 34 项通过，日志 `/tmp/agentscope-sdk-start-barrier-20261002.log`；随后完整 Python SDK suite 83 项通过，日志 `/tmp/agentscope-sdk-start-barrier-full-20261002.log`。主任务负责真实进程 E2E 重跑。

## Invocation webhook 补充回归

新增 `internal/httpapi/service_invocation_webhook_test.go`。此前 Service Invocation webhook 没有专用测试，不能以 Automation webhook 或 Java Session webhook 测试代替。以下三项已在真实本地 TLS receiver 与专用 PostgreSQL 上执行通过，初轮日志 `/tmp/agentscope-go-webhook-regression-20261002.log`：

- `TestServiceWebhookTLSBackoffPauseRetryAndSignature`：真实 HTTPS POST，校验 `t=<seconds>,v1=<hex>` 与 `HMAC-SHA256(secret, timestamp + '.' + rawBody)`；400、503、超时、302 都保留未确认事件，重投保持相同 event ID/body 并生成新 timestamp 签名。虚拟时钟推进指数退避，无 sleep 等待长退避；连续 12 次失败后 `failed`、自动投递停止，公开 retry API 清计数、重新投递同一事件，成功推进 cursor。302 不跟随。
- `TestServiceWebhookProductionClientRejectsNonPublicDestinations`：直接使用生产 SSRF client，loopback TLS receiver 未收到请求，IPv4 私网/link-local/CGNAT/benchmark 和 IPv6 loopback/mapped-loopback 均在拨号前拒绝。
- `TestServiceWebhookPostgresReplicaRestartAndUncertainDelivery`：接收端已 204 后注入 cursor KV 提交失败，关闭旧 store、创建新连接池；两个副本并发争抢同一 webhook，数据库 advisory lock 只允许一次重投，仍是原 event ID/body、新签名；cursor 持久化后可见，密钥以 ciphertext 落盘。此处是服务/store 重接，不是 PostgreSQL 进程重启。

为测试引入了 unexported `deliverServiceWebhooksWith` 的 client/clock 参数。生产入口始终使用 `serviceWebhookClient()` 与 `time.Now`，没有对外暴露绕过 SSRF 的配置。测试注入仅信任 `httptest.NewTLSServer` 证书的 client，公网接收器/真实 DNS rebind/网络分区仍未测试，不把本地 TLS fixture 计为公网可达性验证。

注意两套 API 的区别：**Go Invocation webhook 是 12 次失败后停止**；**Java AgentSession webhook 是 8 次失败后停止**（`SessionWebhookService` 的实际实现）。旧清单里的 eight-failure 用于后者，`session-event-log.md` 的 8 次说明仍正确。本轮只在中英文 `service-api.md` 补充 Invocation 的 12 次与手动 retry 语义。Webhook 本身的互斥是 PostgreSQL advisory lock；Invocation worker 的 due lease/CAS 是另一层，已由前述双副本 queue 测试验证。

添加以上测试后，最终 PG + envtest 全套再次通过：38 个测试包、824 条测试/子测试 PASS、2 个 opt-in CLI smoke skip、13 包无测试、0 fail。完整事件日志 `/tmp/agentscope-go-regression-webhook-final-20261002.jsonl`。HTTPAPI 包完整 race 通过（25.336s），日志 `/tmp/agentscope-go-regression-webhook-race-20261002.log`；本轮相关路径 `git diff --check` 通过。

## Managed Job 启动确认屏障

真实快速 Managed Job 再现了同类时序问题：DP 的会话状态 PATCH 已返回，但 `session.status_running` 仍在异步 outbox 中，模型的 `task.complete` 先到 CP，AgentTask 尚为 dispatched，因而返回 conflict。新增内部 `/api/internal/runtime-sessions/:sessionId/start` 命令，使用捕获的 task/attempt/generation/turn 校验当前执行并提交运行状态；`SessionTurnRunner` 必须收到同步确认后才记录本次输入并调度模型。命令复用既有启动及审批恢复规则，不增加第二份事件记录；旧 outbox 仍使用原事件 ID 异步投递。当前 running 的重试幂等，历史或终态 attempt 的启动拒绝，不能用历史事件接收成功替代启动授权。

新增 `TestManagedStartBarrierBeforeFastCompletion`，分别在 memory 和专用 PostgreSQL 上运行：两个 Server/数据库连接池确认启动，异步 running 事件尚未投递时，立即调用实际 HTTP MCP `task.complete` 成功；错误内部凭据 401、错误 generation 409、终态重启 410；延迟 running 事件可归档但不会逆转完成状态。Java 新增 HTTP fence/失败传播测试及拒绝启动后不接收入参、不调度模型的测试，由 Java reactor 统一执行。

PG 测试把查询池限制为 1，还暴露普通 Session 锁占用查询连接后回调再查询的死锁。此前仅 `service-`/`workflow-` 锁使用独立连接，现在所有 Session advisory locks 都使用独立连接，保留原锁 key 与跨副本互斥语义；连接关闭释放锁。修复后 PG 测试通过。代价是活跃锁需要额外数据库连接，长期容量与连接预算仍属于保留的压测边界。

日志：`/tmp/agentscope-managed-start-barrier-20261002.log`；修复后带真实 PG 的完整 HTTPAPI/PostgreSQL 两包 race 均通过（25.520s/3.859s），`/tmp/agentscope-managed-start-race-20261002.log`。真实 CP/DP 重新加载后的快速 Managed HTTP 端到端结果由主报告记录。

最终再执行一次完整 PG + envtest Go suite：**38 个测试包、827 条测试/子测试 PASS，2 个 opt-in CLI smoke skip，13 包无测试，0 fail**，日志 `/tmp/agentscope-go-regression-managed-final-20261002.jsonl`。新增双副本同时 start 的定向 race 也通过（含真实 PostgreSQL，3.400s），完整输出保存在上述 start-barrier 日志。
