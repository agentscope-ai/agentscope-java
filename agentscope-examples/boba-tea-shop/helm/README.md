# AgentScope 多智能体系统 Helm Chart

这是一个标准的 Helm Chart，用于部署 AgentScope 多智能体系统到 Kubernetes 集群。

## 📦 Chart 信息

- **Chart 名称**: agentscope-multi-agent
- **Chart 版本**: 1.0.0
- **应用版本**: 1.0.0

## 🏗️ 架构概览

本系统包含以下组件：

### 基础设施
- **MySQL** - 数据库服务（端口: 3306）

### 应用服务
- **Supervisor Agent** - 监督者智能体（端口: 10008）
- **Order MCP Server** - 订单 MCP 服务器（端口: 10002）
- **Order Sub Agent** - 订单子智能体（端口: 10006）
- **Feedback MCP Server** - 反馈 MCP 服务器（端口: 10004）
- **Feedback Sub Agent** - 反馈子智能体（端口: 10007）
- **Memory MCP Server** - 记忆 MCP 服务器（端口: 10010）
- **Consult Sub Agent** - 咨询子智能体（端口: 10005）

## 🚀 快速开始

### 前置要求

- Kubernetes 1.19+
- Helm 3.0+
- 已配置的镜像拉取密钥（imagePullSecrets）

### 基础部署

```bash
# 使用默认配置部署
helm install agentscope-demo ./helm

# 查看部署状态
kubectl get pods
kubectl get deployments
kubectl get services
```

### 自定义配置部署

```bash
# 使用自定义 values 文件
helm install agentscope-demo ./helm -f custom-values.yaml

# 通过命令行覆盖特定值
helm install agentscope-demo ./helm \
  --set mysql.rootPassword=your_password \
  --set nacos.serverAddr=your-nacos-server:8848 \
  --set dashscope.apiKey=your_api_key
```

## ⚙️ 配置说明

### 重要配置项

所有配置项都在 `values.yaml` 中定义。以下是需要根据环境调整的关键配置：

#### 1. 镜像配置

```yaml
image:
  registry: registry-vpc.cn-hangzhou.aliyuncs.com/agentscope  # 镜像仓库地址
  pullPolicy: Always
  tag: "1.0.0"  # 默认镜像标签
```

#### 2. Nacos 配置（外部服务地址）

```yaml
nacos:
  serverAddr: your-nacos-server:8848  # ⚠️ 需要修改为实际地址
  namespace: public
  username: nacos
  password: your-nacos-password  # ⚠️ 需要修改为实际密码
```

#### 3. DashScope 配置（阿里云大模型）

```yaml
dashscope:
  apiKey: your-dashscope-api-key  # ⚠️ 需要修改为实际 API Key
  indexId: your-index-id
```

#### 4. Mem0 配置（记忆服务）

```yaml
mem0:
  apiKey: your-mem0-api-key  # ⚠️ 需要修改为实际 API Key
```

#### 5. MySQL 配置

```yaml
mysql:
  enabled: true
  host: mysql  # K8s Service 名称（通常不需要修改）
  port: 3306
  database: multi-agent-demo
  username: multi_agent_demo
  password: multi_agent_demo@321  # ⚠️ 生产环境建议修改
  rootPassword: multi_agent_demo@321  # ⚠️ 生产环境建议修改
```

如果使用外部 MySQL 实例,需要完成以下事项
- `mysql.enabled` 设置为 false
- `mysql.host` 填写外部实例地址
- 提前创建对应`mysql.multi-agent-demo`配置的数据库以及对应`mysql.DB_USERNAME`配置的账号


#### 6. 各服务的启用/禁用

```yaml
supervisorAgent:
  enabled: true  # 设置为 false 可禁用该服务
  port: 10008
  replicas: 1
  resources:
    requests:
      cpu: "1"
      memory: 2048Mi
```

### 完整配置

请参考 `values.yaml` 文件查看所有可配置项。

## 📝 使用示例

### 1. 部署所有服务（使用默认配置）

```bash
helm install my-agentscope ./helm
```

### 2. 仅部署特定服务

创建自定义 `custom-values.yaml`：

```yaml
# 只部署 MySQL 和 Supervisor Agent
mysql:
  enabled: true

supervisorAgent:
  enabled: true

# 禁用其他服务
orderMcpServer:
  enabled: false
orderSubAgent:
  enabled: false
feedbackMcpServer:
  enabled: false
feedbackSubAgent:
  enabled: false
memoryMcpServer:
  enabled: false
consultSubAgent:
  enabled: false
```

部署：

```bash
helm install my-agentscope ./helm -f custom-values.yaml
```

### 3. 使用外部 MySQL

```yaml
mysql:
  enabled: false  # 不部署内置 MySQL
  host: external-mysql-server  # 外部 MySQL 地址
  port: 3306
  database: multi-agent-demo
  username: app_user
  password: secure_password
```

### 4. 生产环境配置示例

创建 `values-prod.yaml`：

```yaml
# 镜像配置
image:
  registry: your-registry.com/agentscope
  pullPolicy: IfNotPresent

# MySQL 持久化存储
mysql:
  persistence:
    enabled: true
    storageClass: "ssd"
    size: 20Gi
  rootPassword: "strong-root-password"
  password: "strong-app-password"

# 增加副本数（高可用）
supervisorAgent:
  replicas: 3
  resources:
    requests:
      cpu: "2"
      memory: 4096Mi

# 使用 Secrets 管理敏感信息（推荐）
nacos:
  serverAddr: prod-nacos-server:8848
  password: "{{ .Values.secrets.nacosPassword }}"

dashscope:
  apiKey: "{{ .Values.secrets.dashscopeApiKey }}"
```

部署：

```bash
helm install agentscope-prod ./helm \
  -f values-prod.yaml \
  --set secrets.nacosPassword=$NACOS_PASSWORD \
  --set secrets.dashscopeApiKey=$DASHSCOPE_API_KEY
```

## 🔧 运维操作

### 升级部署

```bash
# 修改 values.yaml 后升级
helm upgrade agentscope-demo ./helm

# 使用新的配置文件升级
helm upgrade agentscope-demo ./helm -f new-values.yaml

# 仅更新镜像版本
helm upgrade agentscope-demo ./helm --set image.tag=1.0.1
```

### 回滚

```bash
# 查看历史版本
helm history agentscope-demo

# 回滚到上一版本
helm rollback agentscope-demo

# 回滚到指定版本
helm rollback agentscope-demo 2
```

### 卸载

```bash
# 卸载 release
helm uninstall agentscope-demo

# 卸载并删除所有资源（包括 PVC）
helm uninstall agentscope-demo
kubectl delete pvc -l app=mysql  # 如果使用了持久化存储
```

### 查看部署信息

```bash
# 查看 release 信息
helm list
helm status agentscope-demo

# 查看渲染后的 YAML
helm get manifest agentscope-demo

# 查看配置值
helm get values agentscope-demo
```

### 调试

```bash
# 模拟安装（不实际部署）
helm install agentscope-demo ./helm --dry-run --debug

# 渲染模板查看最终 YAML
helm template agentscope-demo ./helm

# 验证 Chart 语法
helm lint ./helm
```

## 📊 监控和日志

### 查看 Pod 状态

```bash
# 查看所有 Pod
kubectl get pods

# 查看特定服务的 Pod
kubectl get pods -l app=supervisor-agent

# 查看 Pod 详细信息
kubectl describe pod <pod-name>
```

### 查看日志

```bash
# 查看实时日志
kubectl logs -f deployment/supervisor-agent

# 查看最近的日志
kubectl logs deployment/business-mcp-server --tail=100

# 查看多个 Pod 的日志
kubectl logs -l app=supervisor-agent --all-containers=true
```

### 进入容器

```bash
# 进入 MySQL 容器
kubectl exec -it deployment/mysql -- bash
kubectl exec -it deployment/mysql -- mysql -uroot -p

# 进入应用容器
kubectl exec -it deployment/supervisor-agent -- sh
```

## ⚠️ 注意事项

### 1. 数据持久化

默认配置下，MySQL 使用 `emptyDir` 存储（临时存储）：
- ✅ 适用于：开发、测试、演示环境
- ❌ **不适用**：生产环境

生产环境请启用持久化：

```yaml
mysql:
  persistence:
    enabled: true
    storageClass: "your-storage-class"
    size: 20Gi
```

### 2. 敏感信息管理

当前配置将密码和 API Key 明文存储在 `values.yaml` 中，**不推荐用于生产环境**。

生产环境建议：

#### 方案 1：使用 Kubernetes Secrets

```bash
# 创建 Secret
kubectl create secret generic agentscope-secrets \
  --from-literal=nacos-password='your-password' \
  --from-literal=dashscope-api-key='your-api-key'

# 修改 templates 引用 Secret
# 示例：在 _helpers.tpl 中修改环境变量模板
```

#### 方案 2：使用 sealed-secrets 或 External Secrets Operator

```bash
# 使用 sealed-secrets 加密敏感信息
kubeseal < secret.yaml > sealed-secret.yaml
kubectl apply -f sealed-secret.yaml
```

#### 方案 3：使用 Helm Secrets 插件

```bash
# 安装 helm-secrets 插件
helm plugin install https://github.com/jkroepke/helm-secrets

# 加密 values 文件
helm secrets enc values-prod.yaml

# 使用加密的 values 部署
helm secrets install agentscope-prod ./helm -f values-prod.yaml.dec
```

### 3. 镜像拉取密钥

确保已创建镜像拉取密钥：

```bash
kubectl create secret docker-registry agentscope \
  --docker-server=registry-vpc.cn-hangzhou.aliyuncs.com \
  --docker-username=<your-username> \
  --docker-password=<your-password> \
  --docker-email=<your-email>
```

### 4. 资源限制

当前配置仅设置了 `requests`，未设置 `limits`。生产环境建议添加资源限制：

```yaml
supervisorAgent:
  resources:
    requests:
      cpu: "1"
      memory: 2048Mi
    limits:
      cpu: "2"
      memory: 4096Mi
```

### 5. 健康检查

当前模板未包含 livenessProbe 和 readinessProbe（MySQL 除外）。建议为应用服务添加健康检查。

### 6. 部署顺序

MySQL 必须先于应用服务启动。Helm 会按依赖关系部署，但首次部署时可能需要等待 MySQL 就绪：

```bash
# 等待 MySQL 就绪
kubectl wait --for=condition=ready pod -l app=mysql --timeout=300s
```

## 🔗 相关文档

- [Helm 官方文档](https://helm.sh/docs/)
- [Kubernetes 最佳实践](https://kubernetes.io/docs/concepts/configuration/overview/)
- [AgentScope 项目](https://github.com/modelscope/agentscope)

## 📋 文件结构

```
helm/
├── Chart.yaml              # Chart 元数据
├── values.yaml             # 默认配置值
├── .helmignore            # 忽略文件列表
├── README.md              # 本文档
├── ENV_VARIABLES.md       # 环境变量说明（历史文档）
└── templates/             # Kubernetes 模板
    ├── _helpers.tpl       # 辅助模板函数
    ├── NOTES.txt          # 安装后显示的说明
    ├── mysql-deployment.yaml
    ├── supervisor-agent-deployment.yaml
    ├── business-mcp-server-deployment.yaml
    ├── business-sub-agent-deployment.yaml
    ├── feedback-mcp-server-deployment.yaml
    ├── feedback-sub-agent-deployment.yaml
    ├── memory-mcp-server-deployment.yaml
    └── consult-sub-agent-deployment.yaml
```

## 🆘 故障排查

### Chart 安装失败

```bash
# 检查 Chart 语法
helm lint ./helm

# 查看渲染后的 YAML
helm template ./helm --debug

# 查看详细错误信息
helm install agentscope-demo ./helm --debug
```

### Pod 启动失败

```bash
# 查看 Pod 事件
kubectl describe pod <pod-name>

# 查看容器日志
kubectl logs <pod-name>

# 查看前一个容器的日志（如果容器崩溃重启）
kubectl logs <pod-name> --previous
```

### 镜像拉取失败

```bash
# 检查 imagePullSecrets
kubectl get secrets

# 测试镜像拉取
kubectl run test --image=registry-vpc.cn-hangzhou.aliyuncs.com/agentscope/supervisor-agent:1.0.0 --dry-run=client
```

### 服务无法连接

```bash
# 检查 Service
kubectl get svc
kubectl describe svc mysql

# 测试服务连通性
kubectl run test-mysql --rm -it --image=mysql:8.0 -- mysql -h mysql -u multi_agent_demo -p
```

## 📞 支持

如有问题，请：
1. 查看本文档的故障排查部分
2. 查看 [ENV_VARIABLES.md](ENV_VARIABLES.md) 了解环境变量配置
3. 提交 Issue 到项目仓库

---

最后更新：2024-11-19
