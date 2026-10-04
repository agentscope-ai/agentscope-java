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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerHostAuthoritativeReadsTest {
    @TempDir Path temp;
    private Path host;
    private LocalFilesystem runtime;
    private WorkspaceManager manager;
    private static final RuntimeContext CTX = RuntimeContext.empty();

    @BeforeEach
    void setup() throws Exception {
        host = temp.resolve("host");
        Files.createDirectories(host);
        Files.createDirectories(temp.resolve("sandbox"));
        runtime = new LocalFilesystem(temp.resolve("sandbox"), true, 10);
        manager = new WorkspaceManager(host, runtime);
    }

    @AfterEach
    void close() throws Exception {
        manager.close();
    }

    private void secure(String... roots) {
        manager.setDefinitionAuthority(
                new WorkspaceDefinitionAuthority(host, List.of(roots), runtime));
    }

    private void sandbox(String path, String text) {
        manager.writeUtf8WorkspaceRelative(CTX, path, text);
    }

    private void host(String path, String text) throws Exception {
        Path p = host.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void projectedReadsRejectSandboxOverridesIncludingMissingAndEmptyHostFiles() throws Exception {
        host("AGENTS.md", "HOST");
        sandbox("AGENTS.md", "INJECTED");
        sandbox("knowledge/KNOWLEDGE.md", "INJECTED_KNOWLEDGE");
        secure("./AGENTS.md", "knowledge");
        assertEquals("HOST", manager.readAgentsMd(CTX));
        assertEquals("HOST", manager.readManagedWorkspaceFileUtf8(CTX, "./AGENTS.md"));
        assertEquals("", manager.readKnowledgeMd(CTX));
        host("AGENTS.md", "");
        assertEquals("", manager.readAgentsMd(CTX));
        Files.delete(host.resolve("AGENTS.md"));
        assertEquals("", manager.readAgentsMd(CTX));
    }

    @Test
    void defaultMemoryAndOrdinaryFilesRetainRuntimeReadsAndWrites() throws Exception {
        host("MEMORY.md", "HOST_MEMORY");
        sandbox("MEMORY.md", "RUNTIME_MEMORY");
        host("output.txt", "HOST_DATA");
        sandbox("output.txt", "RUNTIME_DATA");
        secure("AGENTS.md", "skills", "knowledge");
        manager.appendUtf8WorkspaceRelative(CTX, "MEMORY.md", "_APPENDED");
        assertEquals("RUNTIME_MEMORY_APPENDED", manager.readMemoryMd(CTX));
        assertEquals("HOST_MEMORY", Files.readString(host.resolve("MEMORY.md")));
        assertEquals("RUNTIME_DATA", manager.readManagedWorkspaceFileUtf8(CTX, "output.txt"));
        secure("MEMORY.md");
        assertEquals("HOST_MEMORY", manager.readMemoryMd(CTX));
    }

    @Test
    void ownershipUsesNormalizedPathBoundaries() {
        secure("./skills/", "AGENTS.md");
        var authority = manager.getDefinitionAuthority();
        assertTrue(authority.owns("skills"));
        assertTrue(authority.owns("/skills/good/../good/SKILL.md"));
        assertTrue(authority.owns("skills/../AGENTS.md"));
        assertFalse(authority.owns("skills-other/SKILL.md"));
        assertFalse(authority.owns("../skills/SKILL.md"));
    }

    @Test
    void knowledgeEnumerationCannotDiscoverSandboxOnlyDefinitions() throws Exception {
        host("knowledge/host.md", "HOST");
        sandbox("knowledge/evil.md", "INJECTED");
        secure("knowledge");
        assertEquals(List.of(host.resolve("knowledge/host.md")), manager.listKnowledgeFiles(CTX));
    }

    @Test
    void skillCatalogAndLazyResourcesReadOnlyProjectedHostContent() throws Exception {
        host("skills/good/SKILL.md", skill("good", "HOST_SKILL"));
        host("skills/good/references/guide.md", "HOST_RESOURCE");
        sandbox("skills/good/SKILL.md", skill("good", "INJECTED_SKILL"));
        sandbox("skills/evil/SKILL.md", skill("evil", "EVIL"));
        sandbox("skills/good/references/guide.md", "INJECTED_RESOURCE");
        sandbox("skills/good/extra.md", "EVIL_RESOURCE");
        secure("skills");
        var repo = new WorkspaceSkillRepository(runtime, "skills", "test", false);
        repo.setDefinitionAuthority(manager.getDefinitionAuthority());
        assertEquals(List.of("good"), repo.getAllSkillNames());
        assertEquals("HOST_SKILL", repo.getSkill("good").getDescription());
        assertEquals("HOST_RESOURCE", repo.readSkillFile("good", "references/guide.md", CTX));
        var resources = repo.resourcesFor("good", CTX);
        assertEquals(Optional.of("HOST_RESOURCE"), resources.read("references/guide.md"));
        assertEquals(
                "HOST_RESOURCE",
                new String(
                        resources.readBinary("references/guide.md").orElseThrow(),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Optional.empty(), resources.read("extra.md"));
        assertEquals(List.of("references/guide.md"), resources.list());
    }

    @Test
    void partialSkillProjectionDoesNotHideUnownedRuntimeSkills() throws Exception {
        host("skills/good/SKILL.md", skill("good", "HOST_SKILL"));
        sandbox("skills/good/SKILL.md", skill("good", "INJECTED"));
        sandbox("skills/runtime/SKILL.md", skill("runtime", "RUNTIME"));
        secure("skills/good");
        var repo = new WorkspaceSkillRepository(runtime, "skills", "test", false);
        repo.setDefinitionAuthority(manager.getDefinitionAuthority());
        assertEquals(Set.of("good", "runtime"), new HashSet<>(repo.getAllSkillNames()));
        assertEquals("HOST_SKILL", repo.getSkill("good").getDescription());
    }

    @Test
    void explicitFilesystemRoutesStillWin() throws Exception {
        Path routedRoot = temp.resolve("route");
        Files.createDirectories(routedRoot);
        Files.writeString(routedRoot.resolve("SKILL.md"), "ROUTED");
        var routed =
                new RoutedSandboxFilesystem(
                        new SandboxBackedFilesystem(),
                        Map.of("/skills/", new LocalFilesystem(routedRoot, true, 10)));
        try (var ws = new WorkspaceManager(host, routed)) {
            ws.setDefinitionAuthority(
                    new WorkspaceDefinitionAuthority(host, List.of("skills"), routed));
            assertFalse(ws.getDefinitionAuthority().owns("skills/SKILL.md"));
            assertEquals("ROUTED", ws.readManagedWorkspaceFileUtf8(CTX, "skills/SKILL.md"));
        }
    }

    private static String skill(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\nbody";
    }
}
