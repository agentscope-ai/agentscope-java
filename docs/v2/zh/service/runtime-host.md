---
title: 连接 Runtime Host
---

[English](/v2/en/service/runtime-host)

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

连接成功后，在控制台检查 Host 在线、provider 可用，再绑定 Hosted Agent 并派发一项小任务。失败时同时查看 Task Attempt 和 Host 日志。

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
