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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.SkillToolGroup;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that loading a skill via {@link SkillLoadTool} activates associated
 * {@link SkillToolGroup} instances (on-demand tool disclosure).
 *
 * <p>Issue #2653: HarnessAgent (Skill + ToolGroup: on-demand tool disclosure) not work.
 * When a skill is loaded through the HarnessAgent's SkillLoadTool, the associated
 * SkillToolGroups (bound via {@code activateOnSkill}) should be activated so that
 * the tools in those groups become available to the model.
 */
@SuppressWarnings("deprecation")
class SkillLoadToolToolGroupActivationTest {

    /**
     * Demonstrates issue #2653: loading a skill via SkillLoadTool does NOT activate
     * the associated SkillToolGroup, so tools in that group remain hidden from the model.
     *
     * <p>Expected behavior: After loading a skill, any SkillToolGroup bound to that skill
     * via {@code activateOnSkill} should be activated, making its tools available.
     *
     * <p>Actual behavior (bug): The SkillToolGroup remains inactive after skill loading.
     */
    @Test
    @DisplayName("Issue #2653: Loading skill should activate associated SkillToolGroup")
    void loadingSkillShouldActivateAssociatedSkillToolGroup() {
        // 1. Create a Toolkit
        Toolkit toolkit = new Toolkit();

        // 2. Create a SkillToolGroup bound to a skill via activateOnSkill
        // The group is initially inactive (default for SkillToolGroup)
        String skillName = "data_analysis";
        String groupName = "analysis_tools";
        SkillToolGroup skillToolGroup =
                SkillToolGroup.skillBuilder()
                        .name(groupName)
                        .description("Data analysis tools")
                        .activateOnSkill(skillName)
                        .build();
        skillToolGroup.addTool("analyze_data");
        toolkit.registerToolGroup(skillToolGroup);

        // Verify initial state: group is inactive
        assertFalse(
                toolkit.getActiveGroups().contains(groupName),
                "SkillToolGroup should be inactive before skill is loaded");

        // 3. Create a skill that matches the activateOnSkill binding
        AgentSkill skill =
                new AgentSkill(
                        skillName,
                        "Analyze data",
                        "# Data Analysis Skill\n\nInstructions here.",
                        null,
                        "custom");

        // 4. Create SkillLoadTool and catalog
        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool();
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog);
        ctx.put(Toolkit.class, toolkit);

        // 5. Load the skill via SkillLoadTool
        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-load")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();

        ToolResultBlock result = tool.callAsync(param).block();

        // Verify the skill was loaded successfully
        assertTrue(
                result != null && result.getState() != ToolResultState.ERROR,
                "Skill loading should succeed");

        // 6. Verify that the SkillToolGroup is now activated
        // THIS IS THE BUG: The group should be active after skill loading, but it isn't
        assertTrue(
                toolkit.getActiveGroups().contains(groupName),
                "SkillToolGroup '"
                        + groupName
                        + "' should be activated after loading skill '"
                        + skillName
                        + "'. "
                        + "Active groups: "
                        + toolkit.getActiveGroups());
    }

    /**
     * Verifies that tools in an activated SkillToolGroup become visible in tool schemas.
     *
     * <p>This test demonstrates the end-to-end impact of issue #2653: even after loading
     * a skill, the tools in the associated SkillToolGroup are not disclosed to the model.
     */
    @Test
    @DisplayName("Issue #2653: Tools in SkillToolGroup should be visible after skill loading")
    void toolsInSkillToolGroupShouldBeVisibleAfterSkillLoading() {
        // 1. Create a Toolkit
        Toolkit toolkit = new Toolkit();

        // 2. Create a SkillToolGroup bound to a skill
        String skillName = "coding";
        String groupName = "code_tools";
        SkillToolGroup skillToolGroup =
                SkillToolGroup.skillBuilder()
                        .name(groupName)
                        .description("Code execution tools")
                        .activateOnSkill(skillName)
                        .build();
        skillToolGroup.addTool("run_code");
        toolkit.registerToolGroup(skillToolGroup);

        // 3. Verify the tool group is not active initially
        assertFalse(
                toolkit.getActiveGroups().contains(groupName),
                "Tool group '" + groupName + "' should not be active before skill loading");

        // 4. Create and load the skill
        AgentSkill skill =
                new AgentSkill(
                        skillName,
                        "Code execution",
                        "# Coding Skill\n\nInstructions here.",
                        null,
                        "custom");

        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool();
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog);
        ctx.put(Toolkit.class, toolkit);

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-load-2")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();

        tool.callAsync(param).block();

        // 5. Verify the tool group is now active
        // THIS IS THE BUG: The tool group should be active after skill loading
        assertTrue(
                toolkit.getActiveGroups().contains(groupName),
                "Tool group '"
                        + groupName
                        + "' should be active after loading skill '"
                        + skillName
                        + "'. Active groups: "
                        + toolkit.getActiveGroups());
    }

    /**
     * Verifies the fallback path: when the call carries no toolkit in its RuntimeContext,
     * SkillLoadTool falls back to the shared toolkit reference installed by
     * {@link SkillRuntime#prepareToolkit(Toolkit)}.
     */
    @Test
    @DisplayName("Loading skill without context toolkit uses the shared toolkit reference")
    void loadingSkillWithoutContextToolkitUsesSharedToolkitReference() {
        // 1. Create a Toolkit with a skill-bound tool group
        Toolkit toolkit = new Toolkit();
        String skillName = "shell";
        String groupName = "shell_tools";
        SkillToolGroup skillToolGroup =
                SkillToolGroup.skillBuilder()
                        .name(groupName)
                        .description("Shell tools")
                        .activateOnSkill(skillName)
                        .build();
        skillToolGroup.addTool("run_shell");
        toolkit.registerToolGroup(skillToolGroup);

        AgentSkill skill =
                new AgentSkill(
                        skillName,
                        "Shell execution",
                        "# Shell Skill\n\nInstructions here.",
                        null,
                        "custom");

        // 2. Simulate SkillRuntime.prepareToolkit: only the shared reference is available,
        //    the call's RuntimeContext carries no toolkit.
        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool =
                new SkillLoadTool(
                        new AtomicReference<>(catalog), false, new AtomicReference<>(toolkit));

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-fallback")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(useBlock).input(input).build();

        ToolResultBlock result = tool.callAsync(param).block();

        // 3. Loading succeeds and the group is activated through the shared reference
        assertTrue(
                result != null && result.getState() != ToolResultState.ERROR,
                "Skill loading should succeed without a context toolkit");
        assertTrue(
                toolkit.getActiveGroups().contains(groupName),
                "Tool group '"
                        + groupName
                        + "' should be activated via the shared toolkit reference");
    }

    /**
     * Verifies idempotency: reloading an already-active skill must not fail and must not
     * deactivate the bound tool group.
     */
    @Test
    @DisplayName("Reloading a skill keeps the bound tool group active")
    void reloadingSkillKeepsBoundToolGroupActive() {
        // 1. Create a Toolkit with a skill-bound tool group
        Toolkit toolkit = new Toolkit();
        String skillName = "qa";
        String groupName = "qa_tools";
        SkillToolGroup skillToolGroup =
                SkillToolGroup.skillBuilder()
                        .name(groupName)
                        .description("QA tools")
                        .activateOnSkill(skillName)
                        .build();
        skillToolGroup.addTool("run_tests");
        toolkit.registerToolGroup(skillToolGroup);

        AgentSkill skill =
                new AgentSkill(
                        skillName,
                        "QA automation",
                        "# QA Skill\n\nInstructions here.",
                        null,
                        "custom");

        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool();
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog);
        ctx.put(Toolkit.class, toolkit);

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-reload")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();

        // 2. Load once: group becomes active
        ToolResultBlock first = tool.callAsync(param).block();
        assertTrue(
                first != null && first.getState() != ToolResultState.ERROR,
                "First skill loading should succeed");
        assertTrue(
                toolkit.getActiveGroups().contains(groupName),
                "Tool group should be active after the first load");

        // 3. Reload: still succeeds and the group stays active (no double-activation errors)
        ToolResultBlock second = tool.callAsync(param).block();
        assertTrue(
                second != null && second.getState() != ToolResultState.ERROR,
                "Reloading the skill should succeed");
        assertTrue(
                toolkit.getActiveGroups().contains(groupName),
                "Tool group should stay active after reloading the skill");
    }

    /**
     * Verifies the defensive path: when no toolkit is reachable (no context value and an empty
     * shared reference), loading the skill still succeeds and simply skips activation.
     */
    @Test
    @DisplayName("Loading a skill without any reachable toolkit still succeeds")
    void loadingSkillWithoutAnyReachableToolkitStillSucceeds() {
        AgentSkill skill =
                new AgentSkill(
                        "plain",
                        "Plain skill",
                        "# Plain Skill\n\nInstructions here.",
                        null,
                        "custom");

        // No toolkit in the context and the shared reference stays empty.
        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool();
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog);

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-plain")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();

        ToolResultBlock result = tool.callAsync(param).block();

        assertTrue(
                result != null && result.getState() != ToolResultState.ERROR,
                "Skill loading should succeed without any reachable toolkit");
    }

    /**
     * Verifies resilience: when activating the bound tool group fails (e.g. an underlying
     * group-manager error), the skill still loads successfully and only the activation is
     * skipped, matching the core {@code SkillToolFactory} error tolerance.
     */
    @Test
    @DisplayName("Tool group activation failure does not fail skill loading")
    void toolGroupActivationFailureDoesNotFailSkillLoading() {
        // 1. Mock a toolkit whose updateToolGroups always fails
        Toolkit toolkit = mock(Toolkit.class);
        String skillName = "resilient";
        String groupName = "resilient_tools";
        ToolGroup inactiveGroup = mock(ToolGroup.class);
        when(inactiveGroup.isActive()).thenReturn(false);
        when(toolkit.findSkillToolGroupsByActivateOnSkill(skillName))
                .thenReturn(List.of(groupName));
        when(toolkit.getToolGroup(groupName)).thenReturn(inactiveGroup);
        doThrow(new IllegalStateException("group manager unavailable"))
                .when(toolkit)
                .updateToolGroups(anyList(), eq(true));

        // 2. Load a skill bound to the mocked group
        AgentSkill skill =
                new AgentSkill(
                        skillName,
                        "Resilient skill",
                        "# Resilient Skill\n\nInstructions here.",
                        null,
                        "custom");
        SkillCatalog catalog = SkillCatalog.of(List.of(HarnessSkillEntry.of(skill, null)));
        SkillLoadTool tool = new SkillLoadTool();
        RuntimeContext ctx = RuntimeContext.empty();
        ctx.put(SkillCatalog.class, catalog);
        ctx.put(Toolkit.class, toolkit);

        Map<String, Object> input = Map.of("skillId", skill.getSkillId(), "path", "SKILL.md");
        ToolUseBlock useBlock =
                ToolUseBlock.builder()
                        .id("test-resilient")
                        .name(SkillLoadTool.TOOL_NAME)
                        .input(input)
                        .build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(useBlock)
                        .input(input)
                        .runtimeContext(ctx)
                        .build();

        // 3. Loading still succeeds even though activation threw
        ToolResultBlock result = tool.callAsync(param).block();
        assertTrue(
                result != null && result.getState() != ToolResultState.ERROR,
                "Skill loading should survive tool group activation failures");
        verify(toolkit).updateToolGroups(List.of(groupName), true);
    }
}
