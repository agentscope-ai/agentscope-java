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
package io.agentscope.core.skill.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SkillSummaryReader}: the cache-first decision (the mirror's {@code
 * SKILL.md} when cached, the repository's {@code getSkill} only on miss) and the
 * summary form (metadata and SKILL.md body only — never the package's resources).
 */
@Tag("unit")
@DisplayName("SkillSummaryReader cache-first summary read")
class SkillSummaryReaderTest {

    /** Minimal repository counting {@code getSkill} calls (the fallback under test). */
    private static final class CountingRepository implements AgentSkillRepository {

        private final AtomicInteger fetches = new AtomicInteger();
        private final AgentSkill fetchedOnMiss;

        CountingRepository(AgentSkill fetchedOnMiss) {
            this.fetchedOnMiss = fetchedOnMiss;
        }

        int fetches() {
            return fetches.get();
        }

        @Override
        public AgentSkill getSkill(String name, String version) {
            fetches.incrementAndGet();
            return "v1".equals(version) ? fetchedOnMiss : null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public AgentSkill getSkill(String name) {
            return getSkill(name, "v1");
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<String> getAllSkillNames() {
            return List.of("demo-skill");
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<AgentSkill> getAllSkills() {
            return List.of(getSkill("demo-skill", "v1"));
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
            return true;
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("counting", "summary-reader-test", false);
        }

        @Override
        public String getSource() {
            return "counting";
        }

        @Override
        public void setWriteable(boolean writeable) {}

        @Override
        public boolean isWriteable() {
            return false;
        }

        @Override
        public void close() {}
    }

    @TempDir Path tempDir;

    /** The repository identity the tests key the mirror tree by. */
    private static final String REPO_ID = "counting_summary-reader-test";

    private static AgentSkill fullSkill() {
        // Standard transport form: frontmatter-stripped body, metadata carried separately —
        // the store regenerates the frontmatter when installing to disk.
        return new AgentSkill(
                "demo-skill",
                "Full description straight from the repository",
                "\n# Demo Skill\nCached body.",
                Map.of("scripts/run.sh", "echo full-package-resource"),
                "counting");
    }

    /** Writes the {@code demo-skill}@{@code v1} SKILL.md directly, bypassing the store's
     * install — the fixture for corrupt / foreign cache-entry tests. */
    private void writeCachedSkillMd(String skillMd) throws IOException {
        Path dir = tempDir.resolve(REPO_ID).resolve("demo-skill").resolve("v1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Cache hit serves the SKILL.md frontmatter summary with zero repo contact")
    void testCacheHitServesFrontmatterSummary() throws IOException {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        writeCachedSkillMd(
                "---\nname: demo-skill\ndescription: Cached frontmatter description\n---\n\n"
                        + "# Demo Skill\nCached body.");
        CountingRepository repository = new CountingRepository(fullSkill());

        AgentSkill summary =
                SkillSummaryReader.readSummary(mirror, repository, REPO_ID, "demo-skill", "v1");

        assertEquals("demo-skill", summary.getName());
        assertEquals(
                "Cached frontmatter description",
                summary.getDescription(),
                "The description must come from the cached SKILL.md frontmatter, not the"
                        + " repository object");
        assertEquals("v1", summary.getVersion(), "The summary must carry its version");
        assertEquals(
                mirror.lookup(REPO_ID, "demo-skill", "v1").orElseThrow(),
                summary.getOriginDir().orElseThrow(),
                "The summary must point at the mirrored version directory");
        assertTrue(
                summary.getResources().isEmpty(),
                "The summary must be single-file: no package resources are loaded");
        assertEquals(0, repository.fetches(), "A cache hit must not contact the repository at all");
    }

    @Test
    @DisplayName("Cache miss falls back to the repository version route")
    void testCacheMissFallsBackToRepository() {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        AgentSkill remote = fullSkill();
        CountingRepository repository = new CountingRepository(remote);

        AgentSkill result =
                SkillSummaryReader.readSummary(
                        mirror, repository, "counting_summary-reader-test", "demo-skill", "v1");

        assertEquals(
                remote,
                result,
                "The repository fetch result must be returned verbatim on a cache miss");
        assertEquals(1, repository.fetches(), "The fallback must call the version route once");
    }

    @Test
    @DisplayName("Neither cache nor repository resolves: returns null")
    void testNeitherSourceResolves() {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        CountingRepository repository = new CountingRepository(fullSkill());

        AgentSkill result =
                SkillSummaryReader.readSummary(
                        mirror, repository, "counting_summary-reader-test", "demo-skill", "v2");

        assertNull(result, "An unresolvable version must yield null from both sources");
        assertEquals(1, repository.fetches());
    }

    @Test
    @DisplayName("A cached SKILL.md without valid frontmatter falls back to the repository")
    void testInvalidCachedEntryFallsBackToRepository() throws IOException {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        writeCachedSkillMd("# Body only, no frontmatter at all");
        CountingRepository repository = new CountingRepository(fullSkill());

        AgentSkill result =
                SkillSummaryReader.readSummary(mirror, repository, REPO_ID, "demo-skill", "v1");

        assertEquals(
                "Full description straight from the repository",
                result.getDescription(),
                "An unparseable cache entry must fall back to the repository result");
        assertEquals(1, repository.fetches());
    }

    @Test
    @DisplayName("A cached SKILL.md declaring a different name is treated as a miss")
    void testFrontmatterNameMismatchIsAMiss() throws IOException {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        writeCachedSkillMd("---\nname: other-skill\ndescription: Mismatched\n---\n\n# Body");
        CountingRepository repository = new CountingRepository(fullSkill());

        assertNull(
                SkillSummaryReader.readFromMirror(mirror, REPO_ID, "demo-skill", "v1", "counting"),
                "A name-diverging entry must not be served from the mirror");
        AgentSkill result =
                SkillSummaryReader.readSummary(mirror, repository, REPO_ID, "demo-skill", "v1");
        assertEquals("demo-skill", result.getName(), "The fallback must restore the true entry");
        assertEquals(1, repository.fetches());
    }

    @Test
    @DisplayName("A cached frontmatter-only SKILL.md (empty body) is treated as a miss")
    void testEmptyBodyEntryIsAMiss() throws IOException {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        writeCachedSkillMd("---\nname: demo-skill\ndescription: Cached\n---\n");
        CountingRepository repository = new CountingRepository(fullSkill());

        AgentSkill result =
                SkillSummaryReader.readSummary(mirror, repository, REPO_ID, "demo-skill", "v1");

        assertEquals(
                "Full description straight from the repository",
                result.getDescription(),
                "An empty-body entry must fall back to the repository result");
        assertEquals(1, repository.fetches());
    }

    @Test
    @DisplayName("An invalid skill name is a mirror miss; the repository route still applies")
    void testInvalidNameIsAMirrorMiss() {
        SkillMirrorStore mirror = new SkillMirrorStore(tempDir);
        CountingRepository repository = new CountingRepository(fullSkill());

        assertNull(
                SkillSummaryReader.readFromMirror(mirror, REPO_ID, "../escape", "v1", "counting"));
        assertNull(
                SkillSummaryReader.readSummary(mirror, repository, REPO_ID, "../escape", "v9"),
                "A name that cannot key the mirror falls through to the repository route");
        assertEquals(1, repository.fetches());
    }
}
