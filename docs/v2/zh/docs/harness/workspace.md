---
title: "工作区（Workspace）"
description: "目录结构、注入到 system prompt 的内容、tools.json、多用户隔离"
---

## 作用

工作区是 `HarnessAgent` 的"地基"：人格、长期记忆、领域知识、子 agent 声明、技能定义、会话日志、子任务记录、计划文件、`tools.json` 工具配置，统一以**目录 + Markdown/JSON** 的形式落地，不再散落在代码里。

每次推理时，工作区里的几个关键文件会被自动注入到 system prompt；运行过程中的记忆、会话与任务记录也会按既定路径回写到这里。

## 一个最小的工作区

```
.agentscope/workspace/
├── AGENTS.md          ← 必备：agent 的人格 + 行为约定
├── MEMORY.md          ← 可选：策划过的长期记忆（agent 自己维护）
├── knowledge/         ← 可选：领域知识入口 KNOWLEDGE.md + 任意参考文件
├── memory/            ← 自动生成：每日事实流水账
├── skills/            ← 可选：技能目录，每个子目录里放 SKILL.md
├── subagents/         ← 可选：子 agent spec，文件名即 agent_id
├── plans/             ← 自动生成：Plan Mode 写下的计划文件
├── tools.json         ← 可选：MCP server + 工具白名单
└── agents/<agentId>/  ← 自动生成：会话快照、对话日志、子任务记录
    ├── context/<sessionId>/      会话快照
    ├── sessions/                 对话日志（永不压缩） + 索引
    └── tasks/                    子任务记录
```

只有 `AGENTS.md` 是真正需要你写的（不写也能跑，只是会丢人格段）。其他目录在你启用对应能力时自动产生：

- 启用记忆压缩 → `memory/` + `MEMORY.md`
- 放子 agent spec → `subagents/`
- 装技能 → `skills/`
- 启用 Plan Mode → `plans/`

## system prompt 里被注入的内容

每轮推理前，框架按下面这个结构拼一段文本，合并到第一条 system 消息：

| 段落 | 来源 | 大小限制 |
|------|------|---------|
| `## Session Context` | 模板生成（日期、操作系统、workspace 路径、当前 `sessionId`） | 不限 |
| `## Workspace` 等使用说明 | 内置模板 | 不限 |
| AGENTS context | `AGENTS.md` 全文 | 不限 |
| MEMORY context | `MEMORY.md`（受 token 预算约束） | `maxContextTokens`，默认约 8000 |
| Domain knowledge | `knowledge/KNOWLEDGE.md` + `knowledge/` 下其他文件的路径目录 | 全文 + 路径列表 |
| Additional context files | 你通过 `.additionalContextFile("X.md")` 添加的任意文件 | 全文 |

`MEMORY.md` 估算超出"剩余预算"时会按字符截断并附一行"已截断 — 用 `memory_search` 查更早的"，提示模型改走搜索。

## 配置

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // 不传则用默认 ${user.dir}/.agentscope/workspace
    .additionalContextFile("SOUL.md")                // 任意工作区相对路径
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // 控制 MEMORY 的注入上限
    .build();
```

`AGENTS.md` 至少写一份骨架：

```markdown
# MyAgent

你是 XX 助手，遵循以下行为约定。

## 行为
- ...
- ...
```

## `tools.json` —— 工具白名单 + MCP

把 `tools.json` 放到工作区根目录，框架在构建时自动读它：

```jsonc
{
  // 白名单：非空时只允许列出的工具
  "allow": ["read_file", "grep_files", "execute"],
  // 黑名单：列出的工具一律不暴露（优先级高于 allow）
  "deny":  ["write_file"],
  // MCP server：键是名字，值是连接配置
  "mcpServers": {
    "amap": {
      "transport": "streamableHttp",
      "url": "https://mcp.amap.com/mcp?key=${AMAP_API_KEY}"
    },
    "local-py": {
      "transport": "stdio",
      "command": "python",
      "args": ["mcp_servers/my_server.py"],
      "env": {"PYTHONUNBUFFERED": "1"}
    }
  }
}
```

行为：

- MCP server 在构建期一次性注册到 toolkit，agent 看到的就是这些 server 暴露的工具
- `allow` / `deny` 在所有工具（含 Harness 内置）注册完之后才应用，所以也会过滤掉内置工具——慎用

不想用文件？也可以直接在 builder 上编程注入，或者用 `disableToolsConfig()` 完全关掉读取。

## 多用户共用同一个工作区

通过 `RuntimeContext.userId` 让同一个 agent 实例服务多个用户而互不污染：

- 写到 `<userId>/skills/<name>/SKILL.md` 的技能只对那个用户可见，可以覆盖工作区共用版
- 文件系统模式不同，user 隔离的位置也不同：
  - 默认（本机）：路径前缀 → `workspace/alice/...`
  - 远端 KV：KV 命名空间前缀
  - 沙箱：沙箱状态的 slot key

具体见 [文件系统](./filesystem) 与 [Context](./context)。

## 工作区相对路径的规则

`additionalContextFile`、`writeUtf8WorkspaceRelative`、`memory_get` 这些接口都接受**工作区相对路径**。框架会做基本的 path-traversal 校验，防止 `../../etc/passwd` 这种逃出工作区的写法。

需要写入文件时，**通过 Harness 的工作区管理器**而不是 `java.nio.Files`——后者在沙箱或远端文件系统模式下会写到错的地方。如果你只是写在 builder 装配时的初始化逻辑里（例如示例里 `initWorkspaceIfAbsent`），那时还没有运行时上下文，用 `java.nio.Files` 是 OK 的。

## 相关文档

- [架构](./architecture) — system prompt 是怎么拼出来的
- [文件系统](./filesystem) — 工作区在物理上落到哪里（本机 / 沙箱 / 共享存储）
- [记忆](./memory) — `MEMORY.md` 与 `memory/` 怎么生成与维护
- [Context](./context) — `agents/<agentId>/` 下面的会话快照和对话日志
