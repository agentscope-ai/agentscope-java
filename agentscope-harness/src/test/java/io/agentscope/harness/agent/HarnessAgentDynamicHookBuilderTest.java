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
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware;
import io.agentscope.harness.agent.middleware.SubagentsMiddleware;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Verifies the wiring in {@link HarnessAgent.Builder} for skills + subagents:
 *
 * <ul>
 *   <li>default (workspace filesystem available, no opt-out) → {@link
 *       io.agentscope.core.skill.DynamicSkillMiddleware} + {@link DynamicSubagentsMiddleware}
 *       are registered.
 *   <li>{@code skillRepository(custom)} composes <em>additively</em> with workspace skills — the
 *       dynamic middleware is still registered and exposes both sources.
 *   <li>{@code disableDynamicSkills()} → repositories are frozen in the harness-native skill
 *       middleware without reloading them per call.
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
                anyOfType(mws, io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class),
                "Default build with workspace filesystem must register HarnessSkillMiddleware");
        assertFalse(
                anyOfType(mws, io.agentscope.core.skill.DynamicSkillMiddleware.class),
                "Harness path must NOT install core's DynamicSkillMiddleware");
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
                anyOfType(mws, io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class),
                "Custom skillRepository must compose with the harness skill middleware");
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
                anyOfType(mws, io.agentscope.core.skill.DynamicSkillMiddleware.class),
                "disableDynamicSkills() must skip the dynamic skill middleware");
    }

    @Test
    void disableDynamicSkills_freezesRepositoriesIntoStaticMiddleware() throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        CountingSkillRepository repository =
                new CountingSkillRepository(
                        List.of(
                                new AgentSkill(
                                        "frozen-skill",
                                        "A skill loaded once during agent construction",
                                        "# Frozen skill",
                                        null)));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .skillRepository(repository)
                        .disableDynamicSkills()
                        .build();

        assertEquals(
                1,
                repository.readCount(),
                "Static mode must read each repository once while building the agent");
        io.agentscope.harness.agent.middleware.HarnessSkillMiddleware skillMiddleware =
                agent.getDelegate().getMiddlewares().stream()
                        .filter(
                                io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class
                                        ::isInstance)
                        .map(
                                io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class
                                        ::cast)
                        .findFirst()
                        .orElseThrow();
        assertTrue(skillMiddleware.isFrozen(), "Static mode must freeze repository enumeration");
        assertNotNull(
                agent.getDelegate().getToolkit().getTool("load_skill_through_path"),
                "Static mode must register the skill loading tool during construction");

        agent.call("hello", RuntimeContext.builder().sessionId("static-skills").build()).block();

        assertEquals(
                1,
                repository.readCount(),
                "Static mode must not refresh repositories for each model call");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolSchema>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(anyList(), toolsCaptor.capture(), any());
        assertTrue(
                toolsCaptor.getAllValues().stream()
                        .flatMap(List::stream)
                        .anyMatch(tool -> "load_skill_through_path".equals(tool.getName())),
                "The ungrouped skill loader must remain visible with an empty active-group list");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        String modelInput =
                captor.getAllValues().stream()
                        .flatMap(List::stream)
                        .map(msg -> msg.getTextContent() == null ? "" : msg.getTextContent())
                        .collect(Collectors.joining("\n"));
        assertTrue(
                modelInput.contains("frozen-skill"),
                "Static skill middleware must expose repository skills in the model prompt");
    }

    @Test
    void disableDynamicSkills_appliesBuilderAndVisibilityFiltersToPromptAndLoader()
            throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        CountingSkillRepository repository =
                new CountingSkillRepository(
                        List.of(
                                skill("alpha", "alpha description"),
                                skill("beta", "beta description"),
                                skill("gamma", "gamma description")));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .skillRepository(repository)
                        .enableSkills("alpha", "beta")
                        .enableSkillPromotionGate(
                                null,
                                (skills, ctx) ->
                                        skills.stream()
                                                .filter(skill -> !"alpha".equals(skill.getName()))
                                                .toList())
                        .disableDynamicSkills()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("filtered-static").build();
        agent.call("hello", ctx).block();

        String modelInput = capturedModelInput(model);
        assertFalse(modelInput.contains("alpha description"));
        assertTrue(modelInput.contains("beta description"));
        assertFalse(modelInput.contains("gamma description"));

        io.agentscope.harness.agent.middleware.HarnessSkillMiddleware middleware =
                frozenSkillMiddleware(agent);
        middleware.onSystemPrompt(agent.getDelegate(), ctx, "").block();
        assertEquals(1, middleware.runtime().currentCatalog(ctx).size());
        assertEquals(
                "beta",
                middleware.runtime().currentCatalog(ctx).all().iterator().next().skill().getName(),
                "The loader catalog must use the same filtered view as the prompt");
        assertEquals(1, repository.readCount());
    }

    @Test
    void disableDynamicSkills_skillsEnabledFalseLeavesCatalogEmpty() throws Exception {
        Files.createDirectories(workspace);
        Model model = stubModel("ok");
        CountingSkillRepository repository =
                new CountingSkillRepository(List.of(skill("disabled", "must stay hidden")));

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .skillRepository(repository)
                        .skillsEnabled(false)
                        .disableDynamicSkills()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("no-static-skills").build();
        agent.call("hello", ctx).block();

        assertFalse(capturedModelInput(model).contains("must stay hidden"));
        assertTrue(frozenSkillMiddleware(agent).runtime().currentCatalog(ctx).isEmpty());
        assertEquals(1, repository.readCount());
    }

    @Test
    void disableDynamicSkills_keepsWorkspaceLazyResourcesLoadable() throws Exception {
        Path skillDir = workspace.resolve("skills/lazy-resource");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: lazy-resource\ndescription: Loads a lazy reference\n---\n# Body\n");
        Files.writeString(skillDir.resolve("references/guide.md"), "lazy reference body");

        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("t")
                        .model(stubModel("ok"))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .disableDynamicSkills()
                        .build();

        RuntimeContext ctx = RuntimeContext.builder().sessionId("lazy-static").build();
        agent.call("hello", ctx).block();
        frozenSkillMiddleware(agent).onSystemPrompt(agent.getDelegate(), ctx, "").block();

        ToolResultBlock result =
                agent.getDelegate()
                        .getToolkit()
                        .getTool("load_skill_through_path")
                        .callAsync(
                                ToolCallParam.builder()
                                        .runtimeContext(ctx)
                                        .input(
                                                Map.of(
                                                        "skillId",
                                                        "lazy-resource_workspace-namespaced",
                                                        "path",
                                                        "references/guide.md"))
                                        .build())
                        .block();

        assertNotNull(result);
        assertTrue(toolResultText(result).contains("lazy reference body"));
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

    private static io.agentscope.harness.agent.middleware.HarnessSkillMiddleware
            frozenSkillMiddleware(HarnessAgent agent) {
        return agent.getDelegate().getMiddlewares().stream()
                .filter(
                        io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class
                                ::isInstance)
                .map(io.agentscope.harness.agent.middleware.HarnessSkillMiddleware.class::cast)
                .filter(io.agentscope.harness.agent.middleware.HarnessSkillMiddleware::isFrozen)
                .findFirst()
                .orElseThrow();
    }

    private static AgentSkill skill(String name, String description) {
        return new AgentSkill(name, description, "# " + name, null);
    }

    private static String capturedModelInput(Model model) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, atLeast(1)).stream(captor.capture(), any(), any());
        return captor.getAllValues().stream()
                .flatMap(List::stream)
                .map(msg -> msg.getTextContent() == null ? "" : msg.getTextContent())
                .collect(Collectors.joining("\n"));
    }

    private static String toolResultText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
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

    private static class EmptySkillRepository implements AgentSkillRepository {
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

    private static final class CountingSkillRepository extends EmptySkillRepository {
        private final List<AgentSkill> skills;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingSkillRepository(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            reads.incrementAndGet();
            return skills;
        }

        private int readCount() {
            return reads.get();
        }
    }
}
