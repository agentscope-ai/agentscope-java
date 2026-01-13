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
package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for JarSkillRepositoryAdapter.
 *
 * <p>Tests the adapter's ability to load skills from both:
 * <ul>
 *   <li>File system (development environment)</li>
 *   <li>JAR files (production environment)</li>
 * </ul>
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("JarSkillRepositoryAdapter Unit Tests")
class JarSkillRepositoryAdapterTest {

    @TempDir Path tempDir;

    private JarSkillRepositoryAdapter adapter;

    @AfterEach
    void tearDown() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
    }

    // ==================== File System Tests ====================

    @Test
    @DisplayName("Should load skill from file system (development environment)")
    void testGetSkillFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");

        assertNotNull(adapter);
        assertFalse(adapter.isJarEnvironment(), "Should detect file system environment");

        AgentSkill skill = adapter.getSkill("writing-skill");
        assertNotNull(skill);
        assertEquals("writing-skill", skill.getName());
        assertEquals("A skill for writing and content creation", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("Writing Skill"));
    }

    @Test
    @DisplayName("Should load skill with nested resources from file system")
    void testGetSkillWithNestedResourcesFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");

        AgentSkill skill = adapter.getSkill("writing-skill");
        assertNotNull(skill);

        // Verify nested resource is loaded
        assertTrue(skill.getResources().containsKey("references/guide.md"));
        String guideContent = skill.getResources().get("references/guide.md");
        assertTrue(guideContent.contains("Writing Guide"));
        assertTrue(guideContent.contains("Best Practices"));
    }

    @Test
    @DisplayName("Should get all skill names from file system")
    void testGetAllSkillNamesFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");

        List<String> skillNames = adapter.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(2, skillNames.size());
        assertTrue(skillNames.contains("writing-skill"));
        assertTrue(skillNames.contains("calculation-skill"));
    }

    @Test
    @DisplayName("Should get all skills from file system")
    void testGetAllSkillsFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");

        List<AgentSkill> skills = adapter.getAllSkills();
        assertNotNull(skills);
        assertEquals(2, skills.size());

        // Verify both skills are loaded
        List<String> skillNames = skills.stream().map(AgentSkill::getName).toList();
        assertTrue(skillNames.contains("writing-skill"));
        assertTrue(skillNames.contains("calculation-skill"));
    }

    // ==================== JAR Environment Tests ====================

    @Test
    @DisplayName("Should load skill from JAR file")
    void testGetSkillFromJar() throws Exception {
        // Create a test JAR with a skill
        Path jarPath = createTestJar("test-skill", "Test Skill", "Test content");

        // Load from JAR using custom ClassLoader
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("test-skill", classLoader);

            assertTrue(adapter.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = adapter.getSkill("test-skill");
            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("Test Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Test content"));
        }
    }

    @Test
    @DisplayName("Should load skill with resources from JAR")
    void testGetSkillWithResourcesFromJar() throws Exception {
        // Create a JAR with skill and resources
        Path jarPath = createTestJarWithResources();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("jar-skill", classLoader);

            assertTrue(adapter.isJarEnvironment());

            AgentSkill skill = adapter.getSkill("jar-skill");
            assertNotNull(skill);
            assertEquals("jar-skill", skill.getName());

            // Verify resources are loaded
            assertTrue(skill.getResources().containsKey("config.json"));
            assertEquals("{\"key\": \"value\"}", skill.getResources().get("config.json"));

            assertTrue(skill.getResources().containsKey("data/sample.txt"));
            assertEquals("Sample data", skill.getResources().get("data/sample.txt"));
        }
    }

    @Test
    @DisplayName("Should handle multiple skills in same JAR")
    void testMultipleSkillsInSameJar() throws Exception {
        Path jarPath = createTestJarWithMultipleSkills();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            // Load first skill
            try (JarSkillRepositoryAdapter adapter1 =
                    new JarSkillRepositoryAdapterWithClassLoader("skill-one", classLoader)) {
                AgentSkill skill1 = adapter1.getSkill("skill-one");
                assertEquals("skill-one", skill1.getName());
            }

            // Load second skill
            try (JarSkillRepositoryAdapter adapter2 =
                    new JarSkillRepositoryAdapterWithClassLoader("skill-two", classLoader)) {
                AgentSkill skill2 = adapter2.getSkill("skill-two");
                assertEquals("skill-two", skill2.getName());
            }
        }
    }

    @Test
    @DisplayName("Should get all skill names from JAR")
    void testGetAllSkillNamesFromJar() throws Exception {
        Path jarPath = createTestJarWithMultipleSkills();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            // Create adapter pointing to skill-one, then get all skills from parent directory
            try (JarSkillRepositoryAdapter skillsAdapter =
                    new JarSkillRepositoryAdapterWithClassLoader("skill-one", classLoader)) {
                List<String> skillNames = skillsAdapter.getAllSkillNames();
                assertNotNull(skillNames);
                assertEquals(2, skillNames.size());
                assertTrue(skillNames.contains("skill-one"));
                assertTrue(skillNames.contains("skill-two"));
            }
        }
    }

    @Test
    @DisplayName("Should get all skills from JAR")
    void testGetAllSkillsFromJar() throws Exception {
        Path jarPath = createTestJarWithMultipleSkills();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            try (JarSkillRepositoryAdapter skillsAdapter =
                    new JarSkillRepositoryAdapterWithClassLoader("skill-one", classLoader)) {
                List<AgentSkill> skills = skillsAdapter.getAllSkills();
                assertNotNull(skills);
                assertEquals(2, skills.size());

                // Verify both skills are loaded
                List<String> skillNames = skills.stream().map(AgentSkill::getName).toList();
                assertTrue(skillNames.contains("skill-one"));
                assertTrue(skillNames.contains("skill-two"));
            }
        }
    }

    // ==================== Error Handling Tests ====================

    @Test
    @DisplayName("Should throw IOException when resource not found")
    void testResourceNotFound() {
        assertThrows(
                IOException.class,
                () -> new JarSkillRepositoryAdapter("non-existent-skill"),
                "Should throw IOException for non-existent resource");
    }

    @Test
    @DisplayName("Should throw exception when skill directory not found")
    void testSkillDirectoryNotFound() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.getSkill("non-existent"),
                "Should throw exception when skill directory doesn't exist");
    }

    // ==================== AutoCloseable Tests ====================

    @Test
    @DisplayName("Should close file system when closed (JAR environment)")
    void testAutoCloseableClosesFileSystem() throws Exception {
        Path jarPath = createTestJar("closeable-skill", "Closeable", "Content");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("closeable-skill", classLoader);
            assertTrue(adapter.isJarEnvironment());

            // Load skill to ensure file system is created
            adapter.getSkill("closeable-skill");

            // Close should not throw exception
            adapter.close();
        }
    }

    @Test
    @DisplayName("Should handle close idempotently")
    void testCloseIdempotent() throws Exception {
        Path jarPath = createTestJar("idempotent-skill", "Idempotent", "Content");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("idempotent-skill", classLoader);

            // Multiple closes should not throw exception
            adapter.close();
            adapter.close();
            adapter.close();
        }
    }

    @Test
    @DisplayName("Should not throw when closing file system adapter")
    void testCloseFileSystemAdapter() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills/writing-skill");
        assertFalse(adapter.isJarEnvironment());

        // Close should not throw exception even for file system
        adapter.close();
    }

    // ==================== Environment Detection Tests ====================

    @Test
    @DisplayName("Should correctly detect JAR environment")
    void testIsJarEnvironmentDetection() throws Exception {
        // File system environment
        try (JarSkillRepositoryAdapter fsAdapter =
                new JarSkillRepositoryAdapter("test-skills/writing-skill")) {
            assertFalse(fsAdapter.isJarEnvironment());
        }

        // JAR environment
        Path jarPath = createTestJar("env-test", "Env Test", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            try (JarSkillRepositoryAdapter jarAdapter =
                    new JarSkillRepositoryAdapterWithClassLoader("env-test", classLoader)) {
                assertTrue(jarAdapter.isJarEnvironment());
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a test JAR file with a single skill.
     */
    private Path createTestJar(String skillName, String description, String content)
            throws IOException {
        Path jarPath = tempDir.resolve(skillName + ".jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add directory entry
            JarEntry dirEntry = new JarEntry(skillName + "/");
            jos.putNextEntry(dirEntry);
            jos.closeEntry();

            // Add SKILL.md
            String skillMd =
                    "---\n"
                            + "name: "
                            + skillName
                            + "\n"
                            + "description: "
                            + description
                            + "\n"
                            + "---\n"
                            + content;

            JarEntry entry = new JarEntry(skillName + "/SKILL.md");
            jos.putNextEntry(entry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * Creates a test JAR file with a skill and multiple resources.
     */
    private Path createTestJarWithResources() throws IOException {
        Path jarPath = tempDir.resolve("skill-with-resources.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add directory entries
            jos.putNextEntry(new JarEntry("jar-skill/"));
            jos.closeEntry();

            // Add SKILL.md
            String skillMd =
                    "---\n"
                            + "name: jar-skill\n"
                            + "description: Skill with resources\n"
                            + "---\n"
                            + "Main content";

            JarEntry skillEntry = new JarEntry("jar-skill/SKILL.md");
            jos.putNextEntry(skillEntry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add config.json
            JarEntry configEntry = new JarEntry("jar-skill/config.json");
            jos.putNextEntry(configEntry);
            jos.write("{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add nested resource directory and file
            jos.putNextEntry(new JarEntry("jar-skill/data/"));
            jos.closeEntry();

            JarEntry dataEntry = new JarEntry("jar-skill/data/sample.txt");
            jos.putNextEntry(dataEntry);
            jos.write("Sample data".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * Creates a test JAR file with multiple skills.
     */
    private Path createTestJarWithMultipleSkills() throws IOException {
        Path jarPath = tempDir.resolve("multiple-skills.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add first skill directory
            jos.putNextEntry(new JarEntry("skill-one/"));
            jos.closeEntry();

            // Add first skill
            String skill1Md =
                    "---\n"
                            + "name: skill-one\n"
                            + "description: First skill\n"
                            + "---\n"
                            + "Content one";
            JarEntry entry1 = new JarEntry("skill-one/SKILL.md");
            jos.putNextEntry(entry1);
            jos.write(skill1Md.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add second skill directory
            jos.putNextEntry(new JarEntry("skill-two/"));
            jos.closeEntry();

            // Add second skill
            String skill2Md =
                    "---\n"
                            + "name: skill-two\n"
                            + "description: Second skill\n"
                            + "---\n"
                            + "Content two";
            JarEntry entry2 = new JarEntry("skill-two/SKILL.md");
            jos.putNextEntry(entry2);
            jos.write(skill2Md.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * Custom adapter that uses a specific ClassLoader for testing JAR loading.
     */
    private static class JarSkillRepositoryAdapterWithClassLoader
            extends JarSkillRepositoryAdapter {

        public JarSkillRepositoryAdapterWithClassLoader(
                String resourcePath, ClassLoader classLoader) throws IOException {
            super(resourcePath, classLoader);
        }
    }
}
