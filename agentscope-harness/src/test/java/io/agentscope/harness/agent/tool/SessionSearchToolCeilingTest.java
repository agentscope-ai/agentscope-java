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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the model-supplied result-limit clamp (#3270): {@code session_search}
 * and {@code session_history} are excluded from {@code ToolResultEvictionConfig} on the
 * "bounded results" rationale, so an unbounded model-supplied limit bypasses every downstream
 * trim.
 */
class SessionSearchToolCeilingTest {

    @TempDir Path workspace;

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private void seedSession(int count) throws Exception {
        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, null);
            RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();
            List<Msg> batch = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                batch.add(message("m" + i, MsgRole.USER, "needle item " + i));
            }
            flushManager.offloadMessages(rc, batch, "agent-a", "session-1");
        }
        Path context = workspace.resolve("agents/agent-a/sessions/session-1.jsonl");
        assertTrue(Files.isRegularFile(context), "session context should be offloaded");
    }

    @Test
    void sessionSearch_clampsModelSuppliedMaxResults() throws Exception {
        seedSession(150);
        SessionSearchTool tool = new SessionSearchTool(new WorkspaceManager(workspace));

        String out =
                tool.sessionSearch(
                        RuntimeContext.builder().sessionId("session-1").build(),
                        "needle",
                        "agent-a",
                        100000);

        assertTrue(out.contains("Found 100 matches"), () -> "output was: " + out);
        assertFalse(out.contains("Found 150"), () -> "output was: " + out);
    }

    @Test
    void sessionHistory_clampsModelSuppliedLastN() throws Exception {
        seedSession(150);
        SessionSearchTool tool = new SessionSearchTool(new WorkspaceManager(workspace));

        String out =
                tool.sessionHistory(
                        RuntimeContext.builder().sessionId("session-1").build(),
                        "agent-a",
                        "session-1",
                        100000);

        assertTrue(out.contains("showing last 100)"), () -> "history must be clamped: " + out);
    }

    @Test
    void sessionHistory_boundary_lastN99And101() throws Exception {
        seedSession(150);
        SessionSearchTool tool = new SessionSearchTool(new WorkspaceManager(workspace));

        String out99 =
                tool.sessionHistory(
                        RuntimeContext.builder().sessionId("session-1").build(),
                        "agent-a",
                        "session-1",
                        99);
        assertTrue(out99.contains("showing last 99)"), () -> "out99 was: " + out99);

        String out101 =
                tool.sessionHistory(
                        RuntimeContext.builder().sessionId("session-1").build(),
                        "agent-a",
                        "session-1",
                        101);
        assertTrue(out101.contains("showing last 100)"), () -> "out101 was: " + out101);
    }

    @Test
    void defaultLimitStillApplies() throws Exception {
        seedSession(15);
        SessionSearchTool tool = new SessionSearchTool(new WorkspaceManager(workspace));

        String out =
                tool.sessionSearch(
                        RuntimeContext.builder().sessionId("session-1").build(),
                        "needle",
                        "agent-a",
                        null);

        assertTrue(out.contains("Found 10 matches"), () -> "output was: " + out);
    }
}
