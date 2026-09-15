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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
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
 * Verifies the SKILL.md deduplication added for #1569: a repeat load within the same session
 * returns a notice instead of re-sending the full markdown, while first loads, other sessions,
 * and specific resource paths always receive full content.
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

    private static AgentTool dummyTool(String name) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Dummy tool for testing";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return reactor.core.publisher.Mono.just(ToolResultBlock.text("dummy result"));
            }
        };
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
    @DisplayName("Loading a resource first must not suppress a later SKILL.md load")
    void resourceFirstThenSkillMdStillReturnsFullEntry() {
        Map<String, String> resources = new HashMap<>();
        resources.put("notes.md", "resource body");
        AgentSkill skill =
                AgentSkill.builder()
                        .name("gamma")
                        .description("gamma skill")
                        .skillContent("# Gamma SKILL body")
                        .resources(resources)
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        // Resource load activates the skill but never delivers SKILL.md: a later
        // entry load must still return full content, not the dedup notice.
        callLoadTool(toolkit, skill.getSkillId(), "notes.md");

        String entry = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(
                entry.contains("Successfully loaded skill"),
                "Entry load after a resource load returns the full markdown");
        assertTrue(entry.contains("# Gamma SKILL body"));

        // And only from the second entry load onward does the notice appear.
        String repeat = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(repeat.contains("is already loaded and active"));
    }

    @Test
    @DisplayName("The dedup path still re-enables a tool group disabled behind its back")
    void dedupPathResyncsExternallyDisabledToolGroup() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name("epsilon")
                        .description("epsilon skill")
                        .skillContent("# Epsilon SKILL body")
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        // Register with a tool so the skill actually owns a tool group.
        box.registration().skill(skill).agentTool(dummyTool("epsilon_tool")).apply();
        box.registerSkillLoadTool();
        String groupName = skill.getSkillId() + "_skill_tools";
        assertNotNull(toolkit.getToolGroup(groupName), "Skill tool group should exist");

        callLoadTool(toolkit, skill.getSkillId(), "SKILL.md");
        assertTrue(toolkit.getToolGroup(groupName).isActive());

        // A host can disable the group through the public Toolkit API without touching
        // SkillRegistry — the scenario the registry flag alone cannot see.
        toolkit.updateToolGroups(java.util.List.of(groupName), false);
        assertFalse(toolkit.getToolGroup(groupName).isActive());

        // The deduplicated repeat load must reconcile the group, not just return the notice.
        String repeat = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(repeat.contains("is already loaded and active"));
        assertTrue(
                toolkit.getToolGroup(groupName).isActive(),
                "The dedup path re-enables the externally disabled tool group");
    }

    @Test
    @DisplayName("The dedup notice enumerates resources so the model can re-fetch them")
    void noticeListsAvailableResources() {
        Map<String, String> resources = new HashMap<>();
        resources.put("notes.md", "resource body");
        AgentSkill skill =
                AgentSkill.builder()
                        .name("zeta")
                        .description("zeta skill")
                        .skillContent("# Zeta SKILL body")
                        .resources(resources)
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();
        callLoadTool(toolkit, skill.getSkillId(), "SKILL.md");

        String repeat = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(repeat.contains("is already loaded and active"));
        assertTrue(repeat.contains("notes.md"), "The notice lists the skill's resources");
    }

    @Test
    @DisplayName("A disk-fallback resource load does not suppress a later SKILL.md load")
    void diskFallbackResourceFirstThenSkillMdStillReturnsFullEntry() throws Exception {
        java.nio.file.Path skillDir = java.nio.file.Files.createTempDirectory("zeta-skill");
        java.nio.file.Files.writeString(
                skillDir.resolve("guide.txt"),
                "from disk\n",
                java.nio.charset.StandardCharsets.UTF_8);

        AgentSkill skill =
                AgentSkill.builder()
                        .name("eta")
                        .description("eta skill")
                        .skillContent("# Eta SKILL body")
                        .originDir(skillDir.toAbsolutePath().normalize())
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        // Resource served by the disk-fallback branch, which also calls activateSkill.
        callLoadTool(toolkit, skill.getSkillId(), "guide.txt");

        String entry = textOf(callLoadTool(toolkit, skill.getSkillId(), "SKILL.md"));
        assertTrue(
                entry.contains("Successfully loaded skill"),
                "Disk-fallback resource activation must not suppress the entry load");
        assertTrue(entry.contains("# Eta SKILL body"));
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

    @Test
    @DisplayName("A session's entry load must not suppress another session's first load")
    void sessionsDoNotShareEntryDelivery() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name("iota")
                        .description("iota skill")
                        .skillContent("# Iota SKILL body")
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        // Session A loads the entry; session B (same agent, different context) has
        // never seen it and must still receive the full document on its first load.
        String sessionA =
                textOf(
                        callInSession(
                                toolkit,
                                skill.getSkillId(),
                                "SKILL.md",
                                RuntimeContext.builder().userId("u1").sessionId("s1").build()));
        String sessionARepeat =
                textOf(
                        callInSession(
                                toolkit,
                                skill.getSkillId(),
                                "SKILL.md",
                                RuntimeContext.builder().userId("u1").sessionId("s1").build()));
        String sessionB =
                textOf(
                        callInSession(
                                toolkit,
                                skill.getSkillId(),
                                "SKILL.md",
                                RuntimeContext.builder().userId("u1").sessionId("s2").build()));

        assertTrue(sessionA.contains("# Iota SKILL body"), "session A first load is full");
        assertTrue(
                sessionARepeat.contains("is already loaded and active"), "session A repeat dedups");
        assertTrue(
                sessionB.contains("# Iota SKILL body"),
                "session B first load must NOT be suppressed by session A");
    }

    private ToolResultBlock callInSession(
            Toolkit toolkit, String skillId, String path, RuntimeContext ctx) {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        assertNotNull(tool);
        Map<String, Object> input = new HashMap<>();
        input.put("skillId", skillId);
        input.put("path", path);
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("cross-" + System.nanoTime())
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();
        return tool.callAsync(param).block(TIMEOUT);
    }

    @Test
    @DisplayName("reload=true re-delivers the full entry within the same session")
    void reloadArgumentReDeliversFullEntry() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name("kappa")
                        .description("kappa skill")
                        .skillContent("# Kappa SKILL body")
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        RuntimeContext s1 = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        callInSession(toolkit, skill.getSkillId(), "SKILL.md", s1);
        assertTrue(
                textOf(callInSession(toolkit, skill.getSkillId(), "SKILL.md", s1))
                        .contains("is already loaded and active"));

        // In-session recovery lever for content lost to compaction.
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        Map<String, Object> input = new HashMap<>();
        input.put("skillId", skill.getSkillId());
        input.put("path", "SKILL.md");
        input.put("reload", true);
        ToolUseBlock use =
                ToolUseBlock.builder()
                        .id("reload-1")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolResultBlock reloaded =
                tool.callAsync(
                                ToolCallParam.builder()
                                        .toolUseBlock(use)
                                        .input(input)
                                        .runtimeContext(s1)
                                        .build())
                        .block(TIMEOUT);
        assertTrue(
                textOf(reloaded).contains("# Kappa SKILL body"),
                "reload=true re-sends the full entry in the same session");

        // And the dedup remains armed afterwards.
        assertTrue(
                textOf(callInSession(toolkit, skill.getSkillId(), "SKILL.md", s1))
                        .contains("is already loaded and active"));
    }

    @Test
    @DisplayName("A user with no session id never dedups (unidentifiable conversation)")
    void userWithoutSessionNeverDedups() {
        AgentSkill skill =
                AgentSkill.builder()
                        .name("lambda")
                        .description("lambda skill")
                        .skillContent("# Lambda SKILL body")
                        .build();

        Toolkit toolkit = new Toolkit();
        SkillBox box = new SkillBox(toolkit);
        box.registerSkill(skill);
        box.registerSkillLoadTool();

        RuntimeContext noSession = RuntimeContext.builder().userId("u1").build();
        String first = textOf(callInSession(toolkit, skill.getSkillId(), "SKILL.md", noSession));
        String second = textOf(callInSession(toolkit, skill.getSkillId(), "SKILL.md", noSession));

        assertTrue(first.contains("# Lambda SKILL body"));
        assertTrue(
                second.contains("# Lambda SKILL body"),
                "null sessionId must not collapse a user's conversations into one dedup bucket");
    }
}
