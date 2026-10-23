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
package io.agentscope.examples.documentation2.skill;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Demonstrates loading skills from a remote Git repository.
 *
 * <p>{@link GitSkillRepository} clones a remote Git repository to a local temporary directory
 * and loads skills from it. Each read operation performs a lightweight remote reference check;
 * a pull is only executed when the remote HEAD changes.
 *
 * <p>This example uses the public skill repository at
 * <a href="https://github.com/agentscope-ai/skills">https://github.com/agentscope-ai/skills</a>.
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.skill.GitSkillRepositoryExample
 * </pre>
 */
public class GitSkillRepositoryExample {

    private static final String SKILLS_REPO_URL = "https://github.com/agentscope-ai/skills";

    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Git Skill Repository Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates loading skills from a remote Git repository.\n"
                        + "Repository: "
                        + SKILLS_REPO_URL);
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // ── Create Git skill repository ───────────────────────────────────────────────
        // The repository is cloned to a temporary directory and auto-synced on read.
        // Temporary directory is cleaned up on JVM shutdown or when close() is called.
        try (GitSkillRepository skillRepo = new GitSkillRepository(SKILLS_REPO_URL)) {

            // ── List available skills ─────────────────────────────────────────────────
            System.out.println("Syncing skills from remote repository...\n");
            List<String> skillNames = skillRepo.getAllSkillNames();
            System.out.println("Available skills (" + skillNames.size() + "):");
            for (String name : skillNames) {
                AgentSkill skill = skillRepo.getSkill(name);
                String desc =
                        skill != null && skill.getDescription() != null
                                ? skill.getDescription()
                                : "(no description)";
                System.out.println("  - " + name + ": " + desc);
            }

            if (skillNames.isEmpty()) {
                System.out.println("  (no skills found in repository)");
                System.out.println(
                        "\nHint: skills should be in subdirectories with a SKILL.md entry file.");
                return;
            }

            // ── Build agent with Git skill repository ─────────────────────────────────
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("SkillAgent")
                            .sysPrompt(
                                    "You are a helpful assistant. Use available skills to help"
                                            + " the user.")
                            .model(
                                    DashScopeChatModel.builder()
                                            .apiKey(apiKey)
                                            .modelName("qwen-max")
                                            .stream(true)
                                            .enableThinking(false)
                                            .formatter(new DashScopeChatFormatter())
                                            .build())
                            .toolkit(new Toolkit())
                            .skillRepository(skillRepo)
                            .middleware(new AgentTraceMiddleware())
                            .build();

            // ── Interactive chat ──────────────────────────────────────────────────────
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("\nChat started. Type 'exit' to quit.\n");

            while (true) {
                System.out.print("You: ");
                String input = reader.readLine();
                if (input == null || input.trim().equalsIgnoreCase("exit")) {
                    System.out.println("\nGoodbye!");
                    break;
                }
                if (input.isBlank()) {
                    continue;
                }
                Msg userMsg = new UserMessage(input.trim());
                System.out.print("\nAgent: ");
                agent.streamEvents(userMsg)
                        .doOnNext(
                                event -> {
                                    if (event instanceof TextBlockDeltaEvent e) {
                                        System.out.print(e.getDelta());
                                    }
                                })
                        .blockLast();
                System.out.println("\n");
            }
        }
    }
}
