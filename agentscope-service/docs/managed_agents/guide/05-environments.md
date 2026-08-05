# Environments · 执行面模式

[← Agents](04-agents.md) · [回目录](README.md) · [下一页：Sessions →](06-sessions.md)

---

**Environment** 描述 Session **在哪里执行**：文件系统形态、是否具备 shell/Hands、以及（对 `self_hosted`）Worker 如何接沙箱。

基路径：`/api/environments`。

## 四种类型

| `type` | 定位 | Shell / Hands | 适用场景 |
|---|---|---|---|
| `local` | 宿主机隔离文件系统 | 无独立 Hands 队列 | 本地开发、快速试跑 |
| `sandbox` | **E2B 云沙箱**（平台托管 hands） | E2B 容器内 shell/FS | 需隔离执行、对齐 Claude `cloud` |
| `remote` | 分布式 KV 文件系统 | **无 shell** | 共享产物 / 远程 LTM 路由 |
| `self_hosted` | 客户 Worker 出站执行外化工具 | Worker poll → pending-tools → tool-results | 私有网络 / NAT 后执行工具 |

创建示例：

```bash
curl -s -X POST "$BASE/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"dev-local","type":"local","config":{}}'
```

响应含 `id`。若类型为 `self_hosted`（或任意生成了 key 的环境），响应里的 **`environmentKey` 明文只出现一次**（create / `POST …/keys/rotate`），请立即保存。Worker 用请求头：

```http
X-Builder-Environment-Key: ebk_...
```

详见 [Hands / Worker](08-hands-worker.md)。

## 模式说明

### local

宿主机 `LocalFilesystemSpec`，按隔离范围（默认偏 Session）划分目录。适合单机开发；**不是**生产 Hands 方案。

### sandbox

Managed Environment `type=sandbox` 使用已有扩展 **`agentscope-extensions-sandbox-e2b`**（`E2bFilesystemSpec`）：Brain 向 E2B API 申请云端容器，shell/FS 在 E2B 内执行。**AgentScope Service 不再使用本机 Docker / Daytona。**

创建时需可用的 E2B API key（`config.apiKey`，或全局 `builder.e2b.api-key` / `BUILDER_E2B_API_KEY` / `E2B_API_KEY`）。

```bash
curl -s -X POST "$BASE/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "e2b-dev",
    "type": "sandbox",
    "config": {
      "templateId": "base",
      "isolationScope": "SESSION",
      "sandboxTimeoutSeconds": 300
    }
  }'
```

常用 `config`：`apiKey`、`templateId`、`workspaceRoot`、`sandboxTimeoutSeconds`、`apiBaseUrl`、`domain`、`persistenceMode`（`TAR` / `NATIVE_SNAPSHOT`）、`isolationScope`（默认 `SESSION`）。

依赖与运行时请打进 **自定义 E2B template**；Claude 式 `packages` / `networking` **本迭代不强制执行**（见 [SANDBOX_GAPS.md](../WIP/SANDBOX_GAPS.md)）。

### remote

挂到共享 `BaseStore` 的远程文件系统视图，适合跨副本共享文件类状态；**不提供 shell**。与 `self_hosted` 不同：remote 不跑外部 Worker Hands。

### self_hosted

Brain 只编排模型与工具决策；**内置 shell/FS 工具外化为挂起式 SchemaOnlyTool**，由纯出站
**Environment Worker** 执行并回传 `user.tool_result` 续跑（对齐 Claude self-hosted）。

- Worker：`HandsWorkerMain`（随 service-scheduler jar 发布）+ environment key；Worker 无入站端口、无共享盘要求。
- 开发与生产同一形态——进程内 Worker（`InProcessEnvironmentWorker`）已随四层拆分移除，同机验证也跑这一独立进程。

**不是** `sandbox`（E2B 平台托管）。详见 [Hands / Worker](08-hands-worker.md)。

## API

| Method | Path | 说明 |
|---|---|---|
| `GET` | `/api/environments` | 列表（未归档） |
| `POST` | `/api/environments` | 创建；body：`name`、`type`、`config` |
| `GET` | `/api/environments/{id}` | 详情（不含明文 key） |
| `POST` | `/api/environments/{id}/archive` | 归档 |
| `DELETE` | `/api/environments/{id}` | 硬删（无活跃 Session 引用时） |
| `POST` | `/api/environments/{id}/keys/rotate` | 轮换 environment key |
| `GET/POST/DELETE` | `/api/environments/{id}/shares*` | 分享 ACL |

Worker 工作队列路由在 `/api/environments/{id}/work/*`，见 [08-hands-worker.md](08-hands-worker.md)。

## 与 Session 的关系

创建 Session 时 **必须** 传合法且未归档的 `environmentId`。每个 Session 绑定一个 Environment 模板；具体沙箱实例按隔离策略在 turn 生命周期内申请/释放。

部署与 IM 桥接等内部路径可能调用 `ensureDefaultEnvironment` 自动建 `default-local`；**HTTP 创建 Session 不会省略 environmentId**——Quickstart 需显式创建或选用已有环境。
