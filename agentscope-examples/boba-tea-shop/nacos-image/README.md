# Nacos Server Image with Auto Password Initialization

基于 Nacos v3.1.1 官方镜像，增加了自动设置 admin 密码的功能。

## 功能特性

- 基础镜像: `nacos-registry.cn-hangzhou.cr.aliyuncs.com/nacos/nacos-server:v3.1.1`
- 自动探测 Nacos 服务启动状态（监听 8848 端口）
- 服务就绪后自动设置 admin 用户密码为 `nacos`
- 支持多架构构建 (amd64/arm64)

## 工作原理

1. 容器启动时，后台运行 `init-password.sh` 脚本
2. 脚本持续探测 localhost:8848 端口是否开放
3. 端口开放后，等待额外 10 秒确保 API 完全就绪
4. 执行 curl 命令设置 admin 密码：
   ```bash
   curl -X POST "http://localhost:8080/v3/auth/user/admin" \
       -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
       -d "password=nacos"
   ```

## 构建镜像

### 本地构建

```bash
cd nacos-image
chmod +x build.sh
./build.sh
```

### 推送到远程仓库

```bash
./build.sh -r registry.cn-hangzhou.aliyuncs.com/agentscope --push
```

### 构建选项

```
Usage: ./build.sh [OPTIONS]

OPTIONS:
    -v, --version VERSION       指定镜像版本标签 (默认: 3.1.1)
    -r, --registry REGISTRY     指定 Docker 仓库地址
    --push                      构建后推送镜像
    --no-buildx                 禁用 Docker Buildx (仅构建当前平台)
    -h, --help                  显示帮助信息
```

## 运行容器

### 单机模式

```bash
docker run -d -p 8848:8848 \
    --name nacos \
    -e MODE=standalone \
    -e NACOS_AUTH_ENABLE=true \
    nacos-server:3.1.1
```

### 查看初始化日志

```bash
docker logs -f nacos | grep init-password
```

### 访问 Nacos 控制台

- URL: http://localhost:8848/nacos
- 用户名: nacos
- 密码: nacos

## 文件说明

| 文件 | 说明 |
|------|------|
| `Dockerfile` | Docker 镜像构建文件 |
| `init-password.sh` | 密码初始化脚本 |
| `docker-entrypoint.sh` | 容器入口脚本 |
| `build.sh` | 构建脚本 |

## 环境变量

继承自 Nacos 官方镜像的所有环境变量，常用的有：

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `MODE` | 运行模式 (standalone/cluster) | cluster |
| `NACOS_AUTH_ENABLE` | 是否启用认证 | false |
| `SPRING_DATASOURCE_PLATFORM` | 数据源类型 | embedded |
| `MYSQL_SERVICE_HOST` | MySQL 主机地址 | - |
| `MYSQL_SERVICE_PORT` | MySQL 端口 | 3306 |
| `MYSQL_SERVICE_DB_NAME` | 数据库名 | nacos |
| `MYSQL_SERVICE_USER` | 数据库用户 | - |
| `MYSQL_SERVICE_PASSWORD` | 数据库密码 | - |

## 注意事项

1. 密码初始化脚本会在后台运行，不会阻塞 Nacos 的正常启动
2. 如果 Nacos 在 2 分钟内未能启动，密码初始化脚本会超时退出
3. 如果密码已经设置过，API 可能返回非 200 状态码，这是正常的

