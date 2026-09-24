# Workspace 定义与运行空间统一改造

日期：2026-09-09。实现位置：项目主目录，`agentscope-service-v5`。

## 概念与权限

Namespace 继续作为资源归属和授权边界。Workspace 是 Namespace 内可复用的能力定义；Agent 可以关联其中一个已发布版本，也可以保留私有定义。Workspace 不是新的租户边界，也不授权用户读取其他人的 Issue、Session 或执行过程。

运行空间是运行时为 Session、任务或会话准备的实际目录。Managed 使用已有的 Session 隔离机制；External 消费者每次执行新建目录；Hosted 使用 Runtime Host 已有的任务或会话工作目录策略。Runtime inventory 展示实例上报的路径和元数据，不能据此推断目录为空，也不提供执行数据的访问授权。

共享知识继续通过 Memory Store 显式关联。会话日志、私有 working memory、输入、输出和产物不作为 Workspace 发布内容。发布和运行投影排除 `sessions/`、`memory/`、`logs/`、`inputs/`、`outputs/`、`artifacts/`、`.git/`、根目录 `MEMORY.md` 等运行状态；定义文件可包括 `AGENTS.md`、`skills/`、`subagents/`、参考资料。

文件分类是产品约定，不能识别任意文件里的敏感内容。作者仍需只把可共享的内容放入定义目录。

## 发布与绑定

- Workspace 日常修改只更新草稿。`workspace_revisions` 保存不可变内容快照；版本号与原有草稿 `head_version` 分开。
- 发布快照包含 instructions、工具配置、MCP、Skill 引用和定义文件。相同内容重复发布复用已有的最新版本；摘要用于识别定义内容。
- Agent 的 `workspaceBinding` 保存 `version`、`digest`、`overrides` 和 Agent-specific `instructions`。`version=0` 明确表示发布当前草稿后绑定；正数引用已发布版本。
- 默认继承 Workspace 的 tools、mcpServers、skills。覆盖列表只接受这三个字段；覆盖 skills 且列表为空表示禁用所有 Skill。
- 最终 system 由 Workspace instructions 加 Agent-specific instructions 组成。额外指令单独保存，升级 Workspace 不会重复追加。
- Agent 更新继续使用版本冲突检查。Agent 历史保存完整解析结果、定义文件、Workspace 版本和摘要。
- Workspace 草稿修改不再自动刷新任何关联 Agent，包括旧数据。历史记录不批量重写；没有 `workspaceBinding` 的旧 Agent 可在统一页面显式选择发布版本，进入新绑定模型。
- 解除绑定后采用 Agent 私有文件空间；不会自动把共享文件复制为私有文件。工具等已解析字段在未修改时保留。

UI 在 Resources → Workspaces 中展示发布版本和关联 Agent；三种 Agent 都开放 Definition。在 Definition → Workspace 选择版本、设置继承和额外指令，并查看保存后定义的运行时兼容性。已绑定的 Skills/Subagents/Files 页面展示 Agent 快照，而非正在变化的 Workspace 草稿。私有文件支持保存草稿，然后通过 Publish private files 生成 Agent 版本。

## 三类运行时

| 能力 | Managed | External + Workspace consumer | Hosted |
| --- | --- | --- | --- |
| instructions | Harness 原生 | Harness 原生 | Provider prompt / 原生配置适配 |
| Skill 声明和文件 | Harness Skill 引用与过滤 | Harness Skill 过滤 | 原生技能目录或 context directory，按 Provider 展示 |
| 工具与权限 | 已有 Managed ToolsConfig 映射 | 默认工具、工具选择和权限映射 | 已有 Provider 工具策略映射 |
| MCP | Harness MCP | Harness MCP；应用提供运行条件 | 按 Provider 声明；OpenClaw 当前不支持 |
| Subagent 文件 | Harness 加载 | Harness 加载 | 当前适配器未声明支持，使用时明确拒绝 |
| 日志、私有记忆 | Session 运行空间 | 应用本地每次执行的目录 | Runtime Host 的任务或会话目录 |

Hosted 下发定义在调度时冻结，领取任务不再重新读取 Agent 最新定义。历史没有定义快照的任务保留原有兼容路径。运行投影只下发启用的 Skill 文件，避免 CLI 目录扫描加载禁用的 Skill；完整文件仍留在版本查看接口中。

Runtime Host 执行前检查请求能力。不支持的能力产生 `workspace_capability_unsupported` 失败，不把复制了 Subagent 文件等同于具备 Subagent 执行能力。Provider 的 `mode` 和 `target` 展示实际适配方式，不统一伪装成 Harness 原生能力。

Managed 沿用原有 Agent 版本解析和 Session 文件物化流程。这次并未改变其会话内刷新策略；不要把 Hosted 的调度快照语义推广为所有 Managed 会话永不刷新。

## External 显式接入

已有 External 应用继续使用自己的本地 Harness 配置。只有应用明确安装 `WorkspaceAgentFactory` 后，SDK 才声明 `workspace-definition-v1`。平台为配置了定义的 Agent 选择具备该能力的健康实例；未启用的实例不会被当作消费者调度。

接入示意（`collaboration`、本地 Agent、模型注册和 sandbox 由应用提供）：

```java
var starter = new HarnessAgentTaskStarter(() -> localAgent, collaboration)
        .withWorkspaceFactory(new HarnessWorkspaceAgentFactory(
                Path.of("./runtime-workspaces"),
                () -> HarnessAgent.builder().model(model)));
// 把 starter 配置到现有 AgentScopeAdapter；在注册实例前完成此配置。
```

工厂为每次执行创建独立目录，校验路径和符号链接，然后加载冻结定义。模型名、MCP 命令、凭据和环境要求必须能在应用环境中解析；不能从控制平面的路径推断外部机器上存在对应内容。需要自定义凭据、工具、模型或本地策略映射的应用应实现 `WorkspaceAgentFactory`。内置工厂把平台工具配置与权限应用到新 Builder；应用仍需提供恰当的 sandbox 和进程权限。

加载成功后 SDK 用当前任务 token 上报 definition digest。控制平面只接受与该任务冻结定义一致的摘要，并按 attempt 幂等记录。兼容性页面展示最近加载的 Agent/Workspace 版本和时间；这是应用上报的加载结果，不代表任务已成功完成。

执行结束关闭本次 Harness；目录保留供应用按自身策略管理。内置工厂不会上传日志或私有 memory，也不自动清理本地目录。旧实例必须升级 SDK、启用工厂并重新注册后才能消费平台定义。本次改造不自动替用户启用或重启应用。

## 接口与存储

- `POST /api/workspaces/:id/publish`：发布草稿。
- `GET /api/workspaces/:id/revisions`：该 Workspace 发布历史及内容。
- `GET /api/workspaces/:id/agents`：关联 Agent。
- `POST /api/v1/agents/:agentId/definition`：为没有定义的 Agent 初始化可移植定义。
- `PATCH /api/v1/agents/:agentId/definition`：版本校验、绑定和显式覆盖。
- `GET /api/v1/agents/:agentId/workspace-capabilities`：保存后定义对各绑定的兼容性及加载回执。
- `POST /api/v1/agent-tasks/:taskId/workspace-application`：当前执行 token + 冻结摘要验证的加载回执。

新增 `workspace_revisions` 和 `workspace_applications`，随控制平面数据库迁移创建。数据按现有资源 owner/Namespace 分区访问。新定义管理接口复用 Namespace 权限，回执接口复用当前 attempt 的任务认证。可见两个 Namespace 不意味着可跨边界绑定 Workspace 或调度 Agent。

## 验证

- PostgreSQL 集成测试：发布去重、草稿与绑定隔离、历史不可变、跨 owner 引用拒绝、显式覆盖、版本冲突、禁用 Skill 的运行文件投影。
- 调度测试：Hosted 调度后更新定义不改变领取快照；External 拒绝普通实例并选择显式消费者。
- HTTP 回执测试：缺失定义、缺失 attempt、摘要不一致被拒绝。
- SDK 测试：文件物化、私有状态排除、路径越界和符号链接、工具选择、MCP allowlist 与权限。
- 前端单元测试与构建；Playwright 检查 Hosted 版本升级及 Skill 禁用、External 普通成员只读，以及页面运行错误。

验证采用隔离测试数据库和前端 preview，不写入用户的运行中服务。CLI/模型的真实远端调用以及用户外部应用接入需要在各自环境中验证；自动测试不等同于这些服务已部署。

本次验证结果：Go `go test -p 2 ./...` 通过；隔离 PostgreSQL 下 product/httpapi 测试通过；aistiod 构建通过。Java SDK 定向测试 27 项通过；前端单元测试 134 项、Playwright 3 项通过，TypeScript/Vite 构建通过。未执行整个 Java 多模块 `mvn clean verify`，未部署或重启运行中服务。
