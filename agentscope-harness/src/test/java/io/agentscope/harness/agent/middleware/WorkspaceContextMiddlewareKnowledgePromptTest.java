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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ensures Domain Knowledge guidance, KNOWLEDGE.md content and the knowledge file catalog are
 * suppressed when {@code disableKnowledgeContext} is true, while AGENTS.md and MEMORY.md remain
 * intact. Also verifies the two workspace-files notice variants and the getter.
 */
class WorkspaceContextMiddlewareKnowledgePromptTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager track(WorkspaceManager wm) {
        openManagers.add(wm);
        return wm;
    }

    @TempDir Path workspace;

    @Test
    void disabled_omitsKnowledgeGuidanceContentAndCatalog_keepsAgentsAndMemory() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent persona");
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/KNOWLEDGE.md"), "knowledge entry");
        Files.writeString(workspace.resolve("knowledge/ref.md"), "reference file");

        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, false, false, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        // Knowledge-related content is suppressed
        assertFalse(prompt.contains("## Domain Knowledge"));
        assertFalse(prompt.contains("knowledge entry"));
        assertFalse(prompt.contains("Knowledge files:"));
        assertFalse(prompt.contains("ref.md"));
        assertFalse(prompt.contains("<domain_knowledge_context>"));
        // AGENTS.md and MEMORY.md remain intact
        assertTrue(prompt.contains("agent persona"));
        assertTrue(prompt.contains("prefer dark mode"));
        assertTrue(prompt.contains("<agents_context>"));
        assertTrue(prompt.contains("<memory_context>"));
        // Getter
        assertTrue(mw.isDisableKnowledgeContext());
    }

    @Test
    void disabled_omitsEmptyDomainKnowledgeContextElement() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent persona");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, false, false, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        // The empty <domain_knowledge_context> element must not appear
        assertFalse(prompt.contains("<domain_knowledge_context>"));
        // AGENTS.md still present
        assertTrue(prompt.contains("agent persona"));
    }

    @Test
    void disabled_withMemoryContext_selectsNoKnowledgeWithMemoryNotice() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent persona");
        Files.writeString(workspace.resolve("MEMORY.md"), "prefer dark mode");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, false, false, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        // Should use the WITH_MEMORY notice variant (mentions MEMORY.md)
        assertTrue(prompt.contains("MEMORY.md"));
        // Should NOT use the WITH_KNOWLEDGE notice variant
        assertFalse(prompt.contains("knowledge/KNOWLEDGE.md"));
    }

    @Test
    void disabled_withBothMemoryDisabled_selectsNoKnowledgeWithoutMemoryNotice() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent persona");
        Files.writeString(workspace.resolve("MEMORY.md"), "should not appear");
        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw =
                new WorkspaceContextMiddleware(wm, "agent", null, 8000, true, true, true);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        // Memory is fully off (both tools + hooks disabled)
        assertFalse(prompt.contains("should not appear"));
        assertFalse(prompt.contains("<memory_context>"));
        // Should use the WITHOUT_MEMORY notice variant (does NOT mention MEMORY.md)
        assertFalse(prompt.contains("MEMORY.md"));
        // AGENTS.md still present
        assertTrue(prompt.contains("agent persona"));
    }

    @Test
    void enabled_defaultKeepsKnowledgeInjection() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent persona");
        Files.createDirectories(workspace.resolve("knowledge"));
        Files.writeString(workspace.resolve("knowledge/KNOWLEDGE.md"), "knowledge entry");
        Files.writeString(workspace.resolve("knowledge/ref.md"), "reference file");

        WorkspaceManager wm = track(new WorkspaceManager(workspace));
        WorkspaceContextMiddleware mw = new WorkspaceContextMiddleware(wm);

        String prompt = mw.onSystemPrompt(null, RuntimeContext.empty(), "BASE\n").block();
        assertNotNull(prompt);
        // Default: knowledge injection is intact
        assertTrue(prompt.contains("## Domain Knowledge"));
        assertTrue(prompt.contains("knowledge entry"));
        assertTrue(prompt.contains("Knowledge files:"));
        assertTrue(prompt.contains("ref.md"));
        assertTrue(prompt.contains("<domain_knowledge_context>"));
        // Getter
        assertFalse(mw.isDisableKnowledgeContext());
    }
}
