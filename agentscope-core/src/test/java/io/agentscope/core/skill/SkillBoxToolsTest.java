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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SkillBoxToolsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private SkillBox skillBox;
    private Toolkit toolkit;

    private boolean isErrorResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null || result.getOutput().isEmpty()) {
            return false;
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .anyMatch(text -> text.startsWith("Error:"));
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        skillBox = new SkillBox(toolkit);

        // Register test skills
        Map<String, String> resources1 = new HashMap<>();
        resources1.put("config.json", "{\"key\": \"value\"}");
        resources1.put("data.txt", "sample data");

        AgentSkill skill1 =
                new AgentSkill("test_skill", "Test Skill", "# Test Content", resources1);
        // Register skill1 with a dummy tool to create tool group
        AgentTool dummyTool = createDummyTool("test_skill_tool");
        skillBox.registration().skill(skill1).agentTool(dummyTool).apply();

        AgentSkill skill2 =
                new AgentSkill("empty_skill", "Empty Skill", "# Empty", new HashMap<>());
        skillBox.registerSkill(skill2);

        // Register skill access tools
        skillBox.registerSkillLoadTool();
    }

    private AgentTool createDummyTool(String name) {
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
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("dummy result"));
            }
        };
    }

    @Test
    @DisplayName("Should create load skill resource tool")
    void testCreateLoadSkillResourceTool() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        assertNotNull(tool);
        assertEquals("load_skill_through_path", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("Load and activate"));
        assertTrue(tool.getDescription().contains("path=\"SKILL.md\""));
        assertTrue(tool.getDescription().contains("Do not use '.'"));
    }

    @Test
    @DisplayName("Should load skill resource tool have correct parameters")
    void testLoadSkillResourceToolParameters() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params);
        assertEquals("object", params.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("skillId"));
        assertTrue(properties.containsKey("path"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) params.get("required");
        assertTrue(required.contains("skillId"));
        assertTrue(required.contains("path"));

        // Verify skillId has enum with available skills
        @SuppressWarnings("unchecked")
        Map<String, Object> skillIdParam = (Map<String, Object>) properties.get("skillId");
        assertNotNull(skillIdParam.get("enum"));

        @SuppressWarnings("unchecked")
        Map<String, Object> pathParam = (Map<String, Object>) properties.get("path");
        String pathDescription = (String) pathParam.get("description");
        assertTrue(pathDescription.contains("Use 'SKILL.md'"));
        assertTrue(pathDescription.contains("Do not use '.'"));
    }

    @Test
    @DisplayName("Should load skill markdown successfully")
    void testLoadSkillMarkdownSuccessfully() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-001")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("test_skill"));
        assertTrue(content.contains("Test Content"));
    }

    @Test
    @DisplayName("Should activate skill and tool group when loading markdown")
    void testActivateSkillAndToolGroupWhenLoadingMarkdown() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        String skillId = "test_skill_custom";
        String toolGroupName = skillId + "_skill_tools";

        ToolContextState toolContext = ToolContextState.builder().build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().toolContext(toolContext).build())
                        .build();

        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-002")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolUseBlock)
                        .input(input)
                        .runtimeContext(runtimeContext)
                        .build();
        tool.callAsync(param).block(TIMEOUT);

        // Activation now lands in the per-session activated-group set, never on the shared toolkit.
        assertTrue(
                toolContext.getActivatedGroups().contains(toolGroupName),
                "Per-session activated groups should include the skill's tool group");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "Shared toolkit group-manager flag must remain inactive");
    }

    @Test
    @DisplayName("Should return error for non existent skill")
    void testReturnErrorForNonExistentSkill() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "non_existent_skill", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-003")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
    }

    @Test
    @DisplayName("Should return error for missing skill id")
    void testReturnErrorForMissingSkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-004")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should return error for empty skill id")
    void testReturnErrorForEmptySkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-005")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should load skill resource successfully")
    void testLoadSkillResourceSuccessfully() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "config.json");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-006")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertFalse(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("{\"key\": \"value\"}"));
    }

    @Test
    @DisplayName("Should activate skill and tool group when loading resource")
    void testActivateSkillAndToolGroupWhenLoadingResource() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        String skillId = "test_skill_custom";
        String toolGroupName = skillId + "_skill_tools";

        ToolContextState toolContext = ToolContextState.builder().build();
        RuntimeContext runtimeContext =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().toolContext(toolContext).build())
                        .build();

        Map<String, Object> input = Map.of("skillId", skillId, "path", "data.txt");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-007")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(toolUseBlock)
                        .input(input)
                        .runtimeContext(runtimeContext)
                        .build();
        tool.callAsync(param).block(TIMEOUT);

        // Activation now lands in the per-session activated-group set, never on the shared toolkit.
        assertTrue(
                toolContext.getActivatedGroups().contains(toolGroupName),
                "Per-session activated groups should include the skill's tool group");
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "Shared toolkit group-manager flag must remain inactive");
    }

    @Test
    @DisplayName("Loading without a runtime context does not mutate the shared toolkit")
    void testLoadWithoutRuntimeContextDoesNotMutateSharedToolkit() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        String skillId = "test_skill_custom";
        String toolGroupName = skillId + "_skill_tools";

        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("test-call-no-rc")
                                        .name("load_skill_through_path")
                                        .input(input)
                                        .build())
                        .input(input)
                        .build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        // Without a per-session context, the tool-group gate is skipped and the shared toolkit is
        // never touched.
        assertFalse(
                toolkit.getToolGroup(toolGroupName).isActive(),
                "Shared toolkit group-manager flag must remain inactive");
    }

    @Test
    @DisplayName("Separate sessions load different skills without cross-talk")
    void testSeparateSessionsLoadDifferentSkillsWithoutCrossTalk() {
        // Register a second tool-bearing skill.
        AgentSkill other = new AgentSkill("other_skill", "Other Skill", "# Other", null);
        skillBox.registration().skill(other).agentTool(createDummyTool("other_skill_tool")).apply();
        String groupA = "test_skill_custom_skill_tools";
        String groupB = "other_skill_custom_skill_tools";

        ToolContextState tcsA = ToolContextState.builder().build();
        ToolContextState tcsB = ToolContextState.builder().build();
        RuntimeContext rcA =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().toolContext(tcsA).build())
                        .build();
        RuntimeContext rcB =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().toolContext(tcsB).build())
                        .build();

        loadSkillWithContext("test_skill_custom", rcA);
        loadSkillWithContext("other_skill_custom", rcB);

        assertTrue(tcsA.getActivatedGroups().contains(groupA), "Session A activates its own group");
        assertFalse(
                tcsA.getActivatedGroups().contains(groupB),
                "Session A must not see session B's group");
        assertTrue(tcsB.getActivatedGroups().contains(groupB), "Session B activates its own group");
        assertFalse(
                tcsB.getActivatedGroups().contains(groupA),
                "Session B must not see session A's group");
    }

    private ToolResultBlock loadSkillWithContext(String skillId, RuntimeContext rc) {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                ToolUseBlock.builder()
                                        .id("call-" + skillId)
                                        .name("load_skill_through_path")
                                        .input(input)
                                        .build())
                        .input(input)
                        .runtimeContext(rc)
                        .build();
        return tool.callAsync(param).block(TIMEOUT);
    }

    @Test
    @DisplayName("Parallel loads into the same session do not lose activations")
    void testParallelLoadsIntoSameSessionDoNotLoseActivations() throws Exception {
        int n = 8;
        List<String> groupNames = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AgentSkill skill = new AgentSkill("parallel_" + i, "Parallel " + i, "# Content", null);
            skillBox.registration()
                    .skill(skill)
                    .agentTool(createDummyTool("parallel_tool_" + i))
                    .apply();
            groupNames.add(skill.getSkillId() + "_skill_tools");
        }

        ToolContextState tcs = ToolContextState.builder().build();
        RuntimeContext rc =
                RuntimeContext.builder()
                        .agentState(AgentState.builder().toolContext(tcs).build())
                        .build();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String skillId = "parallel_" + i + "_custom";
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                loadSkillWithContext(skillId, rc);
                                return null;
                            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        for (String group : groupNames) {
            assertTrue(
                    tcs.getActivatedGroups().contains(group),
                    "Activation lost under concurrency for group " + group);
        }
    }

    @Test
    @DisplayName("Should return error for non existent resource and list available resources")
    void testReturnErrorForNonExistentResource() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input =
                Map.of("skillId", "test_skill_custom", "path", "non_existent.txt");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-008")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("not found"));
        // Should also list available resources
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("data.txt"));
    }

    @Test
    @DisplayName("Should return error for missing path parameter")
    void testReturnErrorForMissingPathParameter() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-009")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("Missing") || content.contains("required"));
    }

    @Test
    @DisplayName("Should list all resource paths when using invalid path")
    void testListAllResourcePathsWithInvalidPath() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "list");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-010")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("config.json"));
        assertTrue(content.contains("data.txt"));
    }

    @Test
    @DisplayName("Should list only SKILL.md for skill without additional resources")
    void testListOnlySkillMdForSkillWithoutResources() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "empty_skill_custom", "path", "invalid");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-011")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
        String content = result.getOutput().get(0).toString();
        assertTrue(content.contains("SKILL.md"));
        assertTrue(content.contains("Available resources"));
    }

    @Test
    @DisplayName("Should handle whitespace in skill id")
    void testHandleWhitespaceInSkillId() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "  ", "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-012")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should handle whitespace in resource path")
    void testHandleWhitespaceInResourcePath() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");

        Map<String, Object> input = Map.of("skillId", "test_skill_custom", "path", "  ");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-013")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }

    @Test
    @DisplayName("Should handle skill with no tools gracefully")
    void testLoadSkillWithNoToolsDoesNotFail() {
        // Skill without tools - tool group won't exist
        AgentSkill emptySkill = new AgentSkill("no_tools_skill", "No Tools", "# Empty", null);
        skillBox.registerSkill(emptySkill);

        String skillId = emptySkill.getSkillId();

        // Load skill
        AgentTool loader = toolkit.getTool("load_skill_through_path");
        Map<String, Object> input = Map.of("skillId", skillId, "path", "SKILL.md");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-015")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = loader.callAsync(param).block(TIMEOUT);

        // Should succeed even without tool group
        assertNotNull(result);
        assertFalse(isErrorResult(result), "Should not fail when skill has no tools");
        assertNull(
                toolkit.getToolGroup(skillId + "_skill_tools"), "Tool group should not be created");
    }

    @Test
    @DisplayName("Should not activate skill when resource path is invalid")
    void testInvalidResourcePathDoesNotActivateSkill() {
        AgentTool tool = toolkit.getTool("load_skill_through_path");
        String skillId = "test_skill_custom";

        Map<String, Object> input = Map.of("skillId", skillId, "path", ".");
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("test-call-016")
                        .name("load_skill_through_path")
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(toolUseBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block(TIMEOUT);

        assertNotNull(result);
        assertTrue(isErrorResult(result));
    }
}
