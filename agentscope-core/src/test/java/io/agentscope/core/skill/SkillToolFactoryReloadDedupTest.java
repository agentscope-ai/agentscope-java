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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a repeat {@code load_skill_through_path} call for the SKILL.md of an
 * already-active skill returns a one-line notice instead of re-sending the full markdown
 * (#1569), while the first load and specific resource paths keep returning full content.
 */
class SkillToolFactoryReloadDedupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ToolResultBlock callLoadTool(Toolkit toolkit, String skillId, String path) {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        assertNotNull(tool);
        Map<String, Object> input = new HashMap<>();
        input.put("skillId", skillId);
        input.put("path", path);
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("dedup-" + System.nanoTime())
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();
        return tool.callAsync(param).block(TIMEOUT);
    }

    private String textOf(ToolResultBlock result) {
        StringBuilder sb = new StringBuilder();
        result.getOutput().forEach(b -> sb.append(b.toString()));
        return sb.toString();
    }

    @Test
    @DisplayName("First SKILL.md load returns the full markdown; repeat returns a one-line notice")
    void repeatSkillMdLoadIsDeduplicated() {
        Map<String, String> resources = new HashMap<>();
        resources.put("notes.md", "resource body");
        AgentSkill skill =
                AgentSkill.builder()
                        .name("alpha")
                        .description("alpha skill")
                        .skillContent("# Alpha SKILL body")
                        .resources(resources)
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        String first = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(
                first.contains("Successfully loaded skill"),
                "First load returns the full markdown response");
        assertTrue(first.contains("# Alpha SKILL body"));

        String second = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(
                second.contains("is already loaded and active"),
                "Repeat load returns the short notice");
        assertTrue(
                !second.contains("# Alpha SKILL body"),
                "Repeat load does not re-send the full markdown");
    }

    @Test
    @DisplayName("Resource paths still return full content once the skill is active")
    void resourcePathsAreNotDeduplicated() {
        Map<String, String> resources = new HashMap<>();
        resources.put("notes.md", "resource body");
        AgentSkill skill =
                AgentSkill.builder()
                        .name("beta")
                        .description("beta skill")
                        .skillContent("# Beta SKILL body")
                        .resources(resources)
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        callLoadTool(toolkit, skill.getSkillId(), "SKILL.md");

        String resource = textOf(callLoadTool(toolkit, skill.getSkillId(), "notes.md"));
        assertTrue(
                resource.contains("resource body"),
                "Specific resource paths keep returning full content after activation");
        assertEquals(1, resource.split("resource body", -1).length - 1);
    }
}
