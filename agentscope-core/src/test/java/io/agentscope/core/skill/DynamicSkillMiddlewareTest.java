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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class DynamicSkillMiddlewareTest {

    @TempDir Path workDir;

    /**
     * Minimal fake agent standing in for the framework caller — direct middleware invocation
     * must pass a non-null agent per the {@code onSystemPrompt} contract.
     */
    private final Agent agent = org.mockito.Mockito.mock(Agent.class);

    @BeforeEach
    void stubAgentDefaults() {
        org.mockito.Mockito.when(agent.getSkillWorkDir()).thenReturn(workDir);
    }

    /** Minimal repository stub: only {@code getAllSkills()} is meaningful for these tests. */
    private static final class StubRepo implements AgentSkillRepository {
        List<AgentSkill> skills;

        StubRepo(List<AgentSkill> skills) {
            this.skills = skills;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return null;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.of();
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return skills;
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
            return null;
        }

        @Override
        public String getSource() {
            return "stub";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }

    private DynamicSkillMiddleware middleware(List<AgentSkill> skills) {
        return new DynamicSkillMiddleware(List.of(new StubRepo(skills)));
    }

    @Test
    void emptyRepositoryProducesNoBox() {
        DynamicSkillMiddleware mw = middleware(List.of());
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(agent, rc, "base").block();

        assertEquals("base", prompt);
        assertNull(rc.get(SkillBox.class), "no box should be exposed for an empty repository");
    }

    @Test
    void repositorySkillIsExposedViaRuntimeContext() {
        AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);
        DynamicSkillMiddleware mw = middleware(List.of(skill));
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(agent, rc, "base").block();

        assertNotNull(
                rc.get(SkillBox.class), "the per-call SkillBox must be exposed via RuntimeContext");
        assertTrue(prompt.contains("my-skill"), "the skill prompt must contain the skill name");
    }

    @Test
    void reusedRuntimeContextClearsStaleBox() {
        AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);
        StubRepo repo = new StubRepo(List.of(skill));
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo));
        RuntimeContext rc = RuntimeContext.empty();

        mw.onSystemPrompt(agent, rc, "base").block();
        assertNotNull(rc.get(SkillBox.class));

        // Repository becomes empty → a reused RuntimeContext must no longer serve the stale box.
        repo.skills = List.of();
        mw.onSystemPrompt(agent, rc, "base").block();
        assertNull(
                rc.get(SkillBox.class), "a reused RuntimeContext must have its stale box cleared");
    }

    @Test
    void alternatingContentKeepsMaterializedResourcesIsolated() throws Exception {
        AgentSkill a =
                new AgentSkill(
                        "shared", "Shared", "instructions", java.util.Map.of("data.txt", "A"));
        AgentSkill b =
                new AgentSkill(
                        "shared", "Shared", "instructions", java.util.Map.of("data.txt", "B"));
        StubRepo repo = new StubRepo(List.of(a));
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo), true);
        RuntimeContext rcA = RuntimeContext.empty();
        RuntimeContext rcB = RuntimeContext.empty();
        mw.onSystemPrompt(agent, rcA, "").block();
        SkillBox boxA = rcA.get(SkillBox.class);
        String id = boxA.getAllSkillIds().iterator().next();
        Path fileA = boxA.getUploadDir().resolve(id).resolve("data.txt");
        repo.skills = List.of(b);
        mw.onSystemPrompt(agent, rcB, "").block();
        SkillBox boxB = rcB.get(SkillBox.class);
        org.junit.jupiter.api.Assertions.assertNotEquals(boxA.getUploadDir(), boxB.getUploadDir());
        assertEquals(
                "B",
                java.nio.file.Files.readString(
                        boxB.getUploadDir().resolve(id).resolve("data.txt")));
        repo.skills = List.of(a);
        mw.onSystemPrompt(agent, rcA, "").block();
        org.junit.jupiter.api.Assertions.assertSame(boxA, rcA.get(SkillBox.class));
        assertEquals("A", java.nio.file.Files.readString(fileA));
    }

    @Test
    void concurrentMissesShareOneFullyMaterializedBox() throws Exception {
        AgentSkill skill =
                new AgentSkill(
                        "shared",
                        "Shared",
                        "instructions",
                        java.util.Map.of("data.txt", "complete resource"));
        var barrier = new java.util.concurrent.CyclicBarrier(8);
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(new StubRepo(List.of(skill)))) {
                    @Override
                    protected List<AgentSkill> filterVisible(
                            List<AgentSkill> raw, RuntimeContext ctx) {
                        try {
                            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                        return raw;
                    }
                };
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<SkillBox>>();
            for (int i = 0; i < 8; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    RuntimeContext rc = RuntimeContext.empty();
                                    mw.onSystemPrompt(agent, rc, "").block();
                                    SkillBox box = rc.get(SkillBox.class);
                                    assertEquals(
                                            "complete resource",
                                            java.nio.file.Files.readString(
                                                    box.getUploadDir()
                                                            .resolve(skill.getSkillId())
                                                            .resolve("data.txt")));
                                    return box;
                                }));
            }
            SkillBox first = futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS);
            for (var future : futures) {
                org.junit.jupiter.api.Assertions.assertSame(
                        first, future.get(15, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictionDoesNotRewriteResourcesOfAnActiveBox() throws Exception {
        AgentSkill skill =
                new AgentSkill(
                        "shared",
                        "Shared",
                        "instructions",
                        java.util.Map.of("data.txt", "original"));
        StubRepo repo = new StubRepo(List.of(skill));
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo));
        RuntimeContext rc = RuntimeContext.empty();
        mw.onSystemPrompt(agent, rc, "").block();
        SkillBox active = rc.get(SkillBox.class);
        Path activeFile = active.getUploadDir().resolve(skill.getSkillId()).resolve("data.txt");
        for (int i = 0; i < 32; i++) {
            repo.skills = List.of(new AgentSkill("other" + i, "Other", "content", null));
            mw.onSystemPrompt(agent, RuntimeContext.empty(), "").block();
        }
        // An executing skill may have modified its workspace; rebuilding must leave it alone.
        java.nio.file.Files.writeString(activeFile, "in use");
        repo.skills = List.of(skill);
        mw.onSystemPrompt(agent, rc, "").block();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                active.getUploadDir(), rc.get(SkillBox.class).getUploadDir());
        assertEquals("in use", java.nio.file.Files.readString(activeFile));
        assertEquals(
                "original",
                java.nio.file.Files.readString(
                        rc.get(SkillBox.class)
                                .getUploadDir()
                                .resolve(skill.getSkillId())
                                .resolve("data.txt")));
    }

    @Test
    void contextualRepositoryReceivesExactRequest() {
        var repo =
                org.mockito.Mockito.mock(
                        io.agentscope.core.skill.repository.RuntimeContextSkillRepository.class);
        RuntimeContext a = RuntimeContext.builder().userId("A").sessionId("s").build();
        RuntimeContext b = RuntimeContext.builder().userId("B").sessionId("s").build();
        org.mockito.Mockito.when(repo.getAllSkills(a))
                .thenReturn(List.of(new AgentSkill("skill-a", "A", "A", null)));
        org.mockito.Mockito.when(repo.getAllSkills(b))
                .thenReturn(List.of(new AgentSkill("skill-b", "B", "B", null)));
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo));
        assertTrue(mw.onSystemPrompt(agent, a, "").block().contains("skill-a"));
        assertTrue(mw.onSystemPrompt(agent, b, "").block().contains("skill-b"));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).getAllSkills();
    }

    @Test
    void constructorHasNoRegistrationSideEffects() {
        Toolkit toolkit = new Toolkit();

        new DynamicSkillMiddleware(List.of(new StubRepo(List.of())));

        assertNull(
                toolkit.getTool(SkillToolFactory.LOAD_TOOL_NAME),
                "construction must not register any tool");
        assertTrue(
                toolkit.getToolNames().isEmpty(), "construction must leave the toolkit untouched");
    }

    @Test
    void firstPromptPassRegistersSkillToolsExactlyOnce() {
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(new StubRepo(List.of())));
        var builder =
                ReActAgent.builder()
                        .name("skill-lazy")
                        .model(org.mockito.Mockito.mock(io.agentscope.core.model.Model.class))
                        .middleware(mw);
        try (var first = builder.build();
                var second = builder.build()) {
            assertNull(
                    first.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "build alone must not register the load tool");
            assertNull(
                    second.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "an agent without a prompt pass must stay unregistered");

            mw.onSystemPrompt(first, RuntimeContext.empty(), "base").block();

            assertNotNull(
                    first.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "the first prompt pass must make the load tool available");
            // Repeated passes must not duplicate the registration.
            mw.onSystemPrompt(first, RuntimeContext.empty(), "base").block();
            assertEquals(
                    1,
                    first.getToolkit().getToolSchemas().stream()
                            .filter(s -> SkillToolFactory.LOAD_TOOL_NAME.equals(s.getName()))
                            .count(),
                    "repeated passes must not register the load tool twice");
            // Each agent registers on its own toolkit at its own first pass.
            mw.onSystemPrompt(second, RuntimeContext.empty(), "base").block();
            assertNotNull(
                    second.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "a rebuilt agent must gain the load tool at its own first pass");
        }
    }

    @Test
    void existingBuilderUsageKeepsToolsWorking() {
        var builder =
                ReActAgent.builder()
                        .name("skill-auto")
                        .model(org.mockito.Mockito.mock(io.agentscope.core.model.Model.class))
                        .skillRepository(new StubRepo(List.of()));
        try (var agent = builder.build()) {
            DynamicSkillMiddleware mw =
                    (DynamicSkillMiddleware)
                            agent.getMiddlewares().stream()
                                    .filter(DynamicSkillMiddleware.class::isInstance)
                                    .findFirst()
                                    .orElseThrow();
            assertNull(
                    agent.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "build alone must not register the load tool");

            mw.onSystemPrompt(agent, RuntimeContext.empty(), "").block();

            assertNotNull(
                    agent.getToolkit().getTool(SkillToolFactory.LOAD_TOOL_NAME),
                    "auto-installed dynamic skills must gain the load tool at the first"
                            + " prompt pass");
        }
    }

    @Test
    void directUseRegistersSkillToolsLazilyOnInvokingAgentsToolkit() {
        Toolkit toolkit = new Toolkit();
        Agent wired = org.mockito.Mockito.mock(Agent.class);
        org.mockito.Mockito.when(wired.getToolkit()).thenReturn(toolkit);
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(new StubRepo(List.of())));
        assertNull(
                toolkit.getTool(SkillToolFactory.LOAD_TOOL_NAME), "construction must not register");

        mw.onSystemPrompt(wired, RuntimeContext.empty(), "base").block();

        assertNotNull(
                toolkit.getTool(SkillToolFactory.LOAD_TOOL_NAME),
                "the first prompt pass must lazily register the load tool on the agent's"
                        + " toolkit");
    }

    @Test
    void registerSkillToolsIsANoopWhenLoadToolIsAlreadyPresent() {
        Toolkit toolkit = new Toolkit();
        io.agentscope.core.tool.AgentTool preexisting = SkillToolFactory.createRuntimeLoadTool();
        toolkit.registration().agentTool(preexisting).apply();

        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(new StubRepo(List.of())));
        mw.registerSkillTools(toolkit);

        assertSame(
                preexisting,
                toolkit.getTool(SkillToolFactory.LOAD_TOOL_NAME),
                "an already-present same-named tool must not be overwritten");
        assertEquals(
                1,
                toolkit.getToolSchemas().stream()
                        .filter(s -> SkillToolFactory.LOAD_TOOL_NAME.equals(s.getName()))
                        .count(),
                "the load tool must not be registered twice");
    }

    @Test
    void getSkillFilterReturnsBuilderBaselineWithAllDefault() {
        var builder =
                ReActAgent.builder()
                        .name("skill-filter-baseline")
                        .model(org.mockito.Mockito.mock(io.agentscope.core.model.Model.class));
        try (var built = builder.build()) {
            assertNotNull(built.getSkillFilter(), "getSkillFilter must never return null");
            assertTrue(
                    built.getSkillFilter().isAllowed("any_skill"),
                    "an unset builder filter must default to SkillFilter.all()");
        }

        SkillFilter baseline = SkillFilter.only("alpha");
        try (var built = builder.copy().skillFilter(baseline).build()) {
            assertSame(
                    baseline,
                    built.getSkillFilter(),
                    "the builder-configured baseline must be returned as-is");
            assertTrue(built.getSkillFilter().isAllowed("alpha"));
            assertFalse(built.getSkillFilter().isAllowed("beta"));
        }
    }

    @Test
    void runtimeOverlayComposesOverAgentBaseline() {
        AgentSkill alpha = new AgentSkill("alpha", "Alpha", "alpha content", null);
        AgentSkill beta = new AgentSkill("beta", "Beta", "beta content", null);
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(new StubRepo(List.of(alpha, beta))));
        org.mockito.Mockito.when(agent.getSkillFilter())
                .thenReturn(SkillFilter.only("alpha", "beta"));

        String baseline = mw.onSystemPrompt(agent, RuntimeContext.empty(), "").block();
        assertTrue(baseline.contains("alpha"), "the agent baseline must show alpha");
        assertTrue(baseline.contains("beta"), "the agent baseline must show beta");

        RuntimeContext rc = RuntimeContext.empty();
        rc.put(SkillFilter.class, SkillFilter.disable("alpha"));
        String overlayed = mw.onSystemPrompt(agent, rc, "").block();
        assertFalse(overlayed.contains("alpha"), "the runtime overlay must disable alpha");
        assertTrue(
                overlayed.contains("beta"),
                "skills not named by the overlay must fall through to the baseline decision");
    }

    @Test
    void baselineResolvesFromEachInvokingAgent() {
        AgentSkill alpha = new AgentSkill("alpha", "Alpha", "alpha content", null);
        AgentSkill gamma = new AgentSkill("gamma", "Gamma", "gamma content", null);
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(new StubRepo(List.of(alpha, gamma))));
        Agent alphaOnly = org.mockito.Mockito.mock(Agent.class);
        org.mockito.Mockito.when(alphaOnly.getSkillFilter()).thenReturn(SkillFilter.only("alpha"));
        org.mockito.Mockito.when(alphaOnly.getSkillWorkDir()).thenReturn(workDir);
        Agent gammaOnly = org.mockito.Mockito.mock(Agent.class);
        org.mockito.Mockito.when(gammaOnly.getSkillFilter()).thenReturn(SkillFilter.only("gamma"));
        org.mockito.Mockito.when(gammaOnly.getSkillWorkDir()).thenReturn(workDir);

        String forAlpha = mw.onSystemPrompt(alphaOnly, RuntimeContext.empty(), "").block();
        String forGamma = mw.onSystemPrompt(gammaOnly, RuntimeContext.empty(), "").block();

        assertTrue(forAlpha.contains("alpha"), "agent A's baseline must apply to its pass");
        assertFalse(forAlpha.contains("gamma"), "agent A's baseline must exclude gamma");
        assertTrue(forGamma.contains("gamma"), "agent B's baseline must apply to its pass");
        assertFalse(forGamma.contains("alpha"), "agent B's baseline must exclude alpha");
    }

    @Test
    void baselineDefaultsToAllSkillsWhenAgentExposesNone() {
        AgentSkill alpha = new AgentSkill("alpha", "Alpha", "alpha content", null);
        AgentSkill gamma = new AgentSkill("gamma", "Gamma", "gamma content", null);
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(new StubRepo(List.of(alpha, gamma))));

        String prompt = mw.onSystemPrompt(agent, RuntimeContext.empty(), "").block();

        assertTrue(prompt.contains("alpha"), "no agent baseline must default to all-allowed");
        assertTrue(prompt.contains("gamma"), "no agent baseline must default to all-allowed");
    }

    @Test
    void boxMaterialisesUnderSystemTempWhenAgentProvidesNoWorkDir() throws Exception {
        AgentSkill skill =
                new AgentSkill(
                        "shared", "Shared", "instructions", java.util.Map.of("data.txt", "x"));
        Agent bare = org.mockito.Mockito.mock(Agent.class);
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(new StubRepo(List.of(skill))));

        RuntimeContext rc = RuntimeContext.empty();
        mw.onSystemPrompt(bare, rc, "").block();

        SkillBox box = rc.get(SkillBox.class);
        String id = box.getAllSkillIds().iterator().next();
        assertEquals(
                "x",
                java.nio.file.Files.readString(box.getUploadDir().resolve(id).resolve("data.txt")),
                "materialisation must work (under the OS temp dir) without an agent-provided"
                        + " work directory");
    }

    /**
     * Golden value of {@code computeSignature} for a fixed two-skill input, captured by running
     * the pre-refactor implementation (which copied the whole resource map per key). The refactor
     * to per-key {@code AgentSkill#getResource(String)} access must keep the hashed bytes — and
     * therefore this constant — bit-for-bit identical. {@code originDir} stays empty because
     * {@code Path#toString()} is platform-dependent.
     */
    private static final String GOLDEN_SIGNATURE =
            "12cf4fa8d7ce1d654ed2e5625b41b5e990bf5658db1c2deb7548413ffea1b7e3";

    @Test
    void signatureValueUnchangedAfterPerKeyResourceAccess() throws Exception {
        java.util.LinkedHashMap<String, Object> alphaMeta = new java.util.LinkedHashMap<>();
        alphaMeta.put("name", "alpha");
        alphaMeta.put("description", "Alpha skill");
        alphaMeta.put("version", "1.2.3");
        alphaMeta.put("tags", List.of("one", "two"));
        alphaMeta.put("nested", java.util.Map.of("k", "v"));
        AgentSkill alpha =
                new AgentSkill(
                        alphaMeta,
                        "alpha body\nsecond line",
                        java.util.Map.of("z.txt", "zeta", "a.txt", "ada", "m/n.txt", "nu"),
                        "repo-a");
        AgentSkill beta =
                new AgentSkill(
                        "beta",
                        "Beta skill",
                        "# beta body",
                        java.util.Map.of("y.md", "why", "b.md", "be"));

        java.lang.reflect.Method compute =
                DynamicSkillMiddleware.class.getDeclaredMethod("computeSignature", List.class);
        compute.setAccessible(true);
        String signature = (String) compute.invoke(null, List.of(beta, alpha));

        assertEquals(GOLDEN_SIGNATURE, signature);
    }

    @Test
    void runsReloadOnBoundedElasticWhenCacheMisses() {
        AtomicReference<String> reloadThreadName = new AtomicReference<>();
        AgentSkillRepository repo = org.mockito.Mockito.mock(AgentSkillRepository.class);
        org.mockito.Mockito.when(repo.getAllSkills())
                .thenAnswer(
                        invocation -> {
                            reloadThreadName.set(Thread.currentThread().getName());
                            return List.of(new AgentSkill("my-skill", "My Skill", "# C", null));
                        });
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo));

        StepVerifier.create(mw.onSystemPrompt(null, RuntimeContext.empty(), "base"))
                .assertNext(prompt -> assertTrue(prompt.contains("my-skill")))
                .verifyComplete();

        assertNotNull(reloadThreadName.get(), "getAllSkills must have been invoked");
        assertTrue(
                reloadThreadName.get().contains("boundedElastic"),
                "blocking reload must run on boundedElastic, but ran on " + reloadThreadName.get());
    }

    @Test
    void keepsCacheHitPathBehaviorUnchanged() {
        AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);
        AgentSkillRepository repo = org.mockito.Mockito.mock(AgentSkillRepository.class);
        org.mockito.Mockito.when(repo.getAllSkills()).thenReturn(List.of(skill));
        DynamicSkillMiddleware mw = new DynamicSkillMiddleware(List.of(repo));
        RuntimeContext rc = RuntimeContext.empty();

        String first = mw.onSystemPrompt(null, rc, "base").block();
        SkillBox firstBox = rc.get(SkillBox.class);
        String second = mw.onSystemPrompt(null, rc, "base").block();
        SkillBox secondBox = rc.get(SkillBox.class);

        assertEquals(first, second);
        assertSame(
                firstBox,
                secondBox,
                "a cache hit with the same signature must reuse the materialised box");
        // Per-call reload semantics are preserved: the repository is still read on every call;
        // only the box build/upload is skipped on the cache hit.
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).getAllSkills();
    }
}
