# AgentScope Service 后续待完善事项（生产落地 / 体验）

> 状态：当前 Managed Agents 主链路（Agent 版本、Environment、Session 事件、HITL、Memory/Vault、Deployments、Hands 租约、JdbcAgentStateStore、CoordinationStore）**功能已可用**。  
> 本文仅记录**不影响当前功能完整性**、偏多副本生产、Hands 真隔离、MA 语义加深与体验收敛的后续工作。  
> 明确保持不动：`remote` Environment；`WorkspaceSandbox` 作为本地测租约桩（不做 checkpoint）。  
> 事件契约 / Worker 管理 API / 统一错误模型的产品面补齐见 [DATA_PLANE_CONTRACT.md](DATA_PLANE_CONTRACT.md)（优先于本文多数项）。  
> **self_hosted 专项缺口**见 [SELF_HOSTED_GAPS.md](SELF_HOSTED_GAPS.md)。

最后更新：2026-07-22

---

## 1. 多副本 Brain 控制面

| 项 | 说明 | 优先级建议 |
|---|---|---|
| 跨副本 interrupt | 非 owner 目前写 `interrupt_requested` / 返 409，需 pub/sub 或 owner 轮询真正 cancel Flux | P1 |
| `requires_action` 与 turn 租约 | HITL 长等待期间仍占 turn 租约与工作线程；宜在进入 `requires_action` 时释放互斥，确认后再抢租约续跑 | P1 |
| CoordinationStore Redis 实现 | 接口可替换；高并发 lease/queue 时提供 Redis（或其它）实现并做压测 | P2 |
| Gateway / catalog 缓存失效 | 进程内 `agentRegistry` / UCA 缓存靠按需重建；补显式失效与观测 | P2 |

---

## 2. Hands / Worker 生产形态

| 项 | 说明 | 优先级建议 |
|---|---|---|
| ~~跨机 Hands（事件驱动）~~ | **已落地（Design 1）**：外化 SchemaOnlyTool + 纯出站 Worker + `user.tool_result` 续跑；不再依赖 Brain 侧 `WorkspaceSandbox` / 共享盘 | ✅ |
| Worker 扩展 SPI | 客户注册自定义工具 / 内网 MCP 包装 → `agent.custom_tool_use`（本期仅内置工具参考执行器） | P1 |
| per-session 隔离启动器 | 类似 `work.poller` + spawn 容器 | P2 |
| Subagent 独立 Hands lease | 子代理目前共用 parent session 的 work / 工具挂起语义 | P2 |
| 真容器 checkpoint / resume | 仅当 worker 使用 Docker/云 sandbox 时需要 | P2 |

---

## 3. Managed Agents 语义加深

| 项 | 说明 | 优先级建议 |
|---|---|---|
| Files 资源真上传 | `resources[]` 的 `file` 类型仍为占位笔记；对接上传 / Files API 落盘 | P1 |
| Vault `mcp_oauth` 自动 refresh | 今日只注入已有 `access_token`；缺 refresh 流程 | P2 |
| packages / limited 网络真执行 | `INSTALL_PACKAGES` / `ALLOWED_HOSTS` 多为 env 提示，镜像侧未必执行 | P2 |
| Memory 挂载别名与 per-attachment access | `read_only` 已有；session 级 per-store access、`/mnt/memory/...` 别名可再对齐 Claude | P3 |
| Permission 细化 | 已有 `deny` / `always_ask`；可补 allowlist、网络策略落地 | P3 |

---

## 4. 产品收敛与体验

| 项 | 说明 | 优先级建议 |
|---|---|---|
| Legacy chat 已下线 | `/api/agents/{id}/chat/*` 与 `/api/agents/{id}/sessions/*` 已随四层拆分删除（前端 legacy 模式同步移除）；`chatui` 仅保留为内置渠道类型 | 已完成 |
| Multiagent 体验 | 已走 managed session + parallel；同步轮询等 idle，缺取消 / SSE 流式聚合 | P2 |
| Session 真 replay UI | Deployments 已有「打开 last session」；缺时间轴 scrub / 重放播放器 | P3 |
| `agentscope.json` 双配置收敛 | 明确仅为 bootstrap 种子；全局版本历史已可追加，产品侧可再收敛 | P3 |
| Session 共享 ACL | Env/Memory/Vault 有 share；Session 多为 owner-only，需文档或补 ACL | P3 |
| 部署级可观测仪表盘 | HandsMetrics / `/api/hands/status` 已有雏形；可聚合到 Deployment 仪表盘 | P3 |

---

## 5. 质量与测试

| 项 | 说明 | 优先级建议 |
|---|---|---|
| 双副本集成测 | turn 租约冲突、cron 防双触发、HITL 跨实例 resolve、孤儿 lease 收口 | P1 |
| Worker 队列集成测 | 关 in-process 后 poll/ack/pending-tools/tool-results 端到端 | P2 |
| 故障注入 | 杀持有 turn 的 pod → TTL 内可续聊 | P2 |

---

## 建议下一迭代（若开工）

1. 跨副本 interrupt + `requires_action` 释放 turn 租约  
2. Worker 扩展 SPI（自定义工具 / 内网 MCP）  
3. Files 资源上传  
4. Legacy 下线时间表 + 双副本集成测  

其余按产品优先级拣选即可。
