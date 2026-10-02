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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the #7-B change: {@code load_skill_through_path(path="SKILL.md")} now returns the
 * full markdown document (YAML frontmatter + body) rather than the body-only view, so the LLM's
 * read matches what {@code skill_manage(action=patch)} operates on.
 */
@SuppressWarnings("deprecation")
class SkillLoadToolFrontmatterViewTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    @DisplayName("SKILL.md view includes frontmatter (name + description) alongside body")
    void includesFrontmatter() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "alpha");
        metadata.put("description", "alpha skill");
        metadata.put("version", "1.2.3");
        AgentSkill skill =
                new AgentSkill(metadata, "# Body heading\n\nbody text\n", null, "custom");

        SkillCatalog catalog =
                SkillCatalog.of(java.util.List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool(new AtomicReference<>(catalog));

        String text = runTool(tool, Map.of("skillId", skill.getSkillId(), "path", "SKILL.md"));

        assertTrue(
                text.contains("name: alpha"),
                "expected YAML frontmatter 'name' in view, got: " + text);
        assertTrue(
                text.contains("description: alpha skill"),
                "expected YAML frontmatter 'description' in view");
        assertTrue(text.contains("version: 1.2.3"), "expected extra metadata 'version' in view");
        assertTrue(
                text.contains("# Body heading"),
                "expected body content (a Markdown heading) in view");
        assertTrue(text.contains("body text"), "expected body content (free text) in view");
        // 4-backtick fence (not 3) so the body's potential triple-backtick code blocks don't
        // collide with our wrapper.
        assertTrue(text.contains("````"), "expected 4-backtick fence wrapping the SKILL.md body");
    }

    @Test
    @DisplayName("Resource view also uses 4-backtick fence to avoid collision with body")
    void resourceFenceConsistent() {
        AgentSkill skill =
                new AgentSkill(
                        "alpha",
                        "alpha skill",
                        "# Body\n",
                        Map.of("scripts/run.sh", "echo hi\n"),
                        "custom");
        SkillCatalog catalog =
                SkillCatalog.of(java.util.List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool(new AtomicReference<>(catalog));

        String text =
                runTool(tool, Map.of("skillId", skill.getSkillId(), "path", "scripts/run.sh"));

        assertTrue(text.contains("scripts/run.sh"), "resource path should appear in header");
        assertTrue(text.contains("echo hi"), "resource body should be present");
        // Count the number of `````` fence occurrences (open + close).
        int fenceCount = countOccurrences(text, "````");
        assertEquals(2, fenceCount, "expected exactly one open + one close fence, got " + text);
    }

    private static String runTool(SkillLoadTool tool, Map<String, Object> input) {
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("view-test")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();
        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);
        if (result == null || result.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var b : result.getOutput()) {
            if (b instanceof TextBlock t) {
                sb.append(t.getText());
            } else {
                sb.append(b.toString());
            }
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
