# agentscope-paw

> 🇬🇧 English version: [README.md](README.md)

## 项目概览

paw 是 [OpenPaw] 的 Java 版本 —— 一款装在你自己电脑上的个人助手。它以你的身份、在你的文件系统和 shell 里干活，并且会随着使用慢慢"长大"：它学到的技能、孵化的子智能体、攒下的记忆，都只是它自己在工作区里写的一堆文件。

它擅长的另一件事，是**直接出现在你已经在用的地方**。开箱即支持钉钉、企业微信、飞书、GitHub 和 GitLab，所以你可以从一条 DM、或者一个 Issue 评论里 @ 它，不必再多开一个网页。

paw 故意不去做更多的事 —— 没有登录、没有多租户隔离、没有 Docker sandbox、不做横向扩展。如果你需要这些 —— 想把 paw 风格的 agent 托管给一个团队，或者想让 agent 跑不可信代码而互相隔离 —— 请看姊妹项目 [agentscope-builder](../agentscope-builder/) 和 [agentscope-dataagent](../agentscope-dataagent/)。

### 一览

| | paw |
|---|---|
| **适用场景** | 在自己笔记本 / 工作站上的个人助手 |
| **用户数** | 1 人 —— 你自己 |
| **隔离** | 无 —— 直接以你的身份运行，可访问你的 Shell |
| **自进化** | ✅ 技能、子智能体、记忆、`AGENTS.md` 都是 agent 自己会写的工作区文件 |
| **通道** | 内置 Web UI + 钉钉 · 企业微信 · 飞书 · GitHub · GitLab |
| **分布式** | ❌ 单进程、单节点 |
| **文件系统** | `LocalFilesystemWithShell` —— 直连本机 FS + Shell |

### 架构

paw 是一个轻量的 Spring Boot 应用，把 **HarnessAgent** 直接挂载到 `LocalFilesystemWithShell` 之上。没有鉴权层、没有 sandbox、没有远端存储 —— 所有的读写和 Shell 命令都直接落到本机操作系统。

```
┌─────────────────────────────────────────────────────────────────┐
│                          你的本机                               │
│  ┌─────────────────────┐   ┌─────────────────────────────────┐  │
│  │  通道适配           │   │  HarnessAgent（每个 agent 一个）│  │
│  │  ├ chatui (Web UI)  │──▶│   ├ 推理（LLM）                 │  │
│  │  ├ dingtalk 钉钉    │   │   ├ Skills · Sub-agents · MCP   │  │
│  │  ├ wecom · feishu   │   │   └ 自进化循环                  │  │
│  │  └ github · gitlab  │   └────────────┬────────────────────┘  │
│  └─────────────────────┘                ▼                       │
│                          ┌──────────────────────────────────┐   │
│                          │  LocalFilesystemWithShell        │   │
│                          │   ├ 本机 FS（~/.agentscope/...） │   │
│                          │   └ 本机 Shell（bash / zsh）     │   │
│                          └──────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

[OpenPaw]: https://github.com/agentscope-ai/openpaw

---

## 快速开始

环境要求：

- JDK 17+
- 模型 API key（默认使用 DashScope）。在环境变量里设置 `DASHSCOPE_API_KEY`，或通过 `--paw.dashscope.api-key=…` 传入。

在仓库根目录构建并运行：

```bash
mvn -pl agentscope-examples/agents/agentscope-paw -am clean package -DskipTests
java -jar agentscope-examples/agents/agentscope-paw/target/agentscope-paw-*.jar
```

打开 <http://localhost:8080/>。默认主目录是 `~/.agentscope`，可通过 `PAW_HOME` 环境变量（或 `--paw.home=…`）改写。首次启动时如果 `agentscope.json` 不存在，会自动生成一个内置的 `default` agent。

## 目录结构

所有持久化状态都放在 `${paw.home}` 下（默认 `~/.agentscope`）：

```
~/.agentscope/
├── agentscope.json          # 内置 agent 定义
├── agents.json              # 自定义 agent 目录（JSON 文件）
└── agents/
    └── <agentId>/
        ├── workspace/       # AGENTS.md, skills/, subagents/, tools.json, memory/, …
        └── sessions.json    # 该 agent 的 session-store 索引
```

无论是内置还是自定义 agent，每个 agent 都有自己的 workspace 目录与 session 存储。Harness 在 `workspace/agents/<subId>/` 下管理工作区文件、技能、子智能体以及子会话历史。

## Agent 类型

两种类型，共享同一套运行时：

- **内置 agent** 定义在 `~/.agentscope/agentscope.json`，UI 中只读；要修改请直接编辑 JSON。
- **自定义 agent** 通过 UI（或 `POST /api/agents`）创建，持久化到 `~/.agentscope/agents.json`。

UI 上点 **New agent** 按钮可以基于空白脚手架、内置模板或 AI 起草的草稿创建一个新 agent。

## 通道（Channels）

默认会注册一个 `chatui` 通道（`DmScope.MAIN`），Web UI 通过它和 agent 共享一个 session。其他通道适配（钉钉、企微等）可以在 `~/.agentscope/agentscope.json` 中按需开启。

### 内置通道类型

| `type` | 方向 | 传输 | 说明 |
| --- | --- | --- | --- |
| `chatui` | 入 + 出 | 进程内拉取 | 默认开启的本地 Web UI |
| `dingtalk` | 入 + 出 | **Stream**（WebSocket，无需公网端口） | 企业内部应用 + Stream 订阅 |
| `wecom` | 入 + 出 | HTTP 回调 + REST API | 自建企业应用，需要公网 HTTPS 回调 |
| `feishu` | 入 + 出 | HTTP 事件回调 + REST API | 自建应用 + 事件订阅，需要公网 HTTPS |
| `github` | 入 + 出 | Webhook + REST API | 监听 issue / PR review comment 事件，需要公网 HTTPS |
| `gitlab` | 入 + 出 | Webhook + REST API | 监听 Issue / MR Note Hook（自建 GitLab 也行），需要公网 HTTPS |

### 通道配置 schema

`channels` 下每个条目都遵循同一骨架：

```json
"channels": {
  "<channelId>": {
    "type": "dingtalk | wecom | feishu | github | gitlab | chatui",
    "defaultAgentId": "main",
    "dmScope": "MAIN | PER_PEER | PER_CHANNEL_PEER | PER_ACCOUNT_CHANNEL_PEER",
    "disabled": false,
    "bindings": [ /* 可选路由规则 —— 见 ChannelRouter */ ],
    "properties": { /* 各通道私有配置，见下文示例 */ }
  }
}
```

> `agentscope.json` 当作普通 JSON 解析 —— **不会**展开 `${ENV_VAR}` 占位符。要么直接写明文，要么先用 `envsubst < agentscope.json.template > agentscope.json` 渲染再启动。

各通道（钉钉 Stream、企业微信回调、飞书事件、GitHub/GitLab Webhook）的逐步接入流程、回调地址、所需权限以及联调命令，请直接参考 **[英文版 README](README.md#run-agentscope-paw-locally-with-dingtalk)** 中对应章节 —— 命令本身与平台控制台均为英文/原文，避免反复转述失真。

### 在 agent 内主动外发消息

每个 agent 在 bootstrap 时都会自动注册 `outbound_send` 工具。任何让 agent 调它的 prompt 都行，比如：

> 用 `outbound_send` 工具，往钉钉用户 `dingstaff_001` 在 `dingtalk-dev` 通道发消息："部署完成"。

子 agent 跑完之后，`HarnessGateway.tryDispatchAnnounce` 会自动复用入站时的 `OutboundAddress`，所以完成通知会自然地回到当初触发它的那个钉钉 / 企微会话，无需额外接线。

### 默认开启的可靠性机制

| 机制 | 默认值 | 实现 |
| --- | --- | --- |
| 幂等去重 | 按 `<channelId>\|<msgId>` 去重，TTL 5 分钟，约 1 万条 | `IdempotencyStore` |
| Bot-loop 防护 | 每 peer 每 60 秒 20 条事件，超阈触发 60 秒冷却 | `BotLoopGuard` |
| 企微签名校验 | 按企业微信规范做 SHA-1(token, ts, nonce, encrypt) | `WeComCrypto` |
| 企微 AES-256-CBC 解密 | 43 位 base64 key + "=" → 32 字节 AES key，IV = 前 16 字节 | `WeComCrypto` |
| Access-token 续签 | 在 issued TTL 的 80% 时主动刷新 | 各 `*AccessTokenProvider` |

### 排错速查

- **启动后看不到钉钉 / 企微通道** —— 看启动日志的 `PawBootstrap initialized: ..., channels=[chatui, ...]` 是否包含你的 channelId。如果只有 `chatui`，说明这个条目要么被跳过（缺 `type`、`type` 未知，或 `disabled: true`），要么被工厂拒绝（上方应有 `Failed to instantiate channel` 的错误）。
- **企微 URL 校验返 401** —— `token` / `encodingAesKey` 与控制台的不一致。
- **钉钉 Stream 一直在重连** —— 多半是 `appKey`/`appSecret` 错、缺 Stream 订阅权限，或机器人尚未启用。客户端会按 1s → 60s 指数退避重试。
- **Outbound 返回 400 `peerId is required`** —— 群消息的 `peerId` 是平台侧的群 ID，不是 channelId：钉钉用 `openConversationId`，企微用群注册时的 chatId。
- **触发了 bot-loop 防护** —— 日志会有 `bot-loop guard cooldown`；60 秒窗口会自动复位。压测时单 peer 每分钟控制在 20 条以内即可。

## 配置

可识别的配置（全部在 `paw.*` 命名空间下，对应 `PAW_*` 环境变量）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `paw.home` | `~/.agentscope` | 内置 agent、自定义目录、agent workspace 的根目录 |
| `paw.dashscope.api-key` | _空_ | DashScope API key。设置后会自动创建 `DashScopeChatModel` Bean |
| `paw.dashscope.model-name` | `qwen-max` | 传给 DashScope 的模型名 |
| `paw.dashscope.stream` | `true` | 是否流式回复 |
| `paw.agent.name` | `paw` | 自动生成的 `default` agent 显示名 |
| `paw.agent.sys-prompt` | `You are a helpful local assistant. …` | 自动生成的 `default` agent 系统提示 |
| `server.port` | `8080` | HTTP 端口 |

如果你自己提供了 `Model` Spring Bean（例如再 `@Import` 一个 `@Configuration`），自动注入的 DashScope 模型会被跳过。

## 这个 fork 不再做的事

agentscope-paw 之前曾支持多租户部署、JWT 登录、按用户切分 workspace 命名空间、Docker sandbox 隔离、agent 共享。这些能力已经全部从 paw 中剥离 —— 详见 [`builder.md`](builder.md)，里面给出了被移除的模块清单以及从 git 历史中找回的方法。也可以直接迁到 [agentscope-builder](../agentscope-builder/) —— 那是 paw 多租户能力的正式归宿。

[AgentScope Java]: https://github.com/agentscope-ai/agentscope-java
