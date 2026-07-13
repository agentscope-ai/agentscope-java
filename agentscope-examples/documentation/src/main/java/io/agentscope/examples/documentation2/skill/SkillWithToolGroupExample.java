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
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SkillWithToolGroupExample - Demonstrates combining {@link FileSystemSkillRepository} with
 * a {@link io.agentscope.core.tool.SkillToolGroup}.
 *
 * <p>A {@link io.agentscope.core.tool.SkillToolGroup} ties a group of tools to a specific skill name.
 * When the agent loads a skill whose name matches {@code activateOnSkill}, the group is
 * automatically made visible in the model's tool list.
 *
 * <p><b>Pattern:</b>
 * <pre>
 *   toolkit.createSkillToolGroup("analysis-tools", "Data analysis tools", false, "data-analysis");
 *   toolkit.registration().tool(new AnalysisTools()).group("analysis-tools").apply();
 *   // Agent loads "data-analysis" SKILL.md → "analysis-tools" group becomes active via activateOnSkill
 * </pre>
 *
 * <p><b>Skill file structure (SKILLS_DIR/data-analysis/SKILL.md):</b>
 * <pre>
 * ---
 * name: data-analysis
 * description: Statistical data analysis skill
 * ---
 * Use analyze_data and summarize tools when processing datasets.
 * </pre>
 *
 * <p><b>Run:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=your_key
 *   mvn exec:java -pl agentscope-examples/documentation2 \
 *       -Dexec.mainClass=io.agentscope.examples.documentation2.skill.SkillWithToolGroupExample
 * </pre>
 */
public class SkillWithToolGroupExample {

    /**
     * Skills directory containing a {@code data-analysis/SKILL.md} entry.
     * Adjust to match your workspace layout.
     */
    private static final String SKILLS_DIR =
            "agentscope-examples/documentation/src/main/resources/skills";

    /**
     * Name of the skill that activates the tool group.
     * Must match the {@code name:} field in the corresponding SKILL.md.
     */
    private static final String ACTIVATING_SKILL = "data-analysis";

    /**
     * Name of the tool group bound to the skill. Can be any name — the group is activated
     * automatically when the skill matching {@code activateOnSkill} is loaded.
     */
    private static final String TOOL_GROUP = "data-analysis-tools";

    /**
     * Runs the skill-with-tool-group example.
     *
     * @param args command-line arguments (ignored)
     * @throws Exception if an I/O error occurs
     */
    public static void main(String[] args) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Skill + ToolGroup Example");
        System.out.println("=".repeat(60));
        System.out.println(
                "Demonstrates FileSystemSkillRepository combined with a SkillToolGroup.\n"
                        + "The '"
                        + TOOL_GROUP
                        + "' group activates automatically when the\n"
                        + "'"
                        + ACTIVATING_SKILL
                        + "' skill is loaded by the agent.");
        System.out.println("=".repeat(60) + "\n");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        Path skillsDir = Paths.get(SKILLS_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(skillsDir)) {
            System.out.println("SKILLS_DIR not found: " + skillsDir);
            System.out.println(
                    "Please set SKILLS_DIR to a directory containing a "
                            + ACTIVATING_SKILL
                            + "/SKILL.md entry.");
            return;
        }

        // ── 1. Create the toolkit and tool group ──────────────────────────────────────
        //
        // createSkillToolGroup(name, description, activeByDefault, activateOnSkill)
        //   activateOnSkill: when the DynamicSkillMiddleware loads a skill matching this name,
        //                    the group becomes active (visible to the model).
        Toolkit toolkit = new Toolkit();
        toolkit.createSkillToolGroup(
                TOOL_GROUP,
                "Tools exposed when the '" + ACTIVATING_SKILL + "' skill is active",
                false, // starts inactive — activated by skill, not always on
                ACTIVATING_SKILL);

        // ── 2. Register tools into the skill-bound group ──────────────────────────────
        toolkit.registration().tool(new DataTools()).group(TOOL_GROUP).apply();

        // ── 3. Register a plain tool (always active, no group) ────────────────────────
        toolkit.registerTool(new InfoTool());

        // ── 4. Wire the skill repository ──────────────────────────────────────────────
        //
        // FileSystemSkillRepository(baseDir, writeable)
        //   writeable=false: skills are read-only (agent cannot create new skills)
        FileSystemSkillRepository skillRepo = new FileSystemSkillRepository(skillsDir, false);

        // ── 5. Build the agent ────────────────────────────────────────────────────────
        //
        // .skillRepository(repo) auto-wires DynamicSkillMiddleware, which rebuilds the skill
        // prompt on every call() and activates SkillToolGroups for loaded skills.
        // .enableMetaTool(true) adds a meta-tool so the agent can inspect active groups.
        ReActAgent agent =
                ReActAgent.builder()
                        .name("SkillToolGroupAgent")
                        .sysPrompt(
                                "You are a helpful assistant. Use available skills and tools "
                                        + "as appropriate for the user's request.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(toolkit)
                        .skillRepository(skillRepo)
                        .enableMetaTool(true)
                        .middleware(new AgentTraceMiddleware())
                        .build();

        System.out.println("Loaded skill repository: " + skillsDir);
        System.out.println(
                "SkillToolGroup '" + TOOL_GROUP + "' activates on skill: " + ACTIVATING_SKILL);
        System.out.println("\nTry these prompts:");
        System.out.println("  1. \"Analyze the numbers 5, 12, 3, 8, 15, 27\"");
        System.out.println("  2. \"What tools do you have?\"");
        System.out.println("  3. \"What time is it?\"");
        System.out.println("  4. \"Summarize the analysis and save it to a file\"");
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

    /** Tools registered in the skill-bound group — only visible when the skill is active. */
    public static class DataTools {

        /**
         * Analyzes a list of numbers and returns basic statistics.
         *
         * @param numbers comma-separated numbers
         * @return statistical summary
         */
        @Tool(name = "analyze_data", description = "Compute basic statistics for a list of numbers")
        public String analyzeData(
                @ToolParam(name = "numbers", description = "Comma-separated numbers")
                        String numbers) {
            try {
                String[] parts = numbers.split(",");
                double[] vals = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    vals[i] = Double.parseDouble(parts[i].trim());
                }
                double sum = 0, min = vals[0], max = vals[0];
                for (double v : vals) {
                    sum += v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                double avg = sum / vals.length;
                return String.format(
                        "Count: %d | Sum: %.2f | Min: %.2f | Max: %.2f | Avg: %.2f",
                        vals.length, sum, min, max, avg);
            } catch (NumberFormatException e) {
                return "Error: invalid input — provide comma-separated numbers";
            }
        }

        /**
         * Writes a text summary to a temporary file.
         *
         * @param content text to write
         * @return file path or error message
         */
        @Tool(name = "write_summary", description = "Write a summary to a temp file")
        public String writeSummary(
                @ToolParam(name = "content", description = "Summary text") String content) {
            try {
                Path tmp = Files.createTempFile("summary-", ".txt");
                Files.writeString(tmp, content);
                return "Summary written to: " + tmp;
            } catch (IOException e) {
                return "Error writing summary: " + e.getMessage();
            }
        }
    }

    /** Plain tool always visible regardless of skill activation. */
    public static class InfoTool {

        /**
         * Returns the current UTC timestamp.
         *
         * @return ISO-8601 timestamp
         */
        @Tool(name = "get_timestamp", description = "Get the current UTC timestamp")
        public String getTimestamp() {
            return java.time.Instant.now().toString();
        }
    }
}
