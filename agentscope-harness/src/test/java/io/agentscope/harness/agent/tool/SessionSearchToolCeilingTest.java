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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertErrorKind;
import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.journal.WorkspaceThreadJournalStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the model-supplied result-limit clamp (#3270) on {@code thread_search}
 * and {@code thread_history}.
 */
class SessionSearchToolCeilingTest {

    @TempDir Path directory;
    private final ThreadKey key = new ThreadKey("u", "agent-a", "session-1", "main");
    private final RuntimeContext rc = RuntimeContext.builder().userId("u").build();

    private ThreadHistoryTool seedMessages(int count) {
        var messages = new ArrayList<Msg>();
        for (int i = 1; i <= count; i++) {
            messages.add(
                    Msg.builder()
                            .id("m" + i)
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("needle item " + i).build())
                            .build());
        }
        return seedMessages(messages);
    }

    private ThreadHistoryTool seedMessages(List<Msg> messages) {
        var store = new WorkspaceThreadJournalStore(new LocalFilesystem(directory));
        var log = store.open(key, rc);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        var events = new ArrayList<JournalEvent>();
        for (int i = 0; i < messages.size(); i++) {
            Msg message = messages.get(i);
            long seq = i + 1;
            String type =
                    switch (message.getRole()) {
                        case ASSISTANT -> "message/assistant";
                        case TOOL -> "tool/result";
                        default -> "message/user";
                    };
            events.add(
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            message.getId(),
                            seq,
                            seq,
                            type,
                            "run",
                            "turn",
                            true,
                            JsonUtils.getJsonCodec().toJson(Map.of("message", message))));
        }
        log.commit(writer, "messages", 0, events);
        log.release(writer);
        return new ThreadHistoryTool(
                new WorkspaceThreadJournalStore(new LocalFilesystem(directory)));
    }

    private static int countMatches(String json) {
        return (json.length() - json.replace("needle item", "").length()) / "needle item".length();
    }

    @Test
    void threadSearchClampsModelSuppliedMaxResults() {
        var tool = seedMessages(150);
        var out =
                assertText(
                        tool.threadSearch(rc, "needle", "agent-a", 100000),
                        ToolResultState.SUCCESS);
        assertEquals(100, countMatches(out));
    }

    @Test
    void threadHistoryClampsModelSuppliedLastN() {
        var tool = seedMessages(150);
        var out =
                assertText(
                        tool.threadHistory(rc, "agent-a", "session-1", null, 100000),
                        ToolResultState.SUCCESS);
        assertEquals(100, countMatches(out));
    }

    @Test
    void threadHistoryBoundaryLastN99And101() {
        var tool = seedMessages(150);
        assertEquals(
                99,
                countMatches(
                        assertText(
                                tool.threadHistory(rc, "agent-a", "session-1", null, 99),
                                ToolResultState.SUCCESS)));
        assertEquals(
                100,
                countMatches(
                        assertText(
                                tool.threadHistory(rc, "agent-a", "session-1", null, 101),
                                ToolResultState.SUCCESS)));
    }

    @Test
    void defaultLimitStillApplies() {
        var tool = seedMessages(15);
        var out =
                assertText(
                        tool.threadSearch(rc, "needle", "agent-a", null), ToolResultState.SUCCESS);
        assertEquals(10, countMatches(out));
    }

    @Test
    void threadSearchSummarizesOversizedMatchesAndKeepsThemReadable() {
        var messages = new ArrayList<Msg>();
        for (int i = 1; i <= 11; i++) {
            messages.add(
                    Msg.builder()
                            .id("m" + i)
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("needle item " + i).build())
                            .build());
        }
        String huge = "needle huge " + "x".repeat(80_701);
        messages.add(
                Msg.builderForRole(MsgRole.TOOL)
                        .id("m12")
                        .content(
                                ToolResultBlock.of(
                                        "call-1",
                                        "thread_search",
                                        TextBlock.builder().text(huge).build()))
                        .build());
        var tool = seedMessages(messages);

        String out =
                assertText(tool.threadSearch(rc, "needle", "agent-a", 20), ToolResultState.SUCCESS);

        assertTrue(out.length() <= ThreadHistoryTool.MAX_RESULT_CHARS, "bounded result");
        assertTrue(out.contains("\"messageId\":\"m12\""), out);
        assertTrue(out.contains("Message truncated"), out);
        assertFalse(out.contains("x".repeat(10_000)), "full payload must not be copied");

        String full =
                assertText(
                        tool.threadHistory(rc, "agent-a", "session-1", null, null, "m12"),
                        ToolResultState.SUCCESS);
        assertTrue(full.contains("needle huge"));
        assertTrue(full.length() > 80_000, "full message stays recoverable");
    }

    @Test
    void threadSearchStopsAtTheTotalResultBudget() {
        var messages = new ArrayList<Msg>();
        for (int i = 1; i <= 100; i++) {
            messages.add(
                    Msg.builder()
                            .id("m" + i)
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder().text("needle " + "y".repeat(5_000)).build())
                            .build());
        }
        var tool = seedMessages(messages);

        String out =
                assertText(
                        tool.threadSearch(rc, "needle", "agent-a", 100), ToolResultState.SUCCESS);

        assertTrue(out.length() <= ThreadHistoryTool.MAX_RESULT_CHARS, "bounded result");
        assertTrue(out.contains("\"truncated\":true"), out);
        List<?> parsed = JsonUtils.getJsonCodec().fromJson(out, List.class);
        assertTrue(parsed.size() < 100, "later matches are omitted");
    }

    @Test
    void threadHistoryReadsOneMessageByIdAndRejectsUnknownIds() {
        var tool = seedMessages(3);

        String out =
                assertText(
                        tool.threadHistory(rc, "agent-a", "session-1", null, null, "m2"),
                        ToolResultState.SUCCESS);
        assertTrue(out.contains("needle item 2"));

        assertText(
                tool.threadHistory(rc, "agent-a", "session-1", null, null, "missing"),
                ToolResultState.ERROR);
    }

    @Test
    void threadHistoryBoundsTheRequestedWindowAndKeepsMessagesRecoverable() {
        var messages = new ArrayList<Msg>();
        for (int i = 1; i <= 100; i++) {
            messages.add(
                    Msg.builder()
                            .id("m" + i)
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder().text("needle " + "z".repeat(5_000)).build())
                            .build());
        }
        var tool = seedMessages(messages);

        String out =
                assertText(
                        tool.threadHistory(rc, "agent-a", "session-1", null, 100),
                        ToolResultState.SUCCESS);

        assertTrue(out.length() <= ThreadHistoryTool.MAX_RESULT_CHARS, "bounded result");
        assertTrue(out.contains("\"truncated\":true"), out);
        assertTrue(out.contains("\"omittedFromWindow\":"), out);
        assertTrue(out.contains("Message truncated"), "per-message preview keeps identity");
        assertTrue(out.contains("thread_search"), "note points at a recovery path");
        Map<?, ?> parsed = JsonUtils.getJsonCodec().fromJson(out, Map.class);
        assertTrue(((List<?>) parsed.get("messages")).size() < 100, "later messages are omitted");
    }

    @Test
    void threadListBoundsLargeResultSets() {
        var keys = new ArrayList<ThreadKey>();
        for (int i = 0; i < 2_000; i++) {
            keys.add(new ThreadKey("u", "agent-a", "session-" + i, "main"));
        }
        var store = mock(io.agentscope.core.journal.ThreadJournalStore.class);
        when(store.list(rc)).thenReturn(keys);
        var tool = new ThreadHistoryTool(store);

        String out = assertText(tool.threadList(rc, "agent-a"), ToolResultState.SUCCESS);

        assertTrue(out.length() <= ThreadHistoryTool.MAX_RESULT_CHARS, "bounded result");
        assertTrue(out.contains("\"truncated\":true"), out);
        assertTrue(out.contains("\"total\":2000"), out);
    }

    @Test
    void errorsAreActionable() {
        var tool = seedMessages(1);

        ToolResultBlock missingQuery = tool.threadSearch(rc, " ", "agent-a", null);
        assertTrue(
                assertText(missingQuery, ToolResultState.ERROR).contains("query"),
                "missing query names the argument");
        assertErrorKind(missingQuery, ToolErrorKind.INVALID_ARGUMENT, null);

        ToolResultBlock missingThread = tool.threadHistory(rc, "agent-a", "missing", null, null);
        assertTrue(
                assertText(missingThread, ToolResultState.ERROR).contains("thread_list"),
                "missing thread points at discovery");
        assertErrorKind(missingThread, ToolErrorKind.NOT_FOUND, "agent-a/missing/main");

        ToolResultBlock missingMessage =
                tool.threadHistory(rc, "agent-a", "session-1", null, null, "nope");
        assertTrue(
                assertText(missingMessage, ToolResultState.ERROR).contains("thread_search"),
                "missing message points at search");
        assertErrorKind(missingMessage, ToolErrorKind.NOT_FOUND, "agent-a/session-1/main");
    }
}
