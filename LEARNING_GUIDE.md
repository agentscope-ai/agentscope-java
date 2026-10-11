# AgentScope Java 2.0 学习指南

## 一、项目概述

AgentScope Java 2.0 是一个**生产级分布式代理框架**，提供企业级代理构建能力。

### 核心特性
- **事件系统**: 28种类型事件，支持实时UI渲染和HITL人机交互
- **权限系统**: 工具调用审批（允许/需确认/拒绝）
- **中间件**: AOP风格的推理-执行循环扩展
- **工作空间与沙箱**: 隔离执行环境（本地/Docker/K8s）
- **多代理编排**: 子代理定义与同步/后台执行
- **分布式部署**: 跨节点会话恢复（Redis/MySQL/PostgreSQL）

## 二、模块结构

```
agentscope-java/
├── agentscope-core/          # 核心模块（ReActAgent、消息、事件、权限）
├── agentscope-harness/       # 生产级封装（HarnessAgent、工作空间、沙箱）
├── agentscope-extensions/    # 扩展模块
│   ├── model-openai/         # OpenAI 模型
│   ├── model-anthropic/      # Anthropic Claude
│   ├── model-dashscope/      # 阿里云 DashScope
│   ├── model-gemini/         # Google Gemini
│   └── model-ollama/         # Ollama 本地模型
├── agentscope-service/       # 代理控制面板和仪表板
└── agentscope-examples/      # 示例代码
```

## 三、核心类图

```
┌─────────────────────────────────────────────────────────────────┐
│                        HarnessAgent (生产级)                     │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   ReActAgent (核心引擎)                    │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────┐  │  │
│  │  │  Model  │  │ Toolkit │  │AgentState│  │PermissionEngine│  │
│  │  └─────────┘  └─────────┘  └─────────┘  └─────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
│  + WorkspaceManager  + AbstractFilesystem  + SubagentMiddleware │
│  + SkillRepository   + PlanModeManager     + CompactionMiddleware│
└─────────────────────────────────────────────────────────────────┘
```

## 四、关键类说明

### 4.1 ReActAgent (核心推理引擎)
**文件**: `agentscope-core/.../ReActAgent.java`

实现 ReAct (Reasoning + Acting) 模式：
- **Reasoning**: 调用 LLM 进行思考和规划
- **Acting**: 执行工具调用完成具体任务
- 两阶段交替循环，直到完成或达到 maxIters

### 4.2 HarnessAgent (生产级封装)
**文件**: `agentscope-harness/.../HarnessAgent.java`

包装 ReActAgent，增加：
- 工作空间管理
- 沙箱隔离
- 子代理编排
- 技能自学习
- 上下文压缩

### 4.3 MiddlewareBase (中间件基类)
**文件**: `agentscope-core/.../middleware/MiddlewareBase.java`

5个拦截点（洋葱模型）：
| 拦截点 | 触发时机 | 典型用途 |
|--------|---------|---------|
| onAgent | 整个代理调用 | 日志、鉴权、限流 |
| onReasoning | LLM推理阶段 | 提示词注入 |
| onActing | 工具执行阶段 | 权限检查 |
| onModelCall | 原始API调用 | Token计量 |
| onSystemPrompt | 系统提示词构建 | 上下文注入 |

### 4.4 AgentState (会话状态)
**文件**: `agentscope-core/.../state/AgentState.java`

持久化的会话状态：
- `context`: 对话历史
- `summary`: 滚动摘要
- `permissionContext`: 权限规则
- `toolContext`: 激活的工具组
- `planModeContext`: 计划模式状态

### 4.5 Toolkit (工具管理)
**文件**: `agentscope-core/.../tool/Toolkit.java`

工具注册与执行：
- 工具注册（@Tool注解/AgentTool接口）
- 工具分组与激活
- 并行/串行执行
- MCP服务器集成

### 4.6 Msg (消息模型)
**文件**: `agentscope-core/.../message/Msg.java`

消息角色：
- `USER`: 用户输入
- `ASSISTANT`: 代理回复
- `SYSTEM`: 系统提示
- `TOOL`: 工具结果

内容块类型：TextBlock, ImageBlock, ToolUseBlock, ToolResultBlock...

### 4.7 AgentEvent (事件系统)
**文件**: `agentscope-core/.../event/AgentEvent.java`

28种事件类型，支持：
- 实时UI渲染（TEXT_BLOCK_DELTA）
- HITL交互（REQUIRE_USER_CONFIRM）
- 工具调用追踪（TOOL_CALL_START/END）

### 4.8 PermissionEngine (权限引擎)
**文件**: `agentscope-core/.../permission/PermissionEngine.java`

权限行为：
- `ALLOW`: 允许执行
- `DENY`: 拒绝执行
- `ASK`: 需要用户确认（HITL）

### 4.9 Model (模型抽象)
**文件**: `agentscope-core/.../model/Model.java`

LLM抽象层：
- 统一流式调用接口
- 支持多提供商（OpenAI/Anthropic/DashScope/Gemini/Ollama）

### 4.10 RuntimeContext (运行时上下文)
**文件**: `agentscope-core/.../agent/RuntimeContext.java`

单次调用上下文（非持久化）：
- 会话/用户标识
- 临时属性传递
- 工具执行上下文

## 五、执行流程

```
call()
  │
  ├─→ beforeAgentExecution()  ─→ 激活会话slot，加载AgentState
  │
  ├─→ Middleware Chain (onAgent)
  │       │
  │       ├─→ doCall()
  │       │       │
  │       │       ├─→ reasoning()  ─→ 调用LLM，流式输出
  │       │       │       │           Middleware(onReasoning, onModelCall)
  │       │       │       │
  │       │       │       ↓
  │       │       ├─→ acting()     ─→ 执行工具，权限检查
  │       │       │       │           Middleware(onActing)
  │       │       │       │
  │       │       └─── (循环直到完成或maxIters)
  │       │
  │       └─→ saveStateToSession()
  │
  └─→ afterAgentExecution()
```

## 六、快速开始

```java
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;

// 1. 构建代理
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .sysPrompt("You are a helpful AI assistant.")
    .model("dashscope:qwen-plus")  // 或 "openai:gpt-4.1"
    .workspace(Paths.get(".agentscope/workspace"))
    .build();

// 2. 创建运行时上下文
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("demo")
    .userId("alice")
    .build();

// 3. 调用代理
// 阻塞调用
agent.call("Hello!", ctx).block();

// 流式调用
agent.streamEvents("Summarize today.", ctx)
    .doOnNext(event -> {
        if (event instanceof TextBlockDeltaEvent delta) {
            System.out.print(delta.getDelta());
        }
    })
    .blockLast();
```

## 七、关键代码文件（含中文注释）

以下文件已添加详细中文学习注释：

### 7.1 核心模块 (agentscope-core)

| 文件 | 说明 |
|-----|------|
| `agentscope-core/.../ReActAgent.java` | 核心推理引擎 |
| `agentscope-core/.../middleware/MiddlewareBase.java` | 中间件基类 |
| `agentscope-core/.../state/AgentState.java` | 会话状态 |
| `agentscope-core/.../tool/Toolkit.java` | 工具管理 |
| `agentscope-core/.../message/Msg.java` | 消息模型 |
| `agentscope-core/.../event/AgentEvent.java` | 事件系统 |
| `agentscope-core/.../permission/PermissionEngine.java` | 权限引擎 |
| `agentscope-core/.../model/Model.java` | 模型抽象 |
| `agentscope-core/.../agent/RuntimeContext.java` | 运行时上下文 |

### 7.2 生产级封装 (agentscope-harness)

| 文件 | 说明 |
|-----|------|
| `agentscope-harness/.../HarnessAgent.java` | 生产级代理封装 |

### 7.3 示例模块 (agentscope-examples)

| 文件 | 说明 |
|-----|------|
| `quickstart/BasicChatExample.java` | 基础对话示例 |
| `quickstart/UserIsolatedMultiTurnsExample.java` | 多租户隔离示例 |
| `tool/ToolCallingExample.java` | 工具调用示例 |
| `tool/ToolExecutionContextExample.java` | 工具POJO注入示例 |
| `streaming/AgentEventStreamExample.java` | 事件流示例 |
| `middleware/CustomizedMiddlewareExample.java` | 自定义中间件示例 |
| `middleware/SystemPromptMiddlewareExample.java` | 系统提示词注入示例 |
| `hitl/PermissionHITLExample.java` | 权限HITL示例 |
| `state/StateExample.java` | 状态持久化示例 |
| `context/RuntimeContextExample.java` | 运行时上下文示例 |
| `model/ModelRegistryExample.java` | 模型注册表示例 |
| `structuredoutput/StructuredOutputExample.java` | 结构化输出示例 |
| `mcp/McpStdioExample.java` | MCP协议示例 |
| `multimodal/VisionExample.java` | 多模态视觉示例 |
| `skill/AgentSkillExample.java` | 技能仓库示例 |

### 7.4 文档示例模块 (agentscope-examples/documentation2)

以下示例全部已添加中文学习注释块，包含 ASCII 架构图、代码模式、运行指令：

#### 异步工具 (harness/async/)
| 文件 | 说明 |
|-----|------|
| `AsyncToolExample.java` | 异步工具卸载（3 种交付模式）|
| `AsyncSubagentExample.java` | 后台子代理执行 |
| `AsyncWaitModeExample.java` | Mode C 主动等待模式 |

#### 计划模式 (harness/planmode/)
| 文件 | 说明 |
|-----|------|
| `PlanModeManualExample.java` | 手动计划模式 (HITL 审批) |
| `PlanModeAutoExample.java` | 自动计划模式 |

#### 工作空间 (harness/workspace/)
| 文件 | 说明 |
|-----|------|
| `WorkspaceLocalShellExample.java` | 本地文件系统 + Shell |
| `WorkspaceSharedStoreExample.java` | Redis 分布式存储 |

#### 通道 (harness/channel/)
| 文件 | 说明 |
|-----|------|
| `ChannelSendExample.java` | ChatUiChannel 消息发送 |
| `GatewayMultiAgentExample.java` | 多代理网关路由 |

#### 记忆/上下文 (harness/memory/, context/)
| 文件 | 说明 |
|-----|------|
| `MemoryCompactionExample.java` | 记忆压缩 |
| `ContextStatePersistenceExample.java` | 无状态引擎模型 |
| `RuntimeContextExample.java` | 运行时上下文 |

#### 权限 (permission/)
| 文件 | 说明 |
|-----|------|
| `PermissionContextExample.java` | 权限上下文状态 |

#### 中间件 (middleware/)
| 文件 | 说明 |
|-----|------|
| `HookStopAgentExample.java` | RequestStopEvent 中止代理 |
| `ModelCallMiddlewareExample.java` | onModelCall 钩子 |

#### 工具 (tool/)
| 文件 | 说明 |
|-----|------|
| `ToolBaseExample.java` | ToolBase 自定义工具 |
| `ToolStreamingExample.java` | 工具流式输出 |
| `ToolCallingWithConverterExample.java` | 自定义结果转换器 |
| `MultiModalToolExample.java` | 多模态工具 |
| `InterruptionExample.java` | 用户中断机制 |

#### 流式输出 (streaming/)
| 文件 | 说明 |
|-----|------|
| `StreamingConsoleExample.java` | 控制台流式输出 |
| `StreamingWebExample.java` | Spring Boot SSE |

#### 技能 (skill/)
| 文件 | 说明 |
|-----|------|
| `SkillCompositionExample.java` | 4 层技能组合 |
| `SkillWithToolGroupExample.java` | 技能绑定工具组 |
| `GitSkillRepositoryExample.java` | Git 远程技能仓库 |

#### MCP 协议 (mcp/)
| 文件 | 说明 |
|-----|------|
| `McpSseExample.java` | MCP SSE 传输 |
| `McpStreamableHttpExample.java` | MCP Streamable HTTP |

#### 状态/路由 (state/, router/)
| 文件 | 说明 |
|-----|------|
| `StateAutoSaveExample.java` | 状态自动保存/恢复 |
| `StructuredOutputFallbackExample.java` | 结构化输出降级 |
| `RoutingByToolCallsExample.java` | 工具调用路由模式 |

## 八、学习路径建议

1. **入门阶段**
   - 阅读 README.md 了解项目定位
   - 运行 agentscope-examples 中的示例
   - 理解 ReActAgent 的 Reasoning + Acting 循环

2. **核心理解**
   - 深入阅读 ReActAgent 源码（已添加中文注释）
   - 理解 MiddlewareBase 的洋葱模型
   - 掌握 AgentState 和 RuntimeContext 的区别

3. **进阶应用**
   - 学习 HarnessAgent 的生产级能力
   - 理解 PermissionEngine 的 HITL 流程
   - 掌握 Toolkit 的工具注册与执行

4. **生产部署**
   - 学习分布式状态存储（MySQL/Redis/PostgreSQL）
   - 理解沙箱隔离（Docker/K8s/E2B）
   - 掌握多代理编排

## 九、参考资源

- [官方文档](https://java.agentscope.io/)
- [GitHub仓库](https://github.com/agentscope-ai/agentscope-java)
- [Discord社区](https://discord.gg/eYMpfnkG8h)
