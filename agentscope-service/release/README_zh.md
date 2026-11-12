# Managed Agents 发布工具

[English](README.md)

本独立 GitLab 仓库没有激活发布工作流；原工作流归档仅供参考。以下命名用于
本地候选产物，实际发布前需配置新项目的发布账户、仓库和分发渠道。

## 本地验证和打包

从仓库根目录运行。先在 `.venv` 安装 Python SDK 的 dev 依赖及本目录
`requirements.txt`。Java 构建需要根 README 中说明的兼容 AgentScope Java
二进制依赖。

```bash
.venv/bin/python managed-agents/release/release.py verify

.venv/bin/python managed-agents/release/release.py package \
  --version RELEASE_VERSION --repository REGISTRY/NAMESPACE \
  --platforms linux/amd64,linux/arm64,darwin/amd64,darwin/arm64 \
  --output managed-agents/release/dist/RELEASE_VERSION
```

`--distributions-only` 跳过 SDK 包；本地冒烟候选可只选
`--platforms darwin/arm64`。输出目录不能已存在。产物包括 Compose、Kubernetes、
Helm、CLI/Runtime Host 归档、`release-manifest.json` 与 `SHA256SUMS`，完整
打包还包含 SDK 分发包。清单记录当前工作区是否干净，不代表镜像或包已发布。

五个镜像名称为 `managed-agents-control-plane`、`managed-agents-agent-service`、
`managed-agents-agent-harness-runtime`、`managed-agents-tool-worker` 和
`managed-agents-gateway`。`images` 从本独立源码构建镜像，`--push` 才执行推送。
镜像仓库必须填写实际 hostname/namespace。

## SDK 与发布目标

Java 为 `io.agentcore.managed:managed-agents-client`；Python 为
`managed-agents-sdk`（`import managed_agents`）；DSH 为
`@agentcore/managed-agents-dsh`。项目 `revision` 与外部库 `agentscope.version`
独立。需要配置新的 Maven/PyPI/npm 发布权限；本次未配置签名或 Central 发布。
不要把可执行 Java 服务当作 SDK 依赖发布。

可选 GitHub Release 辅助功能要求显式设置 `GH_REPO`。源发布分支默认 `master`，
可用 `MANAGED_AGENTS_RELEASE_BRANCH` 调整；该功能不是 GitLab Release 发布器。
Go 子模块 tag 前缀改为 `managed-agents/control-plane/v`，需与新 module 和
实际发布源码对应。

可选 GitHub Helm 同步功能不再带旧账户默认值，必须设置
`MANAGED_AGENTS_GITHUB_REPOSITORY`、`MANAGED_AGENTS_HELM_GITHUB_REPOSITORY`
和 `MANAGED_AGENTS_HELM_URL`，缺少配置时在访问外部仓库前停止。
本次未启用该功能或创建远程 Helm 仓库。

`homebrew/managed-agents-cli.rb.in` 为模板。发布时根据实际归档填入版本、下载
地址和四个平台的 SHA-256，再放到目标 tap；不沿用旧项目的校验值。

改名校验不发布包、镜像、Chart、Git tag 或 Release。已有部署接入新名称前，
需要协调迁移 CRD、数据库身份、卷和客户端。持久化 schema/table 标识以及 MQ
topic/group 值保持原样。本次未实际部署或修改数据库。

### PI Brain 可选镜像

PI / PI Durable 使用同一个 Node 制品。`python3 managed-agents/release/release.py images-pi --version <版本> --repository <仓库> --platforms linux/amd64 --output /tmp/pi-release` 只构建并加载本地 PI 镜像；普通 `images` 保持五组件默认行为。发布需显式 `--push`，其干净源码、来源/架构校验和 SBOM 规则不变。PI 元数据与默认镜像清单分开。Helm 内核选择、专用 Secret、模型配置和格式升级顺序见 [PI 部署说明](../agent-harness-runtime/images/pi/README.md)。
