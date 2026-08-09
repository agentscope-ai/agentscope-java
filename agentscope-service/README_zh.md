# AgentScope Service

> **基于 AgentScope Harness 构建的 Agent 管控与治理平台，为企业提供统一的控制面与编排中心。**

[English](README.md)

AgentScope Service 的目标不是替换你现有的 Agent 框架，而是提供一层统一控制面，让你把用不同框架、不同技术栈（Claude、OpenClaw、QwenPaw 等）搭建的智能体统一管控、协作起来。

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-dashboard.png)

- **AgentScope Service 是一个控制面。** 为企业内的所有 Agent 提供智能体注册、查询、分布式协调服务，兼容 AgentScope、LangChain、ADK、Claude / Qoder 等主流 Agent 运行时；企业可以有一个集中的 Agent 指标查看入口，同时可以对运行中的 Session 进行上下文压缩等操作。
- **AgentScope Service 提供低代码 Agent 创建与部署能力。** 底层基于 AgentScope Harness 运行时，可快速将多个 Agent 运行在一套统一管理的 Managed Agents 平台上；平台提供 Harness 能力托管，工具执行则可委托给用户自己控制的 Sandbox。
- **注册在 AgentScope Service 中的智能体，可以被组建为一个或多个 Teams。** 不论是自行部署的 AgentScope 运行时，还是低代码托管的 Agent Harness 运行时，都可以被编排在一起，共同协作完成更复杂的任务。

这些路径并不互斥。一家公司里，研发可能用 Coding Agent，业务中台用 AgentScope，新项目想直接上托管 Harness——这很常见。AgentScope Service 不绑定任何智能体框架或平台，它为所有智能体运行时提供统一的管控能力。

## 产品能力

### Control Plane
Control Plane（控制面，组件名称 Aistio）是 AgentScope Service 的核心组件，所有 Agent 应用都通过控制面进行统一注册，通过 SDK 或 Sidecar 方式支持主流 Agent Framework（AgentScope、LangChain、ADK）以及 Claude、Qoder 等注册与接入。



Dashboard 则是控制面的可视化 UI 控制台，为整个集群提供在线 agent 列表、部署实例信息、活跃 session 信息、token 消耗等全局观测信息，方便了解集群工作状况。



<!-- 这是一张图片，ocr 内容为：AISTIO AS FLEET OVERVIEW CONTROL PLANE CONSOLE CROSS-FRAMEWORK AGENT INSTANCES AND RUNTIME SESSIONS REPORTED INTO AISTIOD. 品 DASHBOARD TOKENS (24H. HEALTHY IDLE SESSIONS STALE ERRORS (24H) AGENTS ACTIVE OVERVIEW 4) INSTANCES INSTANCES SESSIONS 1 R R AGENTS O 1 6,319 3 SESSIONS GOVERNANCE AGENTS COUNTS LIVE INSTANCES ONLY.3 HISTORICAL MANAGED AGENTS TOKEN USAGE(24H) 8 TEAMS HOURLY SUM OF USAGE DELTAS (NOT CUMULATIVE SNAPSHOTS) 14:00:6,319 TOKENS TOP 10 AGENTS BY TOKENS TOP 10 SESSIONS BY TOKENS RANKED BY TOKEN USAGE DELTAS `LAST 24H RANKED BY TOKEN USAGE DELTAS `LAST 24H SESSION TOKENS ACTIVE ERRORS # PHASE AGENT TOKENS ADMIN MAIN-ED0098A8-E94E-42A9-9578 DEFAULT 1 6,319 6,319 B5FAD881F839 DEFAULT ACTIVE PROFILE USERS DEFAULT -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785934371848-7b1b934e-11ed-4625-97cc-820f2fe5d214.png)


此外，还可以在 dashboard 中查看 session 会话信息，查看活跃 session 的实时上下文状态（各部分数据占比），动态调整或压缩会话上下文，介入会话过程等。

<!-- 这是一张图片，ocr 内容为：AISTIO S SESSIONS CONTROL PLANE CONSOLE 7818AE8D-D486-4B41-BD2A ABORT TUM RESTORE EXIT PLAN ENTER PLAN COMPRESS TERMINATE CF8B7EB67224 品 DASHBOARD AGENTSCOPE-PAW - DEFAULT - AGENTSCOPE-JAVA - TURN #7 OVERVIEW AGENTS LIFETIME USAGE PHASE LAST ACTIVE INSTANCE MODEL PRESSURE SESSIONS 31% 46,777 2026/7/30 HEALTHY 22:55:01 GOVERNANCE ZPROMPT+COMPLETION U-FF406114-1819... HTTP://LOCALHOS MANAGED AGENTS WINDOW-IN 45,260/ OUT 1.517 TEAMS CONTEXT VIEW COMPACTED. 6 CFFECTIVE MSGS -7 TOOLS - WINDOW 125/ 32,768 -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785946414310-ff29cee8-2b2b-40df-9ec8-0211ee03fe8c.png)

### Managed Agents
Managed Agents 是由 `agentscope-builder` 平台升级而来，它的定位仍旧是一个低代码 Agent 平台，为开发者提供 Agent 定义、Agent 托管运行的 SaaS 化平台能力。同时更强调推理与工具执行的分离，推理 harness 能力更彻底的托管，工具执行则开放给用户更多的控制权。


<!-- 这是一张图片，ocr 内容为：AISTIO AGENTS NEW AGENT CONTROL PLANE CONSOLE LOW-CODE MANAGED AGENTS. EACH AGENT IS SHAPED BY ITS WORKSPACE - AGENTS.MD, TOOLS, SKILLS AND SUBAGENTS. 品 DASHBOARD CLONE-ONLY O ALL 4 SHARED WITH ME O MINE 4 GLOBAL MANAGED AGENTS AGENTS SESSIONS BBB PPP OWNER OWNER OWNER CCC 调用专用AGENT 擅长做微服务相关搜索 WORKSPACES BBB TEST AG_4ECD3838B3CD AG_1426C299ADF8 AG_5AF01156E61F ENVIRONMENTS WORKSPACE LINKED WORKSPACE LINKED WORKSPACE LINKED MEMORY VAULTS DEPLOYMENTS OWNER AAA CHANNELS XXXXX AG_CECC0395E056 8 TEAMS WORKSPACE LINKED ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948183107-014a5cb1-6fcf-4b04-93cb-f01341b35350.png)


Agent 定义总体围绕 AgentScope Harness 的核心设计理念设计，首先定义好 Workspace、Memory 等基础概念，通过将 workspace、memory 与 agent 关联即可创建一个智能体。


“创建 Agent → 创建 Environment → 创建 Session → 发送第一条消息 → 在 Dashboard 观察事件流”，Session 创建本身不会立刻跑 Agent。对长任务场景，Managed Agents 尤其关键的是**可恢复**：事件落库、状态可重建、HITL 可暂停续跑。前端刷新或服务副本切换，不应等于任务从头开始。

在运行架构上，设计与 Claude Managed Agents 非常类似，Harness 基础设施与运行时全托管（底层依赖AgentScope Harness Runtime），基于 Brain/Hands 分离的架构让用户对工具执行环境有更多控制权。部署架构上，分为控制面、托管数据面两大组件，具体可参考后面的部署架构章节。

### Agent Teams
注册在 AgentScope Service 控制面的所有智能体，不论是使用框架开发部署、自行注册到控制面的（Langchain、AgentScope、ADK、Claude SDK等）智能体，或者是使用 Managed Agents 低代码方式直接创建的托管 Agent，都可以把它们按照你想要的方式编排在一起，形成一个可以互相协作的 Agent Teams 来协作处理复杂。

<!-- 这是一张图片，ocr 内容为：AISTIO IS TEAM1 BACK COMPLETE TEAM FORCE DELETE LEAD CLOSE CONTROL PLANE CONSOLE CCC SOSS_025CA811A27B 帮我分析E2B沙箱和DAYTONA沙箱 SESS_E25CA811A27B FULL PAGE TEAM CHAT DASHBOARD IDLE NS-DEFAULT TASKS 1/2COMPLETE 1 IN PROGRESS 0 PENDING TASK-1.I ALSO LET THEM KNOW THAT THEY CAN REACH OUT IF THEY NEED ANY MANAGED AGENTS SPECIFIC RESOURCES OR HAVE ANY TOPOLOGY AGENTS QUESTIONS. SESSIONS WORKER1 LEAD IS THERE ANYTHING ELSE YOU WOULD LIKE TO ADDRESS AT THIS MOMENT? WORKS PACES ENVIRONMENTS OPEN CHAT CHAT OPEN [TEAM:TEAM1 FROM WORKER1]I HAVE MEMORY CLAIMED TASK-1 AND WILL START THE VAULTS ANALYSIS OF THE E2B SANDBOX.I WILL REACH OUT IF I NEED ANY SPECIFIC DEPLOYMENTS TASK BOARD MEMBERS MESSAGES RESOURCES OR HAVE ANY QUESTIONS. CHANNELS NEW TASK SUBJECT ADD TASK TEAMS TOOL:CLAIMTASK CA11_78816 UNASSIGNED(0) BLOCKED((() COMPLETED(1) IN PROGRESS(1) ASSIGNED(0) TOOL: CA11_5B2 TEAMS 分析E2B治理沙箱 分析DAYTONA 治理沙 箱 TEMPLATES TOOL:TEAM COMPLETE UNCLAIM SEND MESSAGE AG_4ECD3838B3CD... FAILED(0) ADMIN PROFILE USERS -->
![](https://intranetproxy.alipay.com/skylark/lark/0/2026/png/54037/1785948895276-d0221173-9683-4a94-b281-55f9372cee65.png)

在 AgentScope Service 设计中，Teams 团队不是聊天室，而是一套可运营的协作单元：任务可认领、计划可审批、成员可唤醒，状态也不会因为某个 Session 结束而全部消失。一个常见模式是 Lead 负责任务拆解与验收，Member 按能力认领调研、编码、核验等子任务，平台负责消息路由、任务板与生命周期，而不是让业务代码手写一套临时多进程通信。

值得特别说明的是，AgentScope 框架原生支持 Agent Teams 能力，这套机制是基于 AgentScope Service 控制面做分布式任务管理与调度，所以您既可以使用 AgentScope Framework 原生的 Teams 能力在主 agent 编码阶段实现多 agent 编排，也可以在控制台上根据需求将多个独立的 agent 动态编排在一起完成某一项复杂任务。具体取决于您的使用场景。

## 整体架构

### 基本工作原理

Human 通过 Dashboard（浏览器）或 REST API（SDK / curl / 第三方系统集成）进入 Control Plane；控制面之下统一管理四类 Agent 接入方式：

- AgentScope 原生接入
- LangChain 通过 `instrument()` 接入
- Claude 通过 Sidecar 旁路接入
- QwenPaw 通过 Sidecar 旁路接入

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-architecture.png)


### 生产部署架构

在生产环境中，推荐的 AgentScope Service 部署架构如下：

![AgentScope Service](/docs/imgs/agentservice/agentscope-service-production-deploy.png)


| 平面 | 负责 | 不负责 |
| --- | --- | --- |
| Gateway | 公共入口、认证与 API 路由 | 业务状态与 Agent 执行 |
| Control Plane（`aistiod`） | 产品资源、控制台、Agent 状态、Session、Team 与运行时命令 | Harness 推理、Session 流传输 |
| Dataplane | Managed Harness Runtime、事件日志、SSE、Turn Lease、HITL 与 Work Queue | 直读产品 Catalog 表 |
| Scheduler | Channel、Cron、出站任务与 Self-hosted Hands Worker | 推理循环 |


## Agent 如何接入

AgentScope Service 同时服务两类用户：

1. **平台服务型团队**：用 Console / API 创建 Managed Agent，快速构建托管智能体。
2. **业务研发团队**：已有不同技术栈的 Agent 应用，希望纳入统一治理——通过扩展 / SDK / Sidecar 接入控制面。

目前支持 Agent Framework、Coding Agent、

## 快速开始

### 前置条件

- Docker
- JDK 17+
- Maven
- Go 1.26+
- 模型 API Key；以下示例使用 DashScope

仅在重新构建 Web Console 时需要 Node.js。

### 1. 启动本地环境

从 Monorepo 执行：

```bash
git clone https://github.com/agentscope-ai/agentscope-java.git
cd agentscope-java

export DASHSCOPE_API_KEY=sk-xxx
cd agentscope-service
scripts/dev-down.sh && BUILDER_REBUILD=1 scripts/dev-up.sh
```

脚本会启动 PostgreSQL、`aistiod`、Dataplane、Scheduler 和 Gateway。本地开发设置 `AISTIO_ENABLE_KUBERNETES=false`，Hosted Product 流程无需 CRD Reconciler 或 ASDP gRPC。

| 项目 | 值 |
| --- | --- |
| Console 与公共 API | http://localhost:8080 |
| 默认账号 | `admin` / `admin` |
| 其他种子账号 | `alice` / `alice`、`bob` / `bob` |
| 日志与本地状态 | `.dev-stack/` |

默认账号和开发密钥只能用于本地环境。

### 2. 运行第一个 Session

1. 打开 http://localhost:8080 并登录（`admin` / `admin`）。
2. 在 **Managed Agents** 中创建 Agent。
3. 创建一个 `local` Environment。
4. 打开 **Sessions**，创建绑定 Agent 与 Environment 的 Session，并发送第一条消息。
5. 在 **Dashboard** 查看在线状态、事件与运行时信息。
6. 如需协作，再进入 **Agent Teams** 创建团队并观察任务与成员状态。

体验 BYO Agent 注册时，可使用仓库示例 `agentscope-samples/agents/agentscope-paw`；启动后即可在 Dashboard 中看到智能体注册成功。


### 3. 停止环境

```bash
scripts/dev-down.sh
```

## 开发

### 构建后端

请从 Monorepo 根目录执行 Maven，确保 Service JAR 使用的 AgentScope Snapshot 都是最新版本：

```bash
mvn install -DskipTests

cd agentscope-service/aistio
make build
make test
```

### 构建或开发 Console

```bash
cd agentscope-service/frontend
npm install
npm run build   # 静态资源输出到 ../aistio/ui

npm run dev     # Vite HMR，/api 代理到 Gateway
```

### Docker Compose

先构建 Java Artifact，再启动容器化环境：

```bash
mvn install -DskipTests
docker compose -f agentscope-service/docker-compose.yml up --build
```

### 服务端口

| 服务 | 端口 | 暴露方式 |
| --- | ---: | --- |
| Gateway | 8080 | 对外 |
| `aistiod` | 8081 | 内部 |
| Dataplane | 8082 | 内部 |
| Scheduler | 8083 | 内部 |
| PostgreSQL | 5432 | 本地基础设施 |

### 配置

Java Service 使用 `builder.*` 属性与 `BUILDER_*` 环境变量。各平面必须使用一致的认证密钥和内部 URL。

| 变量 | 作用 |
| --- | --- |
| `DASHSCOPE_API_KEY` | 本地 Turn 使用的 DashScope 模型凭据 |
| `BUILDER_JWT_SECRET` | Gateway 与控制组件共享的 JWT 签名密钥 |
| `BUILDER_INTERNAL_TOKEN` | 平面间可信调用密钥 |
| `BUILDER_VAULT_MASTER_KEY` | Vault 凭据加密密钥 |
| `BUILDER_DB_URL`、`BUILDER_DB_USER`、`BUILDER_DB_PASSWORD` | Java Dataplane 数据库 |
| `BUILDER_CONTROL_URL`、`BUILDER_DATA_URL`、`BUILDER_SCHEDULER_URL` | 内部服务地址 |
| `BUILDER_E2B_API_KEY` | `sandbox` Environment 的 E2B 凭据 |
| `AISTIO_PRODUCT_DSN` | `aistiod` 使用的产品数据库 |
| `AISTIO_ENABLE_KUBERNETES` | 是否启用 Aistio CRD Reconciler 与 Kubernetes 集成 |
| `BUILDER_REBUILD=1` | `dev-up` 前强制完整重建 |

生产部署必须替换全部开发密钥，并使用持久化 PostgreSQL。


## Roadmap

AgentScope Service 把不同模式构建的 Agent（Framework、Coding Agent、Managed Agents）收敛在统一控制平面内，为 Agent 间协作提供统一视图。无论你从 Console 新建第一个 Agent，把 Harness 运行托管给平台，还是把现有 AgentScope / LangChain / Claude 应用接入控制面，目标都一样——**让企业拥有一站式的 Agent 管控与治理中心**。

近期重点包括：

1. **围绕 AgentScope Framework 原生能力持续迭代**
2. **支持更多 Agent 框架与 Coding Agent 接入** — 补齐并深化 LangChain、ADK、Claude、Qoder、OpenAI Agents 等适配，降低 BYO 接入成本
3. **Automation** — 围绕 Deployment、Cron、Webhook、Channel 扩展自动触发与闭环执行，让 Agent 走向事件驱动的任务处理
4. **更多事件驱动集成** — 接入 GitHub / GitLab、钉钉、企微等入口，把代码变更、工单、群消息直接变成 Agent Turn 或 Team Task

企业级云产品亦可关注阿里云 [Agent Teams](https://help.aliyun.com/zh/agentteams/magic-console-product-overview)、[Agent Loop](https://help.aliyun.com/zh/document_detail/3033860.html)。

## 文档

关于 AgentScope Service 更多详细讲解，请参考博客文章[《AgentScope Service -企业级智能体管控与治理中心》](https://java.agentscope.io/v2/zh/blogs/agentscope-v2-release.html)
