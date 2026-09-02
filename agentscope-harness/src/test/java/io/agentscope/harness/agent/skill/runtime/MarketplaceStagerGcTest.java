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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused tests for {@link MarketplaceStager#stage(List, Map)} orphan GC behaviour with flat and
 * multi-level source namespaces.
 *
 * <p>GC identifies skill directories by the presence of a {@code SKILL.md} file, so all test
 * skills include it in their resources.
 */
class MarketplaceStagerGcTest {

    @TempDir Path tempWorkspace;

    private static final String SKILL_MD = "---\nname: test\n---";

    // ==================== Flat namespace ====================

    @Test
    @DisplayName("Flat namespace: skill survives staging")
    void flatNamespace_skillSurvives() {
        AgentSkill skill =
                new AgentSkill(
                        "my-skill",
                        "desc",
                        "c",
                        Map.of("f.txt", "hello", "SKILL.md", SKILL_MD),
                        "src");
        StubRepo repo = new StubRepo(List.of(skill), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(List.of(new MarketplaceStager.RepoBound(skill, repo)), Map.of(repo, "src"));

        assertTrue(Files.exists(tempWorkspace.resolve(".skills-cache/src/my-skill/f.txt")));
    }

    @Test
    @DisplayName("Flat namespace: orphan skill is deleted on re-stage")
    void flatNamespace_orphanDeleted() {
        AgentSkill skillA =
                new AgentSkill(
                        "skill-a", "desc", "c", Map.of("a.txt", "a", "SKILL.md", SKILL_MD), "src");
        StubRepo repo = new StubRepo(List.of(skillA), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        // First stage: materialise skill-a
        stager.stage(List.of(new MarketplaceStager.RepoBound(skillA, repo)), Map.of(repo, "src"));

        Path stagedA = tempWorkspace.resolve(".skills-cache/src/skill-a/a.txt");
        assertTrue(Files.exists(stagedA), "skill-a should be staged initially");

        // Second stage: repo no longer publishes skill-a → orphan GC deletes it
        stager.stage(List.of(), Map.of(repo, "src"));

        assertFalse(Files.exists(stagedA), "orphan skill-a should be deleted by GC");
        assertTrue(
                Files.notExists(tempWorkspace.resolve(".skills-cache/src")),
                "empty namespace dir should also be cleaned up");
    }

    @Test
    @DisplayName("Flat namespace: retained skill is not deleted when others change")
    void flatNamespace_retainedSurvivesWhenOthersChange() {
        AgentSkill skillA =
                new AgentSkill(
                        "skill-a", "desc", "c", Map.of("a.txt", "a", "SKILL.md", SKILL_MD), "src");
        AgentSkill skillB =
                new AgentSkill(
                        "skill-b", "desc", "c", Map.of("b.txt", "b", "SKILL.md", SKILL_MD), "src");
        StubRepo repo = new StubRepo(List.of(skillA, skillB), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(
                List.of(
                        new MarketplaceStager.RepoBound(skillA, repo),
                        new MarketplaceStager.RepoBound(skillB, repo)),
                Map.of(repo, "src"));

        Path stagedA = tempWorkspace.resolve(".skills-cache/src/skill-a/a.txt");
        Path stagedB = tempWorkspace.resolve(".skills-cache/src/skill-b/b.txt");
        assertTrue(Files.exists(stagedA));
        assertTrue(Files.exists(stagedB));

        // Re-stage with only skill-a
        stager.stage(List.of(new MarketplaceStager.RepoBound(skillA, repo)), Map.of(repo, "src"));

        assertTrue(Files.exists(stagedA), "retained skill-a should survive");
        assertFalse(Files.exists(stagedB), "orphan skill-b should be deleted");
    }

    // ==================== Multi-level namespace ====================

    @Test
    @DisplayName("Multi-level namespace: skill survives staging (regression guard)")
    void multiLevelNamespace_skillSurvives() {
        AgentSkill skill =
                new AgentSkill(
                        "my-skill",
                        "desc",
                        "c",
                        Map.of("f.txt", "hello", "SKILL.md", SKILL_MD),
                        "git-owner/repo");
        StubRepo repo = new StubRepo(List.of(skill), "git-owner/repo");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(
                List.of(new MarketplaceStager.RepoBound(skill, repo)),
                Map.of(repo, "git-owner/repo"));

        // The intermediate directories (git-owner/, git-owner/repo/) must not
        // be deleted by GC — the skill file at the leaf must survive.
        assertTrue(
                Files.exists(tempWorkspace.resolve(".skills-cache/git-owner/repo/my-skill/f.txt")),
                "skill under multi-level namespace should survive GC");
    }

    @Test
    @DisplayName("Multi-level namespace: orphan skill is deleted on re-stage")
    void multiLevelNamespace_orphanDeleted() {
        AgentSkill skill =
                new AgentSkill(
                        "old-skill",
                        "desc",
                        "c",
                        Map.of("o.txt", "o", "SKILL.md", SKILL_MD),
                        "git-owner/repo");
        StubRepo repo = new StubRepo(List.of(skill), "git-owner/repo");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(
                List.of(new MarketplaceStager.RepoBound(skill, repo)),
                Map.of(repo, "git-owner/repo"));

        Path staged = tempWorkspace.resolve(".skills-cache/git-owner/repo/old-skill/o.txt");
        assertTrue(Files.exists(staged));

        // Re-stage with empty visible list → old-skill is an orphan
        stager.stage(List.of(), Map.of(repo, "git-owner/repo"));

        assertFalse(Files.exists(staged), "orphan skill under multi-level ns should be deleted");
    }

    @Test
    @DisplayName("Multi-level namespace: retained skill survives while orphan is deleted")
    void multiLevelNamespace_retainedSurvivesOrphanDeleted() {
        AgentSkill keep =
                new AgentSkill(
                        "keep",
                        "desc",
                        "c",
                        Map.of("k.txt", "k", "SKILL.md", SKILL_MD),
                        "git-owner/repo");
        AgentSkill drop =
                new AgentSkill(
                        "drop",
                        "desc",
                        "c",
                        Map.of("d.txt", "d", "SKILL.md", SKILL_MD),
                        "git-owner/repo");
        StubRepo repo = new StubRepo(List.of(keep, drop), "git-owner/repo");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(
                List.of(
                        new MarketplaceStager.RepoBound(keep, repo),
                        new MarketplaceStager.RepoBound(drop, repo)),
                Map.of(repo, "git-owner/repo"));

        Path kept = tempWorkspace.resolve(".skills-cache/git-owner/repo/keep/k.txt");
        Path dropped = tempWorkspace.resolve(".skills-cache/git-owner/repo/drop/d.txt");
        assertTrue(Files.exists(kept));
        assertTrue(Files.exists(dropped));

        // Re-stage with only 'keep'
        stager.stage(
                List.of(new MarketplaceStager.RepoBound(keep, repo)),
                Map.of(repo, "git-owner/repo"));

        assertTrue(Files.exists(kept), "retained skill should survive");
        assertFalse(Files.exists(dropped), "orphan skill should be deleted");
        // The intermediate namespace dirs must still exist
        assertTrue(
                Files.isDirectory(tempWorkspace.resolve(".skills-cache/git-owner/repo")),
                "intermediate namespace dir must not be deleted");
        assertTrue(
                Files.isDirectory(tempWorkspace.resolve(".skills-cache/git-owner")),
                "top-level namespace dir must not be deleted");
    }

    // ==================== Mixed namespaces ====================

    @Test
    @DisplayName("Mixed flat and multi-level namespaces: all retained skills survive")
    void mixedNamespaces_allRetainedSurvive() {
        AgentSkill flat =
                new AgentSkill(
                        "flat", "desc", "c", Map.of("f.txt", "f", "SKILL.md", SKILL_MD), "src");
        AgentSkill deep =
                new AgentSkill(
                        "deep",
                        "desc",
                        "c",
                        Map.of("d.txt", "d", "SKILL.md", SKILL_MD),
                        "git-owner/repo");
        StubRepo flatRepo = new StubRepo(List.of(flat), "src");
        StubRepo deepRepo = new StubRepo(List.of(deep), "git-owner/repo");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(
                List.of(
                        new MarketplaceStager.RepoBound(flat, flatRepo),
                        new MarketplaceStager.RepoBound(deep, deepRepo)),
                Map.of(flatRepo, "src", deepRepo, "git-owner/repo"));

        assertTrue(Files.exists(tempWorkspace.resolve(".skills-cache/src/flat/f.txt")));
        assertTrue(Files.exists(tempWorkspace.resolve(".skills-cache/git-owner/repo/deep/d.txt")));
    }

    // ==================== Legacy directory without SKILL.md ====================

    @Test
    @DisplayName("Legacy dir without SKILL.md: not recognized as skill, persists as residual")
    void legacyDirWithoutSkillMd_notRecognizedAsSkill() throws IOException {
        AgentSkill skillA =
                new AgentSkill(
                        "skill-a", "desc", "c", Map.of("a.txt", "a", "SKILL.md", SKILL_MD), "src");
        StubRepo repo = new StubRepo(List.of(skillA), "src");
        MarketplaceStager stager = new MarketplaceStager(tempWorkspace);

        stager.stage(List.of(new MarketplaceStager.RepoBound(skillA, repo)), Map.of(repo, "src"));

        Path stagedA = tempWorkspace.resolve(".skills-cache/src/skill-a/a.txt");
        assertTrue(Files.exists(stagedA));

        // Manually create a legacy directory with no SKILL.md
        Path legacyDir = tempWorkspace.resolve(".skills-cache/src/legacy-skill");
        Files.createDirectories(legacyDir.resolve("residual.txt"));

        // Re-stage with only skill-a → GC runs, legacy dir has no SKILL.md so it's
        // not found by Files.walk and is not in retained set either → it survives
        stager.stage(List.of(new MarketplaceStager.RepoBound(skillA, repo)), Map.of(repo, "src"));

        assertTrue(Files.exists(stagedA), "skill-a should survive");
        assertTrue(
                Files.exists(legacyDir.resolve("residual.txt")),
                "legacy dir without SKILL.md is a residual (known limitation)");
    }

    /** Minimal repository stub for testing. */
    private static final class StubRepo implements AgentSkillRepository {

        private final List<AgentSkill> skills;
        private final String source;

        StubRepo(List<AgentSkill> skills, String source) {
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
