# 多副本与大流量验证场景

本轮按普通组件多副本、共享 PostgreSQL 与 Workspace 设计。容量验证隔离真实模型/供应商响应延迟，使用本地有固定延迟、可控暂停和错误的模型/工具 fixture。先记录平台自己的吞吐/延迟及限额，再提高并发；以下是需执行的场景定义，尚未声称完成压力或容量认证。

## 负载维度

| 场景 | 分别改变的负载 | 机制检查与观测 |
| --- | --- | --- |
| Session/Turn 准入 | 大量不同用户/Session；单热点 Session；同 Application 不同 Session；重复幂等请求 | 受理 QPS、p50/p95/p99、SQL 锁等待/连接数、429/503、队列长度；maxConcurrent 包含 queued/running/cancel_requested/requires_action，实例 slots 独立 |
| 调度/认领 | Agent Service、Data、Go Runtime 各 1→2→4 副本；认领后杀单进程；慢 ACK | command/task fence、同 Session 顺序、不重复启动、无 sticky Session、due 索引读行数、调度等待时间、恢复时间；未知外部副作用不自动重跑 |
| 公开事实与用量 | 单长 Session、许多短 Session；每 Turn 高频 delta；重复/乱序/迟到事实 | 每次有限 batch、事务耗时、receipt 去重、连续 seq、outbox/未 ACK 字节数、事件提交至公开可见延迟；重复用量不重复计费 |
| SSE/快照 | 连接数分级提高；大量同时重连；旧游标；极慢消费者；持续分页期间更新/删除资源 | 每连接最多有限页、实例连接上限、慢消费者超时、Last-Event-ID、snapshot TTL/keyset一致性、堆内存/GC、FD、出站字节、恢复页数；断线不终止任务 |
| 权限/预算 | 同策略很多 Session；到期刷新；撤权；CP 暂时不可用；并行模型报告用量 | 按策略版本共享缓存、刷新 slots、有限有效期、撤权到拒绝的时间、fail-closed、已报告预算边界；在途并行超额无固定上界 |
| Tool Worker/Sandbox | 很多 Workspace；共享 Environment；工具并行；Worker 重启/丢回执；5 分钟 idle回收再恢复 | ToolTask 认领/结果 fence、Worker 本地 outbox、Sandbox创建 CAS、保活/容量、归档恢复时间、目录IO、工具等待/执行延迟；不自动重复未知副作用 |
| Go Team/Workflow | Agent 任务/独立 child Issue/嵌套子图；大量节点失败/取消/等待信号 | 任务 Attempt、根 grant、子图/子Run 所属、递进取消、观察游标、节点/edge 扫描页数、输出汇聚大小；当前部分全图读取仍须优化 |
| Webhook/出站投递 | 多订阅、长日志过滤、慢/失败 receiver、多 Scheduler、副本切换 | idle订阅事件唤醒、due有限页、one-pending-event、发送slots、稳定deliveryID、签名、有限重试、权限复核、待投递年龄；provider去重仍由接收端协议保证 |
| 控制资源配置 | 多用户编辑 Environment/Vault/Memory/Agent；执行期间变更 | 资源revision冻结、动态overlay版本、失效通知、资源权限批量查询；管理变更不阻塞运行事件线程 |

## 执行与记录要求

每个场景先固定语义，记录副本数、各实例 slots/数据库连接数、请求并发、活跃 Turn/SSE/工具/待投递数量和 fixture 延迟。第一组可用 10/100/1000 并发逐级观察，再按本机资源与测量瓶颈增加；这些数字是实验梯度，不是承诺服务的容量。运行线程、HTTP调用、页大小、pending 队列和重试次数均应受明确限制，超出时返回标准限制错误。

每次记录 p50/p95/p99、成功/拒绝/超时数量、CPU/堆/GC/FD、数据库连接与锁等待、索引读行数、outbox最老记录和 backlog增长率。比较提交到受理、受理到 started、事实提交到SSE、工具请求到认领、Webhook event到 provider ACK，避免把所有时间混成一个模型响应时延。

单进程代表性故障和多副本语义验证优先；完整压力曲线、持续运行、扩缩容和故障矩阵按本轮测试策略后补。所有容量结论必须附实际命令、隔离数据空间、配置及采样日志，不从 fixture 通过推断生产并发能力。
