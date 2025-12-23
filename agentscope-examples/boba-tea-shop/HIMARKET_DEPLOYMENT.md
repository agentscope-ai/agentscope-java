# HiMarket 部署与使用指南

## 概述

HiMarket 是一个 AI 能力市场，用于上架和发布 AgentScope 多智能体系统中的各类 Agent。

### HiMarket 功能

- **Agent 市场**: 将注册到 Nacos 的 Agent 一键上架，开发者可浏览、订阅各类 Agent 服务
- **管理后台**: 统一管理 Agent 产品、配置权限、审批订阅
- **开发者门户**: 开发者可在门户中发现、订阅和使用 Agent 能力
- **可观测能力**: 监控 Agent 调用的成功率、延迟等运行状态

HiMarket 与 AgentScope 多智能体系统共享同一个 Nacos 实例，实现 Agent 的统一管理和开放。

---

## 镜像构建

### 构建 HiMarket Server 镜像

```bash
# 设置镜像仓库地址
REGISTRY="registry.cn-hangzhou.aliyuncs.com/agentscope"

# 登录镜像仓库
docker login registry.cn-hangzhou.aliyuncs.com

# 构建 HiMarket Server
cd himarket && sh build.sh -r $REGISTRY && cd ..
```

### 镜像架构说明

| 模块类型 | 支持架构 | 说明 |
|---------|---------|------|
| AgentScope 模块 | amd64 + arm64 | 支持多架构，可在 x86 和 ARM 服务器上运行 |
| Frontend | amd64 + arm64 | 支持多架构 |
| **HiMarket 镜像** | **仅 amd64** | ⚠️ 目前仅支持 x86 架构，需要在 amd64 节点上运行 |

> **注意**: 如果您的 K8s 集群包含 ARM 节点，请确保 HiMarket 相关 Pod（`himarket-server`、`himarket-admin`、`himarket-frontend`）调度到 amd64 节点上。可以通过 `nodeSelector` 或 `tolerations` 来控制 Pod 调度。

---

## 配置 Helm Values

编辑 `himarket-helm/values.yaml`，配置 HiMarket：

```yaml
# HiMarket 镜像配置
server:
  image:
    hub: registry.cn-hangzhou.aliyuncs.com/agentscope
    repository: himarket-server-auto-init
    tag: "latest"

# MySQL 配置（可选使用内置或外部）
mysql:
  enabled: true  # 使用内置 MySQL

# Nacos 配置（导入 AgentScope 的 Nacos）
nacos:
  enabled: true  # 启用 Nacos 导入
  name: agentscope-nacos
  serverUrl: http://agentscope-nacos:8848  # 保持和前面配置的 Nacos 地址一致
  username: nacos
  password: nacos
```

更多配置选项，请参考 [himarket-helm/README.md](himarket-helm/README.md)

---

## 部署到 K8s

```bash
# 创建命名空间（如果还没有创建）
kubectl create namespace mse

# 部署 AgentScope 多智能体系统（需先部署）
helm install agentscope helm/ --namespace mse

# 部署 HiMarket AI 能力市场
# 将 AgentScope 的 Nacos 导入到 HiMarket，实现 Agent 统一管理
helm install himarket himarket-helm/ --namespace mse

# 查看部署状态
kubectl get pods -n mse
kubectl get services -n mse
```

---

## 升级或卸载

### 升级部署

```bash
# 升级 AgentScope
helm upgrade agentscope helm/ --namespace mse

# 升级 HiMarket
helm upgrade himarket himarket-helm/ --namespace mse
```

### 卸载（按相反顺序）

```bash
# 卸载 HiMarket
helm uninstall himarket --namespace mse

# 卸载 AgentScope
helm uninstall agentscope --namespace mse
```

---

## 访问 HiMarket

### 通过 Port-Forward 访问

```bash
# HiMarket 管理后台
kubectl port-forward svc/himarket-admin 5174:80 -n mse
# 访问: http://localhost:5174

# HiMarket 开发者门户
kubectl port-forward svc/himarket-frontend 5173:80 -n mse
# 访问: http://localhost:5173
```

### 通过 LoadBalancer 或 NodePort 访问

如果使用 LoadBalancer 或 NodePort：

```bash
# 查看 HiMarket 服务地址
kubectl get svc -n mse | grep himarket

# LoadBalancer: http://<external-ip>:80
# NodePort: http://<node-ip>:<node-port>
```

---

## HiMarket 使用

### 1. 访问 HiMarket 管理后台

通过 `kubectl port-forward` 访问 HiMarket 管理后台：

```bash
# 转发管理后台服务
kubectl port-forward svc/himarket-admin 5174:80 -n mse

# 浏览器访问
# http://localhost:5174
```

如果使用 LoadBalancer 或 NodePort：

```bash
# 查看 HiMarket 服务地址
kubectl get svc -n mse | grep himarket

# LoadBalancer: http://<external-ip>:80
# NodePort: http://<node-ip>:<node-port>
```

### 2. 登录管理员账号

HiMarket 在部署时已自动创建管理员账号：

**默认管理员账号**：
- 用户名: `admin`
- 密码: `admin`

**自定义管理员账号**：

如需修改默认管理员账号，可在 `himarket-helm/values.yaml` 中配置：

```yaml
autoInit:
  # 管理员账号配置
  admin:
    username: your-admin    # 自定义用户名
    password: your-password # 自定义密码
```

或通过 Helm 命令行设置：

```bash
helm install himarket himarket-helm/ \
  --set autoInit.admin.username=myadmin \
  --set autoInit.admin.password=mypassword123
```

### 3. 查看已导入的 Nacos

1. 登录管理后台
2. 进入「服务管理」→「Nacos 实例」
3. 确认 AgentScope 的 Nacos 实例已自动导入（部署时已配置）
4. 点击 Nacos 实例，可查看所有已注册的 Agent 服务

### 4. 上架 Agent

1. 在管理后台进入「API 产品」
2. 点击「创建产品」，选择 Nacos 中的 Agent 服务（如 `supervisor-agent`、`business-sub-agent` 等）
3. 配置产品信息、文档、权限等
4. 保存后发布到开发者门户

### 5. 上架 MCP Server

除了部署时自动导入的 MCP Server，用户还可以上架自己注册到 Nacos 的 MCP Server。

**上架流程**（与 Agent 上架类似）：

1. **注册 MCP Server 到 Nacos**
   - 将您的 MCP Server 注册到与 HiMarket 共享的 Nacos 实例
   - 确保 MCP Server 已正常启动并可被 Nacos 发现

2. **在管理后台上架**
   - 进入 HiMarket 管理后台
   - 进入「API 产品」→「创建产品」
   - 在服务列表中选择您的 MCP Server 服务
   - 配置产品信息：
     - 产品名称和描述
     - MCP Server 提供的工具（tools）说明
     - API 文档和使用示例
     - 访问权限配置
   - 保存并发布到开发者门户

3. **开发者使用**
   - 开发者在门户中浏览 MCP Server 产品
   - 订阅后可在 AgentScope 应用中集成使用
   - 通过 MCP 协议调用 MCP Server 提供的工具能力

**说明**：
- HiMarket 会自动发现所有注册到 Nacos 的 MCP Server
- 支持上架标准 MCP 协议的服务
- 可以为不同的 MCP Server 配置不同的访问权限和订阅策略

### 6. 开发者订阅

HiMarket 在部署时已自动创建演示用的开发者账号，可以直接登录使用：

**默认开发者账号**：
- 用户名: `demo`
- 密码: `demo123`

**使用流程**：
1. 使用默认账号登录 HiMarket 开发者门户
2. 浏览已发布的 Agent 产品
3. 创建应用并订阅 Agent
4. 获取调用凭证并集成到自己的应用中

**自定义开发者账号**：

如需修改默认开发者账号，可在 `himarket-helm/values.yaml` 中配置：

```yaml
autoInit:
  # 开发者账号配置
  developer:
    username: your-developer  # 自定义用户名
    password: your-password   # 自定义密码
```

或通过 Helm 命令行设置：

```bash
helm install himarket himarket-helm/ \
  --set autoInit.developer.username=mydev \
  --set autoInit.developer.password=mypassword123
```

**说明**：
- HiMarket 与 AgentScope 共享同一个 Nacos 实例
- 所有注册到 Nacos 的 Agent 都会被 HiMarket 自动发现
- 通过 HiMarket 可以统一管理和开放这些 Agent 能力

---

## 更多资源

- **HiMarket Helm Chart 文档**: [himarket-helm/README.md](himarket-helm/README.md)
- **HiMarket 快速开始**: [himarket-helm/QUICKSTART.md](himarket-helm/QUICKSTART.md)
- **HiMarket 使用指南**: [himarket-helm/USAGE.md](himarket-helm/USAGE.md)
- **AgentScope 项目文档**: [README.md](README.md)
