# AgentScope CLI and Runtime Host

Each archive contains `as` and `agentscope-runtime-host` for one platform:
Linux or macOS, amd64 (x86_64) or arm64 (aarch64 / Apple Silicon).
Download the matching archive and `SHA256SUMS` from the same GitHub Release.
Check the archive's SHA-256 before extracting it.

Install both executables together in a directory on your PATH:

```bash
mkdir -p "$HOME/.local/bin"
install -m 755 as agentscope-runtime-host "$HOME/.local/bin/"
export PATH="$HOME/.local/bin:$PATH"
as version
agentscope-runtime-host -help
```

Add the PATH export to your shell configuration to retain it in new terminals.
Install and authenticate at least one supported coding agent (Codex, Claude Code,
Qoder, QwenPaw or OpenClaw), then connect to an existing Service:

```bash
as connect https://YOUR-SERVICE
as runtime status
as runtime stop
```

`as connect` prompts for credentials and starts the bundled Runtime Host.
Use `as connect --help` for foreground execution and provider selection.
Service deployment uses the Compose or Kubernetes package; `as install` is
currently a placeholder and does not deploy the Service.

## 中文

根据系统和 CPU 架构下载对应的压缩包，使用同一 Release 的 `SHA256SUMS`
核对校验和后解压。按上面的命令将两个可执行文件安装到同一个 PATH 目录。
将 PATH 设置写入 shell 配置文件，以便新终端使用。

先安装并登录至少一个支持的 Coding Agent，再用 `as connect` 连接已有 Service。
该命令提示输入凭据并启动 Runtime Host；用 `as runtime status` 查看状态，
用 `as runtime stop` 停止。部署 Service 使用 Compose 或 Kubernetes 安装包；
当前 `as install` 为占位命令，不会实际部署 Service。
