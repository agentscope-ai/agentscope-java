# DeepSeek Harness → AgentScope Service

[English](README.md) | 中文

把已经在跑的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 注册到 AgentScope Service 控制面。DSH 继续用自己的 loop 和 Web UI；本插件只是挂在现有 profile 上的一层 Cordis 扩展。

发布包名为 `@agentscope-service/dsh-controlplane`。使用 `npm install @agentscope-service/dsh-controlplane@2.1.0-BETA1` 安装指定版本，或用 `@agentscope-service/dsh-controlplane@next` 获取预发布版本。Cordis bundle 使用同一个包名。

## 快速开始

源码检出后**没有**全局 `dsh` 命令。官方启动方式是在 `deepseek-harness` **仓库根目录**执行 `pnpm dsh web`（见 DSH README 的 [Run from source](https://github.com/deepseek-ai/deepseek-harness#run-from-source)）。下面默认你就是这种用法；`pnpm dsh` 只是把参数转给仓库里的 CLI，等价于已安装环境下的 `dsh`。

下面把两个路径写成变量，整段命令都可以直接替换：

```bash
DSH_ROOT=/path/to/deepseek-harness
PLUGIN=/path/to/agentscope-java/agentscope-service/service-controlplane/sdk/dsh
```

### 0. 确认源码 DSH 能起来

首次从源码跑时，在 DSH 仓库根目录：

```bash
cd "$DSH_ROOT"
pnpm install
pnpm run build
pnpm dsh web
```

默认 Web UI：http://127.0.0.1:3080 。能正常对话再往下走。`pnpm dsh web` 是 `--profile web` 的别名；若你日常用别的 profile，把后面的 `web` / `--profile web` 换成那个名字。

`pnpm install` / `pnpm run build` 若在 leftover 上报 `refusing to replace user-owned core.hooksPath`：依赖其实已经装完，失败的只是 DSH 给贡献者装 Git 钩子，它拒绝覆盖你全局的 `core.hooksPath`（例如公司 AK 扫描钩子）。pnpm 11 在执行任何 `pnpm run …` 前都会再跑一遍 install，所以 leftover 失败会把 `build` 一起拦住。不要改全局 `~/.gitconfig`，在当前仓库先让 leftover 过关，再构建：

```bash
DSH_LEFTHOOK_ALLOW_HOOKS_PATH_OVERRIDE=1 pnpm install
pnpm run build
pnpm dsh web
```

这只会把当前 worktree 的钩子改成 DSH leftover；其它仓库仍走全局钩子。只想跳过 leftover、不改本仓库钩子时，用 `CI=true pnpm run build`。

### 1. 启动 AgentScope Service 控制面

另开一个终端，从 AgentScope Java 仓库启动本地栈（默认账号 `admin` / `admin`）：

```bash
cd /path/to/agentscope-java/agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

| 入口 | 地址 |
| --- | --- |
| Console / Dashboard | http://localhost:18080 |
| service-controlplane（插件注册目标） | http://localhost:8081 |

`scripts/dev-up.sh` 默认内部 token 是 `local-dev-internal-token-at-least-32chars`。下一步 DSH 必须用**同一把** token，控制面才会接受注册。

### 2. 构建插件，再从 DSH 仓库装进 web profile

不改 DSH 源码。先在本包装出 `dist/`，再回到 **DSH 仓库根目录**执行 `pnpm dsh plugin add`（相对路径相对的是你敲命令时的当前目录，不是 profile 目录）。

```bash
cd "$PLUGIN"
npm install
npm run build

cd "$DSH_ROOT"
pnpm dsh plugin --profile web add "$PLUGIN"
pnpm dsh web --dump-config
```

`--dump-config` 的输出里应出现 `# == @agentscope-service/dsh-controlplane` 或 `id: controlplane`。

### 3. 带控制面凭证重启 DSH

停掉原来的 `pnpm dsh web`，仍在 **DSH 仓库根目录**导出凭证后启动：

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export CONTROL_PLANE_HTTP=http://localhost:8081
export CONTROL_PLANE_GRPC=localhost:15010
export CONTROL_PLANE_AGENT_NAME=deepseek-harness
# 可选：与 service-controlplane 的 CONTROL_PLANE_TRANSCRIPT_FS_ROOT 指到同一目录，DSH 退出后 Operate 仍能读历史
# export CONTROL_PLANE_TRANSCRIPT_DIR=/tmp/controlplane-transcripts

pnpm dsh web
```

启动日志里应有类似：

```text
[controlplane] instrumented DeepSeek Harness as 'deepseek-harness' (contract :18091, control http://localhost:8081, agent-task=true)
[controlplane] registered <instanceId> at http://127.0.0.1:18091 with http://localhost:8081
```

本机可先探活契约端口：

```bash
curl -s http://127.0.0.1:18091/agentscope/health
# {"status":"ok"}
```

没有 `BUILDER_INTERNAL_TOKEN` 时，插件仍会起 `/agentscope/*`，但**不会**向 service-controlplane 注册，Dashboard 里也看不到实例。

### 4. 在 Dashboard 验收

1. 打开 http://localhost:18080 ，用 `admin` / `admin` 登录。
2. **Dashboard**：出现名为 `deepseek-harness` 的健康实例。
3. 回到 DSH Web UI（http://127.0.0.1:3080）发一轮对话；Dashboard Sessions 应在 `idle` → `active` → `idle` 之间切换，并有 token 增量。
4. 需要协作时，创建 Issue，并为该 Agent 或包含它的 Team 启动或分配 Orchestration Run。DSH 收到带 fencing 的 `ExecutionAttemptCommand` 后使用 task token 拉取 discussion，并写 progress/result Comment 与 Artifact。

卸载插件（DSH 仓库根目录）：

```bash
cd "$DSH_ROOT"
pnpm dsh plugin --profile web remove @agentscope-service/dsh-controlplane
```

### 用 npx 而不是源码时

官方 npm 入口是 `npx @deepseek-ai/dsh web`，同样没有全局 `dsh`，除非你自己 `npm i -g @deepseek-ai/dsh`。把上面所有 `pnpm dsh` 换成 `npx @deepseek-ai/dsh`，并且不必 `cd` 进 DSH 仓库，例如：

```bash
npx @deepseek-ai/dsh plugin --profile web add "$PLUGIN"
npx @deepseek-ai/dsh web --dump-config
npx @deepseek-ai/dsh web
```

## 能力

1. 提供 `/agentscope/health`、`/info`、`/sessions`（超过 500 条带 `truncated`/`hasMore`）、context、messages、tasks、plan-mode、subagent-tasks、export-transcript、abort/terminate/compress，以及入站 `POST .../messages`。
2. `POST /api/v1/dataplanes/register` 与周期性心跳（`X-Builder-Internal-Token`）。契约写操作校验同一把 token；task 动作额外使用 `X-Agent-Task-Token`。
3. 把 DSH 的 `session/event`、`agent/status` 映射为契约快照（`active` / `idle` / `compressing`），并填 `systemPrompt`。
4. 有 token 时声明 `agent-task`，并在隔离执行上下文中提供 Issue/Comment/AgentTask/Artifact 工具。
5. 可选 ASDP gRPC 客户端（`CONTROL_PLANE_GRPC`，默认 `localhost:15010`）接收 `ExecutionAttemptCommand` / `SessionCommand`，并上报带 fencing 的 `ExecutionAttemptReport` 状态。连不上只打 warn，不退出 DSH loop。HTTP 自注册保留，prober 仍靠 `baseUrl`。
6. 可选在 `CONTROL_PLANE_TRANSCRIPT_DIR` 按 `{tenant}/{agent}/{session}/events/...jsonl` 增量写 transcript，供进程退出后 Operate 读取。

压缩：agent `running` 时 `POST .../compress` 返回 **409** `{ hint: wait_idle }`。没有 `ctx.compaction.compactNow` 时（非 Web profile 常见）返回 **501**。Web profile 才有 compaction。

## 配置

插件 `config` 与环境变量合并，非空的插件字段优先。快速开始里用环境变量即可，不必改 YAML。

| 字段 | 环境变量 | 默认 |
| --- | --- | --- |
| `controlHttp` | `CONTROL_PLANE_HTTP` / `BUILDER_CONTROL_URL` | `http://localhost:8081` |
| `internalToken` | `BUILDER_INTERNAL_TOKEN` / `CONTROL_PLANE_INTERNAL_TOKEN` | 空（仍提供契约，但不注册） |
| `agentName` | `CONTROL_PLANE_AGENT_NAME` | `deepseek-harness` |
| `namespace` | `CONTROL_PLANE_NAMESPACE` | `default` |
| `instanceId` | `CONTROL_PLANE_INSTANCE_ID` / `HOSTNAME` | 本机主机名 |
| `contractHost` | `CONTROL_PLANE_CONTRACT_HOST` | `127.0.0.1` |
| `contractPort` | `CONTROL_PLANE_CONTRACT_PORT` | `18091` |
| `publicBaseUrl` | `CONTROL_PLANE_PUBLIC_BASE_URL` | `http://127.0.0.1:<实际端口>` |
| `startHttpRegister` | `CONTROL_PLANE_HTTP_REGISTER` | `true` |
| `controlGrpc` | `CONTROL_PLANE_GRPC` | `localhost:15010`（service-controlplane ASDP） |
| `startGrpc` | `CONTROL_PLANE_ENABLE_GRPC` | 有 token 时为 `true` |
| `enableTeamCoordination` | `CONTROL_PLANE_TEAM_COORDINATION` | 有 token 时为 `true` |
| `transcriptDir` | `CONTROL_PLANE_TRANSCRIPT_DIR` | 空（不写 JSONL） |

`service-controlplane` 会探测 `publicBaseUrl`（或推导出的 localhost URL）上的 `/agentscope/info`。DSH 与 service-controlplane 不在同一台机器时，必须把 `publicBaseUrl` 设成控制面能访问的地址。

本地把 `CONTROL_PLANE_TRANSCRIPT_DIR` 与 service-controlplane 的 `CONTROL_PLANE_TRANSCRIPT_FS_ROOT` 指到同一路径（或 docker volume）。没有共享盘时仍回退活实例 `message-query`。

## 不改 profile：`--patch` overlay

不想执行 `plugin add` 时，可以按 DSH 官方插件教程用 `--patch`。Loader 从 **profile 目录**解析裸包名，所以 overlay 里不要写 `@agentscope-service/dsh-controlplane`，必须写本包构建产物的**绝对路径**。

先 `npm run build`，再写例如 `$DSH_ROOT/controlplane.patch.yml`：

```yaml
- insert:
    - id: controlplane
      name: '/path/to/agentscope-java/agentscope-service/service-controlplane/sdk/dsh/dist/index.js'
      config:
        controlHttp: http://localhost:8081
        contractPort: 18091
        agentName: deepseek-harness
        namespace: default
```

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export CONTROL_PLANE_HTTP=http://localhost:8081
pnpm dsh web --patch "$DSH_ROOT/controlplane.patch.yml"
```

## 开发

```bash
cd "$PLUGIN"
npm install
npm test
npm run build
```

## 公开 Session Portal 客户端

Managed 执行使用 `new ServiceClient(baseUrl, credentials).managedSessions()`，对应原生
`/v1/sessions` 协议：

```ts
const managed = new ServiceClient(baseUrl, { apiKey, tenant, namespace }).managedSessions()
const session = await managed.createSession({ agent: agentId, environment_id: environmentId }, 'create-001')
const turn = await managed.submit(String(session.id), { message: '检查项目' }, 'turn-001')
const receipt = await managed.input(String(session.id), String(turn.id), 'steer', { message: '优先检查测试' }, 'steer-001')
const applied = await managed.inputReceipt(String(session.id), String(turn.id), receipt.id)
const snapshot = await managed.snapshot(String(session.id))
for await (const event of managed.stream(String(session.id), snapshot.as_of)) {
    // 应用并保存 event.cursor；只观察某轮时按 data.turn_id 筛选。
}
```

原生生命周期操作须保留幂等键，通过 `lifecycleReceipt` 查询清理完成，不能把受理当作
完成。文件、显式 Artifact、预算与固定资源页已接入；Turn 快照在 Session resources
路径翻页并保留 snapshot ID。usage 保留 data / as_of；Fork / 检查点恢复仍在实施。
轮内操作使用 typed Input，不自动回退旧路径。

以下平铺客户端继续用于其他运行类型的现有协议。

`ServiceClient` 使用 Application API key 或原始平台用户 token，与 DSH 注册使用的内部/
bootstrap 身份分开。`OrchestrationClient` 管理定义与策略，执行的创建和控制使用 Portal
Session 和 Turn。

```ts
import { ServiceClient } from '@agentscope-service/dsh-controlplane'
const api = new ServiceClient(baseUrl, { apiKey, tenant, namespace })
const session = await api.createSession({ type: 'workflow', id: workflowId }, 'create-001')
const turn = await api.submit(String(session.id), { input: { request: 'Review' } }, 'turn-001')
const snapshot = await api.snapshot(String(session.id), String(turn.id))
for await (const event of api.stream(String(session.id), snapshot.as_of, String(turn.id))) {
    // 应用事件后保存 event.cursor，超时后从该游标续传。
}
```

资源分页保持 `snapshot_id` 与 `as_of`，使用 `resources(..., { after, snapshotId, turn,
limit })` 续读。动作统一使用 `{ answers: [...] }`；`commandStatus` 查询 Session 命令并
检查 Turn 归属。`queued` 与 `runtime_accepted` 为准入状态，`command_status`/
`input_status` 才是原生应用状态。`usage` 返回权威汇总，`artifactContent` 只请求授权
`/content` 路径，不抓取调用者 URI。客户端限制 JSON 16 MiB、单 SSE 事件 1 MiB、内容
100 MiB，默认请求总时限 30 秒；观察断开不停止任务。使用配套 SDK/Service 版本及
capabilities，客户端不假设审批、恢复或文件输入能力。

Managed 文件 API 提供 `uploadFile`、`files`、`fileMetadata` 和 `fileContent`。
Turn 文件输入前读取 `capabilities(session).capabilities.file_input`。上传及 JSON 响应
限制为 16 MiB，缓冲下载为 100 MiB。公开调用拒绝重定向，原始凭据不会被转发到新地址。

`api.exportEvents(sessionId, { through: snapshot.as_of, limit: 1000 })` 逐窗口异步
迭代固定水位的公开 JSONL。保存已应用游标，重启时作为 after 传入并保留原 through。
不完整记录和数量不符会报错；提前结束循环会关闭响应。单事件上限 1 MiB，
每个窗口使用配置的请求总时限。

Managed Session 预算使用 `budget(session)` 与 `setBudget(session, limits, revision)`。可传当前版本防止覆盖并发修改（412）。设置返回 202，在下一次模型调用前应用；GET 分别返回设置版本与数据面已应用版本。

`trace(session, after, through, limit)` 使用人类特权 trace 身份读取原生日志。服务端须显式启用；续读沿用 `as_of_seq` 水位和 `next_seq`。Application API key 不可调用。

`checkpoints(session, after, through, limit)` 经 Portal 查询 checkpoint 元数据。续页保持同一 `as_of` 作为 `through`，在 `has_more` 为真时推进 `next_cursor`，包括空页。原始 Agent 状态不公开；`restoreCheckpoint`/`fork` 写接口返回持久化 202 命令，使用 `command` 查询到 completed/rejected；相关根 Session 要求 interact 权限且执行和输入均已结清。
