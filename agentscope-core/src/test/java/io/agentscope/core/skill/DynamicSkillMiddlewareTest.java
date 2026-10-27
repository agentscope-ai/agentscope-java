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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class DynamicSkillMiddlewareTest {

    @TempDir Path workDir;

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
        return new DynamicSkillMiddleware(
                List.of(new StubRepo(skills)), new Toolkit(), null, false, workDir);
    }

    @Test
    void emptyRepositoryProducesNoBox() {
        DynamicSkillMiddleware mw = middleware(List.of());
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(null, rc, "base").block();

        assertEquals("base", prompt);
        assertNull(rc.get(SkillBox.class), "no box should be exposed for an empty repository");
    }

    @Test
    void repositorySkillIsExposedViaRuntimeContext() {
        AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);
        DynamicSkillMiddleware mw = middleware(List.of(skill));
        RuntimeContext rc = RuntimeContext.empty();

        String prompt = mw.onSystemPrompt(null, rc, "base").block();

        assertNotNull(
                rc.get(SkillBox.class), "the per-call SkillBox must be exposed via RuntimeContext");
        assertTrue(prompt.contains("my-skill"), "the skill prompt must contain the skill name");
    }

    @Test
    void reusedRuntimeContextClearsStaleBox() {
        AgentSkill skill = new AgentSkill("my-skill", "My Skill", "# Content", null);
        StubRepo repo = new StubRepo(List.of(skill));
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);
        RuntimeContext rc = RuntimeContext.empty();

        mw.onSystemPrompt(null, rc, "base").block();
        assertNotNull(rc.get(SkillBox.class));

        // Repository becomes empty → a reused RuntimeContext must no longer serve the stale box.
        repo.skills = List.of();
        mw.onSystemPrompt(null, rc, "base").block();
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
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, true, workDir);
        RuntimeContext rcA = RuntimeContext.empty();
        RuntimeContext rcB = RuntimeContext.empty();
        mw.onSystemPrompt(null, rcA, "").block();
        SkillBox boxA = rcA.get(SkillBox.class);
        String id = boxA.getAllSkillIds().iterator().next();
        Path fileA = boxA.getUploadDir().resolve(id).resolve("data.txt");
        repo.skills = List.of(b);
        mw.onSystemPrompt(null, rcB, "").block();
        SkillBox boxB = rcB.get(SkillBox.class);
        org.junit.jupiter.api.Assertions.assertNotEquals(boxA.getUploadDir(), boxB.getUploadDir());
        assertEquals(
                "B",
                java.nio.file.Files.readString(
                        boxB.getUploadDir().resolve(id).resolve("data.txt")));
        repo.skills = List.of(a);
        mw.onSystemPrompt(null, rcA, "").block();
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
                new DynamicSkillMiddleware(
                        List.of(new StubRepo(List.of(skill))),
                        new Toolkit(),
                        null,
                        false,
                        workDir) {
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
                                    mw.onSystemPrompt(null, rc, "").block();
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
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);
        RuntimeContext rc = RuntimeContext.empty();
        mw.onSystemPrompt(null, rc, "").block();
        SkillBox active = rc.get(SkillBox.class);
        Path activeFile = active.getUploadDir().resolve(skill.getSkillId()).resolve("data.txt");
        for (int i = 0; i < 32; i++) {
            repo.skills = List.of(new AgentSkill("other" + i, "Other", "content", null));
            mw.onSystemPrompt(null, RuntimeContext.empty(), "").block();
        }
        // An executing skill may have modified its workspace; rebuilding must leave it alone.
        java.nio.file.Files.writeString(activeFile, "in use");
        repo.skills = List.of(skill);
        mw.onSystemPrompt(null, rc, "").block();
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
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);
        assertTrue(mw.onSystemPrompt(null, a, "").block().contains("skill-a"));
        assertTrue(mw.onSystemPrompt(null, b, "").block().contains("skill-b"));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).getAllSkills();
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
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);

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
        DynamicSkillMiddleware mw =
                new DynamicSkillMiddleware(List.of(repo), new Toolkit(), null, false, workDir);
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
