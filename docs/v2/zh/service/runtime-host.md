---
title: 连接 Runtime Host
en_link: /v2/en/service/runtime-host
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Runtime Host 运行在安装 Coding Agent 的电脑或服务器上。控制面负责派发和记录工作，Host 使用本地 provider 执行。

## 安装

从同一 Service Release 下载对应操作系统和 CPU 架构的 `agentscope-cli-VERSION-OS-ARCH.tar.gz`，核对校验和后解压。包中包含 `agentscope`、别名 `aistioctl` 和 `aistio-runtime-host`。将三个可执行文件放在同一个 PATH 目录中。

按目标机器和发布清单选择 Linux/macOS 的 amd64 或 arm64 包。provider 本身需要另行安装、登录并确认可运行。

## 解压到 PATH

`uname -sm` 可查看平台；包名使用 `linux`/`darwin` 与 `amd64`/`arm64`。从 [Release](https://github.com/agentscope-ai/agentscope-java/releases) 下载匹配包，将下面文件名替换为实际值：

```bash
mkdir -p agentscope-cli "$HOME/.local/bin"
tar -xzf agentscope-cli-VERSION-OS-ARCH.tar.gz -C agentscope-cli
install -m 0755 agentscope-cli/agentscope agentscope-cli/aistioctl agentscope-cli/aistio-runtime-host "$HOME/.local/bin/"
export PATH="$HOME/.local/bin:$PATH"
```

将 PATH 配置写入你使用的 shell 配置以便后续终端使用。

## 连接

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

按 CLI 提示完成登录或 enrollment。连接会保存本机配置并启动守护进程；用户正常使用无需反复传递共享内部令牌。

## 日常操作

```bash
agentscope runtime logs
agentscope runtime stop
agentscope runtime start
```

配置与状态默认在 `~/.agentscope/runtime-host/`。保留 Host 身份和状态文件，避免把已有主机错误地注册为新实例。不要把该目录当成公开的配置样例。

连接成功后，通过管理 API 检查 Host 在线、provider 可用，再绑定 Hosted Agent 并派发一项小任务。失败时同时查看 Task Attempt 和 Host 日志。

Runtime Host 不是托管 Agent 的 Hands Worker；有关执行环境见 [执行环境](/v2/zh/service/environments)。

## 无交互服务器连接

由有权限的操作者生成短期 enrollment token，再交给待连接主机使用：

```bash
agentscope runtime enrollment-token create
```

在目标主机将该值放入 `AGENTSCOPE_ENROLLMENT_TOKEN` 环境变量，再执行 connect。服务交换出绑定 Host 身份和范围的凭据。不要把 enrollment token 或 `config.json` 放进共享脚本。

## CLI 参考

| 命令 | 用途 |
| --- | --- |
| `agentscope connect URL` | 登录/注册本机，保存配置并启动 daemon |
| `agentscope runtime status` | 查看运行状态 |
| `agentscope runtime probe` | 检查 provider 可用性 |
| `agentscope runtime logs -f` | 跟踪日志 |
| `agentscope runtime restart` | 重启 daemon |
| `agentscope runtime stop` / `start` | 停止或启动 |
| `agentscope connect --help` | 查看该版本提供的高级参数 |

配置目录中的 `config.json` 含连接身份，`state/host.id` 保存稳定 Host ID，`daemon.log` 用于排障，`workspaces/` 保存任务工作目录。升级 CLI 前检查活跃任务，再替换配套二进制并重新启动，保留这些持久状态。

## 主机接入与管理 API

CLI 使用同一套 Host API。平台账户凭据用于授权接入和管理主机；enrollment token 用于首次交换；`runtimeToken` 只供对应 Host 的运行协议使用。

| 方法与路径 | 请求字段/查询参数 | 响应 |
| --- | --- | --- |
| `POST /api/v1/runtime-host-enrollment-tokens` | 平台 Bearer；JSON `tenant`、`namespace` | 201；`enrollmentToken`、scope、`expiresAt` |
| `POST /api/v1/runtime-host-enrollments/exchange` | enrollment Bearer；JSON `hostKey`，最长 200 字符 | 201；`runtimeToken`、`hostKey`、scope、`expiresAt`；范围来自 token |
| `POST /api/v1/runtime-host-enrollments` | 平台 Bearer；JSON `hostKey`、`tenant`、`namespace` | 直接签发对应 Host 的 `runtimeToken` |
| `GET /api/v1/runtime-hosts` | 平台 Bearer；查询 `tenant`、`namespace`，可选 `poolName`、`state` | `items`；包含 `id`、`hostKey`、`state`、`capacity`、`active`、`lastSeenAt`、`capabilities` |
| `GET /api/v1/runtime-hosts/{hostId}` | 平台 Bearer；Host UUID | `host` |
| `PATCH /api/v1/runtime-hosts/{hostId}/capacity` | 平台 Bearer；`capacity` 为 1–50，`expectedCapacity` 为当前值 | 更新后的 `host`；并发修改冲突时重新读取 |
| `POST /api/v1/runtime-hosts/{hostId}/drain` | 平台 Bearer | `host`；停止领取新工作 |
| `POST /api/v1/runtime-hosts/{hostId}/resume` | 平台 Bearer | `host`；恢复可调度状态 |

单 scope 部署使用服务配置的固定 scope；多 scope 部署创建 enrollment token 时必须指定 `tenant`、`namespace`。交换请求不能更改 token 中的范围。drain 用于维护前停止接收新任务，取消正在执行的任务仍使用 [AgentTask API](/v2/zh/service/issues)。

例如，在已经配置管理访问的终端生成接入凭据：

```bash
curl -sS "$SERVICE_URL/api/v1/runtime-host-enrollment-tokens" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"default","namespace":"default"}'
```

把响应的 `enrollmentToken` 安全地传到目标主机，再运行 `agentscope connect`，无需业务应用手动调用 Host register、heartbeat 或 claim。

### Runtime Host 协议接口

下面的接口由 daemon 使用 Host 范围的 Bearer 凭据调用。需要实现自定义 Host 时才直接对接它们；业务任务分派应调用 Issue 或 Endpoint API。

| POST 路径 | 关键请求字段 | 行为 |
| --- | --- | --- |
| `/api/v1/runtime-hosts/register` | `hostKey`、`poolName`、scope、`capacity`，可选 `daemonVersion`、`os`、`arch`、`labels`、`capabilities` | 返回 `host` 与 `heartbeatIntervalSeconds`，保存 `host.id` 与 `host.leaseGeneration` |
| `/api/v1/runtime-hosts/{hostId}/heartbeat` | `generation`、`active`、可选 `capabilities` | 更新存活与能力，返回 `host` |
| `/api/v1/runtime-hosts/{hostId}/state` | `generation`、`state` | 更新状态，返回 `host` |
| `/api/v1/runtime-hosts/{hostId}/execution-attempts/claim` | `tenant`、`namespace`、`runtimePoolName`、`generation`、`leaseOwner`、`leaseToken`，可选 `leaseSeconds` | 无任务返回 204；成功返回 `task`、`context`、`attempt`、`taskToken`、`attemptToken`、`runtimeProfile`、`executionOverrides`、`definition` |

领取后，以下相对路径均位于 `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}` 下。除 Host Bearer 外，必须在 `X-Execution-Attempt-Token` Header 传入 claim 返回的 `attemptToken`。请求 JSON 至少携带本次 `leaseToken`、`fencingToken`，daemon 同时传入 `generation`、`leaseOwner`；不能使用另一次执行的值。

| POST 相对路径 | 额外字段 | 用途 |
| --- | --- | --- |
| `/renew` | 可选 `leaseSeconds`，默认 30 秒 | 续租，读取服务端取消/终态 |
| `/preparing` | 无 | 报告正在准备 |
| `/running` | 可选 `providerSessionId`、`workspaceKey` | 报告已开始 provider 执行 |
| `/checkpoint` | `checkpoint`、可选 `providerSessionId` | 记录 provider 恢复信息 |
| `/events` | 正整数 `ordinal`、`provider`、`eventType`、可选 `providerSessionId`、`raw` | 提交执行事件；`raw` 最大 256 KiB |
| `/complete` | 可选 `result`、`checkpoint` | 报告完成 |
| `/fail` | `failureCode`、`failureMessage`、可选 `checkpoint` | 报告失败 |
| `/cancelled` | 无 | 确认执行已取消 |

普通状态方法返回 `attempt`；事件接收响应应按 `accepted` 判断，Attempt 已封存时停止继续提交。Host checkpoint 是 provider 恢复材料，不是统一 Agent API 已支持的 checkpoint 恢复入口。能力区别见[Hosted 恢复](/v2/zh/service/hosted-agent-execution)。
