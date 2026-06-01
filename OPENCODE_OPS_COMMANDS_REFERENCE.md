# OpenCode 运维/会话命令清单（供 AgentScope-Java 借鉴）

> 调研对象：`/Users/ken/agentscope-2/opencode`
> 关键源码：
> - `packages/opencode/src/cli/cmd/tui/app.tsx`（系统级 / 会话级 / Agent 级 命令）
> - `packages/opencode/src/cli/cmd/tui/routes/session/index.tsx`（会话内操作）
> - `packages/opencode/src/cli/cmd/tui/component/prompt/index.tsx`（输入态命令）
> - `packages/opencode/src/cli/cmd/tui/feature-plugins/system/diff-viewer.tsx`（diff 浏览）
> - `packages/opencode/src/cli/cmd/tui/feature-plugins/session/index.tsx`
> - `packages/opencode/src/command/index.ts`（命令模型与默认命令注册）
> - `packages/web/src/content/docs/tui.mdx`（用户向命令文档）
> - `packages/web/src/content/docs/commands.mdx`（自定义命令文档）
> 调研时间：2026-05-31

OpenCode 的 TUI 走"统一命令注册中心 + 槽位（slot）+ slash 别名 + leader 键绑定"的模型：

- 每条命令既可在 **命令面板（`ctrl+p`）** 中点击执行，也可在输入框输入 `/<name>`，
  还能用 `ctrl+x <key>` 快捷键触发，三种入口共享同一份注册表（`Command` schema）。
- 每条命令含字段：`name`（内部 ID）、`title`（显示名）、`category`（分组）、
  `slashName` + `slashAliases`（用户可见别名）、`run`、`hidden`/`enabled`/`suggested`。
- 用户可在配置中扩展自定义命令（`opencode.json` 的 `command` 字段或 MCP/Skill 注入）。

下文按"运维语义"分类，便于映射到 agentscope-java。

---

## 1. 会话生命周期与上下文管理（最核心的"运维"位）

| 命令 | 别名 | 快捷键 | 作用 |
|---|---|---|---|
| `/compact` | `/summarize` | `ctrl+x c` | **压缩当前会话上下文**（让模型对历史做摘要后清空），claude code 同名 |
| `/new` | `/clear` | `ctrl+x n` | 开新会话（清空当前上下文） |
| `/sessions` | `/resume`、`/continue` | `ctrl+x l` | 列出并切换历史会话 |
| `/undo` | — | `ctrl+x u` | 回滚最近一条用户消息 + 关联文件改动（依赖 git） |
| `/redo` | — | `ctrl+x r` | 重做被 `/undo` 撤销的内容 |
| `/timeline` | — | — | 跳到会话中的某条消息 |
| `/fork` | — | — | 从某个时间点 fork 出新会话 |
| `/rename` | — | — | 重命名会话 |
| `/copy` | — | — | 拷贝整段会话 transcript 到剪贴板 |
| `/export` | — | `ctrl+x x` | 导出 transcript 为 Markdown 并打开外部编辑器 |
| `/share` | — | — | 共享会话（生成可分享 URL） |
| `/unshare` | — | — | 取消共享 |

**借鉴点**：`/compact`、`/new`、`/sessions`、`/undo`、`/redo`、`/export` 是几乎所有 AI Agent CLI（Claude Code、Aider、Continue 等）都会落地的"会话管控五件套"。
对 agentscope-java 来说，"压上下文 / 切会话 / 撤消最后一轮 / 导出 transcript / fork 会话" 都能直接映射到 `AgentState` 的快照、回滚、序列化能力上。

---

## 2. 模型 / Agent / Provider 切换

| 命令 | 别名 | 快捷键 | 作用 |
|---|---|---|---|
| `/models` | — | `ctrl+x m` | 切换模型（弹出 model dialog） |
| `/variants` | — | — | 切换模型 variant（同模型不同推理参数，如 thinking on/off） |
| `/agents` | — | — | 切换 Agent（subagent / 角色） |
| `/mcps` | — | — | 启用/禁用 MCP 服务器 |
| `/connect` | — | — | 添加 provider（录入 API key） |
| `/org`、`/orgs`、`/switch-org` | — | — | 在多组织 console 下切换组织 |

附带的 hidden 命令（仅快捷键，无 slash）：
`model.cycle_recent` / `model.cycle_recent_reverse` / `model.cycle_favorite` / `model.cycle_favorite_reverse` / `agent.cycle` / `variant.cycle`
—— 用 `ctrl+x →/←` 类按键在最近 / 收藏列表里循环切换，无需弹窗。

**借鉴点**：模型切换不只是"列表选择"，还有"循环切换最近用过的 N 个"这种盲操作快捷路径，对长会话很省事。

---

## 3. 系统 / 应用级

| 命令 | 别名 | 快捷键 | 作用 |
|---|---|---|---|
| `/help` | — | — | 显示帮助对话框（罗列所有命令 + 快捷键） |
| `/status` | — | — | 显示运行状态（连接、模型、token 余额等） |
| `/themes` | — | `ctrl+x t` | 切换主题 |
| `/exit` | `/quit`、`/q` | `ctrl+x q` | 退出 |
| `/init` | — | — | 引导式生成 `AGENTS.md`（项目说明文件，类似 CLAUDE.md） |
| `/review` | — | — | 内置的 code-review 命令（基于 prompt 模板） |
| `app.debug` | — | — | 切换 debug 面板（hidden，无 slash） |
| `app.console` | — | — | 切换控制台 |
| `theme.switch_mode` | — | — | 切换 light/dark |
| `theme.mode.lock` | — | — | 锁定主题模式 |
| `docs.open` | — | — | 浏览器打开官方文档 |

**借鉴点**：
- `/init` 是一个非常值得抄的设计——让模型根据当前仓库结构自动生成项目级 prompt/约束文档（claude code 也有同名命令）。
- `/status` 是会话内"自检"入口：当前模型、剩余 token、连接的 provider/MCP，一站式查看。

---

## 4. 输入 / 提示词侧

| 命令 | 别名 | 快捷键 | 作用 |
|---|---|---|---|
| `/editor` | — | `ctrl+x e` | 调用 `$EDITOR`（vim / code --wait …）撰写多行 prompt |
| `/skills` | — | — | 列出可用 skill 并把 `/skill ` 写到输入框 |
| `/diff` | — | — | 打开 diff 浏览器查看本轮文件改动 |
| `/timestamps` | `/toggle-timestamps` | — | 显示/隐藏每条消息的时间戳 |
| `/thinking` | `/toggle-thinking` | — | 显示/隐藏思考块（reasoning） |
| `/details` | — | — | 显示/隐藏工具调用细节 |
| `/warp`（实验） | — | — | 切换会话所在 worktree |

**借鉴点**：`/editor` 解决"长 prompt 在终端难写"，`/skills` 是 prompt-as-skill 的入口，`/diff` 把"看本轮代码改动"做成了 first-class 命令——agentscope-java 如果有"工具调用历史 + 文件改动追踪"，把它做成命令体感更好。

---

## 5. 自定义命令机制（运维者扩展）

OpenCode 把命令做成可扩展系统，三个来源（`source`：`command` | `mcp` | `skill`）：

1. **内置命令模板**：`packages/opencode/src/command/template/initialize.txt`、`review.txt` 这种纯 prompt 文件，注册时只是字符串模板。
2. **用户自定义命令**：在 `opencode.json` 的 `command` 字段声明，结构：
   ```json
   {
     "command": {
       "doctor": {
         "description": "run env doctor",
         "agent": "...",
         "model": "...",
         "template": "...prompt with $1, $2, $ARGUMENTS placeholders..."
       }
     }
   }
   ```
   `$1`/`$ARGUMENTS` 由 `hints()` 从模板抽取（见 `command/index.ts:43-51`）。
3. **MCP prompts** 和 **Skill** 也会自动转为 slash 命令注入到注册表。

**借鉴点**：让命令是声明式的、纯 prompt 可加载——把"内置 + 用户自定义 + 第三方扩展"统一为同一个运行时模型。这与 Claude Code 的 `~/.claude/commands/*.md` 做法一致，agentscope-java 可以走同一套设计。

---

## 6. 业界产品命令对照（速查）

下面是用户提到的 Claude Code CLI 等产品里的常见运维命令，许多与 OpenCode 重合，可一起作为参考池：

### Claude Code CLI（已知集合，节选自其官方 docs）
- `/compact`（压缩上下文）
- `/clear`（清当前会话）
- `/cost`（查看本会话 token 消耗）
- `/memory`（查看 / 编辑 CLAUDE.md，相当于 opencode 的 `/init` 反向）
- `/model`（切模型）
- `/agents`（管理 subagent）
- `/login` / `/logout`
- `/permissions`（查看/调整工具权限）
- `/config`（打开设置）
- `/init`（生成 CLAUDE.md）
- `/review`（评审 PR）
- `/hooks`（管理 hook）
- `/mcp`（管理 MCP server）
- `/help`、`/exit`、`/status`、`/resume`
- `/doctor`（环境自检）
- `/ide`、`/install-github-app`、`/security-review`
- `/vim`、`/terminal-setup`

### Cursor / Continue / Aider 常见
- `/edit`、`/diff`、`/apply`、`/test`、`/run`、`/explain`、`/fix`、`/gen`

### OpenCode 独有但值得抄的
- `/fork`（从历史某点分叉）
- `/timeline`（跨消息跳转）
- `/redo` 与 `/undo` 配套（基于 git 还原文件）
- `/share` + `/unshare`（一键托管会话）
- `/variants`（模型 variant 切换，比 `/model` 更细粒度）
- `/skills`（skill 即命令）
- `/warp`（worktree 切换）

---

## 7. 给 AgentScope-Java 的建议命令分组（落地清单）

按"必须有 → 锦上添花"排序：

### 必备（最小可用集）
- `/help` — 列命令
- `/exit` `/quit` — 退出
- `/clear` `/new` — 新会话
- `/sessions` — 切换会话
- `/compact` `/summarize` — 压缩 AgentState 上下文
- `/model` `/models` — 切模型
- `/agents` — 切 Agent / 子 Agent
- `/status` — 查看连接、token、当前 Agent/model
- `/init` — 生成项目级 AGENTS.md / CLAUDE.md
- `/cost` — token 消耗（claude code 风格）
- `/memory` — 查看/编辑 long-term memory
- `/export` — 导出 transcript

### 进阶（基于 AgentState 重构后能力）
- `/undo` `/redo` — 回滚到上一 AgentState 快照
- `/fork` — 在某个 step 分叉新会话
- `/timeline` — 在 AgentState 历史上跳转
- `/diff` — 查看本轮 file/state 变更
- `/mcps` — 启用/禁用 MCP
- `/connect` — 录入 provider key
- `/skills` — Skill 即命令
- `/permissions` — 工具权限审阅

### 系统级
- `/themes` — 切主题
- `/editor` — 外部编辑 prompt
- `/doctor` — 环境自检（JDK 版本、网络、provider 连通性）
- `/review` — 内置 PR/diff review prompt
- `/hooks` — 管理生命周期 hook

---

## 8. 设计模式上的关键收获

1. **统一命令注册表**：内置命令、自定义命令、MCP、Skill 全部用同一个 `Command` 接口，只用 `source` 字段区分来源。
2. **三入口共享同一注册项**：命令面板（`ctrl+p`）/ slash 输入 / leader 快捷键 三套 UX 共用一份元数据。
3. **`hidden` + `enabled` + `suggested` 三态**：决定"出现在面板里 / 默认建议 / 仅快捷键可达"。
4. **slash 可有 `aliases`**：例如 `/compact` 同时绑 `/summarize`、`/new` 同时绑 `/clear`，迁移用户更友好。
5. **prompt 模板用占位符 `$1..$N` / `$ARGUMENTS`**：天然支持参数化命令。
6. **运维命令尽量做成 hidden 快捷键 + 可选 slash**：例如"循环切换最近用过的 model"只配快捷键不占用 slash 命名空间。

---

## 9. 推荐落地的命令注册接口（伪 Java）

```java
public interface AgentCommand {
    String name();                  // 内部 ID, e.g. "session.compact"
    String title();                 // UI 标题
    String category();              // "Session" / "Agent" / "System" / ...
    Optional<String> slashName();   // /compact
    List<String> slashAliases();    // /summarize
    Optional<String> keybind();     // ctrl+x c
    boolean hidden();
    boolean enabled(AgentContext ctx);
    void run(AgentContext ctx);
}

// 来源
enum CommandSource { BUILTIN, USER, MCP, SKILL }
```

`AgentContext` 需要至少能拿到：当前 `AgentState`、Session、Provider、Tool 列表、I/O。

---
