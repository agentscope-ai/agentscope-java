# Hands / Worker · self_hosted

[← Events](07-events.md) · [回目录](README.md) · [下一页：Memory & Vault →](09-memory-vault.md)

---

当 Environment `type=self_hosted` 时，Brain **不在本地执行** shell / 文件系统工具，而是把它们注册为外化 schema（`SchemaOnlyTool`），turn 在 `TOOL_SUSPENDED` 时挂起并持久化 `agent.tool_use`。**纯出站 Worker** 认领 work、下载 skills、执行工具、回传 `user.tool_result` 续跑。

对齐 Claude Managed Agents self-hosted sandboxes：Worker 仅出站 HTTPS（NAT/私网友好），Brain 从不回连 Worker。

契约细节：[events/worker.md](../events/worker.md)。

## 产品边界

| 模式 | 含义 |
|---|---|
| `sandbox`（E2B） | **平台托管**云沙箱；Brain 经 `E2bFilesystemSpec` 调 E2B，不是 self_hosted |
| `self_hosted` + `HandsWorkerMain` | 客户基础设施上的纯出站执行器（开发与生产同一形态） |

进程内 Worker（`InProcessEnvironmentWorker`）已随四层拆分移除；Worker 入口随 **service-scheduler** jar 发布，调度层是唯一 Hands 执行面。

## 事件驱动数据流

```text
Client → user.message
Brain  → model tool_use → SchemaOnlyTool suspend → agent.tool_use (pending)
       → session status = requires_action；enqueue work item
Worker → poll / ack
       → GET …/skills → stage /workspace/skills
       → stage session metadata files → /workspace/inputs
       → GET …/pending-tools → local bash/FS exec
       → POST …/tool-results (user.tool_result) → Brain resume turn
```

## Work 状态机

```text
queued → starting → active → stopping → stopped
              ↑_______________|  (心跳过期可 reclaim → queued)
```

## 路由

### Work 队列（`/api/environments/{id}/work…`）

| Method | Path | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `…/work/poll` | Env key | 长轮询认领；响应含 `metadata`（自 session `resources[]`）；空则 204 |
| `POST` | `…/work/{workId}/ack` | Env key | → `active`（**不再**在 Brain 创建 WorkspaceSandbox） |
| `POST` | `…/work/{workId}/heartbeat` | Env key | 保活（建议 ≤15s） |
| `POST` | `…/work/{workId}/stop` | Env key | → `stopped` |
| `GET` | `…/work` / `…/work/{workId}` / `…/work/stats` | 用户 JWT | 运维面 |

### Session 数据面（`/api/environments/{id}/sessions/{sessionId}/…`）

| Method | Path | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `…/pending-tools` | Env key | 待执行 `agent.tool_use`（含 `id`/`name`/`input`） |
| `POST` | `…/tool-results` | Env key | 批量回传结果并触发续跑 |
| `GET` | `…/skills` | Env key | skills manifest + base64 资源，落到 Worker `/workspace/skills` |

亦可经 Session 入站事件 `user.tool_result` / `user.custom_tool_result` 续跑（JWT 客户端）。

已移除：`…/worker/register`、`…/work/claim`、`…/ready`、`…/complete`。

## Environment key

1. 创建 `self_hosted` 环境或调用 `POST /api/environments/{id}/keys/rotate`。
2. 响应中的 `environmentKey` **只出现一次**。
3. Worker：

```http
X-Builder-Environment-Key: ebk_...
```

## 启动 Worker

入口 `io.agentscope.builder.worker.HandsWorkerMain` 随 **service-scheduler** jar 发布（同机开发也跑这一独立进程）：

```bash
java -cp service-scheduler/target/service-scheduler-*.jar \
  io.agentscope.builder.worker.HandsWorkerMain \
  --base-url http://gateway:8080 \
  --environment-id env_xxx \
  --environment-key ebk_xxx \
  --hands-root /var/lib/agentscope/hands \
  --worker-id worker-1
```

`--base-url` 可指网关（`/work/**`、`/tool-results` 会被路由到数据面）或直连数据面。

## 关键约束

self_hosted 必须 `disableFilesystemTools()` + `disableShellTool()`，并把 `execute`/`read_file`/`write_file`/`edit_file`/`grep_files`/`glob_files`/`list_files` 全部外化为 SchemaOnlyTool。若只外化 shell 而保留默认 `FilesystemTool`，文件工具会在 **Brain 磁盘**静默执行，与 Worker 工作区分叉。

## Session 侧

创建 Session 时指定该 `self_hosted` 的 `environmentId`。Turn 开始时 `HandsLeaseService` **仅入队** work（不等待本地 sandbox）。挂起后 status=`requires_action`；Worker 回传结果后续跑。可用 `GET /api/sessions/{id}/hands-stats` 看租约指标。

Session `resources[]` 中 `type=file`（`url`/`path`/`uri`）会在 poll 的 `metadata` 中暴露，Worker stage 到工作目录 `inputs/`。

## 待完善

仍欠缺项（SPI、Files、E2E、reclaim 等）见专用清单：[SELF_HOSTED_GAPS.md](../WIP/SELF_HOSTED_GAPS.md)。
