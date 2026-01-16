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

    // ==================== File System Loading Tests ====================

    @Test
    @DisplayName("Should load single skill from file system")
    void testLoadSingleSkillFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills");

        assertFalse(adapter.isJarEnvironment(), "Should detect file system environment");

        AgentSkill skill = adapter.getSkill("writing-skill");
        assertNotNull(skill);
        assertEquals("writing-skill", skill.getName());
        assertEquals("A skill for writing and content creation", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("Writing Skill"));
    }

    @Test
    @DisplayName("Should load skill with nested resources from file system")
    void testLoadSkillWithResourcesFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills");

        AgentSkill skill = adapter.getSkill("writing-skill");
        assertNotNull(skill);

        // Verify nested resource is loaded
        assertTrue(skill.getResources().containsKey("references/guide.md"));
        String guideContent = skill.getResources().get("references/guide.md");
        assertTrue(guideContent.contains("Writing Guide"));
        assertTrue(guideContent.contains("Best Practices"));
    }

    @Test
    @DisplayName("Should load all skills from file system")
    void testLoadAllSkillsFromFileSystem() throws IOException {
        adapter = new JarSkillRepositoryAdapter("test-skills");

        // Test getAllSkillNames()
        List<String> skillNames = adapter.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(2, skillNames.size());
        assertTrue(skillNames.contains("writing-skill"));
        assertTrue(skillNames.contains("calculation-skill"));

        // Test getAllSkills()
        List<AgentSkill> skills = adapter.getAllSkills();
        assertNotNull(skills);
        assertEquals(2, skills.size());
        List<String> loadedNames = skills.stream().map(AgentSkill::getName).toList();
        assertTrue(loadedNames.contains("writing-skill"));
        assertTrue(loadedNames.contains("calculation-skill"));
    }

    // ==================== JAR Loading Tests ====================

    @Test
    @DisplayName("Should load single skill from JAR")
    void testLoadSingleSkillFromJar() throws Exception {
        Path jarPath = createTestJarInFolder("test-skill", "Test Skill", "Test content");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("jar-skills", classLoader);

            assertTrue(adapter.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = adapter.getSkill("test-skill");
            assertNotNull(skill);
            assertEquals("test-skill", skill.getName());
            assertEquals("Test Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Test content"));
        }
    }

    @Test
    @DisplayName("Should load skill with nested resources from JAR")
    void testLoadSkillWithResourcesFromJar() throws Exception {
        Path jarPath = createTestJarWithResources();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("jar-skills", classLoader);

            assertTrue(adapter.isJarEnvironment());

            AgentSkill skill = adapter.getSkill("jar-skill");
            assertNotNull(skill);
            assertEquals("jar-skill", skill.getName());

            // Verify nested resources are loaded
            assertTrue(skill.getResources().containsKey("config.json"));
            assertEquals("{\"key\": \"value\"}", skill.getResources().get("config.json"));

            assertTrue(skill.getResources().containsKey("data/sample.txt"));
            assertEquals("Sample data", skill.getResources().get("data/sample.txt"));
        }
    }

    @Test
    @DisplayName("Should load all skills from JAR")
    void testLoadAllSkillsFromJar() throws Exception {
        Path jarPath = createTestJarWithMultipleSkills();

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("jar-skills", classLoader);

            assertTrue(adapter.isJarEnvironment(), "Should detect JAR environment");

            // Test getAllSkillNames()
            List<String> skillNames = adapter.getAllSkillNames();
            assertNotNull(skillNames);
            assertEquals(2, skillNames.size());
            assertTrue(skillNames.contains("skill-one"));
            assertTrue(skillNames.contains("skill-two"));

            // Test getAllSkills()
            List<AgentSkill> skills = adapter.getAllSkills();
            assertNotNull(skills);
            assertEquals(2, skills.size());

            // Test getSkill() for individual skills
            AgentSkill skill1 = adapter.getSkill("skill-one");
            assertNotNull(skill1);
            assertEquals("skill-one", skill1.getName());
            assertEquals("First skill", skill1.getDescription());

            AgentSkill skill2 = adapter.getSkill("skill-two");
            assertNotNull(skill2);
            assertEquals("skill-two", skill2.getName());
            assertEquals("Second skill", skill2.getDescription());
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
        adapter = new JarSkillRepositoryAdapter("test-skills");

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.getSkill("non-existent"),
                "Should throw exception when skill directory doesn't exist");
    }

    // ==================== Lifecycle Management Tests ====================

    @Test
    @DisplayName("Should correctly detect environment type")
    void testEnvironmentDetection() throws Exception {
        // File system environment
        try (JarSkillRepositoryAdapter fsAdapter = new JarSkillRepositoryAdapter("test-skills")) {
            assertFalse(fsAdapter.isJarEnvironment());
        }

        // JAR environment
        Path jarPath = createTestJarInFolder("env-test", "Env Test", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            try (JarSkillRepositoryAdapter jarAdapter =
                    new JarSkillRepositoryAdapterWithClassLoader("jar-skills", classLoader)) {
                assertTrue(jarAdapter.isJarEnvironment());
            }
        }
    }

    @Test
    @DisplayName("Should handle close properly for both environments")
    void testCloseHandling() throws Exception {
        // Test file system adapter close
        adapter = new JarSkillRepositoryAdapter("test-skills");
        assertFalse(adapter.isJarEnvironment());
        adapter.close();
        adapter.close(); // Idempotent close

        // Test JAR adapter close
        Path jarPath = createTestJarInFolder("closeable-skill", "Closeable", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            adapter = new JarSkillRepositoryAdapterWithClassLoader("jar-skills", classLoader);
            assertTrue(adapter.isJarEnvironment());

            adapter.getSkill("closeable-skill"); // Load skill to ensure file system is created
            adapter.close();
            adapter.close(); // Idempotent close
        }
    }

    @Test
    @DisplayName("Should throw exception when using closed adapter")
    void testOperationsAfterClose() throws Exception {
        adapter = new JarSkillRepositoryAdapter("test-skills");

        // Close the adapter
        adapter.close();

        // All operations should throw IllegalStateException
        assertThrows(
                IllegalStateException.class,
                () -> adapter.getSkill("writing-skill"),
                "Should throw exception when getting skill after close");

        assertThrows(
                IllegalStateException.class,
                () -> adapter.getAllSkillNames(),
                "Should throw exception when getting all skill names after close");

        assertThrows(
                IllegalStateException.class,
                () -> adapter.getAllSkills(),
                "Should throw exception when getting all skills after close");
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a test JAR file with a single skill in the jar-skills parent folder.
     */
    private Path createTestJarInFolder(String skillName, String description, String content)
            throws IOException {
        Path jarPath = tempDir.resolve(skillName + "-folder.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add parent directory
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // Add skill directory
            jos.putNextEntry(new JarEntry("jar-skills/" + skillName + "/"));
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

            JarEntry entry = new JarEntry("jar-skills/" + skillName + "/SKILL.md");
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
            // Add parent directory
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // Add skill directory
            jos.putNextEntry(new JarEntry("jar-skills/jar-skill/"));
            jos.closeEntry();

            // Add SKILL.md
            String skillMd =
                    "---\n"
                            + "name: jar-skill\n"
                            + "description: Skill with resources\n"
                            + "---\n"
                            + "Main content";

            JarEntry skillEntry = new JarEntry("jar-skills/jar-skill/SKILL.md");
            jos.putNextEntry(skillEntry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add config.json
            JarEntry configEntry = new JarEntry("jar-skills/jar-skill/config.json");
            jos.putNextEntry(configEntry);
            jos.write("{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add nested resource directory and file
            jos.putNextEntry(new JarEntry("jar-skills/jar-skill/data/"));
            jos.closeEntry();

            JarEntry dataEntry = new JarEntry("jar-skills/jar-skill/data/sample.txt");
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
            // Add parent directory
            jos.putNextEntry(new JarEntry("jar-skills/"));
            jos.closeEntry();

            // Add first skill directory
            jos.putNextEntry(new JarEntry("jar-skills/skill-one/"));
            jos.closeEntry();

            // Add first skill
            String skill1Md =
                    "---\n"
                            + "name: skill-one\n"
                            + "description: First skill\n"
                            + "---\n"
                            + "Content one";
            JarEntry entry1 = new JarEntry("jar-skills/skill-one/SKILL.md");
            jos.putNextEntry(entry1);
            jos.write(skill1Md.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            // Add second skill directory
            jos.putNextEntry(new JarEntry("jar-skills/skill-two/"));
            jos.closeEntry();

            // Add second skill
            String skill2Md =
                    "---\n"
                            + "name: skill-two\n"
                            + "description: Second skill\n"
                            + "---\n"
                            + "Content two";
            JarEntry entry2 = new JarEntry("jar-skills/skill-two/SKILL.md");
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
