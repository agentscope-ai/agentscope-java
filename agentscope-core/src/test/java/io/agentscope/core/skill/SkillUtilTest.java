/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.util.SkillUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillUtilTest {

    @Nested
    @DisplayName("Skill name validation")
    class SkillNameValidation {

        @Test
        @DisplayName("Should validate names against the standard naming rule")
        void testValidatesNamesAgainstTheStandardRule() {
            // Accept: lowercase letters, digits, dashes; digit start; max length.
            assertTrue(SkillUtil.isValidSkillName("a"));
            assertTrue(SkillUtil.isValidSkillName("data-analysis"));
            assertTrue(SkillUtil.isValidSkillName("0start"));
            assertTrue(SkillUtil.isValidSkillName("a".repeat(SkillUtil.MAX_SKILL_NAME_LENGTH)));

            // Reject: everything else, including underscore/dot (skill-id separator and path
            // shapes), non-ASCII, uppercase, and overlong names.
            for (String invalid :
                    new String[] {
                        null,
                        "",
                        "Uppercase",
                        "技能",
                        "with_underscore",
                        "dot.name",
                        "with space",
                        "colon:name",
                        "/tmp/evil",
                        "..",
                        "-leading-dash",
                        "a".repeat(SkillUtil.MAX_SKILL_NAME_LENGTH + 1)
                    }) {
                assertFalse(SkillUtil.isValidSkillName(invalid), "name: " + invalid);
            }
        }

        @Test
        @DisplayName("Should throw with a descriptive message for invalid names")
        void testRequireValidSkillNameThrowsWithMessage() {
            assertEquals("^[a-z0-9][a-z0-9-]*$", SkillUtil.VALID_SKILL_NAME_PATTERN.pattern());
            assertEquals(64, SkillUtil.MAX_SKILL_NAME_LENGTH);

            IllegalArgumentException e =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SkillUtil.requireValidSkillName("Bad Name"));
            assertTrue(e.getMessage().contains("Bad Name"));
            assertTrue(e.getMessage().contains("^[a-z0-9][a-z0-9-]*$"));
            assertDoesNotThrow(() -> SkillUtil.requireValidSkillName("good-name"));
        }
    }

    @Nested
    @DisplayName("Markdown")
    class MarkdownTests {

        @Test
        @DisplayName("Should create from markdown with default source")
        void testCreateFromMarkdownWithDefaultSource() {
            String skillMd =
                    "---\n"
                            + "name: test-skill\n"
                            + "description: A test skill for validation\n"
                            + "---\n"
                            + "# Skill Content\n"
                            + "This is the skill implementation.";

            Map<String, String> resources = Map.of("config.json", "{\"key\": \"value\"}");

            AgentSkill skill = SkillUtil.createFrom(skillMd, resources);

            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("A test skill for validation", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Skill Content"));
            assertEquals("custom", skill.getSource());
            assertEquals(1, skill.getResources().size());
        }

        @Test
        @DisplayName("Should create from markdown with custom source")
        void testCreateFromMarkdownWithCustomSource() {
            String skillMd =
                    "---\n"
                            + "name: github-skill\n"
                            + "description: From GitHub repository\n"
                            + "---\n"
                            + "Content here";

            AgentSkill skill = SkillUtil.createFrom(skillMd, null, "github");

            assertEquals("github-skill", skill.getName());
            assertEquals("github", skill.getSource());
            assertEquals("github-skill_github", skill.getSkillId());
        }

        @Test
        @DisplayName("Should create from markdown with null resources")
        void testCreateFromMarkdownWithNullResources() {
            String skillMd = "---\nname: skill\ndescription: desc\n---\nContent";

            AgentSkill skill = SkillUtil.createFrom(skillMd, null);

            assertNotNull(skill);
            assertTrue(skill.getResources().isEmpty());
        }

        @Test
        @DisplayName("Should create from markdown with null source")
        void testCreateFromMarkdownWithNullSource() {
            String skillMd = "---\nname: skill\ndescription: desc\n---\nContent";

            AgentSkill skill = SkillUtil.createFrom(skillMd, null, null);

            assertEquals("custom", skill.getSource());
        }

        @Test
        @DisplayName("Should create from throws exception for missing name")
        void testCreateFromThrowsExceptionForMissingName() {
            String skillMd = "---\ndescription: desc\n---\nContent";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SkillUtil.createFrom(skillMd, null));

            assertTrue(exception.getMessage().contains("name"));
            assertTrue(exception.getMessage().contains("description"));
        }

        @Test
        @DisplayName("Should create from throws exception for missing description")
        void testCreateFromThrowsExceptionForMissingDescription() {
            String skillMd = "---\nname: test\n---\nContent";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SkillUtil.createFrom(skillMd, null));

            assertTrue(exception.getMessage().contains("description"));
        }

        @Test
        @DisplayName("Should create from throws exception for empty name")
        void testCreateFromThrowsExceptionForEmptyName() {
            String skillMd = "---\nname: \ndescription: desc\n---\nContent";

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
        }

        @Test
        @DisplayName("Should create from throws exception for empty description")
        void testCreateFromThrowsExceptionForEmptyDescription() {
            String skillMd = "---\nname: test\ndescription: \n---\nContent";

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
        }

        @Test
        @DisplayName("Should create from throws exception for missing content")
        void testCreateFromThrowsExceptionForMissingContent() {
            String skillMd = "---\nname: test\ndescription: desc\n---";

            IllegalArgumentException exception =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> SkillUtil.createFrom(skillMd, null));

            assertTrue(exception.getMessage().contains("content"));
        }

        @Test
        @DisplayName("Should create from throws exception for no frontmatter")
        void testCreateFromThrowsExceptionForNoFrontmatter() {
            String skillMd = "Just content without frontmatter";

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
        }

        @Test
        @DisplayName("Should create from edge cases")
        void testCreateFromEdgeCases() {
            // Multiline content
            String multilineSkillMd =
                    "---\n"
                            + "name: multiline\n"
                            + "description: Multi-line skill\n"
                            + "---\n"
                            + "Line 1\n"
                            + "Line 2\n"
                            + "Line 3";
            AgentSkill skill1 = SkillUtil.createFrom(multilineSkillMd, null);
            assertTrue(skill1.getSkillContent().contains("Line 2"));

            // Special characters in description (use quotes for values with colons)
            String specialCharsSkillMd =
                    "---\n"
                            + "name: skill-v1-0-test\n"
                            + "description: 'Special chars @#$%'\n"
                            + "---\n"
                            + "Content";
            AgentSkill skill2 = SkillUtil.createFrom(specialCharsSkillMd, null);
            assertEquals("skill-v1-0-test", skill2.getName());

            // Names outside the standard rule are rejected at creation
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SkillUtil.createFrom(
                                    "---\nname: 测试技能\ndescription: 这是一个测试\n---\n技能内容",
                                    null,
                                    "中文源"));

            // Additional metadata fields (should be ignored)
            String extraFieldsSkillMd =
                    "---\n"
                            + "name: skill\n"
                            + "description: desc\n"
                            + "version: 1.0.0\n"
                            + "author: John\n"
                            + "---\n"
                            + "Content";
            AgentSkill skill4 = SkillUtil.createFrom(extraFieldsSkillMd, null);
            assertEquals("skill", skill4.getName());
            assertEquals("1.0.0", String.valueOf(skill4.getMetadataValue("version")));
            assertEquals("John", skill4.getMetadataValue("author"));
        }

        @Test
        @DisplayName("Should reject numeric values for required metadata")
        void testCreateFromRejectsNumericRequiredMetadata() {
            String skillMd = "---\nname: 123\ndescription: 456\n---\nContent";

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFrom(skillMd, null));
        }
    }

    @Nested
    @DisplayName("Zip")
    class ZipTests {

        @TempDir Path tempDir;

        @Test
        @DisplayName("Should create from zip content")
        void testCreateFromZip() throws IOException {
            String skillMd =
                    "---\n"
                            + "name: zip-skill\n"
                            + "description: From zip package\n"
                            + "---\n"
                            + "Zip skill content";

            byte[] zipBytes =
                    buildZipBytes(
                            Map.of(
                                    "zip-skill/SKILL.md",
                                    skillMd,
                                    "zip-skill/docs/info.txt",
                                    "info content"));

            AgentSkill skill = SkillUtil.createFromZip(zipBytes, "zip");

            assertEquals("zip-skill", skill.getName());
            assertEquals("From zip package", skill.getDescription());
            assertEquals("zip", skill.getSource());
            assertEquals("info content", skill.getResources().get("docs/info.txt"));
        }

        @Test
        @DisplayName("Should create from zip content with custom charset")
        void testCreateFromZipWithCustomCharset() throws IOException {
            Charset charset = Charset.forName("GBK");
            String skillMd =
                    "---\n"
                            + "name: gbk-skill\n"
                            + "description: GBK zip package\n"
                            + "---\n"
                            + "技能内容";

            byte[] zipBytes =
                    buildZipBytes(
                            Map.of("gbk-skill/SKILL.md", skillMd, "gbk-skill/docs/说明.txt", "资源内容"),
                            charset);

            AgentSkill skill = SkillUtil.createFromZip(zipBytes, "zip", charset);

            assertEquals("gbk-skill", skill.getName());
            assertEquals("GBK zip package", skill.getDescription());
            assertEquals("技能内容", skill.getSkillContent());
            assertEquals("资源内容", skill.getResources().get("docs/说明.txt"));
        }

        @Test
        @DisplayName("Should create from zip with root folder")
        void testCreateFromZipWithRootFolder() throws IOException {
            String skillMd =
                    "---\n"
                            + "name: folder-skill\n"
                            + "description: Root folder package\n"
                            + "---\n"
                            + "Folder content";

            byte[] zipBytes =
                    buildZipBytes(
                            Map.of("folder/" + "SKILL.md", skillMd, "folder/readme.txt", "readme"));

            AgentSkill skill = SkillUtil.createFromZip(zipBytes);

            assertEquals("folder-skill", skill.getName());
            assertEquals("readme", skill.getResources().get("readme.txt"));
        }

        @Test
        @DisplayName("Should create from zip path")
        void testCreateFromZipPath() throws IOException {
            String skillMd =
                    "---\n"
                            + "name: path-skill\n"
                            + "description: From path\n"
                            + "---\n"
                            + "Path content";

            byte[] zipBytes =
                    buildZipBytes(
                            Map.of(
                                    "path-skill/SKILL.md",
                                    skillMd,
                                    "path-skill/readme.txt",
                                    "readme"));
            Path zipPath = tempDir.resolve("skill.zip");
            Files.write(zipPath, zipBytes);

            AgentSkill skill = SkillUtil.createFromZip(zipPath, "zip");

            assertEquals("path-skill", skill.getName());
            assertEquals("From path", skill.getDescription());
            assertEquals("readme", skill.getResources().get("readme.txt"));
        }

        @Test
        @DisplayName("Should create from zip input stream")
        void testCreateFromZipInputStream() throws IOException {
            String skillMd =
                    "---\n"
                            + "name: stream-skill\n"
                            + "description: From stream\n"
                            + "---\n"
                            + "Stream content";

            byte[] zipBytes = buildZipBytes(Map.of("stream-skill/SKILL.md", skillMd));

            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(zipBytes)) {
                AgentSkill skill = SkillUtil.createFromZip(inputStream);

                assertEquals("stream-skill", skill.getName());
                assertEquals("From stream", skill.getDescription());
            }
        }

        @Test
        @DisplayName("Should throw when zip missing SKILL.md")
        void testCreateFromZipMissingSkillFile() throws IOException {
            byte[] zipBytes = buildZipBytes(Map.of("skill/notes.txt", "missing skill file"));

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFromZip(zipBytes));
        }

        @Test
        @DisplayName("Should throw when zip has no root directory")
        void testCreateFromZipNoRootDirectory() throws IOException {
            String skillMd = "---\nname: rootless\ndescription: desc\n---\nContent";
            byte[] zipBytes = buildZipBytes(Map.of("SKILL.md", skillMd));

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFromZip(zipBytes));
        }

        @Test
        @DisplayName("Should throw when zip has multiple root directories")
        void testCreateFromZipMultipleRootDirectories() throws IOException {
            String skillMd = "---\nname: multi\ndescription: desc\n---\nContent";
            byte[] zipBytes =
                    buildZipBytes(
                            Map.of("root-a/SKILL.md", skillMd, "root-b/readme.txt", "readme"));

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFromZip(zipBytes));
        }

        @Test
        @DisplayName("Should throw when SKILL.md not under root directly")
        void testCreateFromZipSkillInNestedDirectory() throws IOException {
            String skillMd = "---\nname: nested\ndescription: desc\n---\nContent";
            byte[] zipBytes =
                    buildZipBytes(
                            Map.of("root/nested/SKILL.md", skillMd, "root/readme.txt", "readme"));

            assertThrows(IllegalArgumentException.class, () -> SkillUtil.createFromZip(zipBytes));
        }
    }

    private static byte[] buildZipBytes(Map<String, String> entries) throws IOException {
        return buildZipBytes(entries, StandardCharsets.UTF_8);
    }

    private static byte[] buildZipBytes(Map<String, String> entries, Charset charset)
            throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, charset)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipOutputStream.putNextEntry(zipEntry);
                byte[] content = entry.getValue().getBytes(charset);
                zipOutputStream.write(content);
                zipOutputStream.closeEntry();
            }
        }
        return outputStream.toByteArray();
    }
}
