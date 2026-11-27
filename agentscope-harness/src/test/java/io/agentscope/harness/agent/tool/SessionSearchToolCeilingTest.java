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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.journal.WorkspaceThreadJournalStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        return seedMessages(messages, key);
    }

    private ThreadHistoryTool seedMessages(List<Msg> messages, ThreadKey threadKey) {
        var store = new WorkspaceThreadJournalStore(new LocalFilesystem(directory));
        var log = store.open(threadKey, rc);
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

    @ParameterizedTest
    @ValueSource(strings = {"search", "history"})
    void oversizedNameKeepsExactEscapedIdentityAndFullMessageRecoverable(String operation) {
        ThreadKey escapedKey =
                new ThreadKey("u", "agent\"\\\n\t", "session\"\\\n\t", "thread\"\\\n\t");
        String messageId = "message\"\\\n\t";
        String hugeName = "n".repeat(40_000);
        String fullBody = "needle full body " + "x".repeat(80_701);
        Msg oversized =
                Msg.builderForRole(MsgRole.USER)
                        .id(messageId)
                        .name(hugeName)
                        .content(TextBlock.builder().text(fullBody).build())
                        .build();
        Msg small =
                Msg.builderForRole(MsgRole.USER)
                        .id("small")
                        .content(TextBlock.builder().text("needle small").build())
                        .build();
        ThreadHistoryTool tool =
                seedMessages(
                        operation.equals("search")
                                ? List.of(oversized, small)
                                : List.of(small, oversized),
                        escapedKey);
        ToolResultBlock result =
                operation.equals("search")
                        ? tool.threadSearch(rc, "needle", escapedKey.agentId(), 20)
                        : tool.threadHistory(
                                rc,
                                escapedKey.agentId(),
                                escapedKey.sessionId(),
                                escapedKey.threadId(),
                                20);
        Map<String, Object> arguments = recoveryArguments(result);
        assertEquals(
                Map.of(
                        "agentId", escapedKey.agentId(),
                        "sessionId", escapedKey.sessionId(),
                        "threadId", escapedKey.threadId(),
                        "messageId", messageId),
                arguments,
                "use the real ThreadKey and message ID, not the caller's defaults");
        String error = assertText(result, ToolResultState.ERROR);
        assertFalse(error.contains("small"), "never skip the oversized candidate");
        assertFalse(error.contains("nextOffset"));
        assertFalse(error.contains("nextBeforeMessageId"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        Map<?, ?> full =
                JsonUtils.getJsonCodec()
                        .fromJson(invoke(toolkit, "thread_history", arguments), Map.class);
        Map<?, ?> message = (Map<?, ?>) full.get("message");
        assertEquals(messageId, message.get("id"));
        assertEquals(hugeName, message.get("name"));
        assertEquals(fullBody, ((Map<?, ?>) ((List<?>) message.get("content")).get(0)).get("text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "history"})
    void oversizedNameDoesNotAdvanceThePageStoppingPoint(String operation) {
        Msg oversized =
                Msg.builderForRole(MsgRole.USER)
                        .id("oversized")
                        .name("n".repeat(40_000))
                        .content(TextBlock.builder().text("needle full message").build())
                        .build();
        Msg small =
                Msg.builderForRole(MsgRole.USER)
                        .id("small")
                        .content(TextBlock.builder().text("needle small").build())
                        .build();
        ThreadHistoryTool tool =
                seedMessages(
                        operation.equals("search")
                                ? List.of(small, oversized)
                                : List.of(oversized, small));
        ToolResultBlock result;
        if (operation.equals("search")) {
            List<?> page =
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    assertText(
                                            tool.threadSearch(rc, "needle", "agent-a", 20),
                                            ToolResultState.SUCCESS),
                                    List.class);
            assertEquals(1, page.size());
            Map<?, ?> last = (Map<?, ?>) page.get(0);
            assertEquals("small", last.get("messageId"));
            assertEquals(1, ((Number) last.get("nextOffset")).intValue());
            result = tool.threadSearch(rc, "needle", "agent-a", 20, null, 1);
        } else {
            Map<?, ?> page =
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    assertText(
                                            tool.threadHistory(
                                                    rc, "agent-a", "session-1", null, 20),
                                            ToolResultState.SUCCESS),
                                    Map.class);
            assertEquals(1, ((List<?>) page.get("messages")).size());
            assertEquals("small", page.get("nextBeforeMessageId"));
            assertEquals(true, page.get("hasMore"));
            result = tool.threadHistory(rc, "agent-a", "session-1", null, 20, null, "small");
        }
        Map<String, Object> arguments = recoveryArguments(result);
        assertEquals("oversized", arguments.get("messageId"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        assertTrue(invoke(toolkit, "thread_history", arguments).contains("needle full message"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "history"})
    void oversizedRecoveryIdentityFallsBackWithoutTruncatingIds(String operation) {
        // The escaped argument JSON fits 12,000 chars, but escaping it again inside an error
        // TextBlock does not. Raw character counts must not accidentally admit this identity.
        String escapedId = "\"\\\n".repeat(1_500);
        for (String messageId : List.of("m".repeat(12_001), escapedId)) {
            Msg oversized =
                    Msg.builderForRole(MsgRole.USER)
                            .id(messageId)
                            .name("n".repeat(40_000))
                            .content(TextBlock.builder().text("needle full message").build())
                            .build();
            ThreadKey target =
                    new ThreadKey(
                            "u",
                            messageId.equals(escapedId) ? "agent-escaped" : "agent-long",
                            "session-1",
                            "main");
            ThreadHistoryTool tool = seedMessages(List.of(oversized), target);
            ToolResultBlock result =
                    operation.equals("search")
                            ? tool.threadSearch(rc, "needle", target.agentId(), 20)
                            : tool.threadHistory(
                                    rc, target.agentId(), target.sessionId(), null, 20);
            assertEquals(
                    "Error: Thread or message identity exceeds the result budget; read a known"
                            + " message directly with thread_history messageId.",
                    assertText(result, ToolResultState.ERROR));
            assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
            assertTrue(JsonUtils.getJsonCodec().toJson(result).length() <= 12_000);
            if (messageId.equals(escapedId)) {
                String arguments =
                        JsonUtils.getJsonCodec()
                                .toJson(
                                        Map.of(
                                                "agentId", key.agentId(),
                                                "sessionId", key.sessionId(),
                                                "threadId", key.threadId(),
                                                "messageId", messageId));
                assertTrue(arguments.length() < 12_000);
                assertTrue(
                        JsonUtils.getJsonCodec().toJson(ToolResultBlock.error(arguments)).length()
                                > 12_000);
            }
        }
    }

    @Test
    void oversizedThreadListIdentityKeepsGenericBudgetedError() {
        var store = mock(io.agentscope.core.journal.ThreadJournalStore.class);
        when(store.list(rc))
                .thenReturn(
                        List.of(new ThreadKey("u", "agent-a", "\"\\\n".repeat(10_000), "main")));
        ToolResultBlock result = new ThreadHistoryTool(store).threadList(rc, "agent-a");
        assertTrue(assertText(result, ToolResultState.ERROR).contains("identity exceeds"));
        assertFalse(assertText(result, ToolResultState.ERROR).contains("arguments:"));
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
        assertTrue(JsonUtils.getJsonCodec().toJson(result).length() <= 12_000);
    }

    private Map<String, Object> recoveryArguments(ToolResultBlock result) {
        String text = assertText(result, ToolResultState.ERROR);
        assertErrorKind(result, ToolErrorKind.INVALID_ARGUMENT, null);
        assertTrue(JsonUtils.getJsonCodec().toJson(result).length() <= 12_000);
        String marker = "thread_history arguments: ";
        assertTrue(text.contains(marker), "error must include executable exact-read arguments");
        Map<?, ?> arguments =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                text.substring(text.indexOf(marker) + marker.length()), Map.class);
        assertEquals(4, arguments.size());
        return Map.of(
                "agentId", arguments.get("agentId"),
                "sessionId", arguments.get("sessionId"),
                "threadId", arguments.get("threadId"),
                "messageId", arguments.get("messageId"));
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
        assertTrue(
                out.contains("thread_history(agentId="), "preview points at the original message");
        assertTrue(out.contains("messageId="), "recovery includes the exact message ID");
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

    @ParameterizedTest
    @ValueSource(ints = {0, 5_000})
    void searchPagesRecoverEveryMatchAcrossLimitAndEscapedBudget(int fillerSize) {
        var messages = new ArrayList<Msg>();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 80; i++) {
            String id = "m" + i;
            boolean hit = i % 3 != 0;
            messages.add(
                    Msg.builderForRole(MsgRole.USER)
                            .id(id)
                            .content(
                                    TextBlock.builder()
                                            .text(
                                                    (hit ? "needle " : "other ")
                                                            + "\"\\\n".repeat(fillerSize))
                                            .build())
                            .build());
            if (hit) expected.add(id);
        }
        ThreadHistoryTool tool = seedMessages(messages);
        var actual = new ArrayList<String>();
        int offset = 0;
        int pages = 0;
        while (true) {
            String json =
                    assertText(
                            tool.threadSearch(
                                    rc,
                                    "needle",
                                    "agent-a",
                                    fillerSize == 0 ? 7 : 100,
                                    null,
                                    offset),
                            ToolResultState.SUCCESS);
            assertTrue(json.length() <= ThreadHistoryTool.MAX_RESULT_CHARS);
            List<?> page = JsonUtils.getJsonCodec().fromJson(json, List.class);
            assertFalse(page.isEmpty());
            for (Object entry : page) {
                Map<?, ?> match = (Map<?, ?>) entry;
                assertTrue(match.containsKey("messageId"), "metadata must not count as a match");
                actual.add((String) match.get("messageId"));
            }
            Map<?, ?> last = (Map<?, ?>) page.get(page.size() - 1);
            pages++;
            if (!last.containsKey("nextOffset")) break;
            int next = ((Number) last.get("nextOffset")).intValue();
            assertEquals(offset + page.size(), next, "resume at the rejected candidate");
            offset = next;
            assertTrue(pages < 80);
        }
        assertTrue(pages > 1);
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5_000})
    void historyPagesRecoverEveryOriginalIdWithoutCrossingBudgetRejectedMessages(int fillerSize) {
        var messages = new ArrayList<Msg>();
        var expected = new ArrayList<String>();
        for (int i = 0; i < 63; i++) {
            String id = "m\"\\" + i;
            expected.add(id);
            messages.add(
                    Msg.builderForRole(MsgRole.USER)
                            .id(id)
                            .content(
                                    TextBlock.builder()
                                            .text("\"\\\n".repeat(fillerSize) + i)
                                            .build())
                            .build());
        }
        ThreadHistoryTool tool = seedMessages(messages);
        String anchor = null;
        var actual = new ArrayList<String>();
        int pages = 0;
        while (true) {
            String json =
                    assertText(
                            tool.threadHistory(
                                    rc,
                                    "agent-a",
                                    "session-1",
                                    null,
                                    fillerSize == 0 ? 7 : 100,
                                    null,
                                    anchor),
                            ToolResultState.SUCCESS);
            assertTrue(json.length() <= ThreadHistoryTool.MAX_RESULT_CHARS);
            Map<?, ?> page = JsonUtils.getJsonCodec().fromJson(json, Map.class);
            List<?> returned = (List<?>) page.get("messages");
            assertFalse(returned.isEmpty());
            var ids = new ArrayList<String>();
            for (Object entry : returned) ids.add((String) ((Map<?, ?>) entry).get("id"));
            actual.addAll(0, ids);
            anchor = (String) page.get("nextBeforeMessageId");
            assertEquals(ids.get(0), anchor);
            pages++;
            if (Boolean.FALSE.equals(page.get("hasMore"))) break;
            assertTrue(pages < 64);
        }
        assertTrue(pages > 1);
        assertEquals(expected, actual);
        Map<?, ?> empty =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                assertText(
                                        tool.threadHistory(
                                                rc, "agent-a", "session-1", null, 20, null, anchor),
                                        ToolResultState.SUCCESS),
                                Map.class);
        assertTrue(((List<?>) empty.get("messages")).isEmpty());
        assertEquals(false, empty.get("hasMore"));
    }

    @Test
    void listPagesAreDeterministicAndResumeAtTheBudgetRejectedEntry() {
        var keys = new ArrayList<ThreadKey>();
        for (int i = 0; i < 2_000; i++) {
            keys.add(
                    new ThreadKey(
                            "u", "agent-a", String.format("session-%04d", i) + "\"\\", "main"));
        }
        var reversed = new ArrayList<>(keys);
        Collections.reverse(reversed);
        var store = mock(io.agentscope.core.journal.ThreadJournalStore.class);
        when(store.list(rc)).thenReturn(reversed, keys, reversed, keys);
        var tool = new ThreadHistoryTool(store);
        var actual = new ArrayList<String>();
        int offset = 0;
        int pages = 0;
        while (true) {
            String json =
                    assertText(tool.threadList(rc, "agent-a", offset), ToolResultState.SUCCESS);
            assertTrue(json.length() <= ThreadHistoryTool.MAX_RESULT_CHARS);
            List<?> page = JsonUtils.getJsonCodec().fromJson(json, List.class);
            Map<?, ?> last = (Map<?, ?>) page.get(page.size() - 1);
            boolean more = last.containsKey("nextOffset");
            int count = page.size() - (more ? 1 : 0);
            for (int i = 0; i < count; i++) {
                actual.add((String) ((Map<?, ?>) page.get(i)).get("sessionId"));
            }
            pages++;
            if (!more) break;
            int next = ((Number) last.get("nextOffset")).intValue();
            assertEquals(offset + count, next);
            assertEquals(count, ((Number) last.get("returned")).intValue());
            offset = next;
            assertTrue(pages < 20);
        }
        assertTrue(pages > 1);
        assertEquals(keys.stream().map(ThreadKey::sessionId).toList(), actual);
    }

    @Test
    void everyPageRechecksCurrentNamespaceInsteadOfReconstructingIdentity() {
        var backend = new InMemoryStore();
        var store =
                new WorkspaceThreadJournalStore(
                        new RemoteFilesystem(
                                backend,
                                context ->
                                        List.of(
                                                "thread-pages",
                                                context.get("tenant", String.class))));
        RuntimeContext first = RuntimeContext.builder(rc).put("tenant", "first").build();
        RuntimeContext other = RuntimeContext.builder(rc).put("tenant", "other").build();
        var log = store.open(key, first);
        var writer = log.acquire("namespace-pages", Duration.ofMinutes(2));
        var events = new ArrayList<JournalEvent>();
        for (int i = 1; i <= 2; i++) {
            Msg message =
                    Msg.builderForRole(MsgRole.USER)
                            .id("private-" + i)
                            .content(TextBlock.builder().text("needle private " + i).build())
                            .build();
            events.add(
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            message.getId(),
                            i,
                            i,
                            "message/user",
                            "run",
                            "turn",
                            true,
                            JsonUtils.getJsonCodec().toJson(Map.of("message", message))));
        }
        log.commit(writer, "namespace-pages", 0, events);
        log.release(writer);
        var tool = new ThreadHistoryTool(store);
        String page =
                assertText(
                        tool.threadSearch(first, "needle", "agent-a", 1, null, 0),
                        ToolResultState.SUCCESS);
        assertTrue(page.contains("\"nextOffset\":1"));
        assertEquals(
                "[]",
                assertText(
                        tool.threadSearch(other, "needle", "agent-a", 1, null, 1),
                        ToolResultState.SUCCESS));
        assertEquals(
                "[]", assertText(tool.threadList(other, "agent-a", 0), ToolResultState.SUCCESS));
        assertErrorKind(
                tool.threadHistory(other, "agent-a", "session-1", null, 20, null, "private-2"),
                ToolErrorKind.NOT_FOUND,
                "agent-a/session-1/main");
        assertTrue(
                assertText(
                                tool.threadHistory(
                                        first, "agent-a", "session-1", null, 20, null, "private-2"),
                                ToolResultState.SUCCESS)
                        .contains("needle private 1"));
    }

    @Test
    void invalidOffsetsAndAnchorsKeepExistingErrorCategories() {
        ThreadHistoryTool tool = seedMessages(3);
        assertErrorKind(
                tool.threadSearch(rc, "needle", "agent-a", 10, null, -1),
                ToolErrorKind.INVALID_ARGUMENT,
                null);
        assertErrorKind(tool.threadList(rc, "agent-a", -1), ToolErrorKind.INVALID_ARGUMENT, null);
        assertErrorKind(
                tool.threadHistory(rc, "agent-a", "session-1", null, 20, null, " "),
                ToolErrorKind.INVALID_ARGUMENT,
                null);
        assertErrorKind(
                tool.threadHistory(rc, "agent-a", "session-1", null, 20, "m1", "m2"),
                ToolErrorKind.INVALID_ARGUMENT,
                null);
        assertErrorKind(
                tool.threadHistory(rc, "agent-a", "session-1", null, 20, null, "missing"),
                ToolErrorKind.NOT_FOUND,
                "agent-a/session-1/main");
        assertEquals(
                "[]",
                assertText(
                        tool.threadSearch(rc, "needle", "agent-a", 10, null, 3),
                        ToolResultState.SUCCESS));
    }

    @Test
    void uniqueSchemasExposeOptionalPaginationAndOldJavaEntrypointsStillWork() {
        ThreadHistoryTool tool = seedMessages(3);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        for (String name : List.of("thread_search", "thread_list", "thread_history")) {
            assertEquals(
                    1,
                    Arrays.stream(ThreadHistoryTool.class.getDeclaredMethods())
                            .filter(
                                    m ->
                                            m.isAnnotationPresent(Tool.class)
                                                    && m.getAnnotation(Tool.class)
                                                            .name()
                                                            .equals(name))
                            .count());
            var schemas =
                    toolkit.getToolSchemas().stream()
                            .filter(s -> s.getName().equals(name))
                            .toList();
            assertEquals(1, schemas.size());
            String parameter = name.equals("thread_history") ? "beforeMessageId" : "offset";
            assertTrue(
                    ((Map<?, ?>) schemas.get(0).getParameters().get("properties"))
                            .containsKey(parameter));
            assertFalse(
                    ((List<?>) schemas.get(0).getParameters().get("required")).contains(parameter));
        }
        assertEquals(
                assertText(tool.threadSearch(rc, "needle", "agent-a", 10), ToolResultState.SUCCESS),
                invoke(
                        toolkit,
                        "thread_search",
                        Map.of("query", "needle", "agentId", "agent-a", "maxResults", 10)));
        assertEquals(
                assertText(tool.threadList(rc, "agent-a"), ToolResultState.SUCCESS),
                invoke(toolkit, "thread_list", Map.of("agentId", "agent-a", "offset", 0)));
        assertEquals(
                assertText(
                        tool.threadHistory(rc, "agent-a", "session-1", null, 20),
                        ToolResultState.SUCCESS),
                invoke(
                        toolkit,
                        "thread_history",
                        Map.of("agentId", "agent-a", "sessionId", "session-1")));
        String older =
                invoke(
                        toolkit,
                        "thread_history",
                        Map.of(
                                "agentId",
                                "agent-a",
                                "sessionId",
                                "session-1",
                                "beforeMessageId",
                                "m3"));
        assertFalse(older.contains("needle item 3"));
        assertTrue(older.contains("needle item 2"));
        String later = invoke(toolkit, "thread_search", Map.of("query", "needle", "offset", 1));
        assertFalse(later.contains("needle item 1"));
    }

    private String invoke(Toolkit toolkit, String name, Map<String, Object> input) {
        return assertText(
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("page-test")
                                                        .name(name)
                                                        .input(input)
                                                        .build())
                                        .input(input)
                                        .runtimeContext(rc)
                                        .build())
                        .block(Duration.ofSeconds(10)),
                ToolResultState.SUCCESS);
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
