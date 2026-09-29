# DeepSeek Harness → AgentScope Service

[English](README.md) | 中文

把已经在跑的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 注册到 AgentScope Service 控制面。DSH 继续用自己的 loop 和 Web UI；本插件只是挂在现有 profile 上的一层 Cordis 扩展。

## 快速开始

源码检出后**没有**全局 `dsh` 命令。官方启动方式是在 `deepseek-harness` **仓库根目录**执行 `pnpm dsh web`（见 DSH README 的 [Run from source](https://github.com/deepseek-ai/deepseek-harness#run-from-source)）。下面默认你就是这种用法；`pnpm dsh` 只是把参数转给仓库里的 CLI，等价于已安装环境下的 `dsh`。

下面把两个路径写成变量，整段命令都可以直接替换：

```bash
DSH_ROOT=/path/to/deepseek-harness
PLUGIN=/path/to/agentscope-java/agentscope-service/aistio/sdk/dsh
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
| aistiod（插件注册目标） | http://localhost:8081 |

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

`--dump-config` 的输出里应出现 `# == @agentscope/dsh-aistio` 或 `id: aistio`。

### 3. 带控制面凭证重启 DSH

停掉原来的 `pnpm dsh web`，仍在 **DSH 仓库根目录**导出凭证后启动：

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export AISTIO_CONTROL_HTTP=http://localhost:8081
export AISTIO_CONTROL_GRPC=localhost:15010
export AISTIO_AGENT_NAME=deepseek-harness
# 可选：与 aistiod 的 AISTIO_TRANSCRIPT_FS_ROOT 指到同一目录，DSH 退出后 Operate 仍能读历史
# export AISTIO_TRANSCRIPT_DIR=/tmp/aistio-transcripts

pnpm dsh web
```

启动日志里应有类似：

```text
[aistio] instrumented DeepSeek Harness as 'deepseek-harness' (contract :18091, control http://localhost:8081, agent-task=true)
[aistio] registered <instanceId> at http://127.0.0.1:18091 with http://localhost:8081
```

本机可先探活契约端口：

```bash
curl -s http://127.0.0.1:18091/agentscope/health
# {"status":"ok"}
```

没有 `BUILDER_INTERNAL_TOKEN` 时，插件仍会起 `/agentscope/*`，但**不会**向 aistiod 注册，Dashboard 里也看不到实例。

### 4. 在 Dashboard 验收

1. 打开 http://localhost:18080 ，用 `admin` / `admin` 登录。
2. **Dashboard**：出现名为 `deepseek-harness` 的健康实例。
3. 回到 DSH Web UI（http://127.0.0.1:3080）发一轮对话；Dashboard Sessions 应在 `idle` → `active` → `idle` 之间切换，并有 token 增量。
4. 需要协作时，创建 Issue，并为该 Agent 或包含它的 Team 启动或分配 Orchestration Run。DSH 收到带 fencing 的 `ExecutionAttemptCommand` 后使用 task token 拉取 discussion，并写 progress/result Comment 与 Artifact。

卸载插件（DSH 仓库根目录）：

```bash
cd "$DSH_ROOT"
pnpm dsh plugin --profile web remove @agentscope/dsh-aistio
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
5. 可选 ASDP gRPC 客户端（`AISTIO_CONTROL_GRPC`，默认 `localhost:15010`）接收 `ExecutionAttemptCommand` / `SessionCommand`，并上报带 fencing 的 `ExecutionAttemptReport` 状态。连不上只打 warn，不退出 DSH loop。HTTP 自注册保留，prober 仍靠 `baseUrl`。
6. 可选在 `AISTIO_TRANSCRIPT_DIR` 按 `{tenant}/{agent}/{session}/events/...jsonl` 增量写 transcript，供进程退出后 Operate 读取。

压缩：agent `running` 时 `POST .../compress` 返回 **409** `{ hint: wait_idle }`。没有 `ctx.compaction.compactNow` 时（非 Web profile 常见）返回 **501**。Web profile 才有 compaction。

## 配置

插件 `config` 与环境变量合并，非空的插件字段优先。快速开始里用环境变量即可，不必改 YAML。

| 字段 | 环境变量 | 默认 |
| --- | --- | --- |
| `controlHttp` | `AISTIO_CONTROL_HTTP` / `BUILDER_CONTROL_URL` | `http://localhost:8081` |
| `internalToken` | `BUILDER_INTERNAL_TOKEN` / `AISTIO_INTERNAL_TOKEN` | 空（仍提供契约，但不注册） |
| `agentName` | `AISTIO_AGENT_NAME` | `deepseek-harness` |
| `namespace` | `AISTIO_NAMESPACE` | `default` |
| `instanceId` | `AISTIO_INSTANCE_ID` / `HOSTNAME` | 本机主机名 |
| `contractHost` | `AISTIO_CONTRACT_HOST` | `127.0.0.1` |
| `contractPort` | `AISTIO_CONTRACT_PORT` | `18091` |
| `publicBaseUrl` | `AISTIO_PUBLIC_BASE_URL` | `http://127.0.0.1:<实际端口>` |
| `startHttpRegister` | `AISTIO_HTTP_REGISTER` | `true` |
| `controlGrpc` | `AISTIO_CONTROL_GRPC` | `localhost:15010`（aistiod ASDP） |
| `startGrpc` | `AISTIO_GRPC` | 有 token 时为 `true` |
| `enableTeamCoordination` | `AISTIO_TEAM_COORDINATION` | 有 token 时为 `true` |
| `transcriptDir` | `AISTIO_TRANSCRIPT_DIR` | 空（不写 JSONL） |

`aistiod` 会探测 `publicBaseUrl`（或推导出的 localhost URL）上的 `/agentscope/info`。DSH 与 aistiod 不在同一台机器时，必须把 `publicBaseUrl` 设成控制面能访问的地址。

本地把 `AISTIO_TRANSCRIPT_DIR` 与 aistiod 的 `AISTIO_TRANSCRIPT_FS_ROOT` 指到同一路径（或 docker volume）。没有共享盘时仍回退活实例 `message-query`。

## 不改 profile：`--patch` overlay

不想执行 `plugin add` 时，可以按 DSH 官方插件教程用 `--patch`。Loader 从 **profile 目录**解析裸包名，所以 overlay 里不要写 `@agentscope/dsh-aistio`，必须写本包构建产物的**绝对路径**。

先 `npm run build`，再写例如 `$DSH_ROOT/aistio.patch.yml`：

```yaml
- insert:
    - id: aistio
      name: '/path/to/agentscope-java/agentscope-service/aistio/sdk/dsh/dist/index.js'
      config:
        controlHttp: http://localhost:8081
        contractPort: 18091
        agentName: deepseek-harness
        namespace: default
```

```bash
cd "$DSH_ROOT"
export BUILDER_INTERNAL_TOKEN=local-dev-internal-token-at-least-32chars
export AISTIO_CONTROL_HTTP=http://localhost:8081
pnpm dsh web --patch "$DSH_ROOT/aistio.patch.yml"
```

## 开发

```bash
cd "$PLUGIN"
npm install
npm test
npm run build
```
