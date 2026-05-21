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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.hook.DynamicSkillHook;
import io.agentscope.harness.agent.hook.DynamicSubagentsHook;
import io.agentscope.harness.agent.hook.SubagentsHook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies the three-branch wiring in {@link HarnessAgent.Builder} for skills + subagents:
 *
 * <ul>
 *   <li>default (workspace filesystem available, no custom repo, no opt-out) → dynamic hooks
 *   <li>{@code skillRepository(custom)} → legacy {@link SkillHook} via {@code resolveSkillBox}
 *   <li>{@code disableDynamicSkills()} / {@code disableDynamicSubagents()} → legacy path
 * </ul>
 *
 * <p>The contract under test is the hook list registered on the underlying {@code ReActAgent};
 * each branch must register exactly one of the two skill hooks (or none) and exactly one of the
 * two subagent hooks.
 */
class HarnessAgentDynamicHookBuilderTest {

    @TempDir Path workspace;

    @Test
    void defaultBuild_registersDynamicSkillAndSubagentHooks() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<Hook> hooks = agent.getDelegate().getHooks();
        assertTrue(
                anyOfType(hooks, DynamicSkillHook.class),
                "Default build with workspace filesystem must register DynamicSkillHook");
        assertFalse(
                anyOfType(hooks, SkillHook.class),
                "Default build must NOT register the legacy SkillHook");
        assertTrue(
                anyOfType(hooks, DynamicSubagentsHook.class),
                "Default build with workspace filesystem must register DynamicSubagentsHook");
        assertFalse(
                anyOfType(hooks, SubagentsHook.class),
                "Default build must NOT register the legacy SubagentsHook");
    }

    @Test
    void customSkillRepository_usesLegacySkillHook() throws Exception {
        Files.createDirectories(workspace);
        AgentSkillRepository emptyRepo = new EmptySkillRepository();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .skillRepository(emptyRepo)
                        .build();

        List<Hook> hooks = agent.getDelegate().getHooks();
        // An empty custom repo means resolveSkillBox returns null (no skills) → no SkillHook.
        // But importantly the dynamic hook must NOT be registered.
        assertFalse(
                anyOfType(hooks, DynamicSkillHook.class),
                "Custom skillRepository must skip dynamic loading");
    }

    @Test
    void disableDynamicSkills_fallsBackToLegacyPath() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSkills()
                        .build();

        List<Hook> hooks = agent.getDelegate().getHooks();
        assertFalse(
                anyOfType(hooks, DynamicSkillHook.class),
                "disableDynamicSkills() must skip the dynamic skill hook");
    }

    @Test
    void disableDynamicSubagents_fallsBackToLegacySubagentsHook() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSubagents()
                        .build();

        List<Hook> hooks = agent.getDelegate().getHooks();
        assertFalse(
                anyOfType(hooks, DynamicSubagentsHook.class),
                "disableDynamicSubagents() must skip the dynamic subagent hook");
        assertTrue(
                anyOfType(hooks, SubagentsHook.class),
                "Legacy SubagentsHook must be registered when dynamic is disabled");
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static boolean anyOfType(List<Hook> hooks, Class<?> type) {
        for (Hook hook : hooks) {
            if (type.isInstance(hook)) {
                return true;
            }
        }
        return false;
    }

    private static Model stubModel(String assistantText) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub-model");
        ChatResponse chunk =
                new ChatResponse(
                        "stub-id",
                        List.of(TextBlock.builder().text(assistantText).build()),
                        null,
                        Map.of(),
                        "stop");
        when(model.stream(anyList(), any(), any())).thenReturn(Flux.just(chunk));
        return model;
    }

    /** Skill repository that returns no skills, used to exercise the custom-repo branch. */
    private static final class EmptySkillRepository implements AgentSkillRepository {
        @Override
        public AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return Collections.emptyList();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return Collections.emptyList();
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return false;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("empty", "", false);
        }

        @Override
        public String getSource() {
            return "empty";
        }

        @Override
        public void setWriteable(boolean writeable) {
            // no-op
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
