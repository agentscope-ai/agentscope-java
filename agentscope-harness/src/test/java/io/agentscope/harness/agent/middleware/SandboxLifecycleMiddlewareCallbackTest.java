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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.ShellPathPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link HarnessSkillMiddleware#prestageMarketplaceSkills} materialises
 * {@code .skills-cache/} on the host workspace so that sandbox projection can pick up
 * database-backed skill resources before {@code sandbox.start()}.
 */
class SandboxLifecycleMiddlewareCallbackTest {

    @TempDir Path tempWorkspace;

    @Test
    void prestageMarketplaceSkillsMaterialisesSkillsCache() throws IOException {
        AgentSkill dbSkill =
                new AgentSkill(
                        "db-tool",
                        "A database-sourced skill",
                        "skill content",
                        Map.of("run.sh", "#!/bin/bash\necho hello"),
                        "test-db");

        StubSkillRepository dbRepo = new StubSkillRepository(List.of(dbSkill), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(dbRepo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());

        Path cacheDir = tempWorkspace.resolve(".skills-cache");
        assertTrue(Files.notExists(cacheDir), ".skills-cache should not exist before prestage");

        middleware.prestageMarketplaceSkills(RuntimeContext.empty());

        assertTrue(Files.isDirectory(cacheDir), ".skills-cache should be created by prestage");
        Path stagedScript =
                cacheDir.resolve("_shared").resolve("test-db").resolve("db-tool").resolve("run.sh");
        assertTrue(Files.exists(stagedScript), "run.sh should be staged");
        String content = Files.readString(stagedScript);
        assertEquals("#!/bin/bash\necho hello", content);
    }

    @Test
    void prestageIsIdempotent() {
        AgentSkill skill =
                new AgentSkill(
                        "idempotent-skill", "test", "content", Map.of("data.txt", "hello"), "src");

        StubSkillRepository repo = new StubSkillRepository(List.of(skill), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(repo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());

        middleware.prestageMarketplaceSkills(RuntimeContext.empty());
        middleware.prestageMarketplaceSkills(RuntimeContext.empty());

        Path staged = tempWorkspace.resolve(".skills-cache/_shared/src/idempotent-skill/data.txt");
        assertTrue(Files.exists(staged));
    }

    @Test
    void prestageWithNullStagerIsNoOp() {
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(), new Toolkit(), null, null, null, ShellPathPolicy.noShell());

        middleware.prestageMarketplaceSkills(RuntimeContext.empty());
        assertTrue(Files.notExists(tempWorkspace.resolve(".skills-cache")));
    }

    @Test
    void prestageCalledViaCallbackMaterialisesCache() {
        AgentSkill skill =
                new AgentSkill(
                        "callback-skill",
                        "test",
                        "content",
                        Map.of("tool.py", "print('hi')"),
                        "cb-src");

        StubSkillRepository repo = new StubSkillRepository(List.of(skill), "cb-src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        HarnessSkillMiddleware skillMw =
                new HarnessSkillMiddleware(
                        List.of(repo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());

        // Simulate the callback wiring done by HarnessAgent.Builder.build()
        java.util.function.Consumer<RuntimeContext> callback = skillMw::prestageMarketplaceSkills;
        callback.accept(RuntimeContext.empty());

        Path staged = tempWorkspace.resolve(".skills-cache/_shared/cb-src/callback-skill/tool.py");
        assertTrue(
                Files.exists(staged),
                ".skills-cache should be populated by the callback before sandbox.start()");
    }

    @Test
    void prestageForProjectionReturnsExplicitCacheRoots() {
        AgentSkill skill =
                new AgentSkill(
                        "rooted-skill", "test", "content", Map.of("data.txt", "hello"), "root-src");
        StubSkillRepository repo = new StubSkillRepository(List.of(skill), "root-src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(repo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());

        List<String> roots = middleware.prestageForProjection(RuntimeContext.empty());

        assertEquals(
                List.of(".skills-cache/_shared/root-src/rooted-skill"),
                roots,
                "an unrestricted call projects exactly the staged skill's explicit subtree");
    }

    @Test
    void prestageForProjectionWithoutStagerReturnsNull() {
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(), new Toolkit(), null, null, null, ShellPathPolicy.noShell());

        assertNull(
                middleware.prestageForProjection(RuntimeContext.empty()),
                "no stager means nothing this middleware manages lives on disk: keep the"
                        + " configured projection");
    }

    @Test
    void prestageForProjectionWithDenyAllGrantReturnsEmptyAndStagesNothing() {
        AgentSkill skill =
                new AgentSkill("denied-skill", "test", "content", Map.of("x.txt", "x"), "src");
        StubSkillRepository repo = new StubSkillRepository(List.of(skill), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(repo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());
        RuntimeContext rc = RuntimeContext.builder().userId("u1").build();
        rc.put(CapabilitySurface.class, CapabilitySurface.DENY_ALL);

        List<String> roots = middleware.prestageForProjection(rc);

        assertTrue(
                roots.isEmpty(),
                "a deny-all grant must remove the whole cache from this call's projection");
        assertTrue(
                Files.notExists(tempWorkspace.resolve(".skills-cache")),
                "nothing may be staged for a call whose grant set is empty");
    }

    @Test
    void prestageForProjectionNarrowsAfterGrantShrinksWhileDiskResidueRemains() {
        // P1-2 scenario: call 1 grants alpha+beta (both staged); call 2 for the same user
        // grants only alpha. The residue stays on disk (grace-window GC owns deletion), but
        // the returned projection roots must no longer name beta.
        AgentSkill alpha =
                new AgentSkill("alpha", "Alpha", "# A", Map.of("run.sh", "echo a"), "market");
        AgentSkill beta =
                new AgentSkill("beta", "Beta", "# B", Map.of("run.sh", "echo b"), "market");
        StubSkillRepository repo = new StubSkillRepository(List.of(alpha, beta), "market");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(repo),
                        new Toolkit(),
                        null,
                        null,
                        stager,
                        ShellPathPolicy.noShell());

        RuntimeContext wide = RuntimeContext.builder().userId("u1").build();
        wide.put(
                CapabilitySurface.class,
                new CapabilitySurface(java.util.Set.of("alpha", "beta"), null, null));
        RuntimeContext narrow = RuntimeContext.builder().userId("u1").build();
        narrow.put(
                CapabilitySurface.class,
                new CapabilitySurface(java.util.Set.of("alpha"), null, null));

        List<String> wideRoots = middleware.prestageForProjection(wide);
        List<String> narrowRoots = middleware.prestageForProjection(narrow);

        assertEquals(
                List.of(".skills-cache/u1/market/alpha", ".skills-cache/u1/market/beta"),
                wideRoots);
        assertEquals(
                List.of(".skills-cache/u1/market/alpha"),
                narrowRoots,
                "the shrunken grant must drop beta's subtree from this call's projection");
        assertTrue(
                Files.exists(tempWorkspace.resolve(".skills-cache/u1/market/beta/run.sh")),
                "disk residue stays (shared cache, grace-window GC) — only the projection"
                        + " narrows");
    }

    /** Minimal repository stub that returns a fixed skill list. */
    private static final class StubSkillRepository implements AgentSkillRepository {

        private final List<AgentSkill> skills;
        private final String source;

        StubSkillRepository(List<AgentSkill> skills, String source) {
            this.skills = skills;
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            return skills.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        }

        @Override
        public List<String> getAllSkillNames() {
            return skills.stream().map(AgentSkill::getName).toList();
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
            return skills.stream().anyMatch(s -> s.getName().equals(skillName));
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo(source, "", false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
