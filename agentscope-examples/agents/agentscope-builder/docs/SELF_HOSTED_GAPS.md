# self_hosted Hands · 待完善清单

> 对照 Design 1（事件驱动外化工具 + 纯出站 Worker）主线：**核心路径已可用**。  
> 本文只记 **self_hosted 仍欠缺 / 半成品 / 明确延后** 的项，便于后续迭代拣选。  
> 产品用法见 [guide/08-hands-worker.md](guide/08-hands-worker.md)；通用生产债见 [FOLLOW_UP_PRODUCTION.md](FOLLOW_UP_PRODUCTION.md)。

最后更新：2026-07-22

---

## 0. 已落地（勿再当缺口）

| 能力 | 落点 |
|---|---|
| 内置 shell/FS 外化为 `SchemaOnlyTool` | `EnvironmentSpecFactory` + `SelfHostedToolSchemas` + `disableFilesystemTools/Shell` |
| 挂起 → `agent.tool_use` + `requires_action` | `SessionTurnRunner` |
| `user.tool_result` / `user.custom_tool_result` 续跑 | `ManagedSessionApiController` + `SelfHostedWorkerController` |
| Worker 出站：poll / ack / heartbeat / stop | `WorkerEnvironmentController` |
| pending-tools / tool-results / skills | `SelfHostedWorkerController` + env key |
| 参考执行器（内置工具） | `HandsWorkerMain` + `LocalHandsToolExecutor` |
| 开发同 JVM 执行器 | `InProcessEnvironmentWorker`（**不是**生产形态） |
| session `resources[]` → poll `metadata` → stage `inputs/` | `SessionInputStager`（本地 path / `file://` / http(s)） |

---

## 1. 功能缺口（产品能力）

| 项 | 现状 | 建议优先级 |
|---|---|---|
| **Worker 扩展 SPI** | 仅内置 `execute` / `read_file` / …；无客户注册自定义工具、无内网 MCP 包装入口。Brain 侧 `user.custom_tool_result` 续跑已接线，Worker 侧未消费 `agent.custom_tool_use` | **P0**（对齐 Claude worker SDK 的下一步） |
| **Session 文件 / Files API** | `resources[]` 的 `type=file` 仅能 stage URL/本地 path；无上传 Files API、无 `file_id` 拉取；`github_repository` 仍在 Brain `SessionResourceMountService`，**不会**在独立 Worker 上 clone | **P1** |
| **skills 分发形态** | `GET …/skills` 返回 JSON + base64 资源；无 zip/tar 整包、无增量同步 / ETag、无可执行位权威元数据（靠路径启发式） | **P1** |
| **工作目录生命周期** | Worker ack 后建目录；session 结束后**不保证**清理；无配额 / GC；多 session 共用 `--hands-root` 时需运维自管 | **P2** |
| **per-session 隔离** | 一个 Worker 进程串行/轮询多个 work item；无「每 session 起容器 / 独立 uid」启动器 | **P2** |
| **Subagent Hands** | 子代理共用 parent session 的挂起/work 语义，无独立 lease / 独立工作目录契约 | **P2** |
| **多语言 Worker SDK** | 仅 Java `HandsWorkerMain`；无 Python / TS / Go 参考实现 | **P3**（计划明确排除本期） |

---

## 2. 协议与语义半成品

| 项 | 现状 | 建议优先级 |
|---|---|---|
| **挂起期间 turn 租约** | TOOL_SUSPENDED 后 turn 结束会释放本地 turn lease 并置 `requires_action`；与 HITL 长等待场景的租约策略仍需统一压测（多副本下续跑抢租约 409） | **P1** |
| **并行工具批次策略** | Worker 一次拉取全部 pending 并批量 `tool-results` 续跑；无「集齐 N 个 / 超时部分失败」的显式策略与客户端契约 | **P2** |
| **session 结束判定** | `HandsWorkerMain` / `InProcessEnvironmentWorker` 用「连续空闲 pending 轮次」启发式 `stop`，**未**查询 session `idle`/`terminated` 状态 API | **P2** |
| **失败重试** | poll/执行周期有粗粒度 catch+sleep；ack / heartbeat / tool-results **无**独立退避与幂等键文档 | **P2** |
| **心跳 reclaim** | 状态机文档写了 stale heartbeat → reclaim；需确认多副本下过期 `active` 行是否稳定回到 `queued` 并可被另一 Worker 认领 | **P2** |
| **legacy sandbox 快路径** | `HandsLeaseService` 仍优先返回已注册的 `ExternalSandboxRegistry` 条目（旧同机沙箱）；事件驱动主路径不依赖它，但增加理解成本 | **P3**（可删或文档标 deprecated） |

---

## 3. 执行器与工程化

| 项 | 现状 | 建议优先级 |
|---|---|---|
| **跨机 E2E 验收测** | 有单测（executor / stager / pending / schemas）；**缺** Brain↔独立进程 Worker、无共享盘、关 Worker 后停在 `requires_action` 的自动化 E2E | **P0** |
| **工具→shell 翻译复用** | 计划建议复用 `BaseSandboxFilesystem` 命令串；现实现为 `LocalHandsToolExecutor` 自管 FS/shell，行为需与 harness 内置工具保持对齐回归 | **P2** |
| **可观测性** | `HandsMetrics` / hands-stats 偏租约入队；缺 per-tool 延迟、失败率、Worker 在线数、skills 下载失败指标 | **P2** |
| **安全边界** | Worker 信任 env key；工作目录内路径校验有相对路径限制，但无细粒度 allowlist / 网络 egress 策略；与 Claude 侧 sandbox 策略对齐仍浅 | **P2** |
| **优雅停机** | SIGTERM 设 `running=false`；进行中的 tool exec / HTTP 调用未强制 drain 超时与 `stop` 保证 | **P3** |

---

## 4. 文档 / 产品表述仍易混淆的点

| 项 | 说明 |
|---|---|
| **InProcess ≠ 生产 self_hosted** | 默认 `builder.hands.in-process-worker=true`；验收跨机清单必须关进程内 Worker |
| **`sandbox` ≠ `self_hosted`** | `sandbox` = E2B 平台托管；不要标成 self_hosted |
| **guide 与产品边界** | `sandbox`=E2B、`self_hosted`=出站 Worker；验收路径见 [guide/14-validation.md](guide/14-validation.md) |
| **契约路径别名** | 计划草案写过 `GET …/events?type=agent.tool_use&state=pending`；实现为 `GET …/pending-tools` + `POST …/tool-results`（见 [events/worker.md](events/worker.md)） |

---

## 5. 建议下一迭代顺序

1. **跨机 E2E 测试**（关 in-process，独立 `HandsWorkerMain`，断言执行面在 Worker）  
2. **Worker 扩展 SPI**（自定义工具 + 可选 MCP 包装 → `custom_tool_use` / `custom_tool_result`）  
3. **Files / skills 生产分发**（真上传或 `file_id`、skills 包体与增量）  
4. **session 结束 / reclaim / 可观测** 收口  
5. 多语言 SDK（按需）

---

## 6. 相关代码索引

| 区域 | 路径 |
|---|---|
| 外化装配 | `…/managed/EnvironmentSpecFactory.java`、`…/selfhosted/SelfHostedToolSchemas.java` |
| 挂起 / 续跑 | `…/managed/SessionTurnRunner.java` |
| Worker API | `…/api/WorkerEnvironmentController.java`、`…/api/SelfHostedWorkerController.java` |
| 执行器 | `…/worker/HandsWorkerMain.java`、`…/selfhosted/LocalHandsToolExecutor.java` |
| 开发同机 | `…/managed/InProcessEnvironmentWorker.java` |
| 单测 | `…/test/…/selfhosted/SelfHostedHandsUnitTest.java` |
