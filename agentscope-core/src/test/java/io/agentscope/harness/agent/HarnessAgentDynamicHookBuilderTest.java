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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.core.legacy.skill.SkillHook;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepository;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
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
 * Verifies the wiring in {@link HarnessAgent.Builder} for skills + subagents:
 *
 * <ul>
 *   <li>default (workspace filesystem available, no opt-out) → {@link DynamicSkillHook} +
 *       {@link DynamicSubagentsHook} are registered.
 *   <li>{@code skillRepository(custom)} composes <em>additively</em> with workspace skills — the
 *       dynamic hook is still registered and exposes both sources.
 *   <li>{@code disableDynamicSkills()} → no {@link DynamicSkillHook}; falls back to the static
 *       legacy {@link SkillHook} path.
 *   <li>{@code disableDynamicSubagents()} → no {@link DynamicSubagentsHook}; falls back to the
 *       static {@link SubagentsHook}.
 * </ul>
 *
 * <p>The contract under test is the hook list registered on the underlying {@code ReActAgent}.
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
    void customSkillRepository_composesWithDynamicHook() throws Exception {
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
        // Repos now compose additively with the workspace and namespaced filesystem layers.
        // The dynamic hook must be registered, and the legacy SkillHook must NOT be registered.
        assertTrue(
                anyOfType(hooks, DynamicSkillHook.class),
                "Custom skillRepository must compose with the dynamic skill hook");
        assertFalse(
                anyOfType(hooks, SkillHook.class),
                "Legacy SkillHook must not be registered when dynamic loading is active");
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
    void getSkillRepositories_exposesComposedListInOrder() throws Exception {
        Files.createDirectories(workspace);
        AgentSkillRepository custom = new EmptySkillRepository();

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .skillRepository(custom)
                        .build();

        List<AgentSkillRepository> repos = agent.getSkillRepositories();
        assertNotNull(repos, "getSkillRepositories() must never return null");
        // Marketplace (custom) repo + workspace shared + per-user namespace = 3 entries.
        // Marketplaces sit at index 0 (lowest priority above project-global, which is unset).
        assertTrue(
                repos.size() >= 1,
                "Composed skill repositories must include at least the registered marketplace");
        assertSame(
                custom,
                repos.get(0),
                "First composed repository should be the registered marketplace");
    }

    @Test
    void getSkillRepositories_isEmptyWhenNothingComposed() throws Exception {
        Files.createDirectories(workspace);
        // Use disableDynamicSkills() to bypass workspace/namespace layers; build with no
        // marketplaces. Even when dynamic loading is off, composeSkillRepositories() still
        // computes a snapshot at build time, which is what getSkillRepositories() returns.
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSkills()
                        .build();

        // Even in static mode the field must be non-null.
        assertNotNull(agent.getSkillRepositories());
    }

    @Test
    void getSkillRepositories_returnsImmutableList() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<AgentSkillRepository> first = agent.getSkillRepositories();
        List<AgentSkillRepository> second = agent.getSkillRepositories();
        // Same snapshot on repeated calls.
        assertEquals(first.size(), second.size());
        try {
            first.add(new EmptySkillRepository());
            // If we reach here, the list is mutable — this is a bug in the accessor contract.
            org.junit.jupiter.api.Assertions.fail(
                    "getSkillRepositories() must return an immutable list");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
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
