# Boba Tea Shop 构建指南

本文档介绍如何使用构建脚本来构建 Boba Tea Shop 项目的各个模块。

## 目录

- [快速开始](#快速开始)
- [总构建脚本](#总构建脚本-buildsh)
- [子模块构建脚本](#子模块构建脚本)
- [其他模块](#其他模块)

---

## 快速开始

```bash
# 进入项目目录
cd agentscope-examples/boba-tea-shop

# 构建默认模块（不包含基础设施模块）
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# 构建所有模块（包含基础设施模块）
./build.sh -m all -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push
```

---

## 总构建脚本

位于 `boba-tea-shop/build.sh`，用于批量构建多个子模块。

### 模块分类

| 类型 | 模块名称 | 说明 |
|------|----------|------|
| **默认模块** | `supervisor-agent` | 主管理 Agent（含前端） |
| | `business-mcp-server` | 业务 MCP 服务器 |
| | `business-sub-agent` | 业务子 Agent |
| | `consult-sub-agent` | 咨询子 Agent |
| **额外模块** | `mysql-image` | MySQL 数据库镜像 |
| | `nacos-image` | Nacos 注册中心镜像 |

> **注意**：前端已与 `supervisor-agent` 合并部署，前端静态文件由 Spring Boot 直接托管，统一通过端口 10008 访问。

### 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-v, --version` | 镜像版本标签 | `test` |
| `-r, --registry` | Docker 镜像仓库地址 | 无 |
| `-p, --platform` | 目标平台 | 当前平台 |
| `-t, --run-tests` | 运行 Maven 测试 | 跳过测试 |
| `--skip-build` | 跳过源码构建 (Maven/npm) | 不跳过 |
| `--skip-docker` | 跳过 Docker 镜像构建 | 不跳过 |
| `--push` | 构建后推送镜像到仓库 | 不推送 |
| `-m, --modules` | 指定要构建的模块 | `default` |
| `-h, --help` | 显示帮助信息 | - |

### `-m` 参数特殊值

| 值 | 说明 |
|----|------|
| `default` | 构建默认 4 个模块（默认行为） |
| `all` | 构建所有 6 个模块 |
| `模块名` | 构建指定模块，多个用逗号分隔 |

### 使用示例

```bash
# 1. 构建并推送到镜像仓库
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# 2. 只进行源码构建，不构建 Docker 镜像
./build.sh --skip-docker

# 3. 完整示例：构建所有模块并推送到仓库
./build.sh -m all \
    -v 1.0.0 \
    -r registry.cn-hangzhou.aliyuncs.com/myapp \
    -p linux/amd64 \
    --push
```

---

## 子模块构建脚本

每个子模块目录下都有独立的 `build.sh`，可单独构建某个模块。

### 应用模块

包括：`supervisor-agent`（含前端）、`business-mcp-server`、`business-sub-agent`、`consult-sub-agent`

#### 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-v, --version` | 镜像版本标签 | `test` |
| `-r, --registry` | Docker 镜像仓库地址 | 无 |
| `-p, --platform` | 目标平台 | 当前平台 |
| `-t, --run-tests` | 运行测试（仅 Java 模块） | 跳过 |
| `--skip-build` | 跳过源码构建 | 不跳过 |
| `--skip-docker` | 跳过 Docker 镜像构建 | 不跳过 |
| `--push` | 推送镜像到仓库 | 不推送 |
| `-h, --help` | 显示帮助信息 | - |

#### 使用示例

```bash
# 构建 supervisor-agent（含前端）
cd supervisor-agent

# 1. 构建并推送
./build.sh -v 1.0.0 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push

# 2. 只进行 Maven 构建，不构建 Docker 镜像
./build.sh --skip-docker
```

> **注意**：`supervisor-agent` 的构建脚本会自动构建前端并打包到同一镜像中。

### 基础设施模块

包括：`mysql-image`、`nacos-image`

这些模块只构建 Docker 镜像，没有源码构建步骤。

#### 参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-v, --version` | 镜像版本标签 | 各模块不同 |
| `-r, --registry` | Docker 镜像仓库地址 | 无 |
| `-p, --platform` | 目标平台 | 当前平台 |
| `--push` | 推送镜像到仓库 | 不推送 |
| `-h, --help` | 显示帮助信息 | - |

#### 使用示例

```bash
# 进入模块目录
cd mysql-image  # 或 nacos-image

# 1. 默认构建
./build.sh

# 2. 指定版本
./build.sh -v 8.0.30  # mysql-image
./build.sh -v 3.1.1   # nacos-image

# 3. 指定平台
./build.sh -p linux/amd64

# 4. 构建并推送
./build.sh -v 8.0.30 -p linux/amd64 -r registry.cn-hangzhou.aliyuncs.com/myapp --push
```
## 其他模块

### HiMarket

HiMarket 的介绍以及构建部署指南详见 HIMARKET_DEPLOYMENT.md