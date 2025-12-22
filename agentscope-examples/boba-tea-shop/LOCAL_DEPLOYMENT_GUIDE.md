# 🚀 AgentScope Boba Tea Shop - 本地部署指南

本指南将帮助您在本地环境快速部署和运行 AgentScope 多智能体系统。

## 📋 目录

- [前置要求](#前置要求)
- [环境准备](#环境准备)
- [配置说明](#配置说明)
- [快速开始](#快速开始)
- [常用命令](#常用命令)
- [常见问题](#常见问题)

---

## 📦 前置要求

### 运行环境

| 组件 | 最低版本 | 说明 |
|------|---------|------|
| JDK | 17+ | Java 运行环境 |
| Node.js | 18+ | 前端构建（可选） |
| Maven | 3.6+ | Java 项目构建 |

### 外部服务依赖

以下服务需要预先部署或使用云服务：

| 服务 | 必需 | 说明 |
|------|-----|------|
| MySQL | ✅ | 数据存储 |
| Nacos | ✅ | 服务注册与发现 |

### 需要准备的 API Keys

| 配置项 | 必需 | 说明 | 获取方式 |
|-------|-----|------|---------|
| MODEL_API_KEY | ✅ | LLM 模型 API Key | [阿里云 DashScope](https://bailian.console.aliyun.com/?tab=model#/model-market) |
| DASHSCOPE_API_KEY | ✅ | 知识库检索 API Key | 同上 |
| DASHSCOPE_INDEX_ID | ✅ | RAG 知识库索引 ID | [阿里云知识库](https://bailian.console.aliyun.com/?tab=app#/knowledge-base) |
| MEM0_API_KEY | ✅ | 记忆服务 API Key | [Mem0 官网](https://app.mem0.ai/) |

> 💡 **提示**：RAG 知识库索引可以使用 `consult-sub-agent/src/main/resources/knowledge` 目录下的文件构建。


---

## 🛠 环境准备

### 步骤 1：克隆项目

```bash
git clone <repository-url>
cd agentscope-java/agentscope-examples/boba-tea-shop
```

### 步骤 2：创建配置文件

```bash
# 复制配置模板
cp local-env.example local-env.sh
```

### 步骤 3：编辑配置文件

使用您喜欢的编辑器修改 `local-env.sh`：

```bash
vim local-env.sh
# 或
code local-env.sh
```

---

## ⚙️ 配置说明

### MySQL 配置

```bash
export DB_HOST=localhost          # MySQL 主机地址
export DB_PORT=3306               # MySQL 端口
export DB_NAME=multi_agent_demo   # 数据库名称
export DB_USERNAME=multi_agent_demo
export DB_PASSWORD=multi_agent_demo@321
```

### Nacos 配置

```bash
export NACOS_SERVER_ADDR=localhost:8848  # Nacos 服务地址
export NACOS_NAMESPACE=public            # 命名空间
export NACOS_USERNAME=nacos
export NACOS_PASSWORD=nacos
export NACOS_REGISTER_ENABLED=true       # 是否启用服务注册
```

### LLM 模型配置

```bash
# 模型提供商: dashscope 或 openai
export MODEL_PROVIDER=dashscope

# API Key（⚠️ 必须配置）
export MODEL_API_KEY=sk-xxxxxxxxxxxxxxxx

# 模型名称
# - dashscope: qwen-max, qwen-plus, qwen-turbo
# - openai: gpt-4, gpt-3.5-turbo
export MODEL_NAME=qwen-max

# OpenAI 兼容接口时配置（可选）
# export MODEL_BASE_URL=https://api.openai.com/v1
```

### DashScope 知识库配置（RAG）

```bash
# 知识库检索 API Key（⚠️ 必须配置）
export DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx

# 知识库索引 ID（⚠️ 必须配置）
export DASHSCOPE_INDEX_ID=your_index_id
```

### Mem0 记忆服务配置

```bash
# Mem0 API Key（⚠️ 必须配置）
export MEM0_API_KEY=m0-xxxxxxxxxxxxxxxx
```

### 服务端口配置（可选）

```bash
# 使用默认端口即可，如需修改可取消注释
# export BUSINESS_MCP_SERVER_PORT=10002
# export CONSULT_SUB_AGENT_PORT=10005
# export BUSINESS_SUB_AGENT_PORT=10006
# export SUPERVISOR_AGENT_PORT=10008
```

---

## 🚀 快速开始

### 方式一：一键启动（推荐）

```bash
# 1. 加载环境变量
source local-env.sh

# 2. 启动所有服务
./local-deploy.sh start
```

### 方式二：分步启动

```bash
# 1. 加载环境变量
source local-env.sh

# 2. 仅构建项目（不启动服务）
./local-deploy.sh build

# 3. 启动服务
./local-deploy.sh start
```

---

## 📝 常用命令

| 命令 | 说明 |
|------|------|
| `./local-deploy.sh start` | 构建并启动所有服务 |
| `./local-deploy.sh stop` | 停止所有服务 |
| `./local-deploy.sh restart` | 重启所有服务 |
| `./local-deploy.sh status` | 查看服务状态 |
| `./local-deploy.sh build` | 仅构建项目 |
| `./local-deploy.sh config` | 显示当前配置 |
| `./local-deploy.sh logs` | 查看日志目录 |
| `./local-deploy.sh help` | 显示帮助信息 |

### 查看日志

```bash
# 查看 supervisor-agent 日志
tail -f logs/supervisor-agent.log

# 查看所有日志文件
ls -la logs/
```

---

## ❓ 常见问题

### 1. JAR 中未找到前端静态文件

**错误信息**：`Maven 项目构建完成，但 JAR 中未找到前端静态文件`

**解决方案**：
```bash
# 确保 Node.js 已安装
node --version

# 重新构建
./local-deploy.sh stop
./local-deploy.sh start
```

### 2. 环境变量未配置

**错误信息**：`以下必需的环境变量未配置`

**解决方案**：
```bash
# 确保已加载环境变量
source local-env.sh

# 验证环境变量
echo $MODEL_API_KEY
```

### 3. 前端页面无法访问后端

**解决方案**：
1. 打开浏览器访问 `http://localhost:10008`
2. 点击右上角**设置**图标
3. 确认后端地址为 `http://localhost:10008`
4. 填写用户 ID 并保存

---

