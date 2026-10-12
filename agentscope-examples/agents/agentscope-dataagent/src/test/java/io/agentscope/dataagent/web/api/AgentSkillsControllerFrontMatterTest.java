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
package io.agentscope.dataagent.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * Tests for the YAML frontmatter parsing behind the workspace skill listing, in particular the
 * byte shapes a SKILL.md may arrive in: a UTF-8 BOM, CR line endings, or a closing fence at EOF.
 */
class AgentSkillsControllerFrontMatterTest {

    private static final String AGENT_ID = "demo";
    private static final String USER_ID = "u1";

    private AbstractFilesystem fs;
    private AgentSkillsController controller;

    @BeforeEach
    void setUp() {
        fs = mock(AbstractFilesystem.class);

        AgentCatalogService catalog = mock(AgentCatalogService.class);
        when(catalog.isGlobal(AGENT_ID)).thenReturn(false);
        when(catalog.findOwnerOf(AGENT_ID)).thenReturn(Optional.empty());
        when(catalog.findStoredEntry(AGENT_ID)).thenReturn(Optional.empty());

        WorkspaceManager workspace = mock(WorkspaceManager.class);
        when(workspace.getFilesystem()).thenReturn(fs);

        WorkspaceManagerFactory factory = mock(WorkspaceManagerFactory.class);
        when(factory.forAgent(USER_ID, AGENT_ID, null)).thenReturn(workspace);

        controller =
                new AgentSkillsController(
                        mock(AgentAccessGuard.class),
                        mock(AgentActivityStore.class),
                        catalog,
                        factory,
                        mock(UserMarketplaceRegistry.class));
    }

    @Test
    void listWorkspaceSkills_parsesFrontmatterBehindUtf8Bom() {
        givenSkill(
                "bom-dir",
                "\uFEFF---\nname: bom-skill\ndescription: Saved with a BOM\n---\n# body\n");

        List<AgentSkillsController.WorkspaceSkillInfo> list =
                controller.listWorkspaceSkills(AGENT_ID, auth()).block();

        assertNotNull(list);
        assertEquals(1, list.size());
        // The directory name deliberately differs from the frontmatter name: before the fix
        // both fields silently degraded (name fell back to the directory, description to null).
        assertEquals("bom-skill", list.get(0).name());
        assertEquals("Saved with a BOM", list.get(0).description());
    }

    @Test
    void listWorkspaceSkills_parsesFrontmatterWithCrLineEndings() {
        givenSkill("cr-dir", "---\rname: cr-skill\rdescription: CR endings\r---\r# body\r");

        List<AgentSkillsController.WorkspaceSkillInfo> list =
                controller.listWorkspaceSkills(AGENT_ID, auth()).block();

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("cr-skill", list.get(0).name());
        assertEquals("CR endings", list.get(0).description());
    }

    @Test
    void listWorkspaceSkills_parsesFrontmatterEndingAtEof() {
        givenSkill("eof-dir", "---\nname: eof-skill\ndescription: No trailing newline\n---");

        List<AgentSkillsController.WorkspaceSkillInfo> list =
                controller.listWorkspaceSkills(AGENT_ID, auth()).block();

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("eof-skill", list.get(0).name());
        assertEquals("No trailing newline", list.get(0).description());
    }

    private static Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(USER_ID);
        return auth;
    }

    private void givenSkill(String dirName, String markdown) {
        when(fs.ls(null, "/skills"))
                .thenReturn(LsResult.success(List.of(FileInfo.ofDir("/skills/" + dirName, 0L))));
        when(fs.read(null, "/skills/" + dirName + "/SKILL.md", 0, Integer.MAX_VALUE))
                .thenReturn(ReadResult.success(FileData.create(markdown)));
    }
}
