# HiMarket Auto-Init Helm Chart

## 概述

这是一个**开箱即用**的 HiMarket 完整部署方案，包含：

- **himarket-server** - 后端 API 服务（使用 `himarket-server-auto-init` 镜像，自动初始化）
- **himarket-admin** - 管理后台前端
- **himarket-frontend** - 开发者门户前端
- **MySQL** - 数据库（可选内置或外部）

### 特性

- ✅ **完整架构** - 一次部署所有组件（Server + Admin + Frontend）
- ✅ **自动初始化** - Server 自动创建管理员、开发者、Portal
- ✅ **可选内置 MySQL** - 支持部署内置 MySQL 或连接外部数据库
- ✅ **灵活配置** - 通过 values.yaml 配置 Nacos、网关、MCP
- ✅ **零脚本依赖** - 无需手动执行初始化脚本（去掉了原有的 post-install hooks）

---

## 快速开始

### 1. 最小化部署（内置 MySQL）

```bash
# 使用默认配置（包含 MCP 自动初始化）
helm install himarket ./himarket-auto-init

# 或使用最小化配置文件（不含 MCP）
helm install himarket ./himarket-auto-init -f values-minimal.yaml
```

**部署内容:**
- HiMarket Server (带自动初始化，**默认启用 MCP 导入和上架**）
- HiMarket Admin
- HiMarket Frontend  
- MySQL StatefulSet

**访问方式:**
```bash
# Server API (后端)
kubectl port-forward svc/himarket-server 8080:80

# Admin (管理后台)
kubectl port-forward svc/himarket-admin 8001:80

# Frontend (开发者门户)
kubectl port-forward svc/himarket-frontend 3000:80
```

### 2. 自定义 Server 镜像

```bash
# 使用自己的镜像仓库（默认使用 agentscope 凭证）
helm install himarket ./himarket-auto-init \
  --set server.image.hub=your-registry.com/your-namespace \
  --set server.image.repository=himarket-server-auto-init \
  --set server.image.tag=v1.0.0

# 如需使用其他凭证
helm install himarket ./himarket-auto-init \
  --set server.image.hub=your-registry.com/your-namespace \
  --set imagePullSecrets[0].name=your-registry-secret
```

### 3. 使用外部 MySQL

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=false \
  --set database.host=mysql.default.svc.cluster.local \
  --set database.password=yourpassword
```

### 3. 集成 Nacos 和网关

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true \
  --set nacos.enabled=true \
  --set nacos.serverUrl=http://nacos:8848 \
  --set nacos.username=nacos \
  --set nacos.password=nacos \
  --set gateway.enabled=true \
  --set gateway.type=HIGRESS \
  --set gateway.higress.url=http://higress-console:8080
```

### 4. 导入和上架 MCP

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true \
  --set nacos.enabled=true \
  --set nacos.serverUrl=http://nacos:8848 \
  --set nacos.username=nacos \
  --set nacos.password=nacos \
  --set mcp.importToNacos=true \
  --set mcp.publishToHimarket=true
```

---

## 配置说明

### 核心配置

| 参数 | 默认值 | 说明 |
|------|-------|------|
| `imagePullSecrets` | `[{name: agentscope}]` | 镜像拉取凭证（默认 agentscope）⭐ |
| `server.image.hub` | `registry.cn-hangzhou.aliyuncs.com/agentscope` | Server 镜像仓库（可自定义） |
| `server.image.repository` | `himarket-server-auto-init` | Server 镜像名称 |
| `server.image.tag` | `latest` | Server 镜像标签 |
| `frontend.image.hub` | `opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group` | Frontend 镜像仓库（开源默认） |
| `admin.image.hub` | `opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group` | Admin 镜像仓库（开源默认） |

### MySQL 配置

#### 使用内置 MySQL

```yaml
mysql:
  enabled: true  # 启用内置 MySQL
  image:
    hub: opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group
    repository: mysql
    tag: "latest"
  auth:
    rootPassword: himarket123
    database: himarket
  persistence:
    storageClass: ""  # 使用默认 StorageClass
    size: 10Gi
```

#### 使用外部 MySQL

```yaml
mysql:
  enabled: false  # 禁用内置 MySQL
  external:
    host: mysql.default.svc.cluster.local
    port: 3306
    database: himarket
    username: root
    password: yourpassword
```

### Nacos 配置

```yaml
nacos:
  enabled: true  # 注册 Nacos 实例
  name: nacos-demo
  serverUrl: http://nacos:8848
  
  # 方式 1: 用户名密码（开源 Nacos）
  username: nacos
  password: nacos
  
  # 方式 2: AccessKey/SecretKey（商业化 Nacos）
  accessKey: LTAI5t...
  secretKey: xxx...
```

### 网关配置

#### Higress 网关

```yaml
gateway:
  enabled: true
  type: HIGRESS
  name: higress-demo
  higress:
    url: http://higress-console:8080
    username: admin
    password: admin
```

#### 阿里云 AI 网关

```yaml
gateway:
  enabled: true
  type: APIG_AI
  name: ai-gateway-demo
  apig:
    region: cn-hangzhou
    accessKey: LTAI5t...
    secretKey: xxx...
```

### MCP 配置（默认启用）

```yaml
mcp:
  importToNacos: true       # 导入 MCP 到 Nacos（默认启用）⭐
  publishToHimarket: true   # 上架 MCP 到 HiMarket（默认启用）⭐
  jsonFile: /opt/himarket/data/nacos-mcp.json  # 使用内置 MCP 文件
```

**默认行为:**
- ✅ 自动导入 5 个内置 MCP Server 到 Nacos
- ✅ 自动在 HiMarket 中上架这些 MCP

**内置 MCP Server:**
- context7 - 文档上下文查询
- git - Git 仓库操作
- Time - 时区时间转换
- memory - 知识图谱管理
- fetch - 网页内容抓取

**禁用 MCP（如果不需要）:**
```bash
helm install himarket ./himarket-auto-init \
  --set mcp.importToNacos=false \
  --set mcp.publishToHimarket=false
```

---

## 使用场景

### 场景 1: 快速体验（默认配置，含 MCP）

```bash
# 使用默认配置，自动初始化并导入 MCP
helm install himarket ./himarket-auto-init
```

**包含：**
- ✅ HiMarket Server + Admin + Frontend
- ✅ 内置 MySQL
- ✅ 管理员和开发者账号
- ✅ Portal
- ✅ 5 个内置 MCP Server（自动导入和上架）⭐

### 场景 1b: 最小化部署（不含 MCP）

```bash
# 使用最小化配置文件
helm install himarket ./himarket-auto-init -f values-minimal.yaml
```

**包含：**
- ✅ HiMarket Server + Admin + Frontend
- ✅ 内置 MySQL
- ✅ 管理员和开发者账号
- ✅ Portal
- ❌ 不导入 MCP

---

### 场景 2: 使用自定义镜像

```bash
# 1. 创建镜像拉取凭证（如果需要）
kubectl create secret docker-registry my-registry-secret \
  --docker-server=your-registry.com \
  --docker-username=your-username \
  --docker-password=your-password

# 2. 部署，使用自定义 Server 镜像
helm install himarket ./himarket-auto-init \
  --set server.image.hub=your-registry.com/your-namespace \
  --set server.image.repository=himarket-server-auto-init \
  --set server.image.tag=v1.0.0 \
  --set imagePullSecrets[0].name=my-registry-secret
```

---

### 场景 3: 完整功能部署（生产环境）

使用 `values-full.yaml`:

```bash
helm install himarket ./himarket-auto-init -f values-full.yaml
```

或创建 `custom-values.yaml`:

```yaml
# 自定义 Server 镜像
server:
  image:
    hub: your-registry.com/your-namespace
    repository: himarket-server-auto-init
    tag: v1.0.0

# 使用外部 MySQL
mysql:
  enabled: false

database:
  host: prod-mysql.database.svc.cluster.local
  password: prod-password

# 注册商业化 Nacos
nacos:
  enabled: true
  name: nacos-prod
  serverUrl: mse-xxx.nacos-ans.mse.aliyuncs.com:8848
  accessKey: LTAI5t...
  secretKey: xxx...
  username: nacos
  password: nacos

# 注册 AI 网关
gateway:
  enabled: true
  type: APIG_AI
  name: ai-gateway-prod
  apig:
    region: cn-hangzhou
    accessKey: LTAI5t...
    secretKey: xxx...

# MCP 配置（默认已启用）
mcp:
  importToNacos: true
  publishToHimarket: true

# 镜像拉取凭证
imagePullSecrets:
  - name: my-registry-secret

# 资源配置
resources:
  limits:
    cpu: 4000m
    memory: 4Gi
  requests:
    cpu: 1000m
    memory: 1Gi
```

部署：

```bash
helm install himarket ./himarket-auto-init -f custom-values.yaml
```

---

### 场景 4: 本地开发（Kind/Minikube）

```bash
helm install himarket ./himarket-auto-init
```

访问（使用 port-forward）：

```bash
kubectl port-forward svc/himarket-server 8080:80     # Server API
kubectl port-forward svc/himarket-admin 8001:80      # Admin UI
kubectl port-forward svc/himarket-frontend 3000:80   # Frontend UI
```

---

## 常用命令

### 查看部署状态

```bash
# 查看所有 Pods
kubectl get pods | grep himarket

# 查看所有服务
kubectl get svc | grep himarket

# 查看 Server 初始化日志
kubectl logs -f -l app=himarket-server
```

### 端口转发访问

```bash
# 转发所有服务（需要开 3 个终端）
kubectl port-forward svc/himarket-server 8080:80     # Server API
kubectl port-forward svc/himarket-admin 8001:80      # Admin UI
kubectl port-forward svc/himarket-frontend 3000:80   # Frontend UI
```

### 更新配置

```bash
# 修改配置后升级
helm upgrade himarket ./himarket-auto-init -f custom-values.yaml

# 快速修改某个参数
helm upgrade himarket ./himarket-auto-init \
  --set nacos.enabled=true \
  --set nacos.serverUrl=http://new-nacos:8848 \
  --reuse-values

# 更新 Server 镜像
helm upgrade himarket ./himarket-auto-init \
  --set server.image.tag=v1.0.1 \
  --reuse-values
```

### 卸载

```bash
# 卸载 HiMarket
helm uninstall himarket

# 删除 MySQL 数据（如果使用内置 MySQL）
kubectl delete pvc -l app=mysql
```

---

## 配置项完整列表

### 镜像配置

```yaml
image:
  registry: registry.cn-hangzhou.aliyuncs.com
  repository: agentscope/himarket-server-auto-init
  tag: latest
  pullPolicy: Always
```

### HiMarket 配置

```yaml
himarket:
  frontendUrl: http://localhost:3000
  admin:
    username: admin
    password: admin
  developer:
    username: demo
    password: demo123
  portal:
    name: demo
```

### 初始化控制

```yaml
autoInit: true      # 是否自动初始化
initDelay: 10       # 初始化延迟（秒）
replicaCount: 1     # 副本数（建议保持为 1）
```

### 资源配置

```yaml
resources:
  limits:
    cpu: 2000m
    memory: 2Gi
  requests:
    cpu: 500m
    memory: 512Mi
```

---

## 架构说明

### 部署组件

```
himarket-auto-init/
├── himarket-server        # 后端 API (8080端口)
│   └── 使用 himarket-server-auto-init 镜像
│       ├── 自动初始化管理员账号
│       ├── 自动创建 Portal
│       ├── 自动注册开发者
│       ├── 可选注册 Nacos
│       ├── 可选注册 Gateway
│       └── 可选导入和上架 MCP
│
├── himarket-admin         # 管理后台前端 (8000端口)
│   └── 使用 himarket-admin 镜像
│
├── himarket-frontend      # 开发者门户前端 (8000端口)
│   └── 使用 himarket-frontend 镜像
│
└── MySQL (可选)           # 数据库 (3306端口)
    └── 可选内置或连接外部
```

### 与原 Helm Chart 的区别

| 特性 | 原 Helm Chart | 新 Helm Chart (Auto-Init) |
|------|--------------|--------------------------|
| **组件** | Server + Admin + Frontend | Server + Admin + Frontend (相同) |
| **Server 镜像** | himarket-server | himarket-server-auto-init ⭐ |
| **初始化方式** | Post-install hooks (6个脚本) | 镜像内置自动初始化 ⭐ |
| **复杂度** | 高（多个 hook 脚本） | 低（通过环境变量配置） ⭐ |
| **部署速度** | 较慢（脚本串行执行） | 较快（容器内并行） |
| **MySQL** | 支持内置或外部 | 支持内置或外部（相同） |
| **MCP 支持** | 需要手动配置 | 内置 5 个 MCP，开箱即用 ⭐ |
| **适用场景** | 所有场景 | 所有场景 |

**核心改进:** 只替换了 Server 镜像，去掉了复杂的 hook 脚本，保留了完整的三个组件架构。

---

## 故障排查

### 查看初始化日志

```bash
kubectl logs -f -l app.kubernetes.io/name=himarket-auto-init | grep -A 5 "步骤"
```

### 检查 MySQL 连接

```bash
# 进入容器
kubectl exec -it <pod-name> -- bash

# 测试 MySQL 连接
mysql -h <mysql-host> -u root -p
```

### 常见问题

1. **Pod 一直 CrashLoopBackOff**
   - 检查 MySQL 是否就绪: `kubectl get pods -l app.kubernetes.io/component=mysql`
   - 查看日志: `kubectl logs <pod-name>`

2. **初始化超时**
   - 增加 `initDelay`: `--set initDelay=30`
   - 检查网络连接（Nacos、Gateway）

3. **MCP 导入失败**
   - 确认 `nacos.enabled=true` 且 Nacos 可访问
   - 确认提供了 `nacos.username` 和 `nacos.password`

---

## 进阶使用

### 使用自定义 MCP 配置

1. 创建 ConfigMap:

```bash
kubectl create configmap custom-mcp-config \
  --from-file=nacos-mcp.json=/path/to/your/custom-mcp.json
```

2. 修改 values.yaml:

```yaml
mcp:
  importToNacos: true
  publishToHimarket: true
  customConfig: custom-mcp-config  # ConfigMap 名称
```

3. 在 Deployment 中挂载（需要修改 templates/deployment.yaml）

---

## 文件结构

```
himarket-auto-init/
├── Chart.yaml                    # Chart 元数据
├── values.yaml                   # 默认配置
├── README.md                     # 本文档
└── templates/
    ├── _helpers.tpl             # 辅助函数
    ├── deployment.yaml          # HiMarket Server Deployment
    ├── service.yaml             # HiMarket Server Service
    ├── mysql-statefulset.yaml   # MySQL StatefulSet（可选）
    ├── mysql-service.yaml       # MySQL Service（可选）
    └── NOTES.txt                # 部署后提示信息
```

---

## 升级指南

### 从旧版 Helm Chart 迁移

1. 备份现有数据（如果需要）
2. 卸载旧版 Chart
3. 安装新版 Chart（使用相同的数据库）

```bash
# 1. 导出旧数据库（可选）
kubectl exec -it <old-mysql-pod> -- mysqldump -u root -p himarket > backup.sql

# 2. 卸载旧版
helm uninstall himarket-old

# 3. 安装新版（使用外部 MySQL）
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=false \
  --set mysql.external.host=<old-mysql-host> \
  --set mysql.external.password=<old-password>
```

---

**祝你使用愉快！** 🚀

