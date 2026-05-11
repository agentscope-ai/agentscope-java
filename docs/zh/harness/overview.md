# Harness 概览

`agentscope-harness` 在 `agentscope-core` 的 `ReActAgent` 之上，通过 Hook 和 Toolkit 两个扩展点，装配出一套面向**长期稳定运行**的工程化基础设施。用户入口只有一个类：`HarnessAgent`。

裸的 `ReActAgent` 只有"请求-推理-工具-回复"一轮循环。harness 要回答的是另一组问题：**下一轮怎么办、下一天怎么办、上下文爆了怎么办、状态丢了怎么办、任务太重怎么办**。它不替换推理循环，而是在循环的关键时机插入 hook、为模型补上一组基础工具，把这些问题的默认工程答案打包好。

## 快速开始

引入依赖：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

下面这个示例演示 harness 的三个核心价值：**工作区驱动的人格**、**会话持久化**（同一 `sessionId` 的第二轮对话能记得第一轮的内容）、**显式启用对话压缩**。第一次运行时会在 `${cwd}/.agentscope/workspace/` 自动生成 `AGENTS.md`，之后的运行复用。

```java
public class QuickstartExample {

    public static void main(String[] args) throws Exception {
        // 1. 准备工作区：第一次运行生成 AGENTS.md，后续运行复用
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);

        // 2. 构建模型
        Model model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-max")
                .stream(true)
                .build();

        // 3. 构建 HarnessAgent：工作区注入、会话持久化、追踪日志默认开启；
        //    这里显式启用对话压缩
        HarnessAgent agent = HarnessAgent.builder()
                .name("quickstart-agent")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model(model)
                .workspace(workspace)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .flushBeforeCompact(true)   // 压缩前把事实提取到日流水账
                        .build())
                .build();

        // 4. 同一个 RuntimeContext 发起两轮对话
        //    sessionId 相同 → 第二轮自动从 Session 恢复第一轮的状态
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        Msg turn1 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("我叫天宇,今天准备一个关于 ReAct 的技术分享。")
                        .build(),
                ctx).block();
        System.out.println("[turn1] " + turn1.getTextContent());

        Msg turn2 = agent.call(
                Msg.builder().role(MsgRole.USER)
                        .textContent("我叫什么?我今天要干什么?")
                        .build(),
                ctx).block();
        System.out.println("[turn2] " + turn2.getTextContent());
    }

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) return;
        Files.writeString(agentsMd, """
                # 笔记助手

                你是一个帮助用户整理笔记和知识的助手。

                ## 行为约定
                - 主动记录用户提到的关键事实(姓名、计划、偏好等)
                - 回答用简洁中文,必要时给出要点列表
                - 对不确定的内容要主动说明,不要臆造
                """);
    }
}
```

完整可运行版本：[`agentscope-examples/harness-example/src/main/java/io/agentscope/harness/example/QuickstartExample.java`](../../../agentscope-examples/harness-example/src/main/java/io/agentscope/harness/example/QuickstartExample.java)

运行：

```bash
export DASHSCOPE_API_KEY=your_key_here

# 首次运行需要把依赖模块 install 到本地仓库（跳过 javadoc 与 spotless）
mvn -pl agentscope-examples/harness-example -am install \
    -DskipTests -Dspotless.check.skip=true -Dmaven.javadoc.skip=true -q

# 执行 main
mvn -pl agentscope-examples/harness-example exec:java \
    -Dexec.mainClass=io.agentscope.harness.example.QuickstartExample \
    -Dspotless.check.skip=true -q
```

**运行后观察**：

- `.agentscope/workspace/AGENTS.md` 被自动创建 —— 这就是 agent 的人格来源
- 第二轮提问"我叫什么"能答出来，因为同一 `sessionId=demo-session` 的第二次 `call()` 在开头通过 `bindRuntimeContext` 自动从 `Session` 加载了第一轮的状态
- 多聊几轮触发压缩（消息数 ≥ 30）后，可以在 `workspace/memory/YYYY-MM-DD.md` 看到 LLM 提炼出来的事实流水账；后台的 `MemoryMaintenanceScheduler` 会继续把它合并到 `MEMORY.md`
- 下次重启进程、只要 `sessionId` 不变，agent 依然记得这一切

**关于 `RuntimeContext`**：它是当次 `call()` 的身份载体，`sessionId` 决定状态存放与日志归档位置，`userId` 决定默认文件系统的命名空间（天然的多租户隔离）。它**不会被持久化**，只在当次调用的 hook 与工具间共享。

**扩展方向**：在工作区里放 `KNOWLEDGE.md`、`skills/*/SKILL.md`、`subagents/*.md` 就能分别开启领域知识注入、技能加载、子 agent 编排；`.toolResultEviction(ToolResultEvictionConfig.defaults())` 一行启用大结果卸载；**文件/命令的落点**用 [Filesystem — 三种声明式模式](./filesystem.md#三种声明式模式) 选择 **共享存储、沙箱或本机+shell**；需隔离执行时优先 `filesystem(SandboxFilesystemSpec)`（见 [Sandbox](./sandbox.md)），`abstractFilesystem` 仅作自管后端的逃生口。

## 核心能力

每一项能力对应**一个问题 → 一个组件**：

- **工作区上下文注入** —— 解决 *agent 的身份从哪里来*。每次推理前由 `WorkspaceContextHook` 把 `AGENTS.md`、`MEMORY.md`、今日记忆、`KNOWLEDGE.md` 注入 system prompt。工作区即 agent 的"人格与知识库"。
- **双层持久记忆** —— 解决 *对话里的事实如何跨会话沉淀*。`MemoryFlushHook` 在压缩前用 LLM 把对话提炼到日流水账；`MemoryConsolidator` 在后台把日流水账合并去重到长期 `MEMORY.md`。下次上线仍然能用。
- **对话压缩与溢出恢复** —— 解决 *历史太长怎么办*。`CompactionHook` 在消息/Token 超阈值时摘要历史、保留尾部；模型真的报 context overflow 时，`HarnessAgent` 捕获错误、强制压缩、自动重试。
- **大工具结果卸载** —— 解决 *单次工具返回过大*。`ToolResultEvictionHook` 把超限结果落盘到文件系统，上下文里只留占位符 + 预览，agent 可以按需回读。
- **会话持久化** —— 解决 *状态如何跨进程保留*。`SessionPersistenceHook` 按 `sessionId` 把 agent 状态写入工作区，下次调用自动从断点恢复。
- **子 agent 编排** —— 解决 *复杂任务如何分解*。`SubagentsHook` 注入 `task` / `task_output` 工具，主 agent 可同步或后台委派子 agent；子 agent 可由工作区规格文件、编程式 spec、自定义工厂声明。
- **可插拔文件系统** —— 解决 *agent 的环境如何收敛与隔离*。所有文件工具都走 `AbstractFilesystem`；通过 [三种声明式模式](./filesystem.md#三种声明式模式)（本机+shell、复合+Store、沙箱）或 `abstractFilesystem` 自管；配合 `RuntimeContext.userId` 与 `IsolationScope` 做多租户/会话级隔离。隔离执行与沙箱状态恢复见 [Sandbox](./sandbox.md)。

此外还有几项围绕以上能力服务的基础设施：`RuntimeContext` 贯穿整次调用、`MemoryMaintenanceScheduler` 在后台做合并与索引维护、`AgentTraceHook` 统一追踪日志、`AgentSkillRepository` 自动装配 `SkillBox`。

## 能力如何共同构成一个稳定运行的 Agent

把这些能力合起来看，它们其实分别支撑着"持续稳定"的三根支柱：

- **身份持续** —— *工作区上下文注入* 每轮把人格和知识重新喂给模型；*双层持久记忆* 把对话里有价值的事实沉淀回工作区；*Skill 自动加载* 让可复用能力跟着工作区走。于是 agent 的人格和知识不随单次调用结束而消失，而是在工作区里不断累积。
- **上下文可控** —— *对话压缩* 控制深度，*工具结果卸载* 控制宽度，*溢出恢复* 是最后一道兜底。三者合在一起保证在任意长度的会话里，上下文都不会把模型压垮；真的压垮了，也能无感恢复。
- **状态可恢复** —— *会话持久化* 保证进程重启能从断点继续；*RuntimeContext* 把当次调用的身份（sessionId/userId）贯穿到所有 hook 和工具；*可插拔文件系统* 让"状态究竟落在哪里"（本地磁盘、沙箱、远端）变成一个配置选择。

这三根支柱之间靠三个共享对象串起来：`WorkspaceManager`（谁来读写工作区）、`AbstractFilesystem`（工作区落在哪里）、`RuntimeContext`（当次调用是谁在说话）。每个 hook 只做自己的事，通过这三个对象和其它 hook 协作——这就是 harness 把一组独立能力合成一个"持续稳定 agent"的方式。

## 能力如何注入到 Agent

`HarnessAgent` 是 `Agent` + `StateModule` 的薄包装，内部持有一个 `ReActAgent delegate`，能力注入全部发生在 `HarnessAgent.Builder.build()`：

- **Hook 通道**：按 `priority` 把若干 hook 交给 `ReActAgent`（含沙箱模式下的 `SandboxLifecycleHook` 等，详见 [Architecture](./architecture.md)）
- **Toolkit 通道**：在用户 `Toolkit` 上追加 `filesystem`、`memory_search`、`memory_get`、`session_search`，沙箱后端额外加 `shell_execute`；`SubagentsHook` 自己注册 `task` / `task_output`
- **SkillBox 通道**：从 `workspace/skills/` 或自定义 `AgentSkillRepository` 自动构造 `SkillBox`

每次 `call()` 开头由 `bindRuntimeContext` 把当次的 `RuntimeContext` 分发给所有实现了 `RuntimeContextAwareHook` 的 hook，并按需从 `Session` 恢复状态。

> 各组件的详细行为、触发时机和时序图见 [Architecture](./architecture.md)。

## 延伸阅读

- [Architecture](./architecture.md) — 各组件定义、生命周期时序图、协作关系
- [工作区（Workspace）](./workspace.md) — 工作区目录结构与上下文注入
- [记忆（Memory）](./memory.md) — 双层记忆、对话压缩与全文检索
- [文件系统（Filesystem）](./filesystem.md) — 三种声明式模式与 `AbstractFilesystem` 层次
- [沙箱（Sandbox）](./sandbox.md) — 隔离执行、沙箱状态与分布式选项
- [子 Agent（Subagent）](./subagent.md) — 子 agent 规格与编排
- [工具（Tool）](./tool.md) — 内置工具参考
- [会话（Session）](./session.md) — 会话持久化与状态恢复