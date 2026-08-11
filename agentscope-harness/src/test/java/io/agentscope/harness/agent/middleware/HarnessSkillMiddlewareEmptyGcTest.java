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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that an empty skill set (repository returns no skills, or every skill is filtered
 * out) still triggers orphan GC on {@code .skills-cache/}, so stale staged content is not
 * projected into the sandbox — while a repository <em>load failure</em> preserves the cache.
 *
 * <p>Sweeps follow {@link MarketplaceStager}'s rules: they are confined to the call's
 * isolation scope ({@link MarketplaceStager#SHARED_SCOPE} for an identity-less context) and
 * reclaim only directories untouched past the orphan grace window. Tests that assert clearing
 * therefore age the staged directory first; one test pins the grace behaviour itself.
 */
class HarnessSkillMiddlewareEmptyGcTest {

    @TempDir Path tempWorkspace;

    @Test
    void onSystemPromptClearsSkillsCacheWhenRepositoryReturnsEmpty() throws IOException {
        MutableSkillRepository repo =
                new MutableSkillRepository(List.of(dbSkill("stale-tool")), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware = newMiddleware(List.of(repo), null, stager);

        middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block();
        assertTrue(
                Files.exists(skillDir("stale-tool").resolve("run.sh")),
                "skill should be staged while DB has it");

        // Simulate the DB being emptied, with the staged copy sitting past the orphan grace.
        repo.setSkills(List.of());
        age(skillDir("stale-tool"));

        String prompt = middleware.onSystemPrompt(null, RuntimeContext.empty(), "base").block();
        assertEquals("base", prompt, "no skill block should be appended for an empty catalog");
        assertCacheEmpty();
    }

    @Test
    void prestageMarketplaceSkillsClearsSkillsCacheWhenRepositoryReturnsEmpty() throws IOException {
        MutableSkillRepository repo =
                new MutableSkillRepository(List.of(dbSkill("stale-tool")), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware = newMiddleware(List.of(repo), null, stager);

        middleware.prestageMarketplaceSkills(RuntimeContext.empty());
        assertTrue(Files.exists(skillDir("stale-tool").resolve("run.sh")));

        repo.setSkills(List.of());
        age(skillDir("stale-tool"));

        middleware.prestageMarketplaceSkills(RuntimeContext.empty());
        assertCacheEmpty();
    }

    @Test
    void onSystemPromptClearsSkillsCacheWhenVisibilityFilterHidesAll() throws IOException {
        MutableSkillRepository repo =
                new MutableSkillRepository(List.of(dbSkill("hidden-tool")), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        // Seed the cache with an unfiltered middleware first.
        newMiddleware(List.of(repo), null, stager)
                .onSystemPrompt(null, RuntimeContext.empty(), "")
                .block();
        assertTrue(Files.exists(skillDir("hidden-tool").resolve("run.sh")));
        age(skillDir("hidden-tool"));

        // A middleware whose filter hides everything must reclaim the cache.
        HarnessSkillMiddleware filtering =
                newMiddleware(List.of(repo), (all, ctx) -> List.of(), stager);
        filtering.onSystemPrompt(null, RuntimeContext.empty(), "").block();
        assertCacheEmpty();
    }

    @Test
    void emptyRepositoryWithNullStagerDoesNotThrow() {
        HarnessSkillMiddleware middleware =
                new HarnessSkillMiddleware(
                        List.of(new MutableSkillRepository(List.of(), "test-db")),
                        new Toolkit(),
                        null,
                        null,
                        null,
                        ShellPathPolicy.noShell());

        assertDoesNotThrow(
                () -> middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block());
        assertDoesNotThrow(() -> middleware.prestageMarketplaceSkills(RuntimeContext.empty()));
    }

    @Test
    void repositoryLoadFailurePreservesSkillsCache() throws IOException {
        // Seed the cache through a healthy repository first.
        MutableSkillRepository repo =
                new MutableSkillRepository(List.of(dbSkill("kept-tool")), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        newMiddleware(List.of(repo), null, stager)
                .onSystemPrompt(null, RuntimeContext.empty(), "")
                .block();
        Path stagedScript = skillDir("kept-tool").resolve("run.sh");
        assertTrue(Files.exists(stagedScript));

        // A transient DB outage must NOT wipe the cache (empty result caused by an error).
        HarnessSkillMiddleware failing =
                newMiddleware(List.of(new FailingSkillRepository("test-db")), null, stager);
        failing.onSystemPrompt(null, RuntimeContext.empty(), "").block();
        failing.prestageMarketplaceSkills(RuntimeContext.empty());
        assertTrue(
                Files.exists(stagedScript),
                "cache must survive a repository load failure (outage protection)");
    }

    @Test
    void emptySweepKeepsFreshlyStagedDirsUnderDefaultGrace() throws IOException {
        MutableSkillRepository repo =
                new MutableSkillRepository(List.of(dbSkill("live-tool")), "test-db");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);
        HarnessSkillMiddleware middleware = newMiddleware(List.of(repo), null, stager);

        middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block();

        // The DB empties immediately after claiming the skill: the staged copy is still
        // fresh, so the empty sweep must leave it alone rather than delete content a
        // concurrent call may still be using (see the orphan grace in MarketplaceStager).
        repo.setSkills(List.of());
        middleware.onSystemPrompt(null, RuntimeContext.empty(), "").block();

        assertTrue(
                Files.exists(skillDir("live-tool").resolve("run.sh")),
                "a directory inside the orphan grace window must survive the empty sweep");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Path cacheDir() {
        return tempWorkspace.resolve(MarketplaceStager.CACHE_DIR);
    }

    /** Scope subtree used when the context carries no identity ({@link RuntimeContext#empty()}). */
    private Path scopeDir() {
        return cacheDir().resolve(MarketplaceStager.SHARED_SCOPE);
    }

    private Path skillDir(String skillName) {
        return scopeDir().resolve("test-db").resolve(skillName);
    }

    /** Backdates a staged directory so it falls outside the orphan grace window. */
    private static void age(Path dir) throws IOException {
        Files.setLastModifiedTime(dir, FileTime.from(Instant.now().minus(Duration.ofDays(7))));
    }

    private void assertCacheEmpty() throws IOException {
        if (Files.notExists(scopeDir())) {
            return;
        }
        try (var entries = Files.list(scopeDir())) {
            assertEquals(0, entries.count(), ".skills-cache must not retain any namespace dir");
        }
    }

    private static AgentSkill dbSkill(String name) {
        return new AgentSkill(
                name,
                "A database-sourced skill",
                "skill content",
                Map.of("run.sh", "#!/bin/bash\necho hello"),
                "test-db");
    }

    private static HarnessSkillMiddleware newMiddleware(
            List<AgentSkillRepository> repos,
            io.agentscope.harness.agent.skill.curator.SkillVisibilityFilter visibilityFilter,
            MarketplaceStager stager) {
        return new HarnessSkillMiddleware(
                repos, new Toolkit(), null, visibilityFilter, stager, ShellPathPolicy.noShell());
    }

    /** Repository stub whose skill list can change between calls (simulates DB mutations). */
    private static final class MutableSkillRepository implements AgentSkillRepository {

        private final List<AgentSkill> skills = new ArrayList<>();
        private final String source;

        MutableSkillRepository(List<AgentSkill> initial, String source) {
            this.skills.addAll(initial);
            this.source = source;
        }

        void setSkills(List<AgentSkill> next) {
            skills.clear();
            skills.addAll(next);
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
            return List.copyOf(skills);
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

    /** Repository stub that always fails, simulating a transient DB outage. */
    private static final class FailingSkillRepository implements AgentSkillRepository {

        private final String source;

        FailingSkillRepository(String source) {
            this.source = source;
        }

        @Override
        public AgentSkill getSkill(String name) {
            throw new IllegalStateException("database unavailable");
        }

        @Override
        public List<String> getAllSkillNames() {
            throw new IllegalStateException("database unavailable");
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            throw new IllegalStateException("database unavailable");
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
