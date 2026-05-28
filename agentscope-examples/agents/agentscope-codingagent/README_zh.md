# agentscope-codingagent

> 🇬🇧 English version: [README.md](README.md)

## 项目概览

codingagent 是一个可以**部署在你自己组织内**的自主编码机器人。在 issue 下评论一句，它会把仓库克隆下来、把活干了、提个 PR；在 PR 上请它做 reviewer，它会读完 diff、写一篇真正的 review；在 review comment 上回复它，它会就地改这个分支、在原 thread 里答复你。

之所以敢给它这么大的"权限"，靠的是两件事：

- 它**永远不会动你本机或构建机的文件系统**。每个 session 都跑在自己专属的、用完即扔的 Docker 容器里 —— `git clone`、`npm install`、`mvn test`、`git push`，全都发生在 sandbox 内部。
- **sandbox 生命周期由框架负责**。agent 不决定什么时候拉起或销毁容器 —— 运行时按 session 替它管，同一会话的多轮之间复用同一个容器。

同一个 JAR 既可以单进程跑（给一个团队的 monorepo 配一个编码机器人），也能挂在负载均衡器后面横向扩展 —— 派发器、消息队列、去重存储都能共享，任何副本都能接管任何会话。

和姊妹项目对比：它比 [claw] 锁得更死（claw 直接在你的本机 shell 上跑），又比 [builder] 更专注（Builder 是托管任意 agent 的平台）。codingagent 就是那个**专门替你写代码**的。

### 它能做什么

- **从 Issue 到 PR。** 在 GitHub Issue 里写一句需求，agent 自己 clone、起分支、写代码、推分支、开（或更新）PR。
- **PR Review。** 把它加为 reviewer，它会读 diff、整理结构化的 findings，并发表一次完整 review。
- **PR 里来回迭代。** 在 PR 某一行留 review comment，它会就地改这个分支、并在原 thread 里答复。
- **多种方式触达。** GitHub webhook、本地 CLI（用来上手把玩）、钉钉 Stream、飞书回调。
- **安全网默认开启。** webhook 签名校验、重复事件丢弃、per-会话限流、模型调用预算，以及对上游限流的透明重试。

### 一览

| | codingagent |
|---|---|
| **适用场景** | 企业内需要自主编码，但执行环境必须与本机 / 构建机隔离 |
| **用户数** | 多人 —— 每条 issue / PR 线程，或每位 IM 对话方都是一个独立 session |
| **隔离** | ✅ 每个 session 跑在自己专属的临时 Docker 容器里 |
| **Sandbox 生命周期** | **由框架自动管理** —— 按 session 拉起、复用、销毁 |
| **通道** | GitHub webhook · CLI · 钉钉 Stream · 飞书回调 |
| **分布式** | ✅ 默认单节点；接上共享存储后可扩展为多副本 |
| **文件系统** | `SandboxFilesystem` —— 所有读 / 写 / shell 都走 sandbox |

### 架构

codingagent 跑在 **HarnessAgent + `SandboxFilesystem`** 之上，sandbox 生命周期由运行时自己管。任何通道送来的事件都会被 `ThreadIdFactory` 确定性地映射成一个 `threadId`，dispatcher 按 thread 派发（thread 忙时入队），agent 在 per-session 的 Docker 容器内执行 —— 第一次用时拉起，同 session 后续轮次复用。

```
   GitHub Webhook · CLI · 钉钉 · 飞书
                   │
                   ▼
        ┌───────────────────────┐
        │ Channel 适配器        │  HMAC 校验 · 去重 · 过滤自评
        └─────────┬─────────────┘
                  ▼
        ┌───────────────────────┐
        │ ThreadIdFactory       │  github:issue:owner/repo#42 → SHA-256 → UUID
        └─────────┬─────────────┘
                  ▼
        ┌───────────────────────┐
        │ RunDispatcher         │  立即派发 · thread 忙时入队
        │   ├ MessageQueueHook  │
        │   ├ ThreadBudgetHook  │
        │   └ ModelCallLimitHook│
        └─────────┬─────────────┘
                  ▼
        ┌──────────────────────────────────────────┐
        │ HarnessGateway                           │
        │   ├ CodingAgent  （issue / PR 迭代）     │
        │   └ ReviewerAgent（review_requested）    │
        └─────────┬────────────────────────────────┘
                  ▼
        ┌──────────────────────────────────────────┐
        │ SandboxFilesystem  （per-thread Docker） │
        │   agentscope/coding-sandbox:latest       │
        │   ├ git · shell · 构建工具集             │
        │   └ 运行时托管生命周期（自动）           │
        └─────────┬────────────────────────────────┘
                  ▼
            GitHub API · 目标仓库
```

[claw]: ../agentscope-claw/
[builder]: ../agentscope-builder/

---

## Agents

| Agent ID   | 类                     | 职责                                       |
|------------|------------------------|--------------------------------------------|
| `coding`   | `CodingAgentFactory`   | 实现 issue、写代码、推 PR                  |
| `reviewer` | `ReviewerAgentFactory` | 评审 PR、记录 findings、发表一次性 Review  |

## 快速开始

最快的体验路径 —— 一个环境变量、一个 Maven 命令，本地文件系统上跑一个交互式 REPL。无需 Docker、无需 webhook、无需 GitHub App。

```bash
# 1. 设置模型 key（默认 DashScope；OpenAI / Anthropic 也支持，详见 ENV_VARS.md）
export DASHSCOPE_API_KEY=sk-...

# 2. 在仓库根目录构建依赖（之后跑可以省略）
cd agentscope-java
mvn install -pl agentscope-examples/agents/agentscope-codingagent -am -DskipTests -q

# 3. 启动 CLI（每次想聊都是它）
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

启动后会出 banner，然后到 `You>` 提示符。**agent 工作在自己的 workspace** `~/.agentscope/codingagent/workspace/`（不是你当前的仓库目录）—— 标准玩法是把目标仓库 *克隆进* workspace 再操作。所以好的首条 prompt 是：

```
You> write hello.txt with a haiku about Java
You> fetch https://github.com/anthropics/anthropic-sdk-python/blob/main/README.md and summarize it
You> clone https://github.com/owner/repo into the workspace and tell me what it does
You> /exit
```

workspace、聊天记录、配置都和你的项目目录、其他 harness 应用（builder / dataagent / claw）相互隔离：

- **Workspace**（agent 读写的 skills/memory/sessions/files）：`~/.agentscope/codingagent/workspace/`
- **聊天记录**（per-thread SQLite）：`./.agentscope/codingagent.db`（相对你 `mvn` 的目录）
- **配置**：硬编码在 `CodingChatCli` 里 —— CLI 会忽略项目根目录的 `.agentscope/agentscope.json`，避免别的 harness 应用留下的旧配置覆盖它。

### 可选：让 agent 评审一个真实的 GitHub PR

```bash
export GITHUB_TOKEN=ghp_...          # 带 `repo` scope 的 PAT
# REPL 内:
You> review https://github.com/owner/repo/pull/42
```

这条会把请求路由到 **reviewer** agent —— 它会拉取 diff、记录结构化 findings，并发表一次完整的 GitHub Review。

### 可选：把每个 session 隔离到 Docker sandbox

```bash
# 先构建 sandbox 镜像（见下方"构建 Sandbox 镜像"）
export SANDBOX_TYPE=docker
mvn exec:java -pl agentscope-examples/agents/agentscope-codingagent
```

每个聊天 thread 都会拿到自己的临时容器 —— 让 agent 跑任意 `execute` 命令时更安全。

## Webhook 服务

```bash
# 必填环境变量
export GITHUB_WEBHOOK_SECRET=your-webhook-secret
export DASHSCOPE_API_KEY=sk-...      # 或者 CODING_MODEL_ID=anthropic:... + ANTHROPIC_API_KEY
export GITHUB_TOKEN=ghp_...          # 或者 GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY

# 可选
export TAVILY_API_KEY=tvly-...       # 启用 web_search 工具
export SANDBOX_TYPE=docker           # 每个 session 跑 Docker sandbox（默认 none）

# 构建并启动
mvn spring-boot:run -pl agentscope-examples/agents/agentscope-codingagent
```

服务在 `8080` 端口启动（用 `PORT=...` 覆盖）。

### GitHub App 配置

1. 创建一个 GitHub App，授权：`Issues: Read/Write`、`Pull requests: Read/Write`、`Contents: Read/Write`、`Metadata: Read`。
2. 订阅 webhook 事件：`issue_comment`、`pull_request`、`pull_request_review_comment`。
3. 把 `Webhook URL` 设为 `https://your-host/webhooks/github`。
4. 生成一个 webhook secret，配到 `GITHUB_WEBHOOK_SECRET`。
5. 把这个 App 安装到目标仓库。

## 启动一次会话

webhook 服务（或钉钉 Stream 客户端）跑起来之后，**通过在源平台执行某个动作**就能开启一个 session —— agent 没有自己的 UI，所有交互都是一条 comment、一个事件，或一条对应通道适配器拣到的 IM 消息。

### 通过 GitHub

`GitHubWebhookHandler` 监听 `POST /webhooks/github`，并把事件经 `ThreadIdFactory` 路由 —— 同一 issue/PR 上所有动作共享同一个 agent session。

**1. 让 coding agent 实现一个 issue**

在已安装 GitHub App 的仓库的任一 issue 下评论：

```
Please implement this feature: <describe what you want>
```

- 触发：`issue_comment.created`
- Thread key: `github:issue:<owner>/<repo>#<issue_number>`
- 路由到：`coding` agent
- agent 把仓库克隆到 sandbox，做修改，并把结果作为追评（也可能再开一个 PR）。

同 issue 下后续评论会接到同一个 session。

**2. 让 reviewer agent 评审一个 PR**

在 PR 上点 **Reviewers → Request review** 选你的 GitHub App 账号：

- 触发：`pull_request.review_requested`
- Thread key: `github:reviewer:<owner>/<repo>#<pr_number>`
- 路由到：`reviewer` agent
- agent 拉取 diff、记录结构化 findings，并发表一次完整的 review。

**3. 在 PR 上和 coding agent 来回迭代**

在 PR 某一行留 review comment（也就是 `pull_request_review_comment` 事件）：

```
Can you refactor this function to use streams?
```

- 触发：`pull_request_review_comment.created`
- Thread key: `github:pr:<owner>/<repo>#<pr_number>`
- 路由到：`coding` agent
- agent 在上下文里读这条评论、改对应分支、回到 thread。

**注意**
- agent 自己 GitHub 账号留的评论会被跳过（`isSelfComment` 检测）以避免自循环。
- thread 忙时新事件会暂存到 `SqliteBaseStore`，由 `MessageQueueHook` 在下一轮推理前注入。
- 仅 `issue_comment.created`、`pull_request.review_requested`、`pull_request_review_comment.created` 三种 action 会触发 dispatch；`pull_request.opened` 仅解析、不自动派发；`push` 事件只观测、不触发 agent 运行。

### 通过钉钉

钉钉适配器（`DingtalkChannel`）通过常驻 WebSocket 直连钉钉 Stream 网关 —— **无需公网 HTTP 端点**，NAT 后或开发笔记本都能跑。

**前置 —— 一次性准备**

1. 在 <https://open-dev.dingtalk.com> 创建企业内部应用。
2. 给应用加一个机器人，记录 `robotCode`。
3. 在凭据/基本信息页记录 `AppKey` 和 `AppSecret`。
4. 在 **事件订阅** 选 **Stream 模式**（流式），订阅 `/v1.0/im/bot/messages/get`。
5. 编辑 `~/.agentscope/codingagent/agentscope.json`：

   ```json
   {
     "channels": {
       "dingtalk": {
         "defaultAgentId": "coding",
         "dmScope": "PER_PEER",
         "properties": {
           "appKey": "dingxxxxxxxx",
           "appSecret": "xxxxxxxxxxxx",
           "robotCode": "dingxxxxxxxx"
         }
       }
     }
   }
   ```

6. 启动方式同 GitHub：

   ```bash
   mvn spring-boot:run -pl agentscope-examples/agents/agentscope-codingagent
   ```

   启动日志应该看到：
   `DingTalk channel 'dingtalk' started: appKey=…, robotCode=…`
   `DingTalk Stream connected: <gateway-host>`

**1. 1:1 私聊**：在钉钉里搜机器人名称，发任意文本。

- 触发：Stream 回调 `conversationType=1`
- Thread key: `dingtalk:<appKey>:<senderStaffId>`
- 路由到：`coding` agent（`agentscope.json` 里的 `defaultAgentId`）
- agent 在同一私聊回复，后续消息保持同一 session。

**2. 群聊**：把机器人加到群（群设置 → 机器人 → 添加），然后 **@机器人**：

- 触发：Stream 回调 `conversationType=2`（钉钉只在被 @ 的群消息上触发事件）
- Thread key: `dingtalk:<appKey>:<openConversationId>`
- 路由到：`coding` agent
- agent 在群内回复。每个群独立 session。

**注意**
- 目前只映射 `msgtype=text`；其他类型（图片、文件、卡片）会被静默 ack。
- 钉钉重发的同 `msgId` 事件会被 `IdempotencyStore` 丢弃。
- 每 peer 一个滑动窗口节流（`BotLoopGuard`，默认 60 秒 20 条）防止 bot 互相循环。
- 想按 conversation 路由不同的 default agent（比如把某个群路由到 `reviewer`），加 `bindings` 即可，规则形状见 `ChannelConfig.Builder.binding(...)`。

### 通过飞书（Lark）

飞书走 HTTP 回调模型，因此服务**必须可被公网访问**（开发期可以用 ngrok）。

**前置 —— 一次性准备**

1. 在 <https://open.feishu.cn> 创建自建应用。
2. 启用 **Bot** 能力，记录 `App ID`（`cli_xxx`）和 `App Secret`。
3. 在 **事件订阅** 把 **Request URL** 设为
   `https://<你的公网 host>/api/channels/feishu/feishu/callback`（路径形如 `/api/channels/feishu/<channelId>/callback`，`<channelId>` 与 `agentscope.json` 中的 key 保持一致）。
4. 订阅事件 `im.message.receive_v1`。
5.（推荐）在同一页生成 **Encrypt Key** 和 **Verification Token**。
6. 编辑 `~/.agentscope/codingagent/agentscope.json`：

   ```json
   {
     "channels": {
       "feishu": {
         "defaultAgentId": "coding",
         "dmScope": "PER_PEER",
         "properties": {
           "appId": "cli_xxxxxxxxxx",
           "appSecret": "xxxxxxxxxxxxxx",
           "encryptKey": "xxxxxxxx",
           "verificationToken": "xxxxxxxx"
         }
       }
     }
   }
   ```

7. 启动服务并完成飞书 URL 校验握手 —— controller 会自动回 `challenge`。

**开始对话** —— 私聊机器人，或加群后 @机器人；路由规则与钉钉一致（thread key = `feishu:<tenantKey>:<chatId>`）。

### Session 续连一览

| 来源                       | 触发新 agent run                                  | 同 session 续连              |
|---------------------------|---------------------------------------------------|------------------------------|
| GitHub issue 评论         | issue 下任一非 bot 评论                           | 同 issue 后续评论            |
| GitHub PR review-request  | 把 bot 加为 PR reviewer                           | 重新对同一 PR 请求 review    |
| GitHub PR review comment  | PR 任一非 bot 行评论                              | 同 PR 后续行评论             |
| 钉钉私聊                  | 私聊任一文本消息                                  | 同 staff id 后续消息         |
| 钉钉群聊                  | 群内 @ 机器人                                     | 同群后续 @                   |
| 飞书私聊 / 群聊           | 私聊任一文本（或群内 @）                          | 同 `chat_id` 后续消息        |

## 环境变量

完整清单见 [ENV_VARS.md](ENV_VARS.md)。

## 架构细节

### Thread 路由

每条 GitHub 事件都被 `ThreadIdFactory` 确定性映射成一个 **thread ID**：

```
github:issue:owner/repo#42   → SHA-256 → UUID → coding agent thread
github:reviewer:owner/repo#7 → SHA-256 → UUID → reviewer agent thread
```

这保证了同一 issue/PR 上所有评论都会路由到同一个 agent session，对话历史得以保留。

### Message Queue

agent thread 正在执行（busy）时，新到事件会写入 `SqliteBaseStore` 的命名空间 `["queue", thread_id]`。`MessageQueueHook` 会在下一次 LLM 调用推理开始前从队列取出内容、注入到系统提示。

### Sandboxes

每个 coding agent session 都有自己的 Docker 容器（`agentscope/coding-sandbox:latest`），`IsolationScope.SESSION`。容器在首次使用时按需创建，并在同 session 多轮之间复用。

### Hook 栈

| Hook                  | 用途                                       |
|-----------------------|--------------------------------------------|
| `MessageQueueHook`    | 推理前注入排队消息                         |
| `ThreadBudgetHook`    | per-thread 模型调用上限                    |
| `ModelCallLimitHook`  | 全局模型调用上限（跨所有 thread）          |

`FallbackModel` 包裹主模型，对限流 / 过载错误做透明重试。

## 可观测性

Spring Boot Actuator 暴露：

- `GET /actuator/health` —— 存活探针
- `GET /actuator/prometheus` —— Prometheus 指标
- `GET /actuator/metrics` —— 指标浏览

关键指标（前缀 `coding_agent.*`）：

| 指标                            | 说明                                   |
|---------------------------------|----------------------------------------|
| `webhook.received`              | 总入站 webhook 数                      |
| `webhook.duplicate`             | 跳过的重复投递                         |
| `dispatch.total`                | 已发起的 agent 派发                    |
| `dispatch.errors`               | 派发失败                               |
| `model.calls`                   | 跨 thread 的 LLM 调用                  |
| `findings.added`                | reviewer 录入的 findings 数            |
| `review.published`              | 已发表的 GitHub PR review 数           |
| `dispatch.duration`             | 端到端派发耗时                         |

设置 `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` 可以打开分布式追踪。

## 构建 Sandbox 镜像

```bash
docker build \
  -t agentscope/coding-sandbox:latest \
  agentscope-examples/agents/agentscope-codingagent/src/main/docker/coding-sandbox/
```

## 模块结构

```
src/main/java/io/agentscope/harness/coding/
├── agent/              # CodingAgentFactory, ReviewerAgentFactory
├── channel/            # Channel 接口, ChatUiChannel
├── control/            # RunDispatcher, ThreadIdFactory
├── gateway/            # HarnessGateway, Gateway 接口
├── hook/               # MessageQueueHook, ThreadBudgetHook, FallbackModel, ...
├── metadata/           # ThreadMetadata, TokenEncryption
├── observability/      # CodingAgentMetrics
├── prompt/             # CodingSystemPrompt, ReviewerSystemPrompt
├── reviewer/           # Finding, ReviewerFindingsService, GitHubReviewPublisher
├── session/            # SessionAgentManager, SessionKind, ...
├── store/              # SqliteBaseStore
├── tools/              # GitHub, HTTP, web 工具；finding 工具
├── webhook/github/     # GitHubWebhookHandler
├── CodingAgentApplication.java
├── CodingBootstrap.java
└── CodingChatCli.java
```

## 尚未支持

- GitHub App JWT token 自动轮换（目前用 PAT）
