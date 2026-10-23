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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@code load_skill_through_path} can fall back to {@code skill.originDir} on disk
 * when an in-memory resource entry is missing (Path-B behavior).
 */
class SkillToolFactoryDiskFallbackTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    @DisplayName("Falls back to originDir when resource missing from in-memory map")
    void disksFallbackHits(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("alpha");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(
                skillDir.resolve("scripts/run.sh"), "echo from-disk\n", StandardCharsets.UTF_8);

        // In-memory resources is empty — only the disk file exists. Simulates lazy mode.
        AgentSkill skill =
                AgentSkill.builder()
                        .name("alpha")
                        .description("alpha skill")
                        .skillContent("# Alpha")
                        .originDir(skillDir.toAbsolutePath().normalize())
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        AgentTool tool = toolkit.getTool("load_skill_through_path");
        assertNotNull(tool);

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "scripts/run.sh");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("fallback-1")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);
        assertNotNull(result);
        String text = textOf(result);
        assertTrue(text.contains("echo from-disk"), "should serve content from disk fallback");
        assertTrue(text.contains("scripts/run.sh"));
    }

    @Test
    @DisplayName("Prefers in-memory entry over disk when both exist")
    void inMemoryTakesPrecedence(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("alpha");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(
                skillDir.resolve("scripts/run.sh"), "echo from-disk\n", StandardCharsets.UTF_8);

        Map<String, String> resources = new HashMap<>();
        resources.put("scripts/run.sh", "echo from-memory\n");
        AgentSkill skill =
                AgentSkill.builder()
                        .name("alpha")
                        .description("alpha skill")
                        .skillContent("# Alpha")
                        .resources(resources)
                        .originDir(skillDir.toAbsolutePath().normalize())
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        AgentTool tool = toolkit.getTool("load_skill_through_path");
        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "scripts/run.sh");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("precedence-1")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);
        String text = textOf(result);
        assertTrue(text.contains("from-memory"), "in-memory must win over disk fallback");
        assertFalse(text.contains("from-disk"));
    }

    @Test
    @DisplayName("Path-traversal '..' is rejected even with originDir present")
    void rejectsPathTraversal(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("alpha");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("ok.txt"), "ok\n", StandardCharsets.UTF_8);
        // Sibling outside skillDir — its content must NEVER appear in the tool output.
        // Use a unique sentinel so the assertion isn't fooled by the path string echo.
        String sentinel = "SENTINEL_TRAVERSAL_LEAK_4f29a7";
        Files.writeString(
                tempDir.resolve("forbidden.txt"), sentinel + "\n", StandardCharsets.UTF_8);

        AgentSkill skill =
                AgentSkill.builder()
                        .name("alpha")
                        .description("alpha")
                        .skillContent("# A")
                        .originDir(skillDir.toAbsolutePath().normalize())
                        .build();
        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        AgentTool tool = toolkit.getTool("load_skill_through_path");
        Map<String, Object> input =
                Map.of("skillId", skill.getSkillId(), "path", "../forbidden.txt");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("traversal-1")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);
        String text = textOf(result);
        assertFalse(text.contains(sentinel), "must NOT leak sibling-of-originDir file contents");
        assertTrue(text.contains("not found") || text.contains("Resource not found"));
    }

    private static String textOf(ToolResultBlock r) {
        if (r == null || r.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        r.getOutput().forEach(b -> sb.append(b.toString()));
        return sb.toString();
    }
}
