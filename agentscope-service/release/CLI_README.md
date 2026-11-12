# Managed Agents CLI and Runtime Host

Install Go 1.26+ on the target Linux or macOS machine and install both commands
from the same published Service version:

```bash
go install gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/cmd/mactl@v2.1.0-BETA1
go install gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/cmd/managed-agents-runtime-host@v2.1.0-BETA1
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
mactl version
managed-agents-runtime-host -help
```

Go builds for the current machine and installs into `GOBIN`, or `$(go env GOPATH)/bin`
when unset. Persist the PATH setting in your shell configuration. These commands
install the `2.1.0-BETA1` prerelease; update both version suffixes together for a new
release. See the [Runtime Host guide](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/en/service/runtime-host.md).

Install and authenticate a supported Coding Agent provider separately, then
connect to an existing Service:

```bash
mactl connect https://YOUR-SERVICE
mactl runtime status
mactl runtime stop
```

`mactl connect` prompts for credentials and starts Runtime Host. It does not deploy
the platform. Use [Docker Compose](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/en/service/quickstart.md)
for quick startup or [Helm](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/en/service/kubernetes.md)
for a Kubernetes production installation. `mactl install` is currently a placeholder.

This archive also contains `mactl` and `managed-agents-runtime-host` for its named platform.
If using the archive offline, verify its SHA-256 against the same Release's
`SHA256SUMS`, then put both executables together in a directory on PATH.

## 中文

在目标 Linux 或 macOS 主机安装 Go 1.26+，按上面的 `go install` 命令安装同一版本的
CLI 与 Runtime Host，再配置 PATH。当前命令安装 `2.1.0-BETA1` 预发布版本；
后续版本一起替换两个版本后缀。完整说明见
[Runtime Host 安装与运维](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/zh/service/runtime-host.md)。

另行安装并登录需要的 Coding Agent provider，然后通过 `mactl connect` 连接已有 Service。
CLI 会提示输入凭据并启动 Runtime Host；它不部署平台。快速启动使用
[Docker Compose](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/zh/service/quickstart.md)，
Kubernetes 生产安装使用 [Helm](https://gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/-/blob/master/docs/v2/zh/service/kubernetes.md)。
当前 `mactl install` 是占位命令。

本压缩包也包含对应平台的两个可执行文件。离线使用时，先通过同一 Release 的
`SHA256SUMS` 核对校验和，再将两个命令一起放入 PATH 目录。
