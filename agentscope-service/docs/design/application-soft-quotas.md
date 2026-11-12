# 应用级宽松额度与最终一致用量

[English](application-soft-quotas.en.md)

应用额度用于流量保护，允许突增超额、短暂过期和汇总延迟。Session FIFO、执行 epoch、取消、幂等和单次模型调用的记账事实仍保持原有一致性。该变更只针对 Managed 路径，不改变通用后端的 `builder_portal_quota`。

## 请求路径

- `ma.admission_quota` 普通读取，不再使用 `FOR NO KEY UPDATE` 或 `FOR SHARE`。
- 策略只在版本或有效字段变化时条件更新；旧版本不能覆盖新版本，基线 Token 用量保持单调。同版本且内容相同不更新。策略更新仍有 PostgreSQL 隐式写锁，但不再逐请求重复写入。
- 应用并发与应用排队数使用每个 Store 的一秒快照，缓存最多 4096 个窗口项。不同副本的快照独立，允许突增超过配置；终态后也可能最多一个窗口仍判断为满载。配置、缓存未命中或数据库查询失败不被静默忽略。未启用并发上限时不统计应用活跃 Turn。
- Session 排队和待处理 Input 容量保持 Session 锁内校验；取消仍可进入已满的普通 Input 队列。
- 应用 RPS 使用 Redis Lua `INCR` 与首次写入时的 `PEXPIRE`。Key 为 `ma:admission-rate:<quotaKey>:<unixSecond>`，有效期两秒，按应用隔离。客户端时钟偏差和秒边界允许短暂超额。额度计数最多等待 200ms；Redis 故障或超时放行并记录告警，不退还数据库准入失败消耗的请求数。线程中断继续传播。执行租约和取消等其他 Redis 操作仍保留严格校验。
- 无 Redis 的嵌入式构造器使用进程内近似秒窗口，仅供隔离运行/测试；生产 Bean 显式注入共享 Redis 计数器。

## 记账与后台汇总

每次 `model/end` 在原有 Session 树锁内重新检查 `ma.model_call`，更新调用用量和 Session 预算，并在同一原生事实事务内写入 `ma.application_usage_delta`。增量身份由根 Scope 与调用 ID 确定。重复投递不增加第二条增量；原事务回滚时增量也回滚。单次调用的最终用量不因应用额度宽松而被随意覆盖或丢弃。

仅 Agent Harness Runtime 启动后台汇总器：每秒领取至多 512 条增量，PostgreSQL 使用 `FOR UPDATE SKIP LOCKED`；按应用排序、合并后更新 `managed_tokens_used`，在同一事务删除已汇总增量。多个实例可并行处理；同一应用总量每批仍有短暂行锁，但该等待已退出 Turn 提交路径。失败时整批回滚并保留增量，下轮重试，进程重启后继续处理。

应用 Token 额度读取已汇总总量，因此正常情况下会延迟一个或多个汇总周期，高负载或持续故障时延迟可更长，没有固定超额上界。后台汇总恢复后最终补齐。Session 树的预算仍基于已持久化的逐调用事实，未放松执行幂等与恢复隔离。

## 升级与验收

发布前通过版本化 Schema Job 执行新增的 `managed-application-usage.sql`，再执行 `deploy/postgres-roles.sh` 为 Runtime/Agent Service 授权。普通服务启动只验证 Schema，不自动建表；旧迁移校验和保持不变。不新增 RocketMQ Topic 或消费组。

升级应先停止旧 Runtime 并排空其提交，再启动新 Runtime，避免同一批调用在新旧同步/异步计账协议之间切换；未完成增量全部汇总前不要回退到不理解该表的旧版本。旧 `managed_tokens_used` 保留，新增量从现值继续累计，不重新扫描历史调用收费。

回归覆盖：配额写锁不阻塞独立 Session 的准入/模型预留/结算，同策略不竞争写锁，允许应用并发突增超额，汇总后预算拒绝新调用，事务回滚不计费，同调用并发重放只记一次，多个汇总器不重复计费，跳过锁定增量，汇总失败后可恢复，Redis 限速故障放行且执行租约仍严格。
