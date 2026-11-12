# Durable 用户 API 实施与验收记录

[English](user-api-verification.en.md)

> 本文保留原有日期、结果、数字和命令，仅作为当时版本的历史证据，不是当前验收通过声明。
> 历史跨语言用例不代表当前存在其他语言客户端；当前检查入口见[库 README](../../README.md)。

日期：2026-11-09。对应[用户 API 与自主运行计划](../archive/user-api-and-runtime-plan.md) P1–P7。
框架目录 `/Users/ken/agentscope-3/agentscope-java`，分支 `automatic-acr-release-durable`。
直接在当前检出工作；未提交、推送或部署。

## 已实现的边界

- `DurableRuntime` 统一装配、生命周期与业务访问，直接创建、查询和列举 Session；不再公开 DurableClient。
  API-only Runtime 无需 Agent/模型/Executor，不认领任务；执行宿主显式 `start()`。
- Runtime/Session/Thread/Turn 句柄按持久身份重新查找；创建、排队、分页历史、控制、交互、观察、
  checkpoint/restore/fork 共用权威 Backend。一个 Durable Turn 可跨多个原生调用 Run 恢复。
- submit、steer、inject、respond 原子记录公开输入及关联身份；类型化控制在事务中校验。
  排队取消立即终态；活动取消等待真实结清；resume 不能绕过工具等待、审批或 UNKNOWN。
- 公共 HTTP 仅开放用户操作，内部 claim/commit/执行账本保持隔离。SSE、查询和 Runtime
  生命周期不驱动 Brain，也不因断开而取消任务；恢复依赖持久扫描、认领和 fencing。
- schema、公共消息/事件、流程及投影向量采用同一当前契约。存储格式为 5；旧开发格式
  明确拒绝，不保留兼容入口、历史读写或迁移层。原生 checkpoint 能力独立声明与校验。

## 检查与证据

| 检查 | 结果 |
| --- | --- |
| Java 17 完整 Durable reactor | 12 模块 `spotless:check clean install` 通过；32 套件、224 测试，0 失败/错误/跳过；启用真实 PostgreSQL 与可选 TypeScript HTTP 测试 |
| 独立源码交付 | `scripts/verify-independent.py` 从 Core/Harness 源码构建依赖、验证所有保留模块及解析后的依赖树；没有 Managed 源码、制品或服务依赖 |
| 用户契约矩阵 | 同一 11 项 `UserApiContract` 运行于直连与公共 HTTP；SQLite 和真实 PostgreSQL 均验证；另测授权、内部操作拒绝、丢失成功响应后的幂等重试 |
| AgentScope | 原生 steer/inject/withdraw、审批、安全恢复边界、工具结果恢复及公开 checkpoint/fork 测试通过 |
| 事件与生命周期 | 无 Brain 观察、游标回放/过期重同步、Run 过滤推进 Session 游标、回调重投、超时/关闭不取消、公开历史在事件裁剪后保留 |
| 无通知恢复 | 新执行宿主在无用户连接、无 Wakeups 时发现并接管已受理 Run；FIFO、等待、认领与 fencing 复用 Backend 契约 |
| 共享协议 | TypeScript runtime 5 项、Pi adapter 5 项通过；这里只同步受影响的 schema/投影向量，不宣称实现其他语言的完整用户 API |
| 示例 | Embedded 两 JVM 恢复、PublicApi 跨 API-only Runtime 提交/查询/取消、QueuedHands 编译及实际 MQ 链路通过 |
| 文档与分发 | 16 项文档测试、Mintlify validate/broken-links、BOM/shade 同步检查通过 |

主要日志：`/tmp/durable-user-independent.log`、`/tmp/durable-user-final-java.log`、
`/tmp/durable-user-example.log`、`/tmp/durable-user-http-example.log`、
`/tmp/durable-user-queue-example.log`、`/tmp/durable-user-bom.log`、
`/tmp/durable-user-doc-{tests,validate,links}.log`。

独立缓存为 `/var/folders/b3/0cr5wc6n1rq5r7x3ts0xvt1c0000gn/T/agentscope-durable-maven-2gendva7`。
验收后因磁盘容量限制清理其中可再生成的第三方缓存，保留构建出的 `io/agentscope` 制品、
源码与测试报告。平台仅消费这些已验收制品，不反向参与框架构建。

## 真实 RocketMQ 进程恢复

本地隔离 PostgreSQL 17、RocketMQ 5.3.2 Broker/Proxy，JDK 17；不使用生产凭据。
工具意图持久化后 SIGKILL Brain，由新 Brain 恢复同一 Run；Hands 重启后再次使用原
提交 requestId，仍返回同一 Run 和执行身份，账本只有一条 execution 记录：

- Run：`run_33a94341-e838-460e-9b11-5e23cdda00cc`。
- Execution：`exec_c37d35783b77ae06baf7fdc970d443b25986bf049412a9a208b6986031f766de`。
- 证据目录：`/tmp/durable-user-queued`，最终 Run revision 16。

该纯工具示例验证恢复身份与账本，不据此承诺任意外部工具 exactly-once。实际 shell 副作用
只发生一次的故障验收在独立平台进程夹具完成，见平台本轮记录。

## 范围与复现

使用 JDK 17，在仓库根运行计划中的独立构建命令。真实 PG 契约通过
`-Ddurable.test.jdbcUrl=jdbc:postgresql://127.0.0.1:56068/postgres?user=postgres` 启用；
跨语言 HTTP 用例通过 `-Ddurable.test.typescript=true` 启用。

未运行包含重复 `agentscope-service` 的框架根 reactor；按用户要求排除该目录。
已验证完整 Durable reactor；独立构建的 Core/Harness 前置模块以 `-DskipTests` 编译安装，
上述 224 项只统计 Durable，本轮不声称重新执行 Core/Harness 完整测试。未进行云账号、Kubernetes、
生产压测或发布验收；这些不在本轮核心 API 计划内。公开协议不意味着不同 Harness 的私有
checkpoint 可互换，也不把未知外部副作用重新执行成成功。
