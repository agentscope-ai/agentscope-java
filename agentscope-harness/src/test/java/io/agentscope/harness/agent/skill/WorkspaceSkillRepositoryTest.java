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
package io.agentscope.harness.agent.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.store.NamespaceFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class WorkspaceSkillRepositoryTest {

    @TempDir Path workspace;

    private LocalFilesystem fs;

    @BeforeEach
    void setUp() {
        fs = new LocalFilesystem(workspace);
    }

    // =========================================================================
    //  Read path
    // =========================================================================

    @Nested
    class ReadPath {

        @Test
        void getAllSkillsLoadsOnlySkillMd() throws IOException {
            writeSkill("alpha", "First skill description here.", "# Alpha\nBody.\n");
            writeSkill("beta", "Second skill description here.", "# Beta\nBody.\n");
            // A resource file that is NOT SKILL.md — must not surface as a skill.
            writeResource("skills/alpha/references/guide.md", "guide content");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            List<AgentSkill> skills = repo.getAllSkills();
            assertEquals(2, skills.size());

            List<String> names = repo.getAllSkillNames();
            assertTrue(names.contains("alpha"));
            assertTrue(names.contains("beta"));
        }

        @Test
        void getSkillFindsByName() throws IOException {
            writeSkill("csv-sum", "Sum CSV columns.", "# CSV Sum\nBody.");
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            AgentSkill loaded = repo.getSkill("csv-sum");
            assertNotNull(loaded);
            assertEquals("csv-sum", loaded.getName());
            assertEquals("Sum CSV columns.", loaded.getDescription());
            assertTrue(loaded.getSkillContent().contains("# CSV Sum"));
        }

        @Test
        void resourcesIsAlwaysEmptyOnAgentSkill() throws IOException {
            writeSkill("alpha", "Alpha description here.", "# Alpha");
            writeResource("skills/alpha/references/guide.md", "guide content");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            AgentSkill loaded = repo.getSkill("alpha");
            // Lazy semantics: AgentSkill.resources stays empty; runtime walks resourcesFor()
            // instead. Verifies we are not accidentally preloading.
            assertTrue(loaded.getResources().isEmpty());
        }

        @Test
        void metadataDirectoriesAreSkipped() throws IOException {
            writeSkill("real-skill", "Real skill.", "# Real");
            // Skill living under _drafts/ should NOT be enumerated.
            writeSkillAt("skills/_drafts/draft-skill", "Draft skill desc.", "# Draft");
            // Skill living under .archive/ should NOT be enumerated.
            writeSkillAt("skills/.archive/old-skill", "Old skill desc.", "# Old");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            List<String> names = repo.getAllSkillNames();
            assertEquals(1, names.size());
            assertEquals("real-skill", names.get(0));
        }

        @Test
        void sourceDefaultsToWorkspace() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertEquals("workspace", repo.getSource());
        }

        @Test
        void sourceOverrideRespected() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(
                            fs, "skills", RuntimeContext::empty, "custom-src", false);
            assertEquals("custom-src", repo.getSource());
        }

        @Test
        void emptyDirectoryReturnsEmptyList() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertTrue(repo.getAllSkills().isEmpty());
            assertTrue(repo.getAllSkillNames().isEmpty());
            assertFalse(repo.skillExists("anything"));
            assertNull(repo.getSkill("anything"));
        }
    }

    // =========================================================================
    //  Write path
    // =========================================================================

    @Nested
    class WritePath {

        @Test
        void writableDefaultsToFalseUnlessOptedIn() {
            WorkspaceSkillRepository ro =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertFalse(ro.isWriteable());
            // Save returns false (no-op) when read-only.
            AgentSkill s =
                    new AgentSkill(
                            "x", "Description long enough for validator.", "# X\nBody", null);
            assertFalse(ro.save(List.of(s), false));
        }

        @Test
        void saveAndLoadRoundTrip() throws IOException {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(
                            fs, "skills", RuntimeContext::empty, "wkspace", true);
            AgentSkill s =
                    new AgentSkill(
                            "csv-sum",
                            "Sum a CSV column and write the total to a file.",
                            "# CSV Sum\n\nUse awk to sum column.\n",
                            null);
            assertTrue(repo.save(List.of(s), false));

            Path skillMd = workspace.resolve("skills/csv-sum/SKILL.md");
            assertTrue(Files.isRegularFile(skillMd));
            String text = Files.readString(skillMd);
            assertTrue(text.startsWith("---"));
            assertTrue(text.contains("name: csv-sum"));
            assertTrue(text.contains("# CSV Sum"));

            AgentSkill loaded = repo.getSkill("csv-sum");
            assertNotNull(loaded);
            assertEquals("csv-sum", loaded.getName());
            assertEquals("wkspace", loaded.getSource());
        }

        @Test
        void saveRefusesDuplicateUnlessForce() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(
                            fs, "skills", RuntimeContext::empty, "wkspace", true);
            AgentSkill v1 =
                    new AgentSkill("dup", "First version description here.", "# v1\nBody", null);
            AgentSkill v2 =
                    new AgentSkill("dup", "Second version description here.", "# v2\nBody", null);

            assertTrue(repo.save(List.of(v1), false));
            assertFalse(repo.save(List.of(v2), false));
            assertTrue(repo.save(List.of(v2), true));

            AgentSkill loaded = repo.getSkill("dup");
            assertTrue(loaded.getSkillContent().contains("# v2"));
        }

        @Test
        void deleteArchivesUnderArchivePrefix() throws IOException {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(
                            fs, "skills", RuntimeContext::empty, "wkspace", true);
            AgentSkill s =
                    new AgentSkill(
                            "to-archive", "Will be archived shortly.", "# To Archive\nBody", null);
            assertTrue(repo.save(List.of(s), false));
            assertTrue(repo.skillExists("to-archive"));

            assertTrue(repo.delete("to-archive"));
            assertFalse(repo.skillExists("to-archive"));

            // Original dir gone; archive subtree present with the moved skill.
            assertFalse(Files.exists(workspace.resolve("skills/to-archive")));
            Path archive = workspace.resolve("skills/.archive");
            assertTrue(Files.isDirectory(archive));
            try (var stream = Files.list(archive)) {
                assertTrue(stream.findAny().isPresent());
            }
        }

        @Test
        void writeAndReadAndDeleteSkillFile() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(
                            fs, "skills", RuntimeContext::empty, "wkspace", true);
            AgentSkill s =
                    new AgentSkill(
                            "with-files", "Has sub-files for testing.", "# With Files\nBody", null);
            assertTrue(repo.save(List.of(s), false));

            assertTrue(repo.writeSkillFile("with-files", "scripts/run.py", "print('hi')\n"));
            String content = repo.readSkillFile("with-files", "scripts/run.py");
            assertEquals("print('hi')\n", content);

            assertTrue(repo.deleteSkillFile("with-files", "scripts/run.py"));
            assertNull(repo.readSkillFile("with-files", "scripts/run.py"));
        }

        @Test
        void subFileOpsRejectedWhenNotWritable() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertFalse(repo.writeSkillFile("any", "scripts/x.py", "body"));
            assertFalse(repo.deleteSkillFile("any", "scripts/x.py"));
        }

        @Test
        void setWriteableToggles() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertFalse(repo.isWriteable());
            repo.setWriteable(true);
            assertTrue(repo.isWriteable());
            repo.setWriteable(false);
            assertFalse(repo.isWriteable());
        }
    }

    // =========================================================================
    //  Lazy resources
    // =========================================================================

    @Nested
    class LazyResources {

        @Test
        void readReturnsFileContents() throws IOException {
            writeSkill("alpha", "Alpha skill desc.", "# Alpha");
            writeResource("skills/alpha/references/guide.md", "## Guide\nDetails here.");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            SkillResources res = repo.resourcesFor("alpha", RuntimeContext.empty());
            Optional<String> g = res.read("references/guide.md");
            assertTrue(g.isPresent());
            assertEquals("## Guide\nDetails here.", g.get());
        }

        @Test
        void readMissingReturnsEmpty() throws IOException {
            writeSkill("alpha", "Alpha skill desc.", "# Alpha");
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);

            SkillResources res = repo.resourcesFor("alpha", RuntimeContext.empty());
            assertTrue(res.read("does-not-exist.txt").isEmpty());
        }

        @Test
        void readRejectsPathTraversal() throws IOException {
            writeSkill("alpha", "Alpha skill desc.", "# Alpha");
            // Create a sibling file that traversal might otherwise reach.
            writeResource("skills/secret.txt", "secret");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            SkillResources res = repo.resourcesFor("alpha", RuntimeContext.empty());

            assertTrue(res.read("../secret.txt").isEmpty());
            assertTrue(res.read("/etc/passwd").isEmpty());
        }

        @Test
        void listEnumeratesResourcesExcludingSkillMd() throws IOException {
            writeSkill("alpha", "Alpha skill desc.", "# Alpha");
            writeResource("skills/alpha/references/guide.md", "guide");
            writeResource("skills/alpha/scripts/run.py", "print");

            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            SkillResources res = repo.resourcesFor("alpha", RuntimeContext.empty());
            List<String> all = res.list();

            assertTrue(all.contains("references/guide.md"));
            assertTrue(all.contains("scripts/run.py"));
            assertFalse(all.contains("SKILL.md"));
        }

        @Test
        void emptyOrNullSkillNameReturnsEmptyAccessor() {
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(fs, "skills", RuntimeContext::empty);
            assertTrue(repo.resourcesFor(null, RuntimeContext.empty()).list().isEmpty());
            assertTrue(repo.resourcesFor("", RuntimeContext.empty()).list().isEmpty());
        }
    }

    // =========================================================================
    //  Namespace switching
    // =========================================================================

    @Nested
    class NamespaceSwitching {

        @Test
        void differentRuntimeContextSeesDifferentSkills() throws IOException {
            NamespaceFactory nsf =
                    rc -> {
                        String tenant = rc != null ? (String) rc.get("tenant") : null;
                        return tenant != null ? List.of("tenants", tenant) : List.of();
                    };
            LocalFilesystem nsFs = new LocalFilesystem(workspace, false, 64, nsf);

            // tenant=alice: skills/alice-skill/
            Files.createDirectories(workspace.resolve("tenants/alice/skills/alice-skill"));
            Files.writeString(
                    workspace.resolve("tenants/alice/skills/alice-skill/SKILL.md"),
                    skillMd("alice-skill", "Alice's skill.", "# Alice"));

            // tenant=bob: skills/bob-skill/
            Files.createDirectories(workspace.resolve("tenants/bob/skills/bob-skill"));
            Files.writeString(
                    workspace.resolve("tenants/bob/skills/bob-skill/SKILL.md"),
                    skillMd("bob-skill", "Bob's skill.", "# Bob"));

            AtomicReference<RuntimeContext> ctxRef = new AtomicReference<>(RuntimeContext.empty());
            WorkspaceSkillRepository repo =
                    new WorkspaceSkillRepository(nsFs, "skills", ctxRef::get);

            ctxRef.set(RuntimeContext.builder().put("tenant", "alice").build());
            List<String> alice = repo.getAllSkillNames();
            assertEquals(1, alice.size());
            assertEquals("alice-skill", alice.get(0));

            ctxRef.set(RuntimeContext.builder().put("tenant", "bob").build());
            List<String> bob = repo.getAllSkillNames();
            assertEquals(1, bob.size());
            assertEquals("bob-skill", bob.get(0));
        }
    }

    // =========================================================================
    //  Static helper: hasMetadataAncestor
    // =========================================================================

    @Nested
    class HasMetadataAncestor {

        @Test
        void detectsUnderscoreAndDotChildren() {
            assertTrue(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            "/foo/skills/_drafts/x/SKILL.md", "skills"));
            assertTrue(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            "/foo/skills/.archive/x/SKILL.md", "skills"));
            assertFalse(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            "/foo/skills/real/SKILL.md", "skills"));
        }

        @Test
        void worksWithoutLeadingSlash() {
            assertTrue(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            "skills/_drafts/x/SKILL.md", "skills"));
            assertFalse(
                    WorkspaceSkillRepository.hasMetadataAncestor("skills/real/SKILL.md", "skills"));
        }

        @Test
        void specialCasesBaseDotAndEmpty() {
            // With base="." the marker "/./" never matches; fall back to first-segment check.
            assertTrue(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            "/.skills-cache/git/foo/SKILL.md", "."));
            assertTrue(
                    WorkspaceSkillRepository.hasMetadataAncestor(
                            ".skills-cache/git/foo/SKILL.md", ""));
            assertFalse(WorkspaceSkillRepository.hasMetadataAncestor("skills/real/SKILL.md", "."));
        }
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    private void writeSkill(String name, String description, String body) throws IOException {
        writeSkillAt("skills/" + name, description, body);
    }

    private void writeSkillAt(String relativeDir, String description, String body)
            throws IOException {
        Path dir = workspace.resolve(relativeDir);
        Files.createDirectories(dir);
        String name = dir.getFileName().toString();
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name, description, body));
    }

    private static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
    }

    private void writeResource(String relativePath, String content) throws IOException {
        Path target = workspace.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
