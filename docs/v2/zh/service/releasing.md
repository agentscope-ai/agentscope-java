---
title: 组件发布指南
---

[English](/v2/en/service/releasing)

本页面向发布维护者。构建和打包脚本位于 `agentscope-service/release/`，用户安装从[快速上手](/v2/zh/service/quickstart)开始。

## 制品清单

四个镜像分别为 `agentscope-service-control`、`agentscope-service-gateway`、`agentscope-service-dataplane`、`agentscope-service-scheduler`。控制台随 control 镜像交付。

同批次交付完整 Helm Chart、Compose 包、CLI/Runtime Host 分平台包、Python wheel/sdist、DSH npm 包、`release-manifest.json` 和 `SHA256SUMS`。Java SDK 与依赖通过 Maven 发布；服务应用默认不向 Maven Central 发布。

Service、Java 和 SDK 版本可独立演进，manifest 记录源码提交及版本关系。准备新版本时核对 Python 与 DSH 包版本，已公开的同版本制品不能覆盖发布。

## 本地准备

使用 Java 21、Maven、Go（以 go.mod 为准）、Node.js 22、Python 3.10+、Docker Buildx 和 Helm。建立 Python 虚拟环境，安装 `release/requirements.txt` 和 Python SDK 的 dev 依赖。

在仓库根目录执行：

```bash
python agentscope-service/release/release.py verify
python agentscope-service/release/release.py package   --version VERSION --repository REGISTRY/NAMESPACE
```

默认输出到 `agentscope-service/release/dist/VERSION/`。脚本拒绝覆盖已有打包目录，部署包使用明确文件清单，不包含本地 `.env`。候选包可以从未提交源码构建，manifest 会标记 dirty；对外推送要求工作区干净。

## 镜像与 Chart

本地验证一套镜像：

```bash
python agentscope-service/release/release.py images --version VERSION   --repository REGISTRY/NAMESPACE --platforms linux/arm64
```

在目标仓库完成 Docker 与 Helm 登录，确认候选部署和兼容性验证通过后发布：

```bash
python agentscope-service/release/release.py images --version VERSION   --repository REGISTRY/NAMESPACE --platforms linux/amd64,linux/arm64 --push
python agentscope-service/release/release.py publish-chart   --version VERSION --repository REGISTRY/NAMESPACE
```

镜像推送请求生成 SBOM 与 provenance，digest 保存在 `image-*.json`。OCI Chart 使用 [Helm 的 registry 发布流程](https://helm.sh/docs/topics/registries/)。推送成功不自动意味着仓库允许匿名拉取，需要用未登录客户端复验。

## 正式发布顺序

冻结源码与版本，完成测试和候选安装，创建对应 Git tag，再运行 `AgentScope Service release` 工作流。工作流默认只构建；选择 publish 时要求使用 tag，并配置目标仓库凭据。

将制品、校验和、镜像 digest、已验证平台与已知限制写入 Release 草稿。完成本次范围内的 Maven/Python/npm 发布并验证新环境安装，再公开 Release。管理员权限准备、版本选择、Actions 操作、SDK 发布和故障处理的完整中文步骤见仓库中的 `agentscope-service/release/README_zh.md`。

官网内容位于 `/docs`，由 Mintlify GitHub App 按站点配置的分支发布；仓库工作流负责文档校验。合入正式发布分支后，核对托管站点的中英文 Service 导航、直接链接、图片和搜索。
