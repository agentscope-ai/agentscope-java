# HiMarket Auto-Init 快速部署指南

## 🎯 5 分钟快速上手

### 步骤 1: 准备 Kubernetes 集群

确保你有可用的 Kubernetes 集群：

```bash
# 检查集群状态
kubectl cluster-info

# 查看节点
kubectl get nodes
```

---

### 步骤 2: 安装 HiMarket

```bash
cd himarket/deploy/himarket-auto-init

# 使用默认配置（包含 MCP 自动初始化）
helm install himarket .

# 或使用最小化配置（不含 MCP）
helm install himarket . -f values-minimal.yaml

# 或使用自定义 Server 镜像
helm install himarket . \
  --set server.image.hub=your-registry.com/your-namespace \
  --set server.image.repository=himarket-server-auto-init \
  --set server.image.tag=v1.0.0
```

---

### 步骤 3: 等待部署完成

```bash
# 查看 Pod 状态
kubectl get pods -w

# 等待所有 Pod READY 变为 1/1
# NAME                                READY   STATUS    RESTARTS
# himarket-server-xxx                 1/1     Running   0
# himarket-admin-xxx                  1/1     Running   0
# himarket-frontend-xxx               1/1     Running   0
# himarket-mysql-0                    1/1     Running   0
```

---

### 步骤 4: 查看初始化日志

```bash
# 查看 Server 初始化日志
kubectl logs -f -l app=himarket-server | grep "✓\|步骤"
```

**预期输出：**
```
[17:11:00] ========================================
[17:11:00] 步骤 1: 注册管理员账号
[17:11:00] ========================================
[✓] 管理员账号注册成功

[17:11:01] ========================================
[17:11:01] 步骤 2: 管理员登录
[17:11:01] ========================================
[✓] 管理员登录成功

...

[17:11:10] ========================================
[17:11:10] ✓ HiMarket 初始化完成！
[17:11:10] ========================================
```

---

### 步骤 5: 访问 HiMarket

```bash
# 获取服务列表
kubectl get svc

# 方式 1: Port Forward (推荐本地开发)
kubectl port-forward svc/himarket-admin 8001:80      # 管理后台
kubectl port-forward svc/himarket-frontend 3000:80   # 开发者门户
kubectl port-forward svc/himarket-server 8080:80     # 后端 API

# 方式 2: LoadBalancer (如果配置了)
export ADMIN_IP=$(kubectl get svc himarket-admin -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
export FRONTEND_IP=$(kubectl get svc himarket-frontend -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
```

**访问地址：**
- 管理后台: http://localhost:8001
- 开发者门户: http://localhost:3000
- 后端 API: http://localhost:8080

**登录信息（管理后台）：**
- 用户名: `admin`
- 密码: `admin`

**登录信息（开发者门户）：**
- 用户名: `demo`
- 密码: `demo123`

---

## 🎉 完成！

现在你可以：
1. ✅ 访问**管理后台** (http://localhost:8001) 创建 API 产品
2. ✅ 访问**开发者门户** (http://localhost:3000) 浏览和订阅 API
3. ✅ 配置开发者权限
4. ✅ 发布产品到门户
5. ✅ 管理 Nacos 和网关实例

### 组件说明

| 组件 | 端口 | 说明 |
|------|------|------|
| **himarket-server** | 8080 | 后端 API 服务（自动初始化） |
| **himarket-admin** | 8001 | 管理后台前端 |
| **himarket-frontend** | 3000 | 开发者门户前端 |
| **MySQL** | 3306 | 数据库 |

---

## 📝 下一步

### 启用 Nacos 和 Gateway

如果需要注册 Nacos 和网关：

```bash
helm upgrade himarket ./himarket-auto-init \
  --set nacos.enabled=true \
  --set nacos.serverUrl=http://nacos:8848 \
  --set nacos.username=nacos \
  --set nacos.password=nacos \
  --set gateway.enabled=true \
  --set gateway.type=HIGRESS \
  --reuse-values
```

### 切换到外部 MySQL

```bash
# 升级为外部 MySQL
helm upgrade himarket ./himarket-auto-init \
  --set mysql.enabled=false \
  --set database.host=your-mysql-host \
  --set database.password=your-password \
  --reuse-values
```

---

## ⚠️ 注意事项

1. **默认启用 MCP**
   - 默认会自动导入和上架 5 个 MCP Server
   - 如果不需要，使用 `values-minimal.yaml`

2. **资源要求**
   - 最小: 500m CPU + 512Mi 内存
   - 推荐: 1000m CPU + 2Gi 内存

3. **MySQL 存储**
   - 使用 PVC 持久化数据
   - 卸载前备份重要数据

4. **商业化 Nacos**
   - 确保 Pod 出口 IP 在白名单中
   - 同时需要提供 AccessKey 和用户名密码

---

## 🛠️ 故障排查

### Pod 启动失败

```bash
# 查看详细信息
kubectl describe pod -l app.kubernetes.io/name=himarket-auto-init

# 常见问题：
# - ImagePullBackOff → 检查镜像地址
# - CrashLoopBackOff → 查看日志
# - Pending → 检查资源和 PVC
```

### 初始化失败

```bash
# 查看完整日志
kubectl logs -l app.kubernetes.io/name=himarket-auto-init --tail=500

# 重新初始化（删除 Pod 触发重启）
kubectl delete pod -l app.kubernetes.io/name=himarket-auto-init
```

### 连接问题

```bash
# 测试 MySQL 连接
kubectl exec -it <pod-name> -- mysql -h <mysql-host> -u root -p

# 测试 Nacos 连接
kubectl exec -it <pod-name> -- curl http://nacos:8848/nacos/
```

---

**更多帮助请查看 [README.md](./README.md) 和 [USAGE.md](./USAGE.md)**

