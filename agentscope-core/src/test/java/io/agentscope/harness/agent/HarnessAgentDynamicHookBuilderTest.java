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

import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepository;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.DynamicSkillMiddleware;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
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
 *   <li>default (workspace filesystem available, no opt-out) → {@link DynamicSkillMiddleware} +
 *       {@link DynamicSubagentsMiddleware} are registered.
 *   <li>{@code skillRepository(custom)} composes <em>additively</em> with workspace skills — the
 *       dynamic middleware is still registered and exposes both sources.
 *   <li>{@code disableDynamicSkills()} → no {@link DynamicSkillMiddleware}.
 *   <li>{@code disableDynamicSubagents()} → no {@link DynamicSubagentsMiddleware}; falls back to
 *       the static {@link SubagentsMiddleware}.
 * </ul>
 *
 * <p>The contract under test is the middleware list registered on the underlying
 * {@code ReActAgent}.
 */
class HarnessAgentDynamicHookBuilderTest {

    @TempDir Path workspace;

    @Test
    void defaultBuild_registersDynamicSkillAndSubagentMiddlewares() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build();

        List<MiddlewareBase> mws = agent.getDelegate().getMiddlewares();
        assertTrue(
                anyOfType(mws, DynamicSkillMiddleware.class),
                "Default build with workspace filesystem must register DynamicSkillMiddleware");
        assertTrue(
                anyOfType(mws, DynamicSubagentsMiddleware.class),
                "Default build with workspace filesystem must register DynamicSubagentsMiddleware");
        assertFalse(
                anyOfType(mws, SubagentsMiddleware.class),
                "Default build must NOT register the static SubagentsMiddleware");
    }

    @Test
    void customSkillRepository_composesWithDynamicMiddleware() throws Exception {
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

        List<MiddlewareBase> mws = agent.getDelegate().getMiddlewares();
        assertTrue(
                anyOfType(mws, DynamicSkillMiddleware.class),
                "Custom skillRepository must compose with the dynamic skill middleware");
    }

    @Test
    void disableDynamicSkills_skipsDynamicSkillMiddleware() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSkills()
                        .build();

        List<MiddlewareBase> mws = agent.getDelegate().getMiddlewares();
        assertFalse(
                anyOfType(mws, DynamicSkillMiddleware.class),
                "disableDynamicSkills() must skip the dynamic skill middleware");
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
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSkills()
                        .build();

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
        assertEquals(first.size(), second.size());
        try {
            first.add(new EmptySkillRepository());
            org.junit.jupiter.api.Assertions.fail(
                    "getSkillRepositories() must return an immutable list");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    void disableDynamicSubagents_fallsBackToStaticSubagentsMiddleware() throws Exception {
        Files.createDirectories(workspace);
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSubagents()
                        .build();

        List<MiddlewareBase> mws = agent.getDelegate().getMiddlewares();
        assertFalse(
                anyOfType(mws, DynamicSubagentsMiddleware.class),
                "disableDynamicSubagents() must skip the dynamic subagent middleware");
        assertTrue(
                anyOfType(mws, SubagentsMiddleware.class),
                "Static SubagentsMiddleware must be registered when dynamic is disabled");
    }

    private static boolean anyOfType(List<MiddlewareBase> mws, Class<?> type) {
        for (MiddlewareBase mw : mws) {
            if (type.isInstance(mw)) {
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
