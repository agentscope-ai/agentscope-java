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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ClasspathSkillRepository.
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
@DisplayName("ClasspathSkillRepository Unit Tests")
class ClasspathSkillRepositoryTest {

    @TempDir Path tempDir;

    private ClasspathSkillRepository repository;

    @AfterEach
    void tearDown() throws IOException {
        if (repository != null) {
            repository.close();
        }
    }

    // ==================== File System Loading Tests ====================

    @Test
    @DisplayName("Should load single skill from file system")
    void testLoadSingleSkillFromFileSystem() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        assertFalse(repository.isJarEnvironment(), "Should detect file system environment");

        AgentSkill skill = repository.getSkill("writing-skill");
        assertNotNull(skill);
        assertEquals("writing-skill", skill.getName());
        assertEquals("A skill for writing and content creation", skill.getDescription());
        assertTrue(skill.getSkillContent().contains("Writing Skill"));
    }

    @Test
    @DisplayName("Should load skill with nested resources from file system")
    void testLoadSkillWithResourcesFromFileSystem() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        AgentSkill skill = repository.getSkill("writing-skill");
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
        repository = new ClasspathSkillRepository("test-skills");

        // Test getAllSkillNames()
        List<String> skillNames = repository.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(2, skillNames.size());
        assertTrue(skillNames.contains("writing-skill"));
        assertTrue(skillNames.contains("calculation-skill"));

        // Test getAllSkills()
        List<AgentSkill> skills = repository.getAllSkills();
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
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("test-skill");
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
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment());

            AgentSkill skill = repository.getSkill("jar-skill");
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
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            // Test getAllSkillNames()
            List<String> skillNames = repository.getAllSkillNames();
            assertNotNull(skillNames);
            assertEquals(2, skillNames.size());
            assertTrue(skillNames.contains("skill-one"));
            assertTrue(skillNames.contains("skill-two"));

            // Test getAllSkills()
            List<AgentSkill> skills = repository.getAllSkills();
            assertNotNull(skills);
            assertEquals(2, skills.size());

            // Test getSkill() for individual skills
            AgentSkill skill1 = repository.getSkill("skill-one");
            assertNotNull(skill1);
            assertEquals("skill-one", skill1.getName());
            assertEquals("First skill", skill1.getDescription());

            AgentSkill skill2 = repository.getSkill("skill-two");
            assertNotNull(skill2);
            assertEquals("skill-two", skill2.getName());
            assertEquals("Second skill", skill2.getDescription());
        }
    }

    @Test
    @DisplayName("Should load skills from Spring Boot Fat JAR (BOOT-INF/classes/)")
    void testLoadFromSpringBootJar() throws Exception {
        Path jarPath = createSpringBootTestJar("sb-skill", "SB Skill", "SB content");

        // Create a custom ClassLoader that simulates Spring Boot's LaunchedURLClassLoader behavior
        // In Spring Boot, classLoader.getResource("jar-skills") will automatically resolve to
        // "BOOT-INF/classes/jar-skills" and return a valid URL
        try (URLClassLoader baseClassLoader =
                new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            ClassLoader springBootClassLoader =
                    new ClassLoader(baseClassLoader) {
                        @Override
                        public URL getResource(String name) {
                            // Simulate Spring Boot behavior: automatically prepend
                            // BOOT-INF/classes/
                            URL resource = super.getResource("BOOT-INF/classes/" + name);
                            if (resource != null) {
                                return resource;
                            }
                            // Fallback to original behavior
                            return super.getResource(name);
                        }
                    };

            repository =
                    new ClasspathSkillRepositoryWithClassLoader(
                            "jar-skills", springBootClassLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("sb-skill");
            assertNotNull(skill);
            assertEquals("sb-skill", skill.getName());
            assertEquals("SB Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("SB content"));
        }
    }

    @Test
    @DisplayName("Should load skills from Spring Boot nested lib JAR (BOOT-INF/lib/)")
    void testLoadFromSpringBootNestedLibJar() throws Exception {
        // Simulates the URL pattern used by Spring Boot 3.2+ for multi-module projects:
        // jar:nested:/opt/app/nested-springboot.jar/!BOOT-INF/lib/nested-skill.jar!/jar-skills
        Path outerJarPath =
                createSpringBootNestedLibTestJar(
                        "nested-lib-skill", "Nested Lib Skill", "Nested lib content");

        // Extract the inner JAR from the outer to a temp file,
        // simulating Spring Boot's runtime resolution of nested JARs
        Path innerJarPath = extractInnerJar(outerJarPath, "BOOT-INF/lib/nested-skill.jar");

        // Configure the test FileSystemProvider so that ZipFileSystemProvider can
        // resolve the nested: URI to the extracted inner JAR path.
        // In production, Spring Boot's NestedFileSystemProvider handles this.
        TestNestedFileSystemProvider.configuredInnerJarPath = innerJarPath;
        try {
            // Build a ClassLoader that returns a jar:nested: format URL.
            ClassLoader nestedClassLoader =
                    new ClassLoader(ClassLoader.getSystemClassLoader()) {
                        @Override
                        public URL getResource(String name) {
                            if ("jar-skills".equals(name)) {
                                try {
                                    String nestedUrlStr =
                                            "jar:nested:"
                                                    + outerJarPath.toUri().getRawPath()
                                                    + "/!BOOT-INF/lib/nested-skill.jar!/"
                                                    + name;
                                    return new URL(
                                            null,
                                            nestedUrlStr,
                                            new URLStreamHandler() {
                                                @Override
                                                protected URLConnection openConnection(URL u)
                                                        throws IOException {
                                                    throw new UnsupportedOperationException(
                                                            "nested URL for test only");
                                                }
                                            });
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            return super.getResource(name);
                        }
                    };

            repository =
                    new ClasspathSkillRepositoryWithClassLoader("jar-skills", nestedClassLoader);

            assertTrue(repository.isJarEnvironment(), "Should detect JAR environment");

            AgentSkill skill = repository.getSkill("nested-lib-skill");
            assertNotNull(skill);
            assertEquals("nested-lib-skill", skill.getName());
            assertEquals("Nested Lib Skill", skill.getDescription());
            assertTrue(skill.getSkillContent().contains("Nested lib content"));
        } finally {
            TestNestedFileSystemProvider.configuredInnerJarPath = null;
        }
    }

    // ==================== getSource Tests ====================

    @Test
    @DisplayName("Should return default source with format: classpath-path")
    void testGetSource_DefaultSource() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");
        assertEquals("classpath-test-skills", repository.getSource());
    }

    @Test
    @DisplayName("Should return custom source when provided")
    void testGetSource_CustomSource() throws IOException {
        repository = new ClasspathSkillRepository("test-skills", "my-custom-source");
        assertEquals("my-custom-source", repository.getSource());
    }

    @Test
    @DisplayName("Should handle trailing slash in path")
    void testGetSource_TrailingSlash() throws IOException {
        repository = new ClasspathSkillRepository("test-skills/");
        assertEquals("classpath-test-skills", repository.getSource());
    }

    // ==================== Error Handling Tests ====================

    @Test
    @DisplayName("Should throw IOException when resource not found")
    void testResourceNotFound() {
        assertThrows(
                IOException.class,
                () -> new ClasspathSkillRepository("non-existent-skill"),
                "Should throw IOException for non-existent resource");
    }

    @Test
    @DisplayName("Should throw exception when skill directory not found")
    void testSkillDirectoryNotFound() throws IOException {
        repository = new ClasspathSkillRepository("test-skills");

        assertThrows(
                IllegalArgumentException.class,
                () -> repository.getSkill("non-existent"),
                "Should throw exception when skill directory doesn't exist");
    }

    // ==================== Lifecycle Management Tests ====================

    @Test
    @DisplayName("Should correctly detect environment type")
    void testEnvironmentDetection() throws Exception {
        // File system environment
        try (ClasspathSkillRepository fsRepository = new ClasspathSkillRepository("test-skills")) {
            assertFalse(fsRepository.isJarEnvironment());
        }

        // JAR environment
        Path jarPath = createTestJarInFolder("env-test", "Env Test", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            try (ClasspathSkillRepository jarRepository =
                    new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader)) {
                assertTrue(jarRepository.isJarEnvironment());
            }
        }
    }

    @Test
    @DisplayName("Should handle close properly for both environments")
    void testCloseHandling() throws Exception {
        // Test file system adapter close
        repository = new ClasspathSkillRepository("test-skills");
        assertFalse(repository.isJarEnvironment());
        repository.close();
        repository.close(); // Idempotent close

        // Test JAR adapter close
        Path jarPath = createTestJarInFolder("closeable-skill", "Closeable", "Content");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jarPath.toUri().toURL()})) {
            repository = new ClasspathSkillRepositoryWithClassLoader("jar-skills", classLoader);
            assertTrue(repository.isJarEnvironment());

            repository.getSkill("closeable-skill"); // Load skill to ensure file system is created
            repository.close();
            repository.close(); // Idempotent close
        }
    }

    @Test
    @DisplayName("Should throw exception when using closed adapter")
    void testOperationsAfterClose() throws Exception {
        repository = new ClasspathSkillRepository("test-skills");

        // Close the adapter
        repository.close();

        // All operations should throw IllegalStateException
        assertThrows(
                IllegalStateException.class,
                () -> repository.getSkill("writing-skill"),
                "Should throw exception when getting skill after close");

        assertThrows(
                IllegalStateException.class,
                () -> repository.getAllSkillNames(),
                "Should throw exception when getting all skill names after close");

        assertThrows(
                IllegalStateException.class,
                () -> repository.getAllSkills(),
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
     * Creates a test JAR file with Spring Boot structure (BOOT-INF/classes/).
     */
    private Path createSpringBootTestJar(String skillName, String description, String content)
            throws IOException {
        Path jarPath = tempDir.resolve(skillName + "-springboot.jar");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // Add Spring Boot structure
            jos.putNextEntry(new JarEntry("BOOT-INF/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/jar-skills/"));
            jos.closeEntry();

            // Add skill directory
            jos.putNextEntry(new JarEntry("BOOT-INF/classes/jar-skills/" + skillName + "/"));
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

            JarEntry entry = new JarEntry("BOOT-INF/classes/jar-skills/" + skillName + "/SKILL.md");
            jos.putNextEntry(entry);
            jos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    /**
     * Creates a test fat JAR simulating a Spring Boot multi-module project. The
     * outer JAR contains
     * BOOT-INF/lib/nested-skill.jar, which itself contains
     * jar-skills/{skillName}/SKILL.md.
     *
     * <p>
     * This simulates the URL pattern:
     * jar:nested:/opt/app/xxx-app.jar/!BOOT-INF/lib/nested-skill.jar!/jar-skills
     */
    private Path createSpringBootNestedLibTestJar(
            String skillName, String description, String content) throws IOException {
        // First, create the inner JAR (the library module jar)
        byte[] innerJarBytes = createInnerSkillJar(skillName, description, content);

        // Then, create the outer fat JAR containing the inner JAR at BOOT-INF/lib/
        Path outerJarPath = tempDir.resolve(skillName + "-nested-springboot.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outerJarPath))) {
            // Add Spring Boot directory structure
            jos.putNextEntry(new JarEntry("BOOT-INF/"));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("BOOT-INF/lib/"));
            jos.closeEntry();

            // Embed the inner JAR as a nested entry (STORED, not compressed)
            JarEntry nestedJarEntry = new JarEntry("BOOT-INF/lib/nested-skill.jar");
            nestedJarEntry.setMethod(JarEntry.STORED);
            nestedJarEntry.setSize(innerJarBytes.length);
            nestedJarEntry.setCompressedSize(innerJarBytes.length);
            CRC32 crc = new CRC32();
            crc.update(innerJarBytes);
            nestedJarEntry.setCrc(crc.getValue());
            jos.putNextEntry(nestedJarEntry);
            jos.write(innerJarBytes);
            jos.closeEntry();
        }

        return outerJarPath;
    }

    /**
     * Creates an inner JAR byte array containing skills at
     * jar-skills/{skillName}/SKILL.md.
     */
    private byte[] createInnerSkillJar(String skillName, String description, String content)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos)) {
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
        return baos.toByteArray();
    }

    /**
     * Extracts an inner JAR from an outer JAR to a temp file. This simulates how
     * Spring Boot
     * resolves nested JARs at runtime.
     */
    private Path extractInnerJar(Path outerJarPath, String innerEntryName) throws IOException {
        Path innerJarPath = tempDir.resolve("extracted-inner.jar");
        try (JarFile outerJar = new JarFile(outerJarPath.toFile())) {
            JarEntry innerEntry = outerJar.getJarEntry(innerEntryName);
            assertNotNull(innerEntry, "Inner JAR entry should exist: " + innerEntryName);
            try (InputStream is = outerJar.getInputStream(innerEntry)) {
                Files.copy(is, innerJarPath);
            }
        }
        return innerJarPath;
    }

    /**
     * Custom adapter that uses a specific ClassLoader for testing JAR loading.
     */
    private static class ClasspathSkillRepositoryWithClassLoader extends ClasspathSkillRepository {

        public ClasspathSkillRepositoryWithClassLoader(String resourcePath, ClassLoader classLoader)
                throws IOException {
            super(resourcePath, classLoader);
        }
    }

    /**
     * Test-only {@link FileSystemProvider} for the {@code nested:} scheme.
     *
     * <p>
     * In Spring Boot 3.2+, the {@code nested:} scheme is handled by Spring Boot's
     * {@code NestedFileSystemProvider} from {@code spring-boot-loader}. In our test
     * environment (without Spring Boot), this provider simulates the same behavior.
     *
     * <p>
     * When {@link ClasspathSkillRepository} processes a {@code jar:nested:} URI,
     * the
     * JDK's {@code ZipFileSystemProvider} internally calls
     * {@code Path.of(new URI("nested:..."))} to locate the JAR file. This provider
     * intercepts that call and returns the path to the extracted inner JAR.
     *
     * <p>
     * Registered via SPI in
     * {@code META-INF/services/java.nio.file.spi.FileSystemProvider}.
     */
    public static class TestNestedFileSystemProvider extends FileSystemProvider {

        /**
         * Path to the extracted inner JAR. Must be set before creating a
         * {@link ClasspathSkillRepository} with a {@code jar:nested:} URL.
         */
        static volatile Path configuredInnerJarPath;

        @Override
        public String getScheme() {
            return "nested";
        }

        /**
         * Returns the configured inner JAR path for the given {@code nested:} URI.
         *
         * <p>
         * Called by {@code ZipFileSystemProvider.uriToPath()} when it encounters
         * a {@code nested:} URI like
         * {@code nested:/path/outer.jar/!BOOT-INF/lib/inner.jar}.
         */
        @Override
        public Path getPath(URI uri) {
            if (configuredInnerJarPath == null) {
                throw new IllegalStateException(
                        "TestNestedFileSystemProvider.configuredInnerJarPath not set");
            }
            return configuredInnerJarPath;
        }

        // ---- All methods below are not used; required by abstract contract ----

        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileSystem getFileSystem(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SeekableByteChannel newByteChannel(
                Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(
                Path dir, DirectoryStream.Filter<? super Path> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameFile(Path path, Path path2) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHidden(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileStore getFileStore(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkAccess(Path path, AccessMode... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(
                Path path, Class<V> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <A extends BasicFileAttributes> A readAttributes(
                Path path, Class<A> type, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> readAttributes(
                Path path, String attributes, LinkOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw new UnsupportedOperationException();
        }
    }
}
