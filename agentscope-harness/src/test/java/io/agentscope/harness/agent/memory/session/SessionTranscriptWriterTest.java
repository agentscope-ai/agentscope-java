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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.harness.agent.transcript.FilesystemTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTranscriptWriterTest {

    @TempDir Path workspace;

    @Test
    void appendMessages_isIdempotent_andKeepsChain() throws Exception {
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();
        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            SessionTranscriptWriter writer = new SessionTranscriptWriter(wm);
            writer.appendMessages(
                    rc,
                    List.of(
                            text("m1", MsgRole.USER, "hello"),
                            text("m2", MsgRole.ASSISTANT, "world")),
                    "agent-a",
                    "session-1");
            writer.appendMessages(
                    rc,
                    List.of(
                            text("m1", MsgRole.USER, "hello"),
                            text("m2", MsgRole.ASSISTANT, "world"),
                            text("m3", MsgRole.USER, "follow-up")),
                    "agent-a",
                    "session-1");
        }

        Path context = workspace.resolve("agents/agent-a/sessions/session-1.jsonl");
        assertEquals(3, Files.readAllLines(context).size());
        SessionTree tree = new SessionTree(context, workspace, null);
        tree.load();
        assertEquals(3, tree.getMessageEntries().size());
    }

    @Test
    void deriveEntries_emitsStructuredToolUseAndResult_withPairing() {
        Msg assistant =
                Msg.builder()
                        .id("a1")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("calling tools").build(),
                                ToolUseBlock.builder()
                                        .id("t1")
                                        .name("search")
                                        .input(Map.of("q", "cats"))
                                        .build(),
                                ToolUseBlock.builder()
                                        .id("t2")
                                        .name("read")
                                        .input(Map.of("path", "/tmp/x"))
                                        .build())
                        .build();
        Msg tool =
                Msg.builder()
                        .id("r1")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("t1")
                                        .name("search")
                                        .output(TextBlock.builder().text("meow").build())
                                        .build())
                        .build();

        List<SessionEntry> a = SessionTranscriptWriter.deriveEntries(assistant, null);
        assertEquals(3, a.size());
        assertInstanceOf(SessionEntry.MessageEntry.class, a.get(0));
        assertEquals("calling tools", ((SessionEntry.MessageEntry) a.get(0)).getContent());
        SessionEntry.ToolUseEntry use1 =
                assertInstanceOf(SessionEntry.ToolUseEntry.class, a.get(1));
        SessionEntry.ToolUseEntry use2 =
                assertInstanceOf(SessionEntry.ToolUseEntry.class, a.get(2));
        assertEquals("t1", use1.getToolCallId());
        assertEquals("search", use1.getName());
        assertEquals("t2", use2.getToolCallId());
        assertEquals("a1:use:t1", use1.getId());
        assertEquals("a1:use:t2", use2.getId());

        List<SessionEntry> r = SessionTranscriptWriter.deriveEntries(tool, use2.getId());
        assertEquals(1, r.size());
        SessionEntry.ToolResultEntry result =
                assertInstanceOf(SessionEntry.ToolResultEntry.class, r.get(0));
        assertEquals("t1", result.getToolCallId());
        assertEquals("meow", result.getOutput());
        assertFalse(result.isTruncated());
    }

    @Test
    void deriveEntries_placeholderForImageOnlyMessage() {
        Msg img =
                Msg.builder()
                        .id("img1")
                        .role(MsgRole.USER)
                        .content(
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/a.png")
                                                        .build())
                                        .build())
                        .build();
        List<SessionEntry> entries = SessionTranscriptWriter.deriveEntries(img, null);
        assertEquals(1, entries.size());
        SessionEntry.MessageEntry msg =
                assertInstanceOf(SessionEntry.MessageEntry.class, entries.get(0));
        assertEquals("", msg.getContent());
        assertTrue(msg.getBlockTypes().contains("image"));
    }

    @Test
    void appendMessages_withTranscriptStore_writesImmutableSegments() throws Exception {
        RuntimeContext rc = RuntimeContext.builder().sessionId("s-seg").build();
        Path storeRoot = workspace.resolve("segments");
        TranscriptStore store = new FilesystemTranscriptStore(storeRoot);
        try (WorkspaceManager wm = new WorkspaceManager(workspace)) {
            SessionTranscriptWriter writer = new SessionTranscriptWriter(wm, store, "tenant-a");
            writer.appendMessages(
                    rc, List.of(text("m1", MsgRole.USER, "hello")), "agent-a", "s-seg");
            writer.appendMessages(
                    rc,
                    List.of(text("m1", MsgRole.USER, "hello"), text("m2", MsgRole.ASSISTANT, "hi")),
                    "agent-a",
                    "s-seg");
        }
        // Allow async mirror executor to finish.
        Thread.sleep(300);
        List<TranscriptStore.SegmentInfo> segs =
                store.listSegments(new TranscriptRef("tenant-a", "agent-a", "s-seg"));
        assertFalse(segs.isEmpty(), "expected at least one segment");
        long totalLines = 0;
        for (TranscriptStore.SegmentInfo seg : segs) {
            try (var in = store.readSegment(seg.key())) {
                String body = new String(in.readAllBytes());
                totalLines += body.lines().filter(l -> !l.isBlank()).count();
            }
        }
        assertTrue(totalLines >= 2, "segments should contain both entries across flushes");
    }

    private static Msg text(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
