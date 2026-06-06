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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AgentSkillExample - Demonstrates loading skills from a file-system skill repository.
 *
 * <p>Migration notes (from documentation/quickstart):
 * <ul>
 *   <li>{@code legacy.skill.SkillBox} + {@code .skillBox(skillBox)} replaced by
 *       {@code FileSystemSkillRepository} + {@code .skillRepository(repo)} on the builder.</li>
 *   <li>{@code SkillBox.codeExecution().withShell(...).withRead().withWrite().enable()} removed;
 *       individual tools registered manually in the toolkit instead.</li>
 *   <li>Removed {@code .memory(new InMemoryMemory())}.</li>
 * </ul>
 *
 * <p><b>Note:</b> This example requires a {@code SKILLS_DIR} directory containing a
 * {@code skill-creator/SKILL.md} file. Adjust the {@code SKILLS_DIR} constant to match your
 * workspace layout before running.
 */
public class AgentSkillExample {

    /**
     * Directory that contains skill subdirectories, each with a {@code SKILL.md} entry file.
     * Set this to the absolute path of your skills directory before running.
     */
    private static final String SKILLS_DIR =
            "agentscope-examples/documentation/quickstart/src/main/resources/skills";

    /**
     * Output directory where the agent may write new skill files during the demo.
     */
    private static final String OUTPUT_DIR =
            "agentscope-examples/documentation2/target/skill-output";

    /**
     * Runs the agent skill example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Agent Skill Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "This example demonstrates a ReActAgent using a FileSystemSkillRepository.\n"
                        + "The agent will:\n"
                        + "  - Load skills from the local file system repository\n"
                        + "  - Use file tools to inspect and create skill files");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        // Resolve skill repository path
        Path skillsDir = Paths.get(SKILLS_DIR).toAbsolutePath().normalize();
        Path outputDir = Paths.get(OUTPUT_DIR).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        if (!Files.isDirectory(skillsDir)) {
            System.out.println(
                    "SKILLS_DIR not found: "
                            + skillsDir
                            + "\nPlease set the SKILLS_DIR constant to an existing skills"
                            + " directory.");
            return;
        }

        // FileSystemSkillRepository is the non-legacy replacement for SkillBox + AgentSkill loading
        FileSystemSkillRepository skillRepo = new FileSystemSkillRepository(skillsDir, false);

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new FileTools(outputDir));

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
                        .skillRepository(skillRepo) // replaces .skillBox(skillBox)
                        .build();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Chat started. Type 'exit' to quit.\n");

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

    private static String buildSystemPrompt(Path outputDir) {
        return """
        You are a skill creation assistant. Use available skills when asked to create or
        update a skill. Write new skill files under this output directory:
        %s

        Use write_text_file to create SKILL.md files with valid YAML frontmatter (name
        and description fields). Keep SKILL.md content concise.
        """
                .formatted(outputDir.toString());
    }

    /** Simple file read/write tools for skill creation. */
    public static class FileTools {

        private final Path workDir;

        FileTools(Path workDir) {
            this.workDir = workDir;
        }

        /**
         * Writes text content to a file under the working directory.
         *
         * @param relativePath relative path of the file within the working directory
         * @param content      text content to write
         * @return result message
         */
        @Tool(name = "write_text_file", description = "Write text content to a file")
        public String writeTextFile(
                @ToolParam(name = "path", description = "Relative file path") String relativePath,
                @ToolParam(name = "content", description = "Text content to write")
                        String content) {
            try {
                Path target = workDir.resolve(relativePath).normalize();
                if (!target.startsWith(workDir)) {
                    return "Error: Path traversal not allowed";
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                System.out.println("[write_text_file] Wrote " + target);
                return "File written: " + target;
            } catch (IOException e) {
                return "Error writing file: " + e.getMessage();
            }
        }

        /**
         * Reads text content from a file under the working directory.
         *
         * @param relativePath relative path of the file within the working directory
         * @return the file content or an error message
         */
        @Tool(name = "read_text_file", description = "Read text content from a file")
        public String readTextFile(
                @ToolParam(name = "path", description = "Relative file path") String relativePath) {
            try {
                Path target = workDir.resolve(relativePath).normalize();
                if (!target.startsWith(workDir)) {
                    return "Error: Path traversal not allowed";
                }
                if (!Files.exists(target)) {
                    return "Error: File not found: " + relativePath;
                }
                System.out.println("[read_text_file] Read " + target);
                return Files.readString(target);
            } catch (IOException e) {
                return "Error reading file: " + e.getMessage();
            }
        }
    }
}
