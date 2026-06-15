package io.agentscope.examples.harness.quickstart;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * QuickstartExample — 演示 HarnessAgent 中多个 Skill 协同工作的完整流程。
 *
 * <p>本示例注册了两个 Skill：
 *
 * <ul>
 *   <li><b>note-organizer</b>：将原始对话信息结构化整理，提取关键事实、时间、人物与主题
 *   <li><b>task-breakdown</b>：将整理后的笔记分解为可执行的任务清单，含优先级与依赖关系
 * </ul>
 *
 * <p>Skill 文件放在工作区 {@code workspace/skills/} 下，HarnessAgent 通过内置的
 * {@code DynamicSkillHook} 自动发现并注入到系统提示词中，无需手动创建 {@code SkillBox}。
 *
 * <p>运行前需设置环境变量 {@code DASHSCOPE_API_KEY}。
 */
public class QuickstartExample {

    public static void main(String[] args) throws Exception {
        // 1. 准备工作区：含 AGENTS.md 与两个 Skill 的 SKILL.md
        Path workspace = Paths.get(".agentscope/workspace");
        initWorkspaceIfAbsent(workspace);
        initSkillsIfAbsent(workspace);

        // 2. 构建模型
        Model model =
                DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen-max")
                        .stream(true)
                        .build();

        // 3. 构建 HarnessAgent：工作区注入、会话持久化、对话压缩；Skill 自动从 workspace/skills/ 加载
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("quickstart-agent")
                        .sysPrompt(
                                """
                                你是一个智能助手，可以使用两项专业技能协同工作：

                                1. note-organizer：将对话中的零散信息结构化整理为笔记卡片
                                2. task-breakdown：将笔记卡片分解为可执行任务清单

                                协作流程：先使用 note-organizer 整理信息 → 再用 task-breakdown 生成任务计划。

                                行为约定：
                                - 遇到需要整理的信息时，主动调用 note-organizer 技能
                                - 整理完成后，主动调用 task-breakdown 技能生成任务清单
                                - 回答用简洁中文，必要时给出要点列表
                                - 对不确定的内容要主动说明，不要臆造
                                """)
                        .model(model)
                        .workspace(workspace)
                        .compaction(
                                CompactionConfig.builder()
                                        .triggerMessages(30)
                                        .keepMessages(10)
                                        .flushBeforeCompact(true)
                                        .build())
                        .build();

        // 4. 同一个 RuntimeContext 发起多轮对话，演示 Skill 协同工作
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("demo-session").userId("alice").build();

        // 第一轮：提供原始信息，期望 Agent 使用 note-organizer 整理
        Msg turn1 =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent(
                                                """
                                                我叫天宇，下周五要在团队内做一个关于 ReAct 模式的技术分享。\
                                                大纲包括：ReAct 原理简介、AgentScope Java 中的实现、实际案例演示。\
                                                预计 40 分钟，听众约 15 人，主要是后端开发。\
                                                """)
                                        .build(),
                                ctx)
                        .block();
        System.out.println("===== 第一轮（信息整理）=====");
        System.out.println(turn1.getTextContent());
        System.out.println();

        // 第二轮：要求生成任务计划，期望 Agent 使用 task-breakdown
        Msg turn2 =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("请根据刚才整理的笔记，帮我生成详细的任务清单。")
                                        .build(),
                                ctx)
                        .block();
        System.out.println("===== 第二轮（任务分解）=====");
        System.out.println(turn2.getTextContent());
        System.out.println();

        // 第三轮：验证两个 Skill 协同效果 — 上下文记忆 + 任务追踪
        Msg turn3 =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .textContent("我之前说了什么？我的任务进度如何？")
                                        .build(),
                                ctx)
                        .block();
        System.out.println("===== 第三轮（状态回顾）=====");
        System.out.println(turn3.getTextContent());
    }

    // ---------- 工作区初始化 ----------

    private static void initWorkspaceIfAbsent(Path workspace) throws Exception {
        Files.createDirectories(workspace);
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            return;
        }
        Files.writeString(
                agentsMd,
                """
                # 智能助手

                你是一个帮助用户整理笔记和规划任务的智能助手。

                ## 可用技能
                - note-organizer：结构化整理对话信息
                - task-breakdown：将笔记分解为可执行任务

                ## 行为约定
                - 主动记录用户提到的关键事实（姓名、计划、偏好等）
                - 回答用简洁中文，必要时给出要点列表
                - 对不确定的内容要主动说明，不要臆造
                """);
    }

    // ---------- Skill 初始化 ----------

    private static void initSkillsIfAbsent(Path workspace) throws Exception {
        // Skill 1: note-organizer — 结构化整理笔记
        initSkillIfAbsent(
                workspace,
                "note-organizer",
                """
                ---
                name: note-organizer
                description: >-
                  Structures raw conversation information into organized notes. Extracts key facts,
                  names, dates, topics, and action items. Use when the user provides unstructured
                  information that needs to be organized, summarized, or categorized.
                ---

                # Note Organizer

                ## Purpose
                Transform unstructured user input into clean, structured note cards.

                ## Output Format
                Always produce notes in this structure:

                ```
                ## 笔记卡片

                **主题**：[一句话概括]
                **人物**：[涉及的人名及角色]
                **时间**：[关键日期/时间节点]
                **关键事实**：
                - 事实 1
                - 事实 2
                **行动项**：
                - [ ] 待办事项 1
                - [ ] 待办事项 2
                ```

                ## Guidelines
                - Extract ALL named entities (people, dates, places, topics)
                - Group related facts together
                - Mark uncertain information with `[待确认]`
                - Keep each bullet point concise (one sentence)
                - If the user mentions a plan, always extract action items
                """);

        // Skill 2: task-breakdown — 将笔记分解为可执行任务
        initSkillIfAbsent(
                workspace,
                "task-breakdown",
                """
                ---
                name: task-breakdown
                description: >-
                  Breaks down organized notes into actionable task lists with priorities and
                  dependencies. Use when the user needs a concrete action plan, to-do list,
                  or project timeline based on previously organized notes.
                ---

                # Task Breakdown

                ## Purpose
                Convert structured notes into an actionable task plan with clear priorities,
                estimates, and dependencies.

                ## Prerequisites
                This skill works best after `note-organizer` has structured the raw information.
                Read the organized notes first, then generate the task breakdown.

                ## Output Format

                ```
                ## 任务清单

                ### 高优先级
                - [ ] **任务名** ⏱ 预估时间
                  - 详情：具体描述
                  - 依赖：前置条件

                ### 中优先级
                - [ ] **任务名** ⏱ 预估时间
                  - 详情：具体描述

                ### 低优先级
                - [ ] **任务名** ⏱ 预估时间
                  - 详情：具体描述
                ```

                ## Guidelines
                - Each task must be concrete and verifiable
                - Assign priority based on urgency and dependency order
                - Include time estimates where possible
                - Mark dependencies between tasks explicitly
                - If the input contains a deadline, work backwards to schedule tasks
                - Tasks should be small enough to complete in one sitting (<4 hours)
                - For presentations/speeches: always include rehearsal and slide preparation tasks
                """);
    }

    private static void initSkillIfAbsent(Path workspace, String skillName, String skillMd)
            throws Exception {
        Path skillDir = workspace.resolve("skills").resolve(skillName);
        Path skillFile = skillDir.resolve("SKILL.md");
        if (Files.exists(skillFile)) {
            return;
        }
        Files.createDirectories(skillDir);
        Files.writeString(skillFile, skillMd);
        System.out.println("[init] Created skill: " + skillName);
    }
}
