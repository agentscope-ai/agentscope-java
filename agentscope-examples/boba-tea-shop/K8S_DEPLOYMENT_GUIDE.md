# 🚀 AgentScope Boba Tea Shop - Kubernetes 部署指南

本指南将帮助您使用 Helm 一键部署 AgentScope 多智能体系统到 Kubernetes 集群。

## 📋 目录

- [前置要求](#前置要求)
- [组件说明](#组件说明)
- [配置说明](#配置说明)
- [一键部署](#一键部署)
- [访问服务](#访问服务)

---

## 📦 前置要求

### 环境要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| Kubernetes | 1.19+ | 集群环境 |
| Helm | 3.0+ | 包管理工具 |
| kubectl | 与集群版本匹配 | 命令行工具 |

### 需要准备的 API Keys 和配置

在部署前，请确保您已获取以下服务的凭证：

| 配置项                | 必需 | 说明 | 获取方式                                                                                   |
|--------------------|-----|------|----------------------------------------------------------------------------------------|
| DashScope API Key  | ✅ | 阿里云大模型服务 | [阿里云 DashScope 模型服务](https://bailian.console.aliyun.com/?tab=model#/model-market)      |
| DashScope Index ID | ✅ | RAG 知识库索引 | [阿里云 DashScope 应用开发（知识库）](https://bailian.console.aliyun.com/?tab=app#/knowledge-base) |
| Mem0 API Key       | ✅ | 记忆服务 | [Mem0 官网](https://app.mem0.ai/)                                                            |

---

## 组件说明

| 组件 | 说明 | 端口 |
|------|------|------|
| **Frontend** | Vue.js 前端应用 | 3000 → 80 |
| **Supervisor Agent** | 监督者智能体，协调各子智能体 | 10008 → 80 |
| **Business MCP Server** | 业务 MCP 服务器，提供订单等业务能力 | 10002 |
| **Business Sub Agent** | 业务子智能体，处理业务相关请求 | 10006 |
| **Consult Sub Agent** | 咨询子智能体，处理咨询相关请求 | 10005 |
| **MySQL** | 数据库服务 | 3306 |
| **Nacos** | 服务注册与发现中心 | 8848 |

---

## ⚙️ 配置说明

### 步骤 1：克隆项目并进入目录

```bash
cd agentscope-examples/boba-tea-shop
```

### 步骤 2：修改 values.yaml

编辑 `helm/values.yaml` 文件，替换所有 `{...}` 占位符：

```bash
# 使用您喜欢的编辑器
vim helm/values.yaml
# 或
code helm/values.yaml
```

### 需要替换的配置项

#### 1. 模型配置（必须修改）

Dashscope：

```yaml
agentscope:
  model:
    provider: dashscope      # 模型提供商: dashscope 或 openai
    apiKey: {API_KEY}        # ⚠️ 替换为您的 API Key
    modelName: qwen-max      # 模型名称
```

OpenAI：

```yaml
agentscope:
  model:
    provider: openai      # 模型提供商: dashscope 或 openai
    apiKey: {API_KEY}     # ⚠️ 替换为您的 API Key
    modelName: gpt-5      # 模型名称
    baseUrl: {BASE_URL}   # ⚠️ 替换为您的 API 地址
```

#### 2. DashScope 知识库配置（如使用 RAG 功能则必须修改）

```yaml
dashscope:
  apiKey: {DASHSCOPE_API_KEY}  # ⚠️ 替换为您的 API Key
  indexId: {DASHSCOPE_RAG_ID}  # ⚠️ 替换为您的索引 ID
```

#### 3. Mem0 配置（可选，用于记忆服务）

```yaml
mem0:
  apiKey: {MEM0_API_KEY}  # ⚠️ 替换为您的 Mem0 API Key
```

**替换示例：**
```yaml
mem0:
  apiKey: m0-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### 可选配置项

#### 镜像配置

```yaml
image:
  registry: registry-vpc.cn-hangzhou.aliyuncs.com/agentscope  # 镜像仓库(该地址有社区构建完成的镜像，可供直接部署)
  pullPolicy: Always         # 镜像拉取策略
  tag: "1.0.1"        # 镜像标签
```

#### 数据库配置

```yaml
mysql:
  deployEnabled: true        # 是否部署内置 MySQL
  host: mysql                # MySQL 服务名
  dbname: multi_agent_demo   # 数据库名
  username: multi_agent_demo # 用户名
  password: multi_agent_demo@321  # 密码
```

#### Nacos 配置

```yaml
nacos:
  deployEnabled: true        # 是否部署内置 Nacos
  serverAddr: nacos-server:8848  # Nacos 地址
  namespace: public          # 命名空间
  username: nacos            # 用户名
  password: nacos            # 密码
  registerEnabled: true      # 是否启用服务注册
```

#### 服务开关

```yaml
services:
  frontend:
    enabled: true            # 前端应用
  supervisorAgent:
    enabled: true            # 监督者智能体
  businessMcpServer:
    enabled: true            # 业务 MCP 服务器
  businessSubAgent:
    enabled: true            # 业务子智能体
  consultSubAgent:
    enabled: true            # 咨询子智能体
```

---

## 🚀 一键部署

### 方式一：使用默认命名空间配置

```bash
# 1. 创建命名空间
kubectl create namespace agentscope

# 2. 一键部署
helm install agentscope helm/ --namespace agentscope --values helm/values.yaml
```

### 方式二：指定自定义命名空间

```bash
# 1. 创建命名空间
kubectl create namespace my-agentscope

# 2. 部署到自定义命名空间
helm install agentscope helm/ \
  --namespace my-agentscope \
  --values helm/values.yaml \
  --set global.namespace=my-agentscope
```

### 方式三：通过命令行覆盖敏感配置（推荐用于 CI/CD）

```bash
# 使用环境变量传递敏感信息
helm install agentscope helm/ \
  --namespace agentscope \
  --values helm/values.yaml \
  --set agentscope.model.apiKey=$DASHSCOPE_API_KEY \
  --set dashscope.apiKey=$DASHSCOPE_API_KEY \
  --set dashscope.indexId=$DASHSCOPE_INDEX_ID \
  --set mem0.apiKey=$MEM0_API_KEY
```

### 部署输出示例

成功部署后，您将看到类似以下输出：

```
================================================================
  AgentScope 多智能体系统已成功部署！
================================================================

📦 部署信息:
  Release Name: agentscope
  Namespace:    agentscope
  Chart:        agentscope-multi-agent-1.0.0

🚀 已启用的服务:

  ✅ MySQL 数据库
     - Service: mysql:3306
     - Database: multi-agent-demo

  ✅ Frontend (前端应用) - Port: 3000
     - 访问: http://frontend:3000

  ✅ Supervisor Agent (监督者智能体) - Port: 10008

  ✅ Business MCP Server (业务 MCP 服务器) - Port: 10002

  ✅ Business Sub Agent (业务子智能体) - Port: 10006

  ✅ Consult Sub Agent (咨询子智能体) - Port: 10005

📋 查看部署状态:
  kubectl get pods -n agentscope
  kubectl get deployments -n agentscope
  kubectl get services -n agentscope
================================================================
```

---

## 🌐 访问服务

### 获取 LoadBalancer 外部 IP

```bash
# 获取 Frontend 服务的外部 IP
kubectl get svc frontend -n agentscope -o jsonpath='{.status.loadBalancer.ingress[0].ip}'

# 获取 Supervisor Agent 的外部 IP
kubectl get svc supervisor-agent -n agentscope -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

### 使用端口转发（本地开发/测试）

如果没有 LoadBalancer 或在本地测试：

```bash
# 转发 Frontend 到本地 8080 端口
kubectl port-forward svc/frontend 8080:80 -n agentscope

# 在另一个终端转发 Supervisor Agent 到本地 8081 端口
kubectl port-forward svc/supervisor-agent 8081:80 -n agentscope
```

然后访问：
- **Frontend**: http://localhost:8080
- **Supervisor Agent API**: http://localhost:8081

