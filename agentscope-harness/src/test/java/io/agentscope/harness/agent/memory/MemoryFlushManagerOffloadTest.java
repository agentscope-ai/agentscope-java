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
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.memory.session.SessionEntry;
import io.agentscope.harness.agent.memory.session.SessionTree;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryFlushManagerOffloadTest {

    @TempDir Path workspace;

    @Test
    void offloadMessages_skipsAlreadyPersistedPrefix_andKeepsChain() throws Exception {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, null);

            flushManager.offloadMessages(
                    rc,
                    List.of(
                            message("m1", MsgRole.USER, "hello"),
                            message("m2", MsgRole.ASSISTANT, "world")),
                    "agent-a",
                    "session-1");

            flushManager.offloadMessages(
                    rc,
                    List.of(
                            message("m1", MsgRole.USER, "hello"),
                            message("m2", MsgRole.ASSISTANT, "world"),
                            message("m3", MsgRole.USER, "follow-up"),
                            message("m4", MsgRole.ASSISTANT, "reply")),
                    "agent-a",
                    "session-1");
        }

        Path context = workspace.resolve("agents/agent-a/sessions/session-1.jsonl");
        assertEquals(4, Files.readAllLines(context).size(), "offload should stay idempotent");

        SessionTree tree = new SessionTree(context, workspace, null);
        tree.load();
        assertEquals(4, tree.size());

        List<SessionEntry.MessageEntry> entries = tree.getMessageEntries();
        assertEquals(
                List.of("m1", "m2", "m3", "m4"),
                entries.stream().map(SessionEntry.MessageEntry::getId).toList());
        assertEquals(
                Arrays.asList(null, "m1", "m2", "m3"),
                entries.stream().map(SessionEntry.MessageEntry::getParentId).toList());
    }

    @Test
    void offloadMessages_nullAndBlankIds_stillAppendButDoNotBreakStableIds() throws Exception {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, null);

            List<Msg> batch =
                    List.of(
                            message("m1", MsgRole.USER, "stable-hello"),
                            message(null, MsgRole.ASSISTANT, "anonymous-null"),
                            message("", MsgRole.USER, "anonymous-blank"),
                            message("m2", MsgRole.ASSISTANT, "stable-world"));

            flushManager.offloadMessages(rc, batch, "agent-a", "session-1");
            flushManager.offloadMessages(rc, batch, "agent-a", "session-1");
        }

        Path context = workspace.resolve("agents/agent-a/sessions/session-1.jsonl");
        SessionTree tree = new SessionTree(context, workspace, null);
        tree.load();

        List<SessionEntry.MessageEntry> entries = tree.getMessageEntries();
        assertEquals(6, entries.size());
        assertEquals(
                1,
                entries.stream()
                        .filter(entry -> "stable-hello".equals(entry.getContent()))
                        .count());
        assertEquals(
                1,
                entries.stream()
                        .filter(entry -> "stable-world".equals(entry.getContent()))
                        .count());
        assertEquals(
                2,
                entries.stream()
                        .filter(entry -> "anonymous-null".equals(entry.getContent()))
                        .count());
        assertEquals(
                2,
                entries.stream()
                        .filter(entry -> "anonymous-blank".equals(entry.getContent()))
                        .count());
    }

    private static Msg message(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
