/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.coding.ShellCommandTool;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.Set;

/**
 * AgentSkillExample - Demonstrates creating skills using the skill-creator skill.
 *
 * <p>This example shows a complete skill creation workflow with:
 *
 * <ul>
 *   <li>Loading a skill from resources via FileSystemSkillRepository
 *   <li>Enabling code execution tools for writing new skills
 *   <li>Running a demo prompt that creates a new skill on disk
 * </ul>
 */
public class AgentSkillExample {

    private static final String SKILL_NAME = "skill-creator";
    private static final String RESOURCES_DIR =
            "agentscope-examples/quickstart/src/main/resources/skills";
    private static final String OUTPUT_DIR = "agentscope-examples/quickstart/target/skill-output";

    public static void main(String[] args) throws Exception {
        ExampleUtils.printWelcome(
                "Agent Skill Example - Skill Creator",
                "This example demonstrates a ReActAgent using the skill-creator skill.\n"
                        + "The agent will:\n"
                        + "  - Load skill-creator from resources\n"
                        + "  - Use file tools to create a new skill\n"
                        + "  - Write SKILL.md and references under a target folder");

        String apiKey = ExampleUtils.getDashScopeApiKey();

        Toolkit toolkit = new Toolkit();
        SkillBox skillBox = new SkillBox(toolkit);

        AgentSkill skillCreator = loadSkillCreatorSkill();
        skillBox.registration().skill(skillCreator).apply();

        Path outputDir = resolvePath(OUTPUT_DIR);
        Scanner scanner = new Scanner(System.in);
        ShellCommandTool shellCommandTool =
                new ShellCommandTool(
                        Set.of("python", "ls", "cat"),
                        cmd -> {
                            System.out.println("Enter y/n to approve or deny execution:");
                            System.out.println(cmd);
                            System.out.println();
                            String response = scanner.nextLine();
                            return response.equalsIgnoreCase("y");
                        });

        skillBox.codeExecution()
                .workDir(outputDir.toString())
                .withShell(shellCommandTool)
                .withRead()
                .withWrite()
                .enable();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("SkillCreator")
                        .sysPrompt(buildSystemPrompt(outputDir))
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .skillBox(skillBox)
                        .memory(new InMemoryMemory())
                        .build();

        ExampleUtils.startChat(agent);

        scanner.close();
    }

    private static AgentSkill loadSkillCreatorSkill() {
        Path resourcesDir = resolvePath(RESOURCES_DIR);
        FileSystemSkillRepository repository = new FileSystemSkillRepository(resourcesDir, false);
        return repository.getSkill(SKILL_NAME);
    }

    private static Path resolvePath(String relativePath) {
        return Paths.get(relativePath).toAbsolutePath().normalize();
    }

    private static String buildSystemPrompt(Path outputDir) {
        return """
        You are a skill creation assistant. Use the skill-creator skill when asked to create or
        update a skill. Prefer concise SKILL.md content and put detailed guidance in references.

        File tools are available. Write new skills under this output directory:
        %s

        Use write_text_file to create files and include a valid YAML frontmatter with name and
        description.
        """
                .formatted(outputDir.toString());
    }
}
