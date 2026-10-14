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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
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

    @Test
    void hostNamespacesPreserveUserSkillsAndLazyResourcesWithoutSandboxOverrides()
            throws Exception {
        host("skills/good/SKILL.md", skill("good", "SHARED"));
        host("skills/good/guide.md", "SHARED_RESOURCE");
        host("alice/skills/good/SKILL.md", skill("good", "ALICE"));
        host("alice/skills/good/guide.md", "ALICE_RESOURCE");
        host("alice/skills/personal/SKILL.md", skill("personal", "ALICE_ONLY"));
        sandbox("skills/good/SKILL.md", skill("good", "INJECTED"));
        sandbox("skills/evil/SKILL.md", skill("evil", "EVIL"));
        var repo = new WorkspaceSkillRepository(runtime, "skills", "test", false);
        repo.setDefinitionAuthority(
                new WorkspaceDefinitionAuthority(
                        host,
                        List.of("skills"),
                        runtime,
                        IsolationScope.USER.toNamespaceFactory()));
        var alice = RuntimeContext.builder().userId("alice").build();
        var bob = RuntimeContext.builder().userId("bob").build();
        assertEquals(
                Set.of("good", "personal"),
                new HashSet<>(repo.getAllSkills(alice).stream().map(s -> s.getName()).toList()));
        assertEquals("ALICE", repo.getSkill("good", alice).getDescription());
        assertEquals("SHARED", repo.getSkill("good", bob).getDescription());
        assertEquals(
                List.of("good"), repo.getAllSkills(bob).stream().map(s -> s.getName()).toList());
        assertEquals(
                Optional.of("ALICE_RESOURCE"), repo.resourcesFor("good", alice).read("guide.md"));
        assertEquals(
                Optional.of("SHARED_RESOURCE"), repo.resourcesFor("good", bob).read("guide.md"));
        assertEquals(
                "ALICE_RESOURCE",
                new String(
                        repo.resourcesFor("good", alice).readBinary("guide.md").orElseThrow(),
                        java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void hostDefinitionReadsAcceptVirtualPathsButRejectTraversal() throws Exception {
        host("AGENTS.md", "HOST");
        secure("AGENTS.md");
        assertEquals("HOST", manager.readManagedWorkspaceFileUtf8(CTX, "/AGENTS.md"));
        assertEquals("HOST", manager.readManagedWorkspaceFileUtf8(CTX, "./AGENTS.md"));
        Files.writeString(host.getParent().resolve("AGENTS.md"), "OUTSIDE_WORKSPACE");
        // This accessor's existing contract returns empty for paths outside the workspace.
        assertEquals("", manager.readManagedWorkspaceFileUtf8(CTX, "../AGENTS.md"));
    }

    @Test
    void hostDefinitionsAboveSearchSizeLimitRemainReadable() throws Exception {
        String content = "x".repeat(10 * 1024 * 1024 + 1);
        host("knowledge/large.md", content);
        secure("knowledge");
        assertEquals(
                content,
                manager.getDefinitionAuthority()
                        .readFilesystem("knowledge/large.md", runtime)
                        .read(CTX, "knowledge/large.md", 0, 0)
                        .fileData()
                        .content());
    }

    @Test
    void globPreservesErrorsWhenBothSourcesFail() {
        var failingRuntime = mock(AbstractFilesystem.class);
        when(failingRuntime.glob(any(), anyString(), anyString()))
                .thenReturn(GlobResult.fail("runtime unavailable"));
        try (var hosts =
                mockConstruction(
                        LocalFilesystem.class,
                        (fs, context) ->
                                when(fs.glob(any(), anyString(), anyString()))
                                        .thenReturn(GlobResult.fail("host unavailable")))) {
            var authority =
                    new WorkspaceDefinitionAuthority(host, List.of("skills"), failingRuntime);
            var result = authority.glob(CTX, failingRuntime, "SKILL.md", ".");
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("host unavailable"));
            assertTrue(result.error().contains("runtime unavailable"));
        }
    }

    @Test
    void ownedGlobFailureDoesNotQueryUntrustedRuntime() {
        var untrusted = mock(AbstractFilesystem.class);
        try (var hosts =
                mockConstruction(
                        LocalFilesystem.class,
                        (fs, context) ->
                                when(fs.glob(any(), anyString(), anyString()))
                                        .thenReturn(GlobResult.fail("host unavailable")))) {
            var authority = new WorkspaceDefinitionAuthority(host, List.of("skills"), untrusted);
            assertFalse(authority.glob(CTX, untrusted, "SKILL.md", "skills").isSuccess());
            verify(untrusted, never()).glob(any(), anyString(), anyString());
        }
    }

    private static String skill(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\nbody";
    }
}
