/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * ============================================================================
 * 【学习示例】技能组合 - SkillCompositionExample
 * ============================================================================
 *
 * 演示四层技能组合模型：工作空间技能 + 类路径市场技能合并。
 *
 * 1. 技能层次（优先级从高到低）
 *    ┌─────────────────────────────────────────────────────────┐
 *    │  Layer 4: 运行时技能 (代理动态加载)                     │
 *    │  Layer 3: 工作空间技能 (workspace/skills/)              │
 *    │  Layer 2: 类路径市场 (resources/skills/)               │
 *    │  Layer 1: 内置技能 (框架默认)                          │
 *    └─────────────────────────────────────────────────────────┘
 *    同名技能：高层覆盖低层
 *
 * 2. 工作空间技能目录结构
 *    workspace/
 *    └── skills/
 *        ├── code-reviewer/
 *        │   └── SKILL.md
 *        └── translator/
 *            └── SKILL.md
 *
 * 3. SKILL.md 文件格式
 *    ---
 *    name: code-reviewer
 *    description: 代码审查技能
 *    ---
 *
 *    # Code Reviewer
 *    Review the given code for:
 *    1. Correctness
 *    2. Style
 *    ...
 *
 * 4. ClasspathSkillRepository
 *    // 从 JAR 内 resources/skills/ 加载
 *    ClasspathSkillRepository repo = new ClasspathSkillRepository("skills");
 *    List<String> names = repo.getAllSkillNames();
 *
 * 5. 多源合并
 *    HarnessAgent agent = HarnessAgent.builder()
 *        .workspace(workspace)              // 层3: 工作空间技能
 *        .skillRepository(classpathRepo)    // 层2: 类路径市场
 *        .build();
 *
 *    // 代理系统提示中看到 <available_skills> 块
 *    // 调用 load_skill_through_path 加载技能详情
 *
 * 6. 技能加载工具
 *    代理可调用:
 *    - load_skill_through_path: 读取技能 SKILL.md 内容
 *    - list_available_skills: 列出所有可用技能
 *
 * 7. 运行方式
 *    export DASHSCOPE_API_KEY=your_key
 *    mvn exec:java -pl agentscope-examples/documentation \
 *        -Dexec.mainClass=io.agentscope.examples.documentation2.harness.skill.SkillCompositionExample
 *
 * ============================================================================
 */

package io.agentscope.examples.documentation2.harness.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SkillCompositionExample — Demonstrates the four-layer skill composition model with workspace
 * skills and classpath marketplace skills merged together.
 *
 * <p>What this example shows:
 * <ol>
 *   <li><b>Workspace skills</b> — a skill placed in {@code workspace/skills/<name>/SKILL.md}
 *       is automatically discovered and available to the agent.</li>
 *   <li><b>Classpath marketplace</b> — {@link ClasspathSkillRepository} loads skills bundled
 *       inside the JAR at {@code resources/skills/}.</li>
 *   <li><b>Multi-layer merge</b> — workspace skills (layer 3) take priority over marketplace
 *       skills (layer 2). Non-conflicting skills from both layers are visible.</li>
 *   <li><b>Agent skill usage</b> — the agent sees an {@code <available_skills>} block in its
 *       system prompt and calls {@code load_skill_through_path} to read a skill's details.</li>
 * </ol>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.harness.skill.SkillCompositionExample
 * </pre>
 */
public class SkillCompositionExample {

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Skill — Multi-Layer Skill Composition");
        System.out.println("=".repeat(60) + "\n");

        // ── Create workspace with a skill ───────────────────────────────────

        Path workspace = Files.createTempDirectory("agentscope-skill-example");

        // Workspace skill (layer 3): code-reviewer
        Path codeReviewerDir = Files.createDirectories(workspace.resolve("skills/code-reviewer"));
        Files.writeString(
                codeReviewerDir.resolve("SKILL.md"),
                """
                ---
                name: code-reviewer
                description: Use when the user asks for code review, style feedback, or quality checks.
                ---

                # Code Reviewer

                Review the given code for:
                1. Correctness — logic errors, off-by-one, null safety
                2. Style — naming conventions, formatting, idiomatic patterns
                3. Performance — unnecessary allocations, O(n^2) traps
                4. Security — injection, path traversal, sensitive data exposure

                Give a 1-5 score and list specific issues by line.
                """);

        // Workspace skill (layer 3): translator
        Path translatorDir = Files.createDirectories(workspace.resolve("skills/translator"));
        Files.writeString(
                translatorDir.resolve("SKILL.md"),
                """
                ---
                name: translator
                description: Use when the user asks to translate text between languages.
                ---

                # Translator

                Translate the given text accurately. Preserve formatting and tone.
                If the target language is not specified, translate to English.
                """);

        System.out.println("Workspace skills created:");
        System.out.println("  workspace/skills/code-reviewer/SKILL.md");
        System.out.println("  workspace/skills/translator/SKILL.md");

        // ── Load classpath marketplace skill (layer 2) ──────────────────────

        // The "summarizer" skill is bundled at resources/skills/summarizer/SKILL.md
        ClasspathSkillRepository classpathRepo = new ClasspathSkillRepository("skills");
        System.out.println("\nClasspath marketplace skills: " + classpathRepo.getAllSkillNames());

        // ── Build the agent with both skill sources ─────────────────────────

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("skill-demo")
                        .sysPrompt(
                                "You are a versatile assistant with multiple skills. When the"
                                        + " user's request matches a skill, load it first using"
                                        + " load_skill_through_path, then follow its instructions.")
                        .model("qwen3.7-plus")
                        .workspace(workspace)
                        .skillRepository(classpathRepo)
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("skill-demo").build();

        // ── Ask the agent to use different skills ───────────────────────────

        System.out.println("\n── Question 1: Triggers the workspace code-reviewer skill ──\n");
        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage(
                                "Review this Java code:\n```java\n"
                                        + "public String getName(Map<String, Object> map) {\n"
                                        + "    return map.get(\"name\").toString();\n"
                                        + "}\n```"),
                        ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        System.out.println("\n\n── Question 2: Triggers the classpath summarizer skill ──\n");
        System.out.print("Agent: ");
        agent.streamEvents(
                        new UserMessage(
                                "Summarize the following: Artificial intelligence has transformed"
                                    + " multiple industries including healthcare, finance, and"
                                    + " education. Key breakthroughs include large language models,"
                                    + " computer vision, and reinforcement learning. Challenges"
                                    + " remain in areas of safety, alignment, and energy"
                                    + " efficiency."),
                        ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent e) {
                                System.out.print(e.getDelta());
                            } else if (event instanceof ToolCallStartEvent e) {
                                System.out.printf("%n[TOOL] %s%n", e.getToolCallName());
                            }
                        })
                .blockLast();

        System.out.println("\n\n" + "=".repeat(60));
        System.out.println("Done. Workspace at: " + workspace);
        System.out.println("=".repeat(60));

        classpathRepo.close();
    }
}
