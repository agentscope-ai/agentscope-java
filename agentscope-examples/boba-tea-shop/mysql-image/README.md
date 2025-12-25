# MySQL Custom Image

自定义 MySQL 8.0 镜像，包含云边奶茶铺系统的数据库初始化脚本。

## 📦 镜像信息

- **镜像名称**: `registry.cn-hangzhou.aliyuncs.com/agentscope/mysql`
- **版本**: `8.0.30` / `latest`
- **基础镜像**: `anolis-registry.cn-zhangjiakou.cr.aliyuncs.com/openanolis/mysql:8.0.30-8.6`
- **支持架构**: `linux/amd64`, `linux/arm64`

## 🗂️ 文件结构

```
mysql-image/
├── Dockerfile          # 镜像构建文件
├── build.sh            # 自动化构建脚本（支持多架构）
├── my.cnf              # MySQL 配置文件（UTF-8 字符集）
├── init.sql.template   # 数据库初始化 SQL 模板（支持环境变量）
├── init-db.sh          # 初始化脚本（处理环境变量替换）
├── .dockerignore       # Docker 构建排除文件
└── README.md           # 本文档
```

## 🚀 构建镜像

### 本地构建（当前架构）

```bash
cd mysql-image
sh build.sh
```

### 构建并推送到远端仓库（多架构）

```bash
sh build.sh -r registry.cn-hangzhou.aliyuncs.com/agentscope
```

## 🌐 UTF-8 字符集配置

镜像已配置完整的 UTF-8 支持（`/etc/my.cnf`），确保中文和其他多字节字符正确存储和显示：

```ini
[client]
default-character-set = utf8mb4

[mysql]
default-character-set = utf8mb4

[mysqld]
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci
init_connect = 'SET NAMES utf8mb4'
skip-character-set-client-handshake  # 强制所有连接使用 UTF-8
default-time-zone = '+08:00'         # 时区设置
```

**特性**：
- ✅ 支持中文、日文、韩文、emoji 等所有 Unicode 字符
- ✅ 自动忽略客户端字符集设置，强制使用 utf8mb4
- ✅ 时区默认为 `Asia/Shanghai` (+08:00)

## 📋 初始化内容

镜像启动后会自动执行 `/docker-entrypoint-initdb.d/init-db.sh`，该脚本会：
1. 从环境变量读取数据库配置
2. 使用 `envsubst` 处理 SQL 模板
3. 执行生成的 SQL 初始化数据库

### 1️⃣ 数据库和用户（可通过环境变量配置）

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| `DB_NAME` | 数据库名称 | `multi-agent-demo` |
| `DB_USERNAME` | 数据库用户名 | `multi_agent_demo` |
| `DB_PASSWORD` | 数据库密码 | `multi_agent_demo@321` |

- **数据库**: 由 `DB_NAME` 环境变量指定 (utf8mb4)
- **用户**: 由 `DB_USERNAME` 环境变量指定 @'%'
- **密码**: 由 `DB_PASSWORD` 环境变量指定
- **权限**: 对指定数据库的所有权限

### 2️⃣ 数据表（4张）

| 表名 | 说明 | 初始数据 |
|------|------|---------|
| `users` | 用户表 | 50 个用户 |
| `products` | 产品表 | 9 个奶茶产品 |
| `orders` | 订单表 | 50 个订单 |
| `feedback` | 反馈表 | 50 条反馈 |

## 🔧 使用方法

### Docker 运行

```bash
# 使用默认配置
docker run -d -p 3306:3306 \
  --name mysql \
  -e MYSQL_ROOT_PASSWORD=your-root-password \
  registry.cn-hangzhou.aliyuncs.com/agentscope/mysql:latest

# 使用自定义数据库配置
docker run -d -p 3306:3306 \
  --name mysql \
  -e MYSQL_ROOT_PASSWORD=your-root-password \
  -e DB_NAME=my-database \
  -e DB_USERNAME=my-user \
  -e DB_PASSWORD=my-password \
  registry.cn-hangzhou.aliyuncs.com/agentscope/mysql:latest
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
spec:
  serviceName: mysql
  replicas: 1
  template:
    spec:
      containers:
      - name: mysql
        image: registry.cn-hangzhou.aliyuncs.com/agentscope/mysql:8.0.30
        env:
        - name: MYSQL_ROOT_PASSWORD
          value: "your-root-password"
        # 可选：自定义数据库配置
        - name: DB_NAME
          value: "multi-agent-demo"
        - name: DB_USERNAME
          value: "multi_agent_demo"
        - name: DB_PASSWORD
          value: "multi_agent_demo@321"
        ports:
        - containerPort: 3306
        volumeMounts:
        - name: mysql-data
          mountPath: /var/lib/mysql
  volumeClaimTemplates:
  - metadata:
      name: mysql-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
```

## ✅ 验证

### 1. 检查数据库是否创建

```bash
docker exec mysql mysql -uroot -p<password> -e "SHOW DATABASES;"

# 应该看到 multi-agent-demo
```

### 2. 检查用户是否创建

```bash
docker exec mysql mysql -uroot -p<password> -e "SELECT user,host FROM mysql.user WHERE user='multi_agent_demo';"

# 应该看到 multi_agent_demo | %
```

### 3. 检查表和数据

```bash
docker exec mysql mysql -uroot -p<password> multi-agent-demo -e "SHOW TABLES;"

# 应该看到 users, products, orders, feedback
```

### 4. 验证初始数据

```bash
docker exec mysql mysql -uroot -p<password> multi-agent-demo -e "SELECT COUNT(*) FROM users;"
# 应该返回: 50

docker exec mysql mysql -uroot -p<password> multi-agent-demo -e "SELECT COUNT(*) FROM products;"
# 应该返回: 9

docker exec mysql mysql -uroot -p<password> multi-agent-demo -e "SELECT COUNT(*) FROM orders;"
# 应该返回: 50
```

### 5. 验证 UTF-8 字符集

```bash
# 检查字符集配置
docker exec mysql mysql -uroot -p<password> -e "SHOW VARIABLES LIKE '%character%';"

# 应该看到：
#   character_set_server     = utf8mb4
#   character_set_database   = utf8mb4
#   collation_server         = utf8mb4_unicode_ci

# 测试中文插入和查询
docker exec mysql mysql -uroot -p<password> multi-agent-demo -e "
  INSERT INTO products (id, name, description, category, price, inventory, created_at) 
  VALUES (100, '珍珠奶茶', '经典珍珠奶茶，香甜可口 🧋', '奶茶', 15.00, 100, NOW());
  SELECT id, name, description, category FROM products WHERE id=100;
"

# 应该能正确显示中文和 emoji
```

## 🔑 连接信息

### Root 用户

- **用户名**: `root`
- **密码**: 通过环境变量 `MYSQL_ROOT_PASSWORD` 设置

### 应用用户（通过环境变量配置）

- **用户名**: 通过 `DB_USERNAME` 设置（默认: `multi_agent_demo`）
- **密码**: 通过 `DB_PASSWORD` 设置（默认: `multi_agent_demo@321`）
- **数据库**: 通过 `DB_NAME` 设置（默认: `multi-agent-demo`）
- **权限**: ALL PRIVILEGES

## 📝 注意事项

1. **首次启动**: MySQL 会在首次启动时执行初始化脚本，耗时约 10-30 秒
2. **数据持久化**: 建议挂载 `/var/lib/mysql` 到持久化存储
3. **密码安全**: 生产环境请通过环境变量设置安全的密码
4. **环境变量**: 
   - `DB_NAME`、`DB_USERNAME`、`DB_PASSWORD` 支持自定义数据库配置
   - 如未设置，将使用默认值
5. **字符集**: 
   - ✅ 镜像已配置 `utf8mb4` 字符集，支持中文、emoji 等所有 Unicode 字符
   - ✅ 已启用 `skip-character-set-client-handshake`，强制所有连接使用 UTF-8
   - ✅ 无需在应用端额外配置字符集
6. **时区**: 镜像已设置默认时区为 `Asia/Shanghai` (+08:00)

## 🔄 更新镜像

如果修改了 `init.sql.template`、`init-db.sh` 或 `my.cnf`：

```bash
# 1. 更新 mysql-image/init.sql.template 或其他文件
# 2. 重新构建镜像
sh build.sh -r registry.cn-hangzhou.aliyuncs.com/agentscope

# 3. 重新部署（注意：会重新初始化数据库）
kubectl delete pod -l app=mysql
```

## 📚 相关文档

- [MySQL Docker 官方文档](https://hub.docker.com/_/mysql)
- [MySQL 8.0 参考手册](https://dev.mysql.com/doc/refman/8.0/en/)
- [Kubernetes StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)

