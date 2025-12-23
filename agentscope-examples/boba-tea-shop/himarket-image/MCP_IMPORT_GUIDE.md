# MCP 导入到 Nacos 使用指南

## 📋 功能说明

本脚本支持将 MCP Server 配置批量导入到 Nacos 中。MCP（Model Context Protocol）是一种标准化的协议，用于 AI 应用与外部工具/数据源的集成。

---

## 🎯 使用场景

### 场景 1: 使用内置 MCP 文件（开箱即用）

镜像已内置 5 个常用 MCP Server，无需额外配置：

```bash
#!/bin/bash

# 启动 HiMarket Server Auto-Init 容器，使用内置 MCP
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USER=root \
  -e DB_PASSWORD=yourpassword \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://localhost:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  -e IMPORT_MCP_TO_NACOS=true \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest
```

**内置 MCP Server:**
- `context7` - 文档上下文查询服务
- `git` - Git 仓库操作服务
- `Time` - 时区时间转换服务
- `memory` - 知识图谱管理服务
- `fetch` - 网页内容抓取服务

### 场景 2: 使用自定义 MCP 文件

如需导入自定义 MCP 配置，可挂载自己的文件覆盖内置文件：

```bash
#!/bin/bash

# 启动 HiMarket Server Auto-Init 容器，使用自定义 MCP
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USER=root \
  -e DB_PASSWORD=yourpassword \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://localhost:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  -e IMPORT_MCP_TO_NACOS=true \
  -e MCP_JSON_FILE=/opt/himarket/data/custom-mcp.json \
  -v /path/to/your/custom-mcp.json:/opt/himarket/data/custom-mcp.json:ro \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest
```

### 场景 3: 本地脚本执行

```bash
#!/bin/bash

# 导出环境变量
export HIMARKET_HOST=localhost:8080
export REGISTER_NACOS=true
export NACOS_URL=http://localhost:8848
export NACOS_USERNAME=nacos
export NACOS_PASSWORD=nacos
export IMPORT_MCP_TO_NACOS=true
export MCP_JSON_FILE=/path/to/nacos-mcp.json

# 执行脚本
./init-himarket-local.sh
```

---

## 📄 MCP JSON 文件格式

### 格式 1: 单个 MCP 对象

```json
{
  "serverSpecification": {
    "name": "weather-mcp",
    "version": "1.0.0",
    "description": "天气查询 MCP Server"
  },
  "toolSpecification": {
    "tools": [
      {
        "name": "get_weather",
        "description": "获取指定城市的天气信息",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "城市名称"
            }
          },
          "required": ["city"]
        }
      }
    ]
  },
  "endpointSpecification": {
    "url": "http://weather-mcp:3000",
    "protocol": "stdio"
  }
}
```

### 格式 2: MCP 数组（批量导入）

```json
[
  {
    "serverSpecification": {
      "name": "weather-mcp",
      "version": "1.0.0",
      "description": "天气查询 MCP Server"
    },
    "toolSpecification": {
      "tools": [...]
    },
    "endpointSpecification": {
      "url": "http://weather-mcp:3000",
      "protocol": "stdio"
    }
  },
  {
    "serverSpecification": {
      "name": "database-mcp",
      "version": "1.0.0",
      "description": "数据库查询 MCP Server"
    },
    "toolSpecification": {
      "tools": [...]
    }
  }
]
```

---

## 🔧 必需的环境变量

| 变量 | 必需 | 说明 | 示例 |
|------|------|------|------|
| `IMPORT_MCP_TO_NACOS` | ✅ 是 | 启用 MCP 导入功能 | `true` |
| `REGISTER_NACOS` | ✅ 是 | 必须先注册 Nacos 实例 | `true` |
| `MCP_JSON_FILE` | ⭐ 可选 | MCP JSON 文件路径 | `/opt/himarket/data/nacos-mcp.json`（默认） |
| `NACOS_URL` | ✅ 是 | Nacos 服务地址 | `http://localhost:8848` |
| `NACOS_USERNAME` | ✅ 是 | Nacos 用户名 | `nacos` |
| `NACOS_PASSWORD` | ✅ 是 | Nacos 密码 | `nacos` |

**注意：** 
- MCP 导入功能目前仅支持用户名密码认证，不支持 AccessKey/SecretKey
- 镜像已内置 `/opt/himarket/data/nacos-mcp.json`，包含 5 个常用 MCP Server
- 如需使用自定义文件，设置 `MCP_JSON_FILE` 并通过 `-v` 挂载

---

## 🚀 Docker 完整示例

### 方式 1: 使用内置 MCP（推荐）

镜像已内置 5 个常用 MCP Server，无需额外准备文件。

#### 创建启动脚本

```bash
#!/bin/bash
# quick-start-with-mcp.sh

docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  \
  # 数据库配置
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=himarket \
  -e DB_USER=root \
  -e DB_PASSWORD=yourpassword \
  \
  # Nacos 配置
  -e REGISTER_NACOS=true \
  -e NACOS_NAME=nacos-demo \
  -e NACOS_URL=http://your-nacos-host:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  \
  # MCP 导入配置（使用内置文件）
  -e IMPORT_MCP_TO_NACOS=true \
  \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest

echo "容器已启动！"
echo "内置 MCP Server: context7, git, Time, memory, fetch"
echo "查看日志: docker logs -f himarket-server"
```

#### 运行脚本

```bash
chmod +x quick-start-with-mcp.sh
./quick-start-with-mcp.sh
```

#### 查看日志

```bash
docker logs -f himarket-server
```

---

### 方式 2: 使用自定义 MCP 文件

如需导入自定义 MCP 配置：

#### 1. 准备自定义 MCP 数据文件

创建 `custom-mcp.json` 文件（参考上面的格式）。

#### 2. 创建启动脚本

```bash
#!/bin/bash
# quick-start-custom-mcp.sh

docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  \
  # 数据库配置
  -e DB_HOST=host.docker.internal \
  -e DB_PASSWORD=yourpassword \
  \
  # Nacos 配置
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://your-nacos-host:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  \
  # MCP 导入配置（使用自定义文件）
  -e IMPORT_MCP_TO_NACOS=true \
  -e MCP_JSON_FILE=/opt/himarket/data/custom-mcp.json \
  \
  # 挂载自定义 MCP 文件
  -v $(pwd)/custom-mcp.json:/opt/himarket/data/custom-mcp.json:ro \
  \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest

echo "容器已启动！使用自定义 MCP 配置"
echo "查看日志: docker logs -f himarket-server"
```

#### 3. 运行脚本

```bash
chmod +x quick-start-custom-mcp.sh
./quick-start-custom-mcp.sh
```

---

## 📊 执行流程

```
1. 检查依赖（curl, jq, python）
   ↓
2. 注册 Nacos 实例到 HiMarket
   ↓
3. 登录 Nacos 获取 accessToken
   ↓
4. 读取 MCP JSON 文件
   ↓
5. 判断文件格式（单个对象 / 数组）
   ↓
6. 遍历 MCP 配置
   ├─ 提取 serverSpecification（必需）
   ├─ 提取 toolSpecification（可选）
   └─ 提取 endpointSpecification（可选）
   ↓
7. URL 编码参数（使用 jq @uri）
   ↓
8. 调用 Nacos MCP API
   POST /nacos/v3/admin/ai/mcp
   Header: accessToken
   Body: form-urlencoded
   ↓
9. 幂等性处理
   ├─ HTTP 200 → 创建成功
   ├─ HTTP 409 → 已存在（跳过）
   └─ 其他 → 失败
   ↓
10. 输出统计结果
    成功: X, 跳过: Y, 失败: Z
```

---

## ⚠️ 常见问题

### 1. **jq 工具未安装**

**错误信息：**
```
jq 未安装，请先安装
```

**解决方案：**
- Docker 镜像已包含 jq（通过系统包管理器安装）
- 本地执行需安装 jq: `brew install jq` (macOS) 或 `apt-get install jq` (Linux)

### 2. **MCP JSON 文件不存在**

**错误信息：**
```
MCP 数据文件不存在: /path/to/nacos-mcp.json
```

**解决方案：**
- 检查 `MCP_JSON_FILE` 环境变量路径是否正确
- 在 Docker 中确保文件已正确挂载: `-v /host/path:/container/path:ro`

### 3. **Nacos 登录失败**

**错误信息：**
```
无法从 Nacos 登录响应中提取 accessToken
```

**解决方案：**
- 检查 `NACOS_URL` 是否正确（格式：`http://host:port` 或 `https://host:port`）
- 检查 `NACOS_USERNAME` 和 `NACOS_PASSWORD` 是否正确
- 确认 Nacos 服务已启动并可访问

### 4. **MCP 创建失败**

**错误信息：**
```
创建 MCP 'xxx' 失败（HTTP 500）
```

**解决方案：**
- 检查 MCP JSON 文件格式是否正确
- 查看 Nacos 日志获取详细错误信息
- 确认 Nacos 版本支持 MCP API（需要 Nacos >= 2.3.0 企业版）

### 5. **幂等性：MCP 已存在**

**日志信息：**
```
[✓] MCP 'weather-mcp' 已存在，跳过创建（幂等）
```

**说明：**
- 这是正常行为，不是错误
- 脚本支持重复执行，已存在的 MCP 会自动跳过

---

## 🔍 调试技巧

### 1. 查看详细日志

```bash
# Docker 容器日志
docker logs -f himarket-server

# 本地执行添加 -x 参数
bash -x ./init-himarket-local.sh
```

### 2. 验证 MCP JSON 格式

```bash
# 使用 jq 验证 JSON 格式
jq . nacos-mcp.json

# 检查是否为数组
jq 'type' nacos-mcp.json
# 输出: "array" 或 "object"
```

### 3. 手动测试 Nacos MCP API

```bash
# 1. 登录 Nacos
LOGIN_RESP=$(curl -sS -X POST "http://localhost:8848/nacos/v1/auth/login" \
  -d "username=nacos" \
  -d "password=nacos")

ACCESS_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken')

# 2. 创建 MCP
curl -X POST "http://localhost:8848/nacos/v3/admin/ai/mcp" \
  -H "accessToken: $ACCESS_TOKEN" \
  -H "Content-Type: application/x-www-form-urlencoded; charset=UTF-8" \
  -d "serverSpecification=%7B%22name%22%3A%22test-mcp%22%7D"
```

---

## 📚 相关资源

- [MCP 协议规范](https://modelcontextprotocol.io/)
- [Nacos 官方文档](https://nacos.io/zh-cn/docs/what-is-nacos.html)
- [HiMarket 部署文档](./README.md)

---

## 🎉 完整示例

### 场景：本地开发环境一键启动 + 导入 MCP

```bash
#!/bin/bash
# all-in-one.sh - 完整的 HiMarket + Nacos + MCP 启动脚本

set -e

# 1. 准备 MCP 数据文件
cat > nacos-mcp.json <<'EOF'
[
  {
    "serverSpecification": {
      "name": "weather-mcp",
      "version": "1.0.0",
      "description": "天气查询服务"
    },
    "toolSpecification": {
      "tools": [
        {
          "name": "get_weather",
          "description": "获取天气信息"
        }
      ]
    }
  }
]
EOF

# 2. 启动容器
docker run -d \
  --name himarket-server \
  -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PASSWORD=yourpassword \
  -e REGISTER_NACOS=true \
  -e NACOS_URL=http://your-nacos:8848 \
  -e NACOS_USERNAME=nacos \
  -e NACOS_PASSWORD=nacos \
  -e IMPORT_MCP_TO_NACOS=true \
  -e MCP_JSON_FILE=/opt/himarket/mcp-data/nacos-mcp.json \
  -v $(pwd)/nacos-mcp.json:/opt/himarket/mcp-data/nacos-mcp.json:ro \
  registry.cn-hangzhou.aliyuncs.com/agentscope/himarket-server-auto-init:latest

echo "✅ HiMarket Server 启动成功！"
echo ""
echo "查看日志: docker logs -f himarket-server"
echo "访问地址: http://localhost:8080"
echo ""
echo "管理员账号: admin / admin"
echo "开发者账号: demo / demo123"
```

运行：

```bash
chmod +x all-in-one.sh
./all-in-one.sh
```

---

**祝你使用愉快！** 🎉

