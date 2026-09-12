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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class MemoryFlushManagerTest {

    @TempDir Path workspace;

    @Test
    void flushMemories_skipsCompactionSummariesWhenRealMessagesRemain() {
        RecordingModel model = new RecordingModel();
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

            flushManager
                    .flushMemories(
                            rc,
                            List.of(
                                    compactionSummary("internal summary should not be flushed"),
                                    message(MsgRole.USER, "real follow-up")))
                    .block();
        }

        String userPrompt = model.userPrompt(0);
        assertFalse(userPrompt.contains("internal summary should not be flushed"));
        assertTrue(userPrompt.contains("real follow-up"));
    }

    @Test
    void flushMemories_summaryOnlyInputDoesNotCallModel() {
        RecordingModel model = new RecordingModel();
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);

            flushManager.flushMemories(rc, List.of(compactionSummary("internal only"))).block();
        }

        assertTrue(model.inputs.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // A/B tests for #3088 — memory flush header timestamp must be zone-aware.
    //
    // Group A pins the OLD behaviour (Instant.toString() always renders UTC, ignoring
    // -Duser.timezone) as a control. Group B exercises the NEW behaviour (ZonedDateTime renders
    // the clock's UTC offset) across multiple zones.
    // ---------------------------------------------------------------------------------------------

    /** A (old behaviour): {@code Instant.toString()} is always UTC and ignores the JVM time zone. */
    @Test
    void a_oldBehavior_instantToStringAlwaysUtc() {
        Instant instant = Instant.parse("2026-09-10T08:05:32.309657400Z");
        assertEquals("2026-09-10T08:05:32.309657400Z", instant.toString());
    }

    /**
     * B (new behaviour): the rendered timestamp carries the clock zone's offset. {@code
     * ISO_OFFSET_DATE_TIME} strips trailing zeros from the fractional second (e.g. {@code
     * .309657400} -> {@code .3096574}); the offset and the parsed instant are unaffected.
     */
    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource({
        "UTC,              2026-09-10T08:05:32.309657400Z, 2026-09-10T08:05:32.3096574Z",
        "Asia/Shanghai,    2026-09-10T08:05:32.309657400Z, 2026-09-10T16:05:32.3096574+08:00",
        "America/New_York, 2026-09-10T08:05:32.309657400Z, 2026-09-10T04:05:32.3096574-04:00",
        "Asia/Shanghai,    2026-09-10T20:00:00Z,           2026-09-11T04:00:00+08:00"
    })
    void b_newBehavior_timestampRespectsZone(String zoneId, String instantStr, String expected) {
        Instant instant = Instant.parse(instantStr);
        Clock clock = Clock.fixed(instant, ZoneId.of(zoneId));

        String actual = MemoryFlushManager.formatTimestamp(clock);

        assertEquals(expected, actual);
        assertEquals(instant, OffsetDateTime.parse(actual).toInstant());
    }

    /** B (new behaviour): the production default honours the JVM default zone. */
    @Test
    @ResourceLock("timezone")
    void b_newBehavior_systemDefaultZoneIsUsed() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            String timestamp = MemoryFlushManager.formatTimestamp(Clock.systemDefaultZone());
            assertTrue(timestamp.endsWith("+08:00"), timestamp);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** B (new behaviour): file name date and header share the same zoned instant. */
    @Test
    void b_newBehavior_fileNameAndHeaderUseSameZone() {
        Instant instant = Instant.parse("2026-09-10T20:00:00Z");
        Clock clock = Clock.fixed(instant, ZoneId.of("Asia/Shanghai"));

        ZonedDateTime now = ZonedDateTime.now(clock);
        String headerTimestamp = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String fileNameDate = now.toLocalDate().toString();

        assertEquals("2026-09-11", fileNameDate);
        assertTrue(headerTimestamp.startsWith("2026-09-11T04:00:00+08:00"));
    }

    /** B (new behaviour) end-to-end: the flushed section and file name use the injected zone. */
    @Test
    void b_newBehavior_flushWritesZoneAwareHeaderAndMatchingFileName() throws Exception {
        Instant instant = Instant.parse("2026-09-10T20:00:00Z");
        Clock clock = Clock.fixed(instant, ZoneId.of("Asia/Shanghai"));
        RecordingModel model = new RecordingModel("- user prefers dark mode");
        RuntimeContext rc = RuntimeContext.builder().sessionId("session-1").build();

        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager =
                    new MemoryFlushManager(workspaceManager, model, null, clock);
            flushManager.flushMemories(rc, List.of(message(MsgRole.USER, "hi"))).block();
        }

        Path daily = workspace.resolve("memory/2026-09-11.md");
        assertTrue(Files.exists(daily), "daily file should be named after the zone's local date");
        String content = Files.readString(daily);
        assertTrue(
                content.contains("## Memory Flush — 2026-09-11T04:00:00+08:00"),
                "header should use the zone's offset: " + content);
        assertFalse(content.contains("2026-09-10T20:00:00Z"), content);
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private static Msg compactionSummary(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static String text(Msg message) {
        return ((TextBlock) message.getContent().get(0)).getText();
    }

    private static final class RecordingModel implements Model {

        private final List<List<Msg>> inputs = new ArrayList<>();
        private final String response;

        RecordingModel() {
            this("NO_REPLY");
        }

        RecordingModel(String response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .id("flush-response")
                            .content(List.of(TextBlock.builder().text(response).build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }

        private String userPrompt(int index) {
            assertEquals(2, inputs.get(index).size());
            return text(inputs.get(index).get(1));
        }
    }
}
