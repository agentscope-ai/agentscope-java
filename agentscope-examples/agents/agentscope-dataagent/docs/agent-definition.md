# Agent 定义方式

本文档说明在 agentscope-dataagent 中定义和配置 Agent 的所有方式。

## 概览

agentscope-dataagent 通过 `DataAgentBootstrap` 组装 Agent。Bootstrap 启动后由
Spring 包装为 Web 应用，每个登录用户都会得到一份基于 `(userId, agentId)` 的
独立 workspace。

支持三种定义方式，可以单独使用，也可以混合使用：

| 方式 | 适用场景 | 是否需要改代码 |
|---|---|---|
| [agentscope.json 文件](#方式一agentscopejson-文件) | 生产部署、多 Agent、需要版本管理 | 否 |
| [application.yml 配置](#方式二applicationyml-配置仅限-web-模块) | 快速试用、单 Agent、零配置启动 | 否 |
| [Java 代码注入](#方式三java-代码注入) | 注入自定义工具、动态构建、测试 | 是 |

GLOBAL 与 USER 两种 scope 的区别：

- **GLOBAL** —— `agentscope.json` 里声明的 agent（v1 默认只有内置 `data-agent`），
  系统启动时由 `DataAgentBootstrap` 注册到 `HarnessGateway`，所有租户共享同一份
  agent 定义（但每个租户仍拥有独立的 `CompositeFilesystem`）。
- **USER** —— 任一登录用户通过 `/api/me/agents` 创建/克隆出的 agent，由
  `JpaUserAgentDefinitionStore` 持久化，按需懒加载，注册 id 形如
  `uda-{userId}-{agentId}`。这些 agent 只对其所有者可见，永远不会被其他用户
  路由到。

---

## 方式一：`agentscope.json` 文件

**文件位置**：`${dataagent.workspace}/.agentscope/agentscope.json`
（`workspace` 默认为 JVM 工作目录，可通过 `application.yml` 中的
`dataagent.workspace` 覆盖）

### 完整示例

```json
{
  "$schema": "...",
  "main": "data-agent",
  "agents": {
    "data-agent": {
      "name": "Data Agent",
      "sysPrompt": "你是一个 Data Agent…（被 workspace/AGENTS.md 覆盖）",
      "workspace": ".agentscope/workspace",
      "maxIters": 20,
      "description": "Tenant-isolated data-analysis assistant."
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
  },
  "channels": {
    "chatui": { "defaultAgentId": "data-agent", "dmScope": "MAIN" },
    "ops-webhook": {
      "type": "webhook",
      "defaultAgentId": "data-agent",
      "dmScope": "MAIN",
      "properties": {
        "sharedSecret": "${WEBHOOK_SECRET}",
        "allowedIps": ["10.0.0.0/8"]
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
| `workspace` | String | `.agentscope/workspace` | workspace 目录（相对 `${dataagent.workspace}`） |
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

DataAgent 自身另外维护一份 **shared** skill 目录
（`${dataagent.workspace}/.agentscope/shared/skills/`），通过 `OverlayFilesystem`
挂载在每个租户 workspace 的 `skills/` 之下，作为下层只读源。这是
[marketplace 贡献流程](../README.md#marketplace-flow) 落盘的目标位置 ——
管理员通过审批 API 写入这里，所有租户的下一次 agent 构建即可看到。

### workspace 目录结构

```
workspace/
├── AGENTS.md          # Agent 角色定义（自动注入系统提示，优先级高于 sysPrompt）
├── subagents/         # Subagent 声明文件（*.md）
├── skills/            # Skill 目录（每个子目录一个 Skill；与 shared/ overlay 合并）
└── knowledge/         # 知识库文件（管理员只读维护，租户通过贡献流程提议变更）
```

### 顶层字段

| 字段 | 说明 |
|---|---|
| `main` | 主 Agent 的 id（`DataAgentBootstrap.Builder.mainAgent(id)` 可覆盖） |
| `agents` | Agent 定义 Map，key 为 agent id |
| `channels` | Channel 配置 Map（v1 支持 `chatui` / `dingtalk` / `webhook`） |

---

## 方式二：`application.yml` 配置（fallback）

若 `${dataagent.workspace}/.agentscope/agentscope.json` **不存在**，
`DataAgentConfig` 会根据 `application.yml` 的 `dataagent.agent.*` 属性
**自动生成**一份单 Agent 配置（id 固定为 `data-agent`）：

```yaml
dataagent:
  agent:
    name: Data Agent
    sys-prompt: |
      You are a Data Agent built with AgentScope. You help users explore,
      analyse, visualise and report on data.
```

等价于自动写出：

```json
{
  "main": "data-agent",
  "agents": {
    "data-agent": {
      "name": "Data Agent",
      "sysPrompt": "You are a Data Agent…",
      "workspace": ".agentscope/workspace",
      "maxIters": 20
    }
  },
  "channels": {
    "chatui": { "defaultAgentId": "data-agent", "dmScope": "MAIN" }
  }
}
```

> **注意**：配置生成后会落盘，后续直接编辑 `agentscope.json` 文件即可。

---

## 方式三：Java 代码注入

通过 `DataAgentBootstrap.Builder` 的 API 在代码中控制 Agent 的构建，适合需要
注入 Spring Bean（数据库连接、API 客户端）或动态参数的场景。

### 3a. 传入预构建的 `HarnessAgent` 实例

```java
HarnessAgent myAgent = HarnessAgent.builder()
    .name("code-agent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))
    .sysPrompt("你是一个专注于代码审查和重构的助手。")
    .maxIters(15)
    .build();

DataAgentBootstrap dataagent = DataAgentBootstrap.builder()
    .agent("code-agent", myAgent)   // 注册预构建 Agent
    .mainAgent("code-agent")        // 设为主 Agent
    .build();
```

### 3b. 用 `configureAgent` 叠加文件配置

文件（`agentscope.json`）声明结构，代码追加动态参数。两者在 `build()` 时合并，
代码侧优先。

```java
DataAgentBootstrap dataagent = DataAgentBootstrap.builder()
    .model(model)
    .configureAgent("data-agent", builder -> {
        builder.maxIters(30);
        builder.environmentMemory("数据库：PostgreSQL 17\nschema: " + loadSchema());
    })
    .configureAgent("analyst", builder -> {
        builder.environmentMemory("可用数据集：sales_2025, user_events");
    })
    .build();
```

### 3c. 跳过配置文件

```java
DataAgentBootstrap dataagent = DataAgentBootstrap.builder()
    .skipConfigFile(true)        // 完全忽略 agentscope.json
    .agent("data-agent", prebuilt)
    .build();
```

### 3d. 注入工具（与 marketplace 配合）

DataAgent 启动时通过 `@PostConstruct` 注册两类内建工具到所有 GLOBAL agent：

- `tools/data/DataAgentToolkit` —— `list_data_sources` / `describe_table` /
  `run_sql_preview` / `render_chart`（通过 `DataSourceRegistry` 与
  `ChartRenderer` SPI 注入实现，未提供时使用 in-memory / stub 默认实现）。
- `web/marketplace/ContributeWorkspaceTool` —— `contribute_to_workspace`
  让 agent 自身可以发起贡献（仍需经管理员审批）。

如需为某个 GLOBAL agent 注入自定义工具，提供一个 `DataSourceRegistry` 或
`ChartRenderer` `@Bean`（Spring 会自动用你的实现替换默认 stub），或在
`configureAgent(...)` 里调用 `builder.tool(...)` 注册任意工具。

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
agentscope.json       → 定义 GLOBAL agent（id、name、sysPrompt、channels）
configureAgent        → 注入 Spring 管理的工具、运行时 environmentMemory
application.yml       → API Key、模型名、JWT 密钥、Redis、JPA 等部署参数
shared/{skills,…}/    → 通过 /api/admin/contributions 审批落盘，所有租户共享
/api/me/agents        → 单个登录用户自助创建 USER scope agent
```
