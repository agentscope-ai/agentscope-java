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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSearchToolCeilingTest {
    @TempDir Path workspace;
    private final RuntimeContext rc = RuntimeContext.empty();

    private SessionSearchTool seedMessages() throws Exception {
        var manager = new WorkspaceManager(workspace);
        var tree =
                new SessionTree(
                        manager.resolveSessionContextFile(rc, "agent-a", "session-1"),
                        workspace,
                        null);
        for (int i = 1; i <= 150; i++) {
            tree.append(
                    new SessionEntry.MessageEntry(
                            null, null, null, "USER", "needle item " + i, null));
        }
        tree.flush();
        return new SessionSearchTool(manager);
    }

    private int matches(String output) {
        return (output.length() - output.replace("needle item", "").length())
                / "needle item".length();
    }

    @Test
    void searchClampsLimitAndKeepsDefaults() throws Exception {
        var tool = seedMessages();
        assertTrue(
                tool.sessionSearch(rc, "needle", "agent-a", 100000)
                        .startsWith("Found 100 matches"));
        assertEquals(99, matches(tool.sessionSearch(rc, "needle", "agent-a", 99)));
        assertEquals(100, matches(tool.sessionSearch(rc, "needle", "agent-a", 101)));
        assertEquals(10, matches(tool.sessionSearch(rc, "needle", "agent-a", null)));
        assertEquals(10, matches(tool.sessionSearch(rc, "needle", "agent-a", -1)));
    }

    @Test
    void historyClampsLimitAndKeepsDefaults() throws Exception {
        var tool = seedMessages();
        assertEquals(100, matches(tool.sessionHistory(rc, "agent-a", "session-1", 100000)));
        assertEquals(99, matches(tool.sessionHistory(rc, "agent-a", "session-1", 99)));
        assertEquals(100, matches(tool.sessionHistory(rc, "agent-a", "session-1", 101)));
        assertEquals(20, matches(tool.sessionHistory(rc, "agent-a", "session-1", null)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyHistoryAlsoClampsLimit() throws Exception {
        var manager = new WorkspaceManager(workspace);
        var file = manager.resolveSessionFile(rc, "agent-a", "legacy");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "needle item\n".repeat(150));
        assertEquals(
                100,
                matches(
                        new SessionSearchTool(manager)
                                .sessionHistory(rc, "agent-a", "legacy", 100000)));
    }
}
