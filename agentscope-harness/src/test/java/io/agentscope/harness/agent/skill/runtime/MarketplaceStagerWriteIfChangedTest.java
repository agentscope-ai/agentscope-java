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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MarketplaceStager} used to hash the on-disk copy and the incoming bytes separately
 * on every restage. Size mismatch must be enough to decide the file changed, and identical
 * bytes must not rewrite the file (mtime stays put).
 */
class MarketplaceStagerWriteIfChangedTest {

    @Test
    @DisplayName("Missing file is treated as changed")
    void missingFileIsChanged(@TempDir Path dir) throws Exception {
        assertFalse(MarketplaceStager.contentUnchanged(dir.resolve("missing.bin"), new byte[] {1}));
    }

    @Test
    @DisplayName("Different length skips a full-content compare")
    void sizeMismatchIsChanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.bin");
        Files.write(file, new byte[] {1, 2, 3});
        assertFalse(MarketplaceStager.contentUnchanged(file, new byte[] {1, 2}));
    }

    @Test
    @DisplayName("Identical payload is unchanged")
    void identicalBytesUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.bin");
        byte[] payload = "hello-skill".getBytes(StandardCharsets.UTF_8);
        Files.write(file, payload);
        assertTrue(MarketplaceStager.contentUnchanged(file, payload));
    }

    @Test
    @DisplayName("Same length but different bytes is changed")
    void sameSizeDifferentBytesIsChanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.bin");
        Files.write(file, new byte[] {1, 2, 3});
        assertFalse(MarketplaceStager.contentUnchanged(file, new byte[] {1, 2, 9}));
    }

    @Test
    @DisplayName("Restaging the same resource does not rewrite the file")
    void restageOfIdenticalResourceDoesNotRewrite(@TempDir Path workspace) throws Exception {
        AgentSkill skill =
                new AgentSkill(
                        "demo",
                        "A demo skill.",
                        "# demo",
                        Map.of("SKILL.md", "# demo\nunchanged body\n"),
                        "marketplace");
        StubRepo repo = new StubRepo(List.of(skill), "market");
        MarketplaceStager stager = new MarketplaceStager(workspace);
        List<RepoBound> visible = List.of(new RepoBound(skill, repo));
        Map<AgentSkillRepository, String> ns = Map.of(repo, "market");

        stager.stage(visible, ns);
        Path staged = workspace.resolve(".skills-cache/market/demo/SKILL.md");
        assertTrue(Files.exists(staged));
        Files.setLastModifiedTime(staged, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        FileTime before = Files.getLastModifiedTime(staged);

        stager.stage(visible, ns);
        assertEquals(before, Files.getLastModifiedTime(staged));
        assertEquals("# demo\nunchanged body\n", Files.readString(staged));
    }

    @Test
    @DisplayName("Restaging a different payload updates the file")
    void restageOfChangedResourceRewrites(@TempDir Path workspace) throws Exception {
        AgentSkill first =
                new AgentSkill(
                        "demo", "A demo skill.", "# demo", Map.of("SKILL.md", "v1"), "marketplace");
        StubRepo repo = new StubRepo(List.of(first), "market");
        MarketplaceStager stager = new MarketplaceStager(workspace);
        Map<AgentSkillRepository, String> ns = Map.of(repo, "market");

        stager.stage(List.of(new RepoBound(first, repo)), ns);
        Path staged = workspace.resolve(".skills-cache/market/demo/SKILL.md");
        assertEquals("v1", Files.readString(staged));

        AgentSkill second =
                new AgentSkill(
                        "demo",
                        "A demo skill.",
                        "# demo",
                        Map.of("SKILL.md", "v2-longer"),
                        "marketplace");
        stager.stage(List.of(new RepoBound(second, repo)), ns);
        assertEquals("v2-longer", Files.readString(staged));
    }

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
