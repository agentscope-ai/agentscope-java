# HiMarket Server Auto-Init Docker 镜像

## 概述

这是一个基于 HiMarket Server 原始镜像构建的增强版本，添加了自动初始化功能。容器启动后会自动执行初始化脚本，完成管理员账号、Portal、开发者账号的创建，并可选地注册 Nacos 和网关实例。

## 目录结构

```
init-himarket/
├── Dockerfile              # Docker 镜像构建文件
├── build.sh               # 构建脚本
├── entrypoint.sh          # 容器启动脚本
├── init-himarket-local.sh # 初始化脚本（从 helm/scripts 复制）
└── README.md              # 本文档
```

## 构建镜像

### 基础构建

```bash
cd /Users/zhuoguang/Documents/Code/agentscope-demo/himarket/deploy/init-himarket
./build.sh
```

### 自定义镜像名称和标签

```bash
IMAGE_NAME=my-himarket IMAGE_TAG=v1.0.0 ./build.sh
```

### 自定义镜像仓库

```bash
REGISTRY=my-registry.com/mygroup ./build.sh
```

## 使用方法

### 1. 基础运行（不自动初始化）

适用于已有数据库或不需要初始化的场景。

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e AUTO_INIT=false \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

### 2. 自动初始化（默认配置）

启动后自动创建管理员账号、Portal 和开发者账号。

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

### 3. 自动初始化 + 注册开源 Nacos

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://nacos:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

### 4. 自动初始化 + 注册商业化 Nacos（AccessKey/SecretKey）

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  -e REGISTER_NACOS=true \
  -e NACOS_NAME=nacos-commercial \
  -e NACOS_URL=mse-xxx.nacos-ans.mse.aliyuncs.com \
  -e NACOS_ACCESS_KEY=LTAI5t... \
  -e NACOS_SECRET_KEY=xxx... \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

### 5. 自动初始化 + 注册网关

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  -e REGISTER_GATEWAY=true \
  -e GATEWAY_NAME=higress \
  -e GATEWAY_URL=http://higress:8001 \
  -e GATEWAY_USERNAME=admin \
  -e GATEWAY_PASSWORD=admin \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

### 6. 自动初始化 + 导入 MCP 到 Nacos（使用内置文件）

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://nacos:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  -e IMPORT_MCP_TO_NACOS=true \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

**内置 MCP Server (5 个):**
- `context7` - 文档上下文查询
- `git` - Git 仓库操作
- `Time` - 时区时间转换
- `memory` - 知识图谱管理
- `fetch` - 网页内容抓取

**自定义 MCP 文件（可选）:**

如需使用自定义 MCP 配置，可通过挂载覆盖内置文件：

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  ... \
  -e IMPORT_MCP_TO_NACOS=true \
  -e MCP_JSON_FILE=/opt/himarket/data/custom-mcp.json \
  -v /path/to/your/custom-mcp.json:/opt/himarket/data/custom-mcp.json:ro \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

> **注意：** 
> - MCP 导入需要 `REGISTER_NACOS=true`（必须先注册 Nacos）
> - 需要提供 `NACOS_USERNAME` 和 `NACOS_PASSWORD`（MCP API 认证）
> - 镜像已内置 5 个常用 MCP Server，无需额外挂载
> - 详细文档：[MCP_IMPORT_GUIDE.md](./MCP_IMPORT_GUIDE.md)

### 7. 完整配置示例

```bash
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=mysql \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=password \
  -e AUTO_INIT=true \
  -e INIT_DELAY=15 \
  -e HIMARKET_HOST=localhost:8080 \
  -e HIMARKET_FRONTEND_URL=http://localhost:3000 \
  -e ADMIN_USERNAME=admin \
  -e ADMIN_PASSWORD=admin123 \
  -e DEVELOPER_USERNAME=developer \
  -e DEVELOPER_PASSWORD=dev123 \
  -e PORTAL_NAME=my-portal \
  -e REGISTER_NACOS=true \
  -e NACOS_NAME=nacos-prod \
  -e NACOS_URL=http://nacos:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  -e REGISTER_GATEWAY=true \
  -e GATEWAY_NAME=higress \
  -e GATEWAY_URL=http://higress:8001 \
  -e GATEWAY_USERNAME=admin \
  -e GATEWAY_PASSWORD=admin \
  opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
```

## 环境变量说明

### 数据库配置（必需）

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `DB_HOST` | - | 数据库主机地址 |
| `DB_PORT` | `3306` | 数据库端口 |
| `DB_NAME` | - | 数据库名称 |
| `DB_USERNAME` | - | 数据库用户名 |
| `DB_PASSWORD` | - | 数据库密码 |

### 初始化控制

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `AUTO_INIT` | `true` | 是否自动执行初始化脚本 |
| `INIT_DELAY` | `10` | 等待服务启动的延迟时间（秒） |

### HiMarket 配置

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `HIMARKET_HOST` | `localhost:8080` | HiMarket Server 地址 |
| `HIMARKET_FRONTEND_URL` | `http://localhost:3000` | 前端访问地址 |
| `ADMIN_USERNAME` | `admin` | 管理员用户名 |
| `ADMIN_PASSWORD` | `admin` | 管理员密码 |
| `DEVELOPER_USERNAME` | `demo` | 开发者用户名 |
| `DEVELOPER_PASSWORD` | `demo123` | 开发者密码 |
| `PORTAL_NAME` | `demo` | Portal 名称 |

### Nacos 配置（可选）

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `REGISTER_NACOS` | `false` | 是否注册 Nacos 实例 |
| `NACOS_NAME` | `nacos-demo` | Nacos 实例名称 |
| `NACOS_URL` | `http://localhost:8848` | Nacos 服务地址 |
| `NACOS_USERNAME` | `空` | Nacos 用户名（可选） |
| `NACOS_PASSWORD` | `空` | Nacos 密码（可选） |
| `NACOS_ACCESS_KEY` | `空` | AccessKey（可选，商业化） |
| `NACOS_SECRET_KEY` | `空` | SecretKey（可选，商业化） |

### 网关配置（可选）

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `REGISTER_GATEWAY` | `false` | 是否注册网关实例 |
| `GATEWAY_TYPE` | `HIGRESS` | 网关类型：`HIGRESS` 或 `APIG_AI` |
| `GATEWAY_NAME` | `higress-demo` | 网关实例名称 |
| **Higress 网关配置** | | |
| `GATEWAY_URL` | `http://localhost:8080` | Higress 控制台地址 |
| `GATEWAY_USERNAME` | `admin` | Higress 用户名 |
| `GATEWAY_PASSWORD` | `admin` | Higress 密码 |
| **AI 网关配置** | | |
| `APIG_REGION` | `cn-hangzhou` | 阿里云 AI 网关所在区域 |
| `APIG_ACCESS_KEY` | `空` | 阿里云 AccessKey |
| `APIG_SECRET_KEY` | `空` | 阿里云 SecretKey |

### MCP 导入配置（可选）

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `IMPORT_MCP_TO_NACOS` | `false` | 是否导入 MCP 到 Nacos |
| `MCP_JSON_FILE` | `/opt/himarket/data/nacos-mcp.json` | MCP JSON 数据文件路径（镜像内置） |

> **注意：** 
> - MCP 导入需要 `REGISTER_NACOS=true`（必须先注册 Nacos）
> - 需要提供 `NACOS_USERNAME` 和 `NACOS_PASSWORD`（MCP API 认证）
> - 镜像已内置 5 个常用 MCP Server，开箱即用
> - 如需使用自定义 MCP，可通过 `-v` 挂载覆盖内置文件
> - 仅依赖 `curl` 和 `jq`，无需额外工具
> - 详细文档请参考：[MCP_IMPORT_GUIDE.md](./MCP_IMPORT_GUIDE.md)

## 在 Docker Compose 中使用

可以在现有的 `docker-compose.yml` 中替换 `himarket-server` 服务：

```yaml
services:
  himarket-server:
    image: opensource-registry.cn-hangzhou.cr.aliyuncs.com/higress-group/himarket-server-auto-init:latest
    container_name: himarket-server
    environment:
      # 数据库配置
      - DB_HOST=${DB_HOST}
      - DB_PORT=${DB_PORT}
      - DB_NAME=${DB_NAME}
      - DB_USERNAME=${DB_USERNAME}
      - DB_PASSWORD=${DB_PASSWORD}
      # 自动初始化配置
      - AUTO_INIT=true
      - INIT_DELAY=15
      # HiMarket 配置
      - HIMARKET_HOST=himarket-server:8080
      - HIMARKET_FRONTEND_URL=http://localhost:5173
      # Nacos 配置（可选）
      - REGISTER_NACOS=true
      - NACOS_URL=http://nacos:8848
      - NACOS_USERNAME=nacos
      - NACOS_PASSWORD=nacos
      # 网关配置（可选）
      - REGISTER_GATEWAY=true
      - GATEWAY_URL=http://higress:8001
      - GATEWAY_USERNAME=admin
      - GATEWAY_PASSWORD=admin
    ports:
      - "8081:8080"
    networks:
      - himarket-network
    depends_on:
      - mysql
      - nacos
      - higress
    restart: always
```

## 注意事项

1. **数据库准备**: 确保数据库已创建并可访问
2. **初始化延迟**: `INIT_DELAY` 应根据实际环境调整，确保服务完全启动后再执行初始化
3. **幂等性**: 初始化脚本支持幂等性，可以多次执行
4. **网络连接**: 如果注册 Nacos 或网关，确保网络可达
5. **商业化 Nacos**: 使用商业化 Nacos 时，可以只提供 `NACOS_ACCESS_KEY` 和 `NACOS_SECRET_KEY`，无需提供用户名密码

## 查看日志

```bash
# 查看容器日志
docker logs -f himarket-server

# 查看初始化过程
docker logs himarket-server 2>&1 | grep -A 100 "开始执行自动初始化"
```

## 故障排除

### 初始化失败

1. 检查服务是否完全启动：
   ```bash
   curl http://localhost:8080/actuator/health
   ```

2. 增加初始化延迟：
   ```bash
   -e INIT_DELAY=20
   ```

3. 查看详细日志：
   ```bash
   docker logs himarket-server
   ```

### Nacos 或网关注册失败

1. 检查网络连通性
2. 验证认证信息是否正确
3. 确认 Nacos/网关服务已启动

## 相关文档

- [初始化脚本文档](../helm/scripts/LOCAL_INIT_README.md)
- [快速开始指南](../helm/scripts/QUICKSTART.md)
- [更新日志](../helm/scripts/CHANGELOG.md)

