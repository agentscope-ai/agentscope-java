# 🐳 AgentScope Boba Tea Shop - Docker 部署指南

本指南将帮助您使用 Docker Compose 一键部署 AgentScope 多智能体系统。

## 📋 目录

- [前置要求](#前置要求)
- [配置说明](#配置说明)
- [一键部署](#一键部署)
- [访问服务](#访问服务)
- [高级配置](#高级配置)

---

## 📦 前置要求

### 环境要求

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| Docker | 20.10+ | 容器运行环境 |
| Docker Compose | 2.0+ | 容器编排工具 |

### 需要准备的 API Keys 和配置

在部署前，请确保您已获取以下服务的凭证：

| 配置项                | 必需 | 说明 | 获取方式                                                                                   |
|--------------------|-----|------|----------------------------------------------------------------------------------------|
| DashScope API Key  | ✅ | 阿里云大模型服务 | [阿里云 DashScope 模型服务](https://bailian.console.aliyun.com/?tab=model#/model-market)      |
| DashScope Index ID | ✅ | RAG 知识库索引 | [阿里云 DashScope 应用开发（知识库）](https://bailian.console.aliyun.com/?tab=app#/knowledge-base) |
| Mem0 API Key       | ✅ | 记忆服务 | [Mem0 官网](https://app.mem0.ai/)                                                            |

> 💡 **提示**：RAG 知识库索引可以使用 `consult-sub-agent/src/main/resources/knowledge` 目录下的文件构建。

---

## ⚙️ 配置说明

### 步骤 1：克隆项目并进入目录

```bash
git clone <repository-url>
cd agentscope-java/agentscope-examples/boba-tea-shop
```

### 步骤 2：创建环境配置文件

```bash
# 复制配置模板
cp docker-env.example .env
```

### 步骤 3：修改 .env 文件

编辑 `.env` 文件，替换所有 `your_xxx_here` 占位符：

```bash
# 使用您喜欢的编辑器
vim .env
# 或
code .env
```

### 需要替换的配置项

#### 1. 模型配置（必须修改）

**DashScope（推荐）：**

```env
MODEL_PROVIDER=dashscope
MODEL_API_KEY=sk-xxxxxxxxxxxxxxxx
MODEL_NAME=qwen-max
```

**OpenAI：**

```env
MODEL_PROVIDER=openai
MODEL_API_KEY=sk-xxxxxxxxxxxxxxxx
MODEL_NAME=gpt-4
MODEL_BASE_URL=https://api.openai.com/v1
```

#### 2. DashScope 知识库配置（必须修改）

```env
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx
DASHSCOPE_INDEX_ID=your_index_id
```

#### 3. Mem0 配置（必须修改）

```env
MEM0_API_KEY=m0-xxxxxxxxxxxxxxxx
```

### 可选配置项

#### 镜像配置

```env
# 镜像仓库地址（社区已构建好的镜像，可直接使用）
IMAGE_REGISTRY=registry.cn-hangzhou.aliyuncs.com/agentscope
# 镜像标签
IMAGE_TAG=1.0.1
```

#### 数据库配置

```env
MYSQL_PORT=3306
DB_NAME=multi_agent_demo
DB_USERNAME=multi_agent_demo
DB_PASSWORD=multi_agent_demo@321
```

#### 服务端口配置

```env
BUSINESS_MCP_SERVER_PORT=10002
CONSULT_SUB_AGENT_PORT=10005
BUSINESS_SUB_AGENT_PORT=10006
SUPERVISOR_AGENT_PORT=10008
```

---

## 🚀 一键部署

### 方式一：使用默认配置启动

```bash
# 1. 创建环境配置文件
cp docker-env.example .env

# 2. 编辑 .env 文件，填入必要的 API Keys
vim .env

# 3. 启动所有服务
docker-compose up -d
```

### 方式二：前台启动（查看日志）

```bash
# 前台启动，可实时查看日志
docker-compose up
```

### 方式三：仅启动部分服务

```bash
# 仅启动基础设施（MySQL + Nacos）
docker-compose up -d mysql nacos-server

# 仅启动某个服务
docker-compose up -d supervisor-agent
```

### 部署输出示例

成功部署后，您可以使用以下命令查看服务状态：

```bash
$ docker-compose ps

NAME                              STATUS                   PORTS
agentscope-mysql                  Up (healthy)             0.0.0.0:3306->3306/tcp
agentscope-nacos                  Up (healthy)             0.0.0.0:8080->8080/tcp, 0.0.0.0:8848->8848/tcp, 0.0.0.0:9848->9848/tcp
agentscope-business-mcp-server    Up                       0.0.0.0:10002->10002/tcp
agentscope-consult-sub-agent      Up                       0.0.0.0:10005->10005/tcp
agentscope-business-sub-agent     Up                       0.0.0.0:10006->10006/tcp
agentscope-supervisor-agent       Up                       0.0.0.0:10008->10008/tcp
```

---

## 🌐 访问服务

### 前端界面

部署成功后，访问以下地址：

- **前端界面**: http://localhost:10008
- **API 接口**: http://localhost:10008/api/...

### 功能测试

1. 访问前端页面：http://localhost:10008
2. 点击右上角 **设置** 图标
3. 配置后端访问地址（http://localhost:10008）与用户ID并保存
4. 与 Agent 对话

---

## 🔧 高级配置

### 自定义网络

如需将 AgentScope 服务集成到现有 Docker 网络：

```yaml
# 在 docker-compose.yml 中修改
networks:
  agentscope-network:
    external: true
    name: my-existing-network
```

### 使用外部 MySQL

如果您已有 MySQL 服务，可以：

1. 注释掉 `docker-compose.yml` 中的 mysql 服务
2. 修改 `.env` 文件中的数据库配置
3. 确保外部 MySQL 可从 Docker 网络访问

### 使用外部 Nacos

如果您已有 Nacos 服务，可以：

1. 注释掉 `docker-compose.yml` 中的 nacos-server 服务
2. 修改各服务的 `NACOS_SERVER_ADDR` 环境变量
3. 移除服务对 nacos-server 的 depends_on 依赖

---

