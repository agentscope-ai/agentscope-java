# 连接 Runtime Host

Runtime Host 运行在安装 Coding Agent 的电脑或服务器上。控制面负责派发和记录工作，Host 使用本地 provider 执行。

## 安装

从同一 Service Release 下载对应操作系统和 CPU 架构的 `agentscope-cli-VERSION-OS-ARCH.tar.gz`，核对校验和后解压。包中包含 `agentscope`、兼容名称 `aistioctl` 和 `aistio-runtime-host`。将三个可执行文件放在同一个 PATH 目录中。

包提供 Linux/macOS 的 amd64、arm64 目标；以该版本实际发布和验证的平台清单为准。provider 本身需要另行安装、登录并确认可运行。

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

Runtime Host 不是托管 Agent 的 Hands Worker；有关执行环境见 [Workspace 与 Environment](workspaces.md)。
