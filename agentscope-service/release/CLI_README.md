# AgentScope CLI and Runtime Host

Install Go 1.26+ on the target Linux or macOS machine and install both commands
from the same published Service version:

```bash
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v2.1.0-BETA1
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v2.1.0-BETA1
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
as version
agentscope-runtime-host -help
```

Go builds for the current machine and installs into `GOBIN`, or `$(go env GOPATH)/bin`
when unset. Persist the PATH setting in your shell configuration. These commands
install the `2.1.0-BETA1` prerelease; update both version suffixes together for a new
release. See the [Runtime Host guide](https://java.agentscope.io/v2/en/service/runtime-host).

Install and authenticate a supported Coding Agent provider separately, then
connect to an existing Service:

```bash
as connect https://YOUR-SERVICE
as runtime status
as runtime stop
```

`as connect` prompts for credentials and starts Runtime Host. It does not deploy
the platform. Use [Docker Compose](https://java.agentscope.io/v2/en/service/quickstart)
for quick startup or [Helm](https://java.agentscope.io/v2/en/service/kubernetes)
for a Kubernetes production installation. `as install` is currently a placeholder.

This archive also contains `as` and `agentscope-runtime-host` for its named platform.
If using the archive offline, verify its SHA-256 against the same Release's
`SHA256SUMS`, then put both executables together in a directory on PATH.

## 中文

在目标 Linux 或 macOS 主机安装 Go 1.26+，按上面的 `go install` 命令安装同一版本的
CLI 与 Runtime Host，再配置 PATH。当前命令安装 `2.1.0-BETA1` 预发布版本；
后续版本一起替换两个版本后缀。完整说明见
[Runtime Host 安装与运维](https://java.agentscope.io/v2/zh/service/runtime-host)。

另行安装并登录需要的 Coding Agent provider，然后通过 `as connect` 连接已有 Service。
CLI 会提示输入凭据并启动 Runtime Host；它不部署平台。快速启动使用
[Docker Compose](https://java.agentscope.io/v2/zh/service/quickstart)，
Kubernetes 生产安装使用 [Helm](https://java.agentscope.io/v2/zh/service/kubernetes)。
当前 `as install` 是占位命令。

本压缩包也包含对应平台的两个可执行文件。离线使用时，先通过同一 Release 的
`SHA256SUMS` 核对校验和，再将两个命令一起放入 PATH 目录。
