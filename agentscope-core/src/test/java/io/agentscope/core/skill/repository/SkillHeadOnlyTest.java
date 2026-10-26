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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the head-only skill loading contract:
 *
 * <ul>
 *   <li>lazy registration keeps metadata only: body/resources are not resident, a body
 *       digest is carried for change detection
 *   <li>the load_skill_through_path channel reads through originDir from disk with the
 *       same semantics as the eager era
 *   <li>resources above the inline limit raise ResourceTooLargeException instead of
 *       entering the context
 * </ul>
 */
class SkillHeadOnlyTest {

    @TempDir Path baseDir;

    private Path writeSkill(String name, String body, String resourceRel, int resourceSizeBytes)
            throws IOException {
        Path dir = baseDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + name + " desc.\n---\n" + body);
        if (resourceRel != null) {
            Path res = dir.resolve(resourceRel);
            Files.createDirectories(res.getParent());
            byte[] data = new byte[resourceSizeBytes];
            for (int i = 0; i < data.length; i++) {
                // Standalone bytes in 0x80..0xFF are invalid UTF-8, forcing the read
                // channel down its binary (base64:) branch.
                data[i] = (byte) (0x80 + (i % 0x80));
            }
            Files.write(res, data);
        }
        return dir;
    }

    @Test
    @DisplayName("lazy repo registers metadata only; body/resources read via originDir")
    void lazyRepositoryLoadsHeadOnly() throws IOException {
        writeSkill("big", "# Body", "references/tpl.bin", 1024);
        try (FileSystemSkillRepository repo =
                new FileSystemSkillRepository(baseDir, true, null, true)) {
            List<AgentSkill> skills = repo.getAllSkills();
            assertEquals(1, skills.size());
            AgentSkill s = skills.get(0);
            assertEquals("big", s.getName());
            assertNull(s.getSkillContent(), "head-only: body must not be resident");
            assertTrue(s.getResources().isEmpty(), "head-only: resources must not be resident");
            assertNotNull(s.getContentDigest(), "change detection needs the body digest");
            assertNotNull(s.getOriginDir(), "disk read-through needs originDir");

            // Read-through channel: full body and resource
            assertTrue(
                    SkillFileSystemHelper.readFullMarkdownFromDir(s.getOriginDir().get())
                            .contains("# Body"));
            Optional<String> res =
                    SkillFileSystemHelper.readResourceFromDir(
                            s.getOriginDir().get(), "references/tpl.bin");
            assertTrue(res.isPresent());
            assertTrue(res.get().startsWith("base64:"));
        }
    }

    @Test
    @DisplayName("resources above the inline limit raise ResourceTooLargeException")
    void oversizeResourceRejected() throws IOException {
        Path dir = writeSkill("huge", "# Body", "references/huge.bin", 600 * 1024);
        SkillFileSystemHelper.ResourceTooLargeException ex =
                assertThrows(
                        SkillFileSystemHelper.ResourceTooLargeException.class,
                        () ->
                                SkillFileSystemHelper.readResourceFromDir(
                                        dir, "references/huge.bin"));
        assertEquals("references/huge.bin", ex.relPath);
        assertTrue(ex.sizeBytes > SkillFileSystemHelper.MAX_INLINE_RESOURCE_BYTES);
    }

    @Test
    @DisplayName("path traversal and missing files read as empty")
    void sanitizeAndMissing() throws IOException {
        Path dir = writeSkill("safe", "# Body", null, 0);
        assertEquals(
                Optional.empty(), SkillFileSystemHelper.readResourceFromDir(dir, "../escape.txt"));
        assertEquals(Optional.empty(), SkillFileSystemHelper.readResourceFromDir(dir, "nope.txt"));
    }
}
