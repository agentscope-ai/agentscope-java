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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lazy {@link FileSystemSkillRepository} publishes no in-memory resources, so
 * {@link MarketplaceStager} has nothing to materialise for its skills. Staging must report
 * {@link StageResult.None} in that case: reporting {@code Cached} renders a {@code <files-root>}
 * for a directory that stays permanently empty.
 */
class MarketplaceStagerLazyRepositoryTest {

    private static final String SOURCE_NS = "test-ns";
    private static final String SCOPE = "session-1";
    private static final String SKILL_NAME = "my-skill";

    @Test
    @DisplayName("lazy repository stages its on-disk tree and keeps a files-root")
    void lazyRepositoryStagesFromOriginDir(@TempDir Path tmp) throws IOException {
        Path library = writeSkillLibrary(tmp);
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        MarketplaceStager stager = new MarketplaceStager(workspace);

        AgentSkillRepository repo = new FileSystemSkillRepository(library, false, SOURCE_NS, true);
        AgentSkill skill = repo.getAllSkills().get(0);
        assertTrue(skill.getResources().isEmpty(), "a lazy repository must not preload resources");
        assertTrue(skill.getOriginDir().isPresent(), "lazy summaries carry their originDir");

        StageResult result =
                stager.stage(List.of(new RepoBound(skill, repo)), Map.of(repo, SOURCE_NS), SCOPE)
                        .get(SKILL_NAME);

        // The descriptor's tree is staged from originDir so shell and sandbox modes keep a
        // <files-root> — the immutable source makes a complete copy the freshness check.
        assertInstanceOf(StageResult.Cached.class, result);
        assertTrue(
                Files.isRegularFile(stagedDir(workspace).resolve("SKILL.md")),
                "the on-disk manifest is staged verbatim");
        assertNotNull(
                ShellPathPolicy.localWithShell(workspace).resolve(SKILL_NAME, result),
                "both shell modes resolve a files-root for the staged tree");

        // A second pass over the unchanged tree is a pure reuse — still cached, files intact.
        StageResult again =
                stager.stage(List.of(new RepoBound(skill, repo)), Map.of(repo, SOURCE_NS), SCOPE)
                        .get(SKILL_NAME);
        assertInstanceOf(StageResult.Cached.class, again);
        assertTrue(Files.isRegularFile(stagedDir(workspace).resolve("SKILL.md")));
    }

    @Test
    @DisplayName(
            "a resource map whose entries are all rejected reports NONE and leaves no directory")
    void allRejectedResourcesReportNone(@TempDir Path tmp) throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        MarketplaceStager stager = new MarketplaceStager(workspace);

        // Non-empty map: every entry is rejected by the path-safety filter, so nothing is
        // written. The result must match the empty-map case rather than creating a directory
        // that no file ever lands in.
        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("/etc/passwd", "root\n");
        rejected.put("../escape.sh", "#!/bin/sh\n");
        AgentSkill skill = new AgentSkill(SKILL_NAME, "demo", "body\n", rejected, SOURCE_NS);
        AgentSkillRepository repo = new StubRepo(SOURCE_NS);

        StageResult result =
                stager.stage(List.of(new RepoBound(skill, repo)), Map.of(repo, SOURCE_NS), SCOPE)
                        .get(SKILL_NAME);

        assertInstanceOf(StageResult.None.class, result);
        assertEquals(0, countRegularFiles(stagedDir(workspace)));
        assertFalse(
                Files.exists(stagedDir(workspace)),
                "an all-rejected resource map must not leave a staged directory behind");
    }

    @Test
    @DisplayName("non-lazy repository still stages resources and reports Cached")
    void nonLazyRepositoryReportsCached(@TempDir Path tmp) throws IOException {
        Path library = writeSkillLibrary(tmp);
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        MarketplaceStager stager = new MarketplaceStager(workspace);

        AgentSkillRepository repo = new FileSystemSkillRepository(library, false, SOURCE_NS, false);
        AgentSkill skill = repo.getAllSkills().get(0);

        StageResult result =
                stager.stage(List.of(new RepoBound(skill, repo)), Map.of(repo, SOURCE_NS), SCOPE)
                        .get(SKILL_NAME);

        assertInstanceOf(StageResult.Cached.class, result);
        assertTrue(
                countRegularFiles(stagedDir(workspace)) > 0,
                "a staged skill directory must contain the skill's resources");
    }

    @Test
    @DisplayName("a same-name version swap rebuilds the staged tree instead of reusing it")
    void stagedTreeFollowsVersionRefresh(@TempDir Path tmp) throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        MarketplaceStager stager = new MarketplaceStager(workspace);
        AgentSkillRepository repo = new StubRepo(SOURCE_NS);

        // Arrange: two immutable origin trees for the same name — v1 and v2, as a mirror
        // store serves them across a LATEST refresh (one materialized dir per token).
        Path v1Dir = writeOriginTree(tmp.resolve("origin-v1"), "body-v1");
        Path v2Dir = writeOriginTree(tmp.resolve("origin-v2"), "body-v2");
        AgentSkill v1 = descriptorSkill(v1Dir, "tok-1");
        AgentSkill v2 = descriptorSkill(v2Dir, "tok-2");

        // Act + assert (first stage, same-token reuse, token-swap rebuild, swap back).
        stager.stage(List.of(new RepoBound(v1, repo)), Map.of(repo, SOURCE_NS), SCOPE);
        assertEquals("body-v1", stagedBody(workspace), "the first stage copies the v1 tree");

        stager.stage(List.of(new RepoBound(v1, repo)), Map.of(repo, SOURCE_NS), SCOPE);
        assertEquals("body-v1", stagedBody(workspace), "a same-token pass is a pure reuse");

        stager.stage(List.of(new RepoBound(v2, repo)), Map.of(repo, SOURCE_NS), SCOPE);
        assertEquals(
                "body-v2",
                stagedBody(workspace),
                "a version swap must rebuild — the staged bytes follow the served version,"
                        + " not the first one ever staged under the name");

        stager.stage(List.of(new RepoBound(v1, repo)), Map.of(repo, SOURCE_NS), SCOPE);
        assertEquals("body-v1", stagedBody(workspace), "the rebuild is symmetric (v2 → v1)");
    }

    @Test
    @DisplayName("an unstamped origin tree refreshes in place on every pass")
    void unstampedTreeTracksLiveEdits(@TempDir Path tmp) throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        MarketplaceStager stager = new MarketplaceStager(workspace);
        AgentSkillRepository repo = new StubRepo(SOURCE_NS);

        // Arrange: an unstamped (version = null) origin dir — the flat live tier — whose
        // content is edited in place between passes.
        Path origin = writeOriginTree(tmp.resolve("origin-live"), "body-a");
        AgentSkill live = descriptorSkill(origin, null);

        stager.stage(List.of(new RepoBound(live, repo)), Map.of(repo, SOURCE_NS), SCOPE);
        assertEquals("body-a", stagedBody(workspace));

        Files.writeString(origin.resolve("SKILL.md"), "---\nname: x\ndescription: d\n---\nbody-b");
        stager.stage(List.of(new RepoBound(live, repo)), Map.of(repo, SOURCE_NS), SCOPE);

        assertEquals(
                "body-b",
                stagedBody(workspace),
                "an unstamped source has no token to pin, so edits must propagate per pass");
    }

    /** Writes {@code SKILL.md} with the given body under {@code root/my-skill/}. */
    private static Path writeOriginTree(Path root, String body) throws IOException {
        Path skillDir = Files.createDirectories(root.resolve(SKILL_NAME));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: " + SKILL_NAME + "\ndescription: demo\n---\n" + body + "\n");
        return skillDir;
    }

    /** A descriptor-form skill: empty resources, the given origin dir and version token. */
    private static AgentSkill descriptorSkill(Path originDir, String version) {
        return AgentSkill.builder()
                .name(SKILL_NAME)
                .description("demo")
                .originDir(originDir)
                .version(version)
                .build();
    }

    /** The body text of the staged {@code SKILL.md}, stripped of the frontmatter. */
    private static String stagedBody(Path workspace) throws IOException {
        Path staged = stagedDir(workspace).resolve("SKILL.md");
        assertTrue(Files.isRegularFile(staged), "the staged tree must hold a SKILL.md");
        String text = Files.readString(staged);
        int end = text.indexOf("---", 4);
        return text.substring(end + 3).trim();
    }

    /**
     * Creates {@code <tmp>/library/my-skill/SKILL.md} plus {@code scripts/gen.py} and returns the
     * library root that a {@link FileSystemSkillRepository} is built from.
     */
    private static Path writeSkillLibrary(Path tmp) throws IOException {
        Path skillDir = Files.createDirectories(tmp.resolve("library").resolve(SKILL_NAME));
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: " + SKILL_NAME + "\ndescription: demo\n---\nbody\n");
        Files.writeString(skillDir.resolve("scripts").resolve("gen.py"), "print('hi')\n");
        return tmp.resolve("library");
    }

    private static Path stagedDir(Path workspace) {
        return workspace
                .resolve(MarketplaceStager.CACHE_DIR)
                .resolve(SCOPE)
                .resolve(SOURCE_NS)
                .resolve(SKILL_NAME);
    }

    private static long countRegularFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    /** Minimal repository stub: the stager only reads {@link #getSource()}. */
    private static final class StubRepo implements AgentSkillRepository {

        /** Always {@code null}: no versioned reads on this stub. */
        @Override
        public AgentSkill getSkill(String name, String version) {
            return null;
        }

        private final String source;

        StubRepo(String source) {
            this.source = source;
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
            return List.of();
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
