# HiMarket Auto-Init 使用指南

## 🚀 快速开始

### 方式 1: 使用安装脚本（推荐）

```bash
cd himarket/deploy/himarket-auto-init
./install.sh
```

按照提示选择安装模式即可。

---

### 方式 2: 使用 Helm 命令

#### 最小化部署

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true
```

#### 使用预置配置文件

```bash
# 最小化部署
helm install himarket ./himarket-auto-init -f values-minimal.yaml

# 完整部署
helm install himarket ./himarket-auto-init -f values-full.yaml

# 商业化部署
helm install himarket ./himarket-auto-init -f values-commercial.yaml
```

---

## 📋 配置场景

### 场景 1: 本地开发（Kind/Minikube）

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true \
  --set service.type=NodePort \
  --set nodePort=30080 \
  --set resources.requests.cpu=250m \
  --set resources.requests.memory=512Mi
```

**访问：**
```bash
# Port-forward
kubectl port-forward svc/himarket-himarket-auto-init 8080:8080

# 或使用 NodePort
open http://localhost:30080
```

---

### 场景 2: 集成开源 Nacos

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true \
  --set nacos.enabled=true \
  --set nacos.serverUrl=http://nacos:8848 \
  --set nacos.username=nacos \
  --set nacos.password=nacos
```

---

### 场景 3: 集成商业化 Nacos（MSE）

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=false \
  --set mysql.external.host=rm-xxx.mysql.rds.aliyuncs.com \
  --set mysql.external.password=your-password \
  --set nacos.enabled=true \
  --set nacos.serverUrl=mse-xxx.nacos-ans.mse.aliyuncs.com:8848 \
  --set nacos.accessKey=LTAI5t... \
  --set nacos.secretKey=xxx... \
  --set nacos.username=nacos \
  --set nacos.password=your-nacos-password
```

---

### 场景 4: 完整 MCP 功能

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

**自动导入 5 个 MCP Server:**
- context7 - 文档上下文查询
- git - Git 仓库操作
- Time - 时区时间转换
- memory - 知识图谱管理
- fetch - 网页内容抓取

---

### 场景 5: 集成 AI 网关

```bash
helm install himarket ./himarket-auto-init \
  --set mysql.enabled=true \
  --set gateway.enabled=true \
  --set gateway.type=APIG_AI \
  --set gateway.name=ai-gateway \
  --set gateway.apig.region=cn-hangzhou \
  --set gateway.apig.accessKey=LTAI5t... \
  --set gateway.apig.secretKey=xxx...
```

---

## 🔧 常用操作

### 查看部署状态

```bash
# 查看 Helm Release
helm list

# 查看 Pods
kubectl get pods -l app.kubernetes.io/name=himarket-auto-init

# 查看 Services
kubectl get svc -l app.kubernetes.io/name=himarket-auto-init
```

### 查看初始化日志

```bash
# 实时查看日志
kubectl logs -f -l app.kubernetes.io/name=himarket-auto-init

# 查看初始化步骤
kubectl logs -l app.kubernetes.io/name=himarket-auto-init | grep "步骤"
```

### 访问服务

```bash
# 获取服务地址（LoadBalancer）
export SERVICE_IP=$(kubectl get svc himarket-himarket-auto-init -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "http://$SERVICE_IP:8080"

# Port-forward（任何 Service 类型）
kubectl port-forward svc/himarket-himarket-auto-init 8080:8080
```

### 更新配置

```bash
# 更新某个参数
helm upgrade himarket ./himarket-auto-init \
  --set nacos.enabled=true

# 使用新的 values 文件
helm upgrade himarket ./himarket-auto-init -f values-full.yaml

# 重新安装（删除并重装）
helm uninstall himarket
helm install himarket ./himarket-auto-init -f values-full.yaml
```

### 卸载

```bash
# 使用脚本（推荐）
./uninstall.sh

# 手动卸载
helm uninstall himarket

# 同时删除数据
helm uninstall himarket
kubectl delete pvc -l app.kubernetes.io/instance=himarket
```

---

## 🔍 故障排查

### 1. Pod 无法启动

```bash
# 查看 Pod 状态
kubectl describe pod -l app.kubernetes.io/name=himarket-auto-init

# 查看事件
kubectl get events --sort-by='.lastTimestamp'

# 常见原因：
# - MySQL 未就绪
# - 资源不足
# - 镜像拉取失败
```

### 2. 初始化失败

```bash
# 查看完整日志
kubectl logs -l app.kubernetes.io/name=himarket-auto-init --tail=200

# 常见错误：
# - 数据库连接失败 → 检查 MySQL 配置
# - Nacos 连接失败 → 检查 Nacos 地址和白名单
# - Token 提取失败 → 检查账号密码
```

### 3. MySQL 连接问题

```bash
# 检查 MySQL Pod
kubectl get pods -l app.kubernetes.io/component=mysql

# 测试连接
kubectl run mysql-client --rm -it --image=mysql:8.0 -- \
  mysql -h himarket-himarket-auto-init-mysql -u root -p
```

### 4. Nacos 连接问题

```bash
# 进入容器测试
kubectl exec -it <himarket-pod> -- bash

# 测试 Nacos 连接
curl -X POST "http://nacos:8848/nacos/v1/auth/login" \
  -d "username=nacos" \
  -d "password=nacos"

# 检查白名单（商业化 Nacos）
# 确保 Pod 的出口 IP 在 MSE Nacos 白名单中
```

---

## 📊 配置优先级

### MySQL 配置

```
mysql.enabled=true
  ↓
使用内置 MySQL
  - StatefulSet 部署
  - 使用 PVC 持久化
  - 主机名: <release-name>-mysql
  
mysql.enabled=false
  ↓
使用外部 MySQL
  - 读取 mysql.external.* 配置
  - 不部署 MySQL Pod
```

### 初始化流程

```
容器启动
  ↓
等待 MySQL 就绪
  ↓
启动 HiMarket Server
  ↓
健康检查通过
  ↓
执行自动初始化脚本
  ├─ 注册管理员
  ├─ 注册 Nacos（如果启用）
  ├─ 注册网关（如果启用）
  ├─ 创建 Portal
  ├─ 注册开发者
  ├─ 导入 MCP（如果启用）
  └─ 上架 MCP（如果启用）
```

---

## 💡 最佳实践

### 1. 生产环境建议

- ✅ 使用外部 MySQL（RDS）
- ✅ 使用商业化 Nacos（MSE）
- ✅ 配置资源限制
- ✅ 使用 LoadBalancer 或 Ingress
- ✅ 修改默认密码
- ✅ 设置合适的 initDelay（20-30秒）

### 2. 安全建议

```yaml
# 不要使用默认密码
himarket:
  admin:
    password: use-strong-password-here
  developer:
    password: use-strong-password-here

mysql:
  builtin:
    rootPassword: use-strong-password-here
```

### 3. 资源规划

| 环境 | CPU | 内存 | MySQL 存储 |
|------|-----|------|-----------|
| 开发 | 250m-1000m | 512Mi-1Gi | 5Gi |
| 测试 | 500m-2000m | 1Gi-2Gi | 10Gi |
| 生产 | 2000m-8000m | 4Gi-8Gi | 20Gi+ |

---

## 🎯 下一步

部署完成后，你可以：

1. ✅ 访问管理后台创建 API 产品
2. ✅ 访问开发者门户浏览 MCP Server
3. ✅ 在 Nacos 控制台查看已导入的 MCP
4. ✅ 配置网关路由和策略

---

**需要帮助？** 查看 [README.md](./README.md) 获取更多信息。

