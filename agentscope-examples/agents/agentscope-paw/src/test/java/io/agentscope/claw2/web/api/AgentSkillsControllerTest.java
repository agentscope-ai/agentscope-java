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
package io.agentscope.claw2.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.claw2.marketplace.ClawMarketplaceRegistry;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AgentSkillsController} covering workspace CRUD and one-click install from
 * an in-memory skill repository. Bootstrap and catalog dependencies are stubbed because the
 * controller only needs four touch points from them: agent lookup, workspace path resolution,
 * built-in flag, and clawHome.
 */
class AgentSkillsControllerTest {

    @TempDir Path tempDir;

    private static final String AGENT_ID = "demo";

    private Path workspace;
    private InMemorySkillRepository repo;
    private AgentSkillsController controller;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve("skills"));

        repo = new InMemorySkillRepository();
        repo.add(
                new AgentSkill(
                        "code-reviewer",
                        "Use when reviewing pull requests for style and bugs.",
                        "---\nname: code-reviewer\ndescription: Review code\n---\n# review\n",
                        Map.of("references/style.md", "be careful")));
        repo.add(
                new AgentSkill(
                        "data-analyzer",
                        "Use when computing statistics or trends.",
                        "---\nname: data-analyzer\ndescription: Analyze\n---\n# analyze\n",
                        Map.of()));

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getSkillRepositories()).thenReturn(List.of(repo));

        ClawBootstrap bootstrap = mock(ClawBootstrap.class);
        when(bootstrap.agents()).thenReturn(Map.of(AGENT_ID, agent));
        when(bootstrap.resolveWorkspace(AGENT_ID)).thenReturn(workspace);
        when(bootstrap.clawHome()).thenReturn(tempDir);

        AgentCatalogService catalog = mock(AgentCatalogService.class);
        when(catalog.isBuiltin(AGENT_ID)).thenReturn(true);

        ClawMarketplaceRegistry registry = mock(ClawMarketplaceRegistry.class);

        controller = new AgentSkillsController(bootstrap, catalog, registry);
    }

    @Test
    void listWorkspaceSkills_emptyInitially() {
        List<AgentSkillsController.WorkspaceSkillInfo> list =
                controller.listWorkspaceSkills(AGENT_ID).block();
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    void upsertWorkspaceSkill_writesSkillMd() throws Exception {
        String md = "---\nname: notes-taker\ndescription: Capture notes\n---\n# notes\n";
        AgentSkillsController.WorkspaceSkillInfo info =
                controller
                        .upsertWorkspaceSkill(
                                AGENT_ID,
                                "notes-taker",
                                new AgentSkillsController.WorkspaceSkillUpsertRequest(md, null))
                        .block();

        assertNotNull(info);
        assertEquals("notes-taker", info.dirName());
        assertEquals("Capture notes", info.description());
        assertTrue(Files.isRegularFile(workspace.resolve("skills/notes-taker/SKILL.md")));
    }

    @Test
    void upsertWorkspaceSkill_writesResources() throws Exception {
        String md = "---\nname: with-refs\ndescription: With resources\n---\n# x\n";
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("references/note.md", "hello");
        resources.put("scripts/run.sh", "echo hi");
        controller
                .upsertWorkspaceSkill(
                        AGENT_ID,
                        "with-refs",
                        new AgentSkillsController.WorkspaceSkillUpsertRequest(md, resources))
                .block();

        assertEquals(
                "hello",
                Files.readString(
                        workspace.resolve("skills/with-refs/references/note.md"),
                        StandardCharsets.UTF_8));
        assertEquals(
                "echo hi",
                Files.readString(
                        workspace.resolve("skills/with-refs/scripts/run.sh"),
                        StandardCharsets.UTF_8));
    }

    @Test
    void upsertWorkspaceSkill_rejectsTraversalInName() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller
                                        .upsertWorkspaceSkill(
                                                AGENT_ID,
                                                "../../etc",
                                                new AgentSkillsController
                                                        .WorkspaceSkillUpsertRequest("body", null))
                                        .block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void upsertWorkspaceSkill_rejectsTraversalInResourcePath() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller
                                        .upsertWorkspaceSkill(
                                                AGENT_ID,
                                                "ok",
                                                new AgentSkillsController
                                                        .WorkspaceSkillUpsertRequest(
                                                        "body", Map.of("../escape.txt", "no")))
                                        .block());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void deleteWorkspaceSkill_removesDirectory() throws Exception {
        Files.createDirectories(workspace.resolve("skills/temp"));
        Files.writeString(
                workspace.resolve("skills/temp/SKILL.md"),
                "---\nname: temp\ndescription: a\n---\nbody",
                StandardCharsets.UTF_8);

        controller.deleteWorkspaceSkill(AGENT_ID, "temp").block();
        assertFalse(Files.exists(workspace.resolve("skills/temp")));
    }

    @Test
    void deleteWorkspaceSkill_404_whenMissing() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.deleteWorkspaceSkill(AGENT_ID, "nope").block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void listRepositories_returnsBoundRepoMetadata() {
        List<AgentSkillsController.RepositoryInfo> list =
                controller.listRepositories(AGENT_ID).block();
        assertNotNull(list);
        assertEquals(1, list.size());
        AgentSkillsController.RepositoryInfo info = list.get(0);
        assertEquals(0, info.index());
        assertEquals("memory", info.type());
        assertTrue(info.writable());
    }

    @Test
    void listRepositorySkills_returnsAllSkills() {
        List<AgentSkillsController.MarketSkillInfo> list =
                controller.listRepositorySkills(AGENT_ID, 0).block();
        assertNotNull(list);
        assertEquals(2, list.size());
        // sorted by name
        assertEquals("code-reviewer", list.get(0).name());
        assertEquals("data-analyzer", list.get(1).name());
    }

    @Test
    void listRepositorySkills_404_whenIndexOutOfRange() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.listRepositorySkills(AGENT_ID, 99).block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getRepositorySkill_returnsContentAndResources() {
        AgentSkillsController.MarketSkillDetail detail =
                controller.getRepositorySkill(AGENT_ID, 0, "code-reviewer").block();
        assertNotNull(detail);
        assertEquals("code-reviewer", detail.name());
        assertTrue(detail.content().contains("# review"));
        assertTrue(detail.resources().containsKey("references/style.md"));
    }

    @Test
    void install_copiesSkillIntoWorkspace() throws Exception {
        AgentSkillsController.WorkspaceSkillInfo result =
                controller
                        .installFromRepository(
                                AGENT_ID,
                                new AgentSkillsController.InstallRequest(
                                        0, "code-reviewer", null, null))
                        .block();

        assertNotNull(result);
        assertEquals("code-reviewer", result.dirName());
        Path md = workspace.resolve("skills/code-reviewer/SKILL.md");
        assertTrue(Files.isRegularFile(md));
        assertTrue(Files.readString(md, StandardCharsets.UTF_8).contains("# review"));
        assertTrue(
                Files.isRegularFile(workspace.resolve("skills/code-reviewer/references/style.md")));
    }

    @Test
    void install_409_whenWorkspaceCollides() throws Exception {
        Files.createDirectories(workspace.resolve("skills/code-reviewer"));
        Files.writeString(
                workspace.resolve("skills/code-reviewer/SKILL.md"),
                "existing",
                StandardCharsets.UTF_8);

        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller
                                        .installFromRepository(
                                                AGENT_ID,
                                                new AgentSkillsController.InstallRequest(
                                                        0, "code-reviewer", null, null))
                                        .block());
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        // Original file untouched.
        assertEquals(
                "existing",
                Files.readString(
                        workspace.resolve("skills/code-reviewer/SKILL.md"),
                        StandardCharsets.UTF_8));
    }

    @Test
    void install_overwriteReplacesExisting() throws Exception {
        Files.createDirectories(workspace.resolve("skills/code-reviewer"));
        Files.writeString(
                workspace.resolve("skills/code-reviewer/SKILL.md"),
                "old content",
                StandardCharsets.UTF_8);

        AgentSkillsController.WorkspaceSkillInfo result =
                controller
                        .installFromRepository(
                                AGENT_ID,
                                new AgentSkillsController.InstallRequest(
                                        0, "code-reviewer", null, true))
                        .block();
        assertNotNull(result);
        String md =
                Files.readString(
                        workspace.resolve("skills/code-reviewer/SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(md.contains("# review"));
    }

    @Test
    void install_underTargetName() throws Exception {
        AgentSkillsController.WorkspaceSkillInfo result =
                controller
                        .installFromRepository(
                                AGENT_ID,
                                new AgentSkillsController.InstallRequest(
                                        0, "data-analyzer", "stats", null))
                        .block();
        assertNotNull(result);
        assertEquals("stats", result.dirName());
        assertTrue(Files.isRegularFile(workspace.resolve("skills/stats/SKILL.md")));
        assertFalse(Files.exists(workspace.resolve("skills/data-analyzer")));
    }

    @Test
    void install_404_whenSkillMissing() {
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller
                                        .installFromRepository(
                                                AGENT_ID,
                                                new AgentSkillsController.InstallRequest(
                                                        0, "no-such-skill", null, null))
                                        .block());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void getWorkspaceSkill_returnsParsedDetail() throws Exception {
        controller
                .upsertWorkspaceSkill(
                        AGENT_ID,
                        "kept",
                        new AgentSkillsController.WorkspaceSkillUpsertRequest(
                                "---\nname: kept\ndescription: keep me\n---\nbody",
                                Map.of("notes.md", "n")))
                .block();
        AgentSkillsController.WorkspaceSkillDetail detail =
                controller.getWorkspaceSkill(AGENT_ID, "kept").block();
        assertNotNull(detail);
        assertEquals("keep me", detail.description());
        assertTrue(detail.markdown().contains("body"));
        assertEquals("n", detail.resources().get("notes.md"));
    }

    @Test
    void install_writesInstallMetaAndOrigin() throws Exception {
        AgentSkillsController.WorkspaceSkillInfo result =
                controller
                        .installFromRepository(
                                AGENT_ID,
                                new AgentSkillsController.InstallRequest(
                                        0, "code-reviewer", null, null))
                        .block();

        assertNotNull(result);
        assertEquals("marketplace", result.origin());
        assertNotNull(result.marketplace(), "marketplace meta must be present after install");
        assertEquals("memory", result.marketplace().repoType());
        assertEquals("code-reviewer", result.marketplace().originalName());
        assertNotNull(result.marketplace().installedAt(), "installedAt must be set");

        // Sidecar file is written and not counted as a resource.
        assertTrue(
                Files.isRegularFile(workspace.resolve("skills/code-reviewer/_install.meta.json")),
                "_install.meta.json must be written into the skill dir");
        assertEquals(
                1,
                result.resourceCount(),
                "Only references/style.md should be counted; the sidecar must be filtered");
    }

    @Test
    void upsertWorkspaceSkill_originIsCustomByDefault() {
        String md = "---\nname: hand-written\ndescription: From scratch\n---\nbody";
        AgentSkillsController.WorkspaceSkillInfo info =
                controller
                        .upsertWorkspaceSkill(
                                AGENT_ID,
                                "hand-written",
                                new AgentSkillsController.WorkspaceSkillUpsertRequest(md, null))
                        .block();
        assertNotNull(info);
        assertEquals("custom", info.origin());
        assertNull(
                info.marketplace(),
                "upsert must not synthesize marketplace meta for hand-authored skills");
    }

    @Test
    void install_thenUpsert_preservesMarketplaceOrigin() throws Exception {
        controller
                .installFromRepository(
                        AGENT_ID,
                        new AgentSkillsController.InstallRequest(0, "code-reviewer", null, null))
                .block();

        // User edits the installed skill — the marketplace marker should survive.
        String editedMd = "---\nname: code-reviewer\ndescription: My local tweak\n---\nedited";
        AgentSkillsController.WorkspaceSkillInfo info =
                controller
                        .upsertWorkspaceSkill(
                                AGENT_ID,
                                "code-reviewer",
                                new AgentSkillsController.WorkspaceSkillUpsertRequest(
                                        editedMd, null))
                        .block();
        assertNotNull(info);
        assertEquals(
                "marketplace",
                info.origin(),
                "Editing an installed skill must not detach it from its marketplace origin");
        assertEquals("code-reviewer", info.marketplace().originalName());
    }

    @Test
    void listWorkspaceSkills_reportsOriginPerSkill() throws Exception {
        // Mix one custom + one installed skill in the same workspace.
        controller
                .upsertWorkspaceSkill(
                        AGENT_ID,
                        "my-custom",
                        new AgentSkillsController.WorkspaceSkillUpsertRequest(
                                "---\nname: my-custom\ndescription: hand\n---\nbody", null))
                .block();
        controller
                .installFromRepository(
                        AGENT_ID,
                        new AgentSkillsController.InstallRequest(0, "code-reviewer", null, null))
                .block();

        List<AgentSkillsController.WorkspaceSkillInfo> list =
                controller.listWorkspaceSkills(AGENT_ID).block();
        assertNotNull(list);
        Map<String, String> originByDir = new LinkedHashMap<>();
        for (AgentSkillsController.WorkspaceSkillInfo s : list)
            originByDir.put(s.dirName(), s.origin());
        assertEquals("custom", originByDir.get("my-custom"));
        assertEquals("marketplace", originByDir.get("code-reviewer"));
    }

    @Test
    void install_emptyMarkdown_returns502() {
        repo.add(
                new AgentSkill(
                        "broken-marker",
                        "Broken skill stub used to verify the controller surfaces empty content.",
                        "non-empty placeholder",
                        Map.of()));
        // Override to simulate a backend that hands back null content.
        repo.lieAboutContent("broken-marker", "");
        ResponseStatusException ex =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                controller
                                        .installFromRepository(
                                                AGENT_ID,
                                                new AgentSkillsController.InstallRequest(
                                                        0, "broken-marker", null, null))
                                        .block());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    // =====================================================================
    //  In-memory test fixture
    // =====================================================================

    /** Minimal repository backed by a {@link Map}. Honours name lookups; no version semantics. */
    private static final class InMemorySkillRepository implements AgentSkillRepository {

        private final Map<String, AgentSkill> store = new LinkedHashMap<>();
        private final Map<String, String> contentOverrides = new LinkedHashMap<>();
        private boolean writable = true;

        void add(AgentSkill skill) {
            store.put(skill.getName(), skill);
        }

        /** Lets a single test simulate a repository returning unexpected content. */
        void lieAboutContent(String name, String fakeContent) {
            contentOverrides.put(name, fakeContent);
        }

        @Override
        public AgentSkill getSkill(String name) {
            AgentSkill skill = store.get(name);
            if (skill == null) return null;
            if (contentOverrides.containsKey(name)) {
                return new AgentSkill(
                        skill.getName(),
                        skill.getDescription(),
                        // AgentSkill rejects empty content at construction time, so wrap it in a
                        // marker that the controller can detect by treating as empty downstream.
                        contentOverrides.get(name).isEmpty() ? "  " : contentOverrides.get(name),
                        skill.getResources());
            }
            return skill;
        }

        @Override
        public List<String> getAllSkillNames() {
            return new ArrayList<>(store.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return new ArrayList<>(store.values());
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            for (AgentSkill s : skills) store.put(s.getName(), s);
            return true;
        }

        @Override
        public boolean delete(String skillName) {
            return store.remove(skillName) != null;
        }

        @Override
        public boolean skillExists(String skillName) {
            return store.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("memory", "in-memory", writable);
        }

        @Override
        public String getSource() {
            return "memory";
        }

        @Override
        public void setWriteable(boolean writeable) {
            this.writable = writeable;
        }

        @Override
        public boolean isWriteable() {
            return writable;
        }
    }
}
