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
package io.agentscope.core.skill.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the Path-B additions: lazy mode, mtime+size cache, originDir stamping. */
@Tag("unit")
@DisplayName("FileSystemSkillRepository: lazy + cache + originDir")
class FileSystemSkillRepositoryLazyTest {

    @TempDir Path tempDir;
    private Path baseDir;

    @BeforeEach
    void setUp() throws IOException {
        baseDir = tempDir.resolve("skills");
        Files.createDirectories(baseDir);
        writeSkill("alpha", "first skill body", "scripts/run.sh", "echo hello\n");
        writeSkill("beta", "second skill body", "references/note.md", "see also: ...\n");
    }

    private void writeSkill(String name, String body, String resourceRel, String resourceContent)
            throws IOException {
        Path skillDir = baseDir.resolve(name);
        Files.createDirectories(skillDir);
        String md = "---\nname: " + name + "\ndescription: " + name + " skill\n---\n" + body + "\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md, StandardCharsets.UTF_8);
        Path resource = skillDir.resolve(resourceRel);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, resourceContent, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("eager mode preloads resources AND stamps originDir")
    void eagerLoadsResourcesAndOriginDir() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
        List<AgentSkill> skills = repo.getAllSkills();
        assertEquals(2, skills.size());

        AgentSkill alpha =
                skills.stream().filter(s -> "alpha".equals(s.getName())).findFirst().orElseThrow();
        assertTrue(
                alpha.getResources().containsKey("scripts/run.sh"),
                "eager mode should preload resources");
        assertEquals("echo hello\n", alpha.getResources().get("scripts/run.sh"));
        assertTrue(alpha.getOriginDir().isPresent(), "originDir must be stamped");
        assertEquals(
                baseDir.resolve("alpha").toAbsolutePath().normalize(), alpha.getOriginDir().get());
    }

    @Test
    @DisplayName("lazy mode skips resources but still stamps originDir")
    void lazySkipsResourcesAndKeepsOriginDir() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir, true, null, true);
        List<AgentSkill> skills = repo.getAllSkills();
        assertEquals(2, skills.size());

        for (AgentSkill skill : skills) {
            assertTrue(
                    skill.getResources().isEmpty(),
                    "lazy mode must NOT preload resources into memory");
            assertTrue(
                    skill.getOriginDir().isPresent(),
                    "lazy mode must still stamp originDir so disk fallback works");
        }
    }

    @Test
    @DisplayName("getAllSkills reuses cached AgentSkill when SKILL.md mtime+size unchanged")
    void cacheReusesWhenUnchanged() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
        AgentSkill alphaFirst = pick(repo.getAllSkills(), "alpha");
        AgentSkill alphaSecond = pick(repo.getAllSkills(), "alpha");
        assertSame(
                alphaFirst,
                alphaSecond,
                "identical SKILL.md mtime+size should return the same AgentSkill instance");
    }

    @Test
    @DisplayName("cache invalidates when SKILL.md mtime changes")
    void cacheInvalidatesOnMtimeBump() throws IOException {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
        AgentSkill alphaFirst = pick(repo.getAllSkills(), "alpha");

        // Bump mtime by 5 seconds to guarantee the filesystem reports a change even on
        // coarse-grained mtime stores; also tweak content so size changes too.
        Path skillFile = baseDir.resolve("alpha/SKILL.md");
        Files.writeString(
                skillFile,
                Files.readString(skillFile, StandardCharsets.UTF_8) + "\nappended\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(skillFile, FileTime.from(Instant.now().plusSeconds(5)));

        AgentSkill alphaSecond = pick(repo.getAllSkills(), "alpha");
        assertFalse(
                alphaFirst == alphaSecond,
                "mtime change should invalidate cache and produce a fresh AgentSkill");
    }

    @Test
    @DisplayName("save() invalidates the cache")
    void saveClearsCache() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
        AgentSkill alphaFirst = pick(repo.getAllSkills(), "alpha");

        AgentSkill updated =
                alphaFirst.toBuilder().skillContent("updated body for invalidation test").build();
        assertTrue(repo.save(List.of(updated), true), "save should report success");

        AgentSkill alphaSecond = pick(repo.getAllSkills(), "alpha");
        assertNotNull(alphaSecond);
        assertEquals("updated body for invalidation test", alphaSecond.getSkillContent());
    }

    private static AgentSkill pick(List<AgentSkill> skills, String name) {
        return skills.stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing skill: " + name));
    }
}
