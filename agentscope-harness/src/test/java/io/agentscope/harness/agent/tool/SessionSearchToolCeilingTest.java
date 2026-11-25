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

import static io.agentscope.harness.agent.tool.ToolResultAssertions.assertText;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.journal.WorkspaceThreadJournalStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
        var store = new WorkspaceThreadJournalStore(new LocalFilesystem(directory));
        var log = store.open(key, rc);
        var writer = log.acquire("owner", Duration.ofMinutes(2));
        var events = new ArrayList<JournalEvent>();
        for (int i = 1; i <= count; i++) {
            var message =
                    Msg.builder()
                            .id("m" + i)
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("needle item " + i).build())
                            .build();
            events.add(
                    new JournalEvent(
                            JournalEvent.SCHEMA_VERSION,
                            "m" + i,
                            i,
                            i,
                            "message/user",
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
}
