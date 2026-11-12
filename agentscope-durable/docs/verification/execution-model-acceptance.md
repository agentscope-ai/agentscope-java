# 执行模型独立验收清单

[English](execution-model-acceptance.en.md)

更新：2026-11-10。关联[实施计划](../design/execution-model-plan.md)与[开发进度](execution-model-progress.md)。用户要求优先完成 Durable 开发，重型集成验收后续单独执行；Managed Agents 不在本轮范围。

## 1. 开发阶段保留的检查

- 编译受影响模块和调用方；同步 Java 类型、schema、示例与双语文档，运行相关 Spotless 检查。
- 新增/修改行为运行定向测试，尤其是原子提交、身份归属、幂等、fencing、恢复和 UNKNOWN。发现的实现缺陷立即修复，不移入验收延期项。
- 使用确定性模型、临时本地存储与已有共享契约；不在每次改动后重复所有后端、所有示例和全仓测试。
- 每次记录命令、实际结果和未覆盖场景。构建中止、跳过、SDK mock 和历史版本通过均不等于当前真实部署验收通过。

## 2. 后续独立执行的验收

以下各项均为**待独立验收**。已有证据可以帮助定位范围，但须在最终开发源码上核定适用性。A 编号对应实施计划第 12 节。

| 项目 | 对应要求 | 场景与判定 | 已有证据 / 后续入口 |
| --- | --- | --- | --- |
| I1 完整构建与发布内容 | A16、A17、A18 | 所有保留框架模块 clean verify；类、资源、BOM 和示例无旧入口或漏打包 | 下方根 reactor 命令；本次全仓检查主动中止，不计通过；journal 资源修复有独立定向测试 |
| I2 独立依赖构建 | A18 | 无 Managed/Service 源码或缓存制品仍能构建、测试、运行 Java 示例；检查解析后的依赖树 | `verify-independent.py`；此前阶段记录不能替代最终源码验证 |
| I3 后端组合 | A1–A13、A17 | SQLite/PostgreSQL × 直连/HTTP 共用契约；提交响应丢失、存储故障、快照与过滤一致 | 已有共享 Backend/HTTP 契约；生命周期阶段真实 PG 124 项通过，其中 81 项为真实 PG 用例；最终源码需复核 |
| I4 真实 MQ 与独立 Hands | A4、A8、A15、A17 | Broker/Proxy + Brain/Hands 独立进程，命令/结果重复、丢失唤醒、重启、取消、接管；executionId 不变，UNKNOWN 不被伪装成功 | 现有 RocketMQ 测试主要为 SDK mock；真实 Broker 驱动与失败注入夹具尚需补齐，不能记为已执行 |
| I5 控制与接管竞争 | A2–A9、A15 | pause/resume/cancel 与模型返回、结果接收/应用、终态提交竞争；旧 owner 被拒，显式暂停不自动解除，同 Thread FIFO | 已有本地竞争/恢复测试；补最终源码下的独立 JVM 强制退出与租约接管组合 |
| I6 用量与来源 | A5、A9、A10、A12 | 实际模型请求与已记录结果回放区分；重试/恢复不重复计费事实；Hands 原始 Run/Step 与结果应用 Run/Step 可区分 | 模型与 Hands 来源已实现并通过定向测试；多进程端到端核对仍待执行 |
| I7 委派与资源隔离 | A3、A13、A14 | 默认独立子 Thread、显式复用、委派幂等；父暂停/取消边界、死锁拒绝；restore/fork 不带入可执行旧任务 | 隔离/复用、原子映射、取消先到/受理中取消、父暂停与依赖环拒绝已通过 SQLite/HTTP 契约和 AgentScope 恢复用例；后续覆盖父子进程分别退出的组合 |
| I8 公共 API 与观察 | A1、A11、A12、A15 | 替换客户端找回对象；SSE 重连/过期/过滤空页；回调失败不确认；观察/断连/close 不驱动或取消任务 | 已有共享 User API/HTTP 契约与聊天示例检查；后续用真实 HTTP/SSE 客户端跑组合流程 |
| I9 全部示例与部署形态 | A16–A18 | Java 示例、普通 call/stream、API-only、内嵌/独立 Hands、可选 MCP/Sandbox/Objects 配置和 EN/ZH 文档一致 | 编译与定向示例检查随开发；Docker、外部服务和全部部署形态留待此阶段 |

## 3. 可复用执行入口

从仓库根目录执行，使用 Java 17 和 `-T1`。隔离环境中的真实模型凭据，默认使用本地夹具；需要真实服务的用例必须显式配置并确认没有跳过。

```sh
# 最终框架回归：按用户范围排除重复的 agentscope-service
mvn -B -T1 -Dagentscope.frameworkOnly=true clean verify

# 独立缓存构建与依赖检查，不创建源码副本
python3 agentscope-durable/scripts/verify-independent.py

# 依赖/BOM 一致性
bash .github/scripts/check-shade-and-bom-sync.sh
```

真实 PostgreSQL 沿用 `-Ddurable.test.jdbcUrl=...` 契约入口，在隔离 schema 中运行。真实 RocketMQ 需记录 Broker/Proxy 版本、启动命令、进程退出点及可复现断言；目前不能提供一个未经验证的“一键验收”命令。文档最终检查为 `docs/` 下 `npm test`、`npm run validate`、`npm run broken-links`，无需每次纯 Java 改动重复运行。

验收记录至少包括源码版本或工作区 diff 标识、环境、命令、通过/失败/跳过数、日志位置和未覆盖项。临时日志仅为阶段证据，后续独立验收需导出必要报告。验收发现缺陷回到实现修复，不降低断言，也不添加旧协议兼容路径。

## 4. 当前执行决定

`/tmp/durable-thread-journal-framework-final.log` 对应的全仓 Maven 已按最新快速开发要求主动停止，退出码 143。停止前 Core 2,686 项、Harness 1,162 项及 Durable 模块测试已完成；这不是全仓通过。原生 journal 资源不一致已修复，并用提交后的真实事件验证当前 schema 与旧/未来版本拒绝，结果记录在开发进度。

本清单中的待验收项不计作开发功能缺口，也不计作验收通过；开发功能缺口继续由进度文档跟踪。本轮不启动 Managed Agents 改造。
