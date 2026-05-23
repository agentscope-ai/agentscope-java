# Agent 定义方式

本文档说明在 agentscope-claw 中定义和配置 Agent 的所有方式。

## 概览

agentscope-claw 通过 `ClawBootstrap` 组装 Agent。支持三种定义方式，可以单独使用，也可以混合使用：

| 方式 | 适用场景 | 是否需要改代码 |
|---|---|---|
| [agentscope.json 文件](#方式一agentscopejson-文件) | 生产部署、多 Agent、需要版本管理 | 否 |
| [application.yml 配置](#方式二applicationyml-配置仅限-web-模块) | 快速试用、单 Agent、零配置启动 | 否 |
| [Java 代码注入](#方式三java-代码注入) | 注入自定义工具、动态构建、测试 | 是 |

---

## 方式一：`agentscope.json` 文件

**文件位置**：`${cwd}/.agentscope/agentscope.json`（`cwd` 为 JVM 工作目录，可通过 `ClawBootstrap.builder().cwd(path)` 覆盖）

### 完整示例

```json
{
  "$schema": "...",
  "main": "default",
  "agents": {
    "default": {
      "name": "通用助手",
      "sysPrompt": "你是一个智能助手。",
      "workspace": ".agentscope/workspace",
      "maxIters": 10,
      "description": "处理日常问题的通用助手",
      "environmentMemory": "当前产品版本：v2.0"
    },
    "analyst": {
      "name": "数据分析师",
      "sysPrompt": "你是一个专业的数据分析师。",
      "workspace": ".agentscope/workspace-analyst",
      "skillRepository": {
        "type": "filesystem",
        "path": ".agentscope/skills"
      }
    }
  }
}
```

### `agents.<id>` 字段说明

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `name` | String | agent id | Agent 显示名 |
| `description` | String | — | Agent 功能描述（用于 subagent 路由决策） |
| `sysPrompt` | String | — | 系统提示词（与 workspace `AGENTS.md` 叠加，后者优先） |
| `workspace` | String | `.agentscope/workspace` | workspace 目录（相对 cwd） |
| `maxIters` | Integer | HarnessAgent 默认值 | ReAct 最大迭代轮数 |
| `environmentMemory` | String | — | 注入 Agent 的额外上下文（如 schema、业务规则） |
| `skillRepository` | Object | — | Skill 仓库配置（见下） |

### Skill 仓库配置

```json
{
  "skillRepository": {
    "type": "filesystem",
    "path": ".agentscope/skills"
  }
}
```

```json
{
  "skillRepository": {
    "type": "git",
    "remoteUrl": "https://github.com/your-org/your-skills.git",
    "branch": "main",
    "localPath": ".agentscope/skills-git",
    "autoSync": true
  }
}
```

| type | 说明 | 依赖 |
|---|---|---|
| `filesystem` | 从本地目录加载（每个子目录含 `SKILL.md`） | 无 |
| `git` | 从 Git 仓库克隆并加载 | `agentscope-extensions-skill-git-repository` |

### workspace 目录结构

```
workspace/
├── AGENTS.md          # Agent 角色定义（自动注入系统提示，优先级高于 sysPrompt）
├── subagents/         # Subagent 声明文件（*.md）
│   ├── analyst.md
│   └── formatter.md
├── skills/            # Skill 目录（每个子目录一个 Skill）
│   └── data-query/
│       └── SKILL.md
└── knowledge/         # 知识库文件
    └── KNOWLEDGE.md
```

### 顶层字段

| 字段 | 说明 |
|---|---|
| `main` | 主 Agent 的 id（`ClawBootstrap.Builder.mainAgent(id)` 可覆盖） |
| `agents` | Agent 定义 Map，key 为 agent id |
| `channels` | Channel 配置 Map（可选，key 为 channel id） |

---

## 方式二：`application.yml` 配置（fallback）

若 `.agentscope/agentscope.json` **不存在**，`ClawConfig` 会根据 `application.yml` 的 `claw.agent.*` 属性**自动生成**一份单 Agent 配置。

```yaml
claw:
  agent:
    name: 我的助手
    sys-prompt: |
      你是一个帮助用户处理工作任务的智能助手。
      回答简洁、准确，中文提问用中文回答。
```

等价于自动写出：

```json
{
  "main": "default",
  "agents": {
    "default": {
      "name": "我的助手",
      "sysPrompt": "你是一个帮助用户处理工作任务的智能助手。...",
      "workspace": ".agentscope/workspace"
    }
  }
}
```

> **注意**：配置生成后会落盘，后续直接编辑 `agentscope.json` 文件即可。

---

## 方式三：Java 代码注入

通过 `ClawBootstrap.Builder` 的 API 在代码中控制 Agent 的构建，适合需要注入 Spring Bean（数据库连接、API 客户端）或动态参数的场景。

### 3a. 传入预构建的 `HarnessAgent` 实例

```java
HarnessAgent myAgent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .sysPrompt("你是一个专注于代码审查和重构的助手。")
    .maxIters(15)
    .build();

ClawBootstrap claw = ClawBootstrap.builder()
    .agent("code-agent", myAgent)   // 注册预构建 Agent
    .mainAgent("code-agent")        // 设为主 Agent
    .build();
```

### 3b. 用 `configureAgent` 叠加文件配置

文件（`agentscope.json`）声明结构，代码追加动态参数。两者在 `build()` 时合并，代码侧优先。

```java
ClawBootstrap claw = ClawBootstrap.builder()
    .model(model)
    .configureAgent("default", builder -> {
        builder.maxIters(20);
        builder.environmentMemory("数据库：PostgreSQL 17\nschema: " + loadSchema());
        // builder.tool(myCustomTool);  // 注入自定义工具（若 API 支持）
    })
    .configureAgent("analyst", builder -> {
        builder.environmentMemory("可用数据集：sales_2025, user_events");
    })
    .build();
```

### 3c. 跳过配置文件

```java
ClawBootstrap claw = ClawBootstrap.builder()
    .skipConfigFile(true)        // 完全忽略 agentscope.json
    .agent("main", prebuilt)
    .build();
```

---

## 优先级与合并规则

当多种方式同时存在时，`build()` 按以下优先级处理：

```
高                                                              低
┌──────────────────┬──────────────────┬──────────────────┬──────────────────┐
│  3a              │  3b              │  1               │  2               │
│  .agent(id, ha)  │  .configureAgent │  agentscope.json │  auto-generate   │
│  预构建实例       │  代码定制         │  文件声明         │  (web 模块兜底)   │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┘
```

- **3a** 完全绕过文件，直接注册现成实例，对同一 id 文件中的声明不生效
- **3b** 在文件声明基础上叠加，可覆盖 `maxIters`、`environmentMemory` 等，也可追加工具
- **1** 文件声明是基础，`3b` 中未覆盖的字段保持文件值
- **2** 仅当文件不存在时触发，相当于自动写入方式 1 的配置

### 典型生产用法

```
agentscope.json       → 定义 Agent id、name、sysPrompt、workspace
configureAgent        → 注入 Spring 管理的工具、运行时 environmentMemory
application.yml       → API Key、模型名、JWT 密钥等部署参数
```
