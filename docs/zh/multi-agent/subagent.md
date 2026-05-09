# Subagents（子智能体）

**子智能体**是主编排智能体（orchestrator）委托工作的**专职智能体**。编排智能体不亲自执行这些工作，而是通过 **Task** 工具传入子智能体类型和任务描述来调用。系统在**独立上下文**（各自的系统提示与工具）中运行选定的子智能体，再将结果返回给编排智能体。这样主对话保持聚焦、避免上下文膨胀，同时仍可由不同专职智能体处理多类任务（如代码库探索、网络调研、依赖分析）。

该模式常被称为**分发–工作者**或**分层**模型：一个“管理”智能体按需将任务分发给“专家”子智能体。

## 为何使用子智能体？

| 优势 | 说明 |
|------|------|
| **上下文保持** | 重或杂的工作（如大代码库搜索、大量 URL）留在子智能体上下文中；只有摘要或相关片段回到主对话。 |
| **专职能力** | 每个子智能体有聚焦的系统提示和有限工具集，在该领域内行为一致。 |
| **可复用** | 子智能体可一次定义（如 Markdown 或共享 Bean），在多流程或项目中复用。 |
| **定义灵活** | 可用 **Markdown**（基于文件的规格）便于编辑与版本管理，或用 **Java**（编程式 ReActAgent）做完全控制。 |

当存在**多个明确领域**、希望**一个编排智能体**驱动流程，且子智能体**不需要**直接与用户对话（只向编排智能体回报）时，适合使用子智能体模式。

## 工作方式

1. **编排智能体**拥有名为 **Task** 的工具（以及可选的 **TaskOutput**，用于后台任务），还可拥有直接工具（如 `glob_search`、`grep_search`、`web_fetch`）处理简单单步工作。
2. 当用户请求复杂或偏某领域时，编排智能体**调用 Task 工具**，传入：
   - **subagent_type**：要运行的子智能体（如 `codebase-explorer`、`web-researcher`）
   - **prompt**：发给该子智能体的任务描述
3. 系统按类型**查找**子智能体，在**独立上下文**（其系统提示与工具）中执行该 prompt，并将子智能体的回复作为工具结果返回。
4. 编排智能体根据该结果（及可选的其他工具结果）**综合**出对用户的最终回答。

子智能体**每次调用无状态**：每次 Task 调用都是新的一次运行。编排智能体持有对话与规划状态；子智能体看不到完整聊天历史。

## 定义子智能体

有两种定义方式。

### 1. Markdown（基于文件）

子智能体在 **Markdown 文件**中描述，使用 **YAML front matter**。front matter 定义智能体的 `name`、`description` 和 `tools`；正文为该子智能体的**系统提示**。

**位置**：例如 `src/main/resources/agents/*.md`（classpath）。

**格式**：

```markdown
---
name: codebase-explorer
description: 用于快速探索代码库。用于查找文件、搜索代码、分析结构。工具：glob_search、grep_search。
tools: glob_search, grep_search
---

你是代码库探索专家，负责高效探索和分析代码库。

**能力：**
- 使用 glob 模式查找文件
- 使用正则搜索文件内容
- 分析项目结构与依赖

**指南：**
- 先用 glob_search 了解布局，再用 grep_search 查内容
- 提供简洁、结构化的发现
```

- **name**：唯一标识；编排智能体在调用 Task 工具时用此作为 `subagent_type`。
- **description**：说明编排智能体（及模型）何时委托给该子智能体；写清楚便于主智能体正确选择。
- **tools**：该子智能体可用的工具名（逗号分隔）。仅这些工具会被挂载，其它不可用，便于聚焦与安全。

加载器（如 `AgentSpecLoader`）读取这些文件，用共享 **Model** 和 **默认工具映射**（工具名 → 实例）为每个规格构建 ReActAgent。因此所有 Markdown 定义的智能体共享同一套底层工具实现（如 `glob_search`、`grep_search`、`web_fetch`），但每个规格限制该智能体只能看到其中一部分。

### 2. API（编程式）

需要完全控制时，在 Java 中构建 **ReActAgent** 并注册到 Task 工具构建器：

```java
ReActAgent dependencyAnalyzerReAct = ReActAgent.builder()
        .name("dependency-analyzer")
        .description("分析项目依赖。用于版本冲突、过时库、安全等问题。")
        .model(model)
        .sysPrompt(DEPENDENCY_ANALYZER_SYSTEM_PROMPT)
        .toolkit(depToolkit)  // 例如仅 glob_search、grep_search
        .memory(new InMemoryMemory())
        .build();

TaskToolsBuilder.builder()
        .model(model)
        .defaultToolsByName(defaultToolsByName)
        .subAgent("dependency-analyzer", dependencyAnalyzerReAct)
        // ... addAgentResource(...) 用于 Markdown 规格
        .build();
```

编排智能体即可通过 Task 工具传入 `subagent_type="dependency-analyzer"` 和任务描述。编程式子智能体可使用默认工具映射之外的自定义工具与逻辑。

## Task 工具构建器（AgentScope 示例）

示例使用 **TaskToolsBuilder** 创建 **Task** 与 **TaskOutput** 工具并注册所有子智能体：

1. **TaskToolsBuilder.builder()**
   - **model**：从 Markdown 规格构建 ReActAgent 时使用。
   - **defaultToolsByName**：工具名 → 实例的映射（如 `glob_search`、`grep_search`、`web_fetch`）。Markdown 规格在 `tools` 字段中按名称引用。
   - **taskRepository**：用于后台执行；即使只做同步 Task 调用也需配置。
   - **subAgent(type, ReActAgent)**：注册一个编程式子智能体。
   - **addAgentResource(Resource)**：从 classpath 或文件资源加载一个 Markdown 规格（如 `classpath:agents/codebase-explorer.md`）。

2. **build()**  
   将所有 Markdown 规格解析为 ReActAgent（通过 `AgentSpecLoader` 与工厂），与编程式子智能体合并，返回 **TaskToolsResult**，包含：
   - **taskTool()**：注册到编排智能体工具集的 Task 工具。
   - **taskOutputTool()**：用于查询或获取后台任务结果的 TaskOutput 工具。

3. **编排智能体工具集**  
   在编排智能体的 Toolkit 上注册 Task 与 TaskOutput，以及编排智能体可直接使用的工具（glob、grep、web_fetch）。编排智能体的系统提示应说明何时委托（何种请求用哪个 subagent_type）、何时使用直接工具。

**同步与后台**：默认 Task 工具会运行子智能体并返回其回复。若传入类似 `run_in_background=true` 的标志，工具会返回 `task_id`；编排智能体（或用户）之后可用 **TaskOutput** 传入该 `task_id` 获取结果。这需要 **TaskRepository** 存储进行中的任务。

## 示例：技术尽调助手

AgentScope 示例实现了一个**技术尽调助手**：一个编排智能体委托给四个子智能体。

| 子智能体 | 定义方式 | 工具 | 用途 |
|----------|----------|------|------|
| **codebase-explorer** | Markdown | glob_search, grep_search | 查找文件、搜索代码、分析结构 |
| **web-researcher** | Markdown | web_fetch | 抓取 URL、调研文档、对比技术 |
| **general-purpose** | Markdown | glob_search, grep_search, web_fetch | 代码 + 网络综合分析 |
| **dependency-analyzer** | API（Java） | glob_search, grep_search | 依赖、版本冲突、过时库 |

- **编排智能体**的系统提示说明何时使用各子智能体、何时使用直接工具；拥有 Task、TaskOutput、glob_search、grep_search、web_fetch。
- **Markdown 规格**位于 `src/main/resources/agents/*.md`，通过 `TaskToolsBuilder.addAgentResource(res)` 加载。
- **dependency-analyzer** 以 ReActAgent 构建，通过 `TaskToolsBuilder.subAgent("dependency-analyzer", dependencyAnalyzerReAct)` 注册。

**交互运行**：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/subagent spring-boot:run \
  -Dspring-boot.run.arguments="--subagent.run-interactive=true"
```

**代码调用**：注入 **OrchestratorService**，调用 `run(userMessage)`；服务会执行调用编排智能体的图。

```java
@Autowired
OrchestratorService orchestratorService;

String answer = orchestratorService.run(
    "分析该代码库的技术债务并调研 Spring AI 文档");
```

**配置**：`subagent.workspace-path`（默认 `${user.dir}`）为 glob_search、grep_search 的根路径；`subagent.run-interactive`（默认 `false`）用于启动时开启交互式对话。

## 子智能体与其他模式

- **Supervisor**：监督者模式也是中心智能体将专家当工具调用，但是**每个专家一个工具**（如 `schedule_event`、`manage_email`），角色通常更少、更稳定。子智能体使用**单一 Task 工具**，通过类型和 prompt 参数选择子智能体，支持大量子智能体（含 Markdown 定义）和可选后台执行。
- **Agent as Tool**：Agent as Tool 将一个子智能体注册为一个工具、固定名称与签名。子智能体使用**分发器**（Task 工具）按名称选择子智能体并传入自由格式任务字符串；子智能体常通过 Markdown 批量定义。
- **Routing**：路由对输入分类并转发给一个或多个专家再综合结果，通常不维护长对话。子智能体编排智能体维护对话，可在一轮或多轮中多次调用 Task（可能不同 subagent_type）。

## 最佳实践

- **写清子智能体描述**：编排智能体依赖 `description`（及自身系统提示）决定调用哪个子智能体。包含触发场景与示例（如“用于查找文件、搜索代码、分析结构”）。
- **限制每子智能体工具**：在 Markdown 中只列出该子智能体需要的工具，行为更可预测、误用风险更小。
- **系统提示要聚焦**：每个 Markdown 正文即该子智能体的系统提示；明确职责、步骤与输出格式，便于返回有用、一致的结果。
- **Markdown 规格纳入版本控制**：将 `agents/*.md` 放入仓库，便于团队在不改代码的情况下增删改子智能体。

## 相关文档

- [Supervisor](./supervisor.md) - 一个监督者，每个专家一个工具
- [Agent as Tool](../task/agent-as-tool.md) - 将智能体注册为工具
- [Pipeline](./pipeline.md) - 顺序与并行组合
