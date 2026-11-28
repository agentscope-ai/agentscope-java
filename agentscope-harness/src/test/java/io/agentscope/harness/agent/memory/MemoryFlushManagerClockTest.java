/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.workspace.WorkspaceConstants;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * The daily memory ledger follows the {@link Clock} supplied in the {@link RuntimeContext}: both
 * the ledger file a flush is filed under and the timestamp heading its entry (#3433).
 *
 * <p>The time source is the request's {@link Clock}, whose zone is the ledger's zone — the same
 * key the durable runtime uses to restore a turn's zone. A deployment whose host runs in one zone
 * but whose users think in another supplies a zoned clock; nothing else needs configuring.
 */
class MemoryFlushManagerClockTest {

    @TempDir Path workspace;

    /** 22:30 UTC on 1 March: still 1 March in UTC, already 2 March at UTC+09:00. */
    private static final Instant AT = Instant.parse("2026-03-01T22:30:00Z");

    private static final class FixedModel implements Model {
        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("user likes tea").build()))
                            .build());
        }
    }

    private static RuntimeContext context(Clock clock) {
        RuntimeContext.Builder builder = RuntimeContext.builder().sessionId("session-1");
        return clock == null ? builder.build() : builder.put(Clock.class, clock).build();
    }

    /** Flushes one real message and returns every file written under the workspace. */
    private List<Path> flush(RuntimeContext rc) throws Exception {
        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            new MemoryFlushManager(workspaceManager, new FixedModel())
                    .flushMemories(
                            rc,
                            List.of(
                                    Msg.builder()
                                            .role(MsgRole.USER)
                                            .textContent("I like tea")
                                            .build()))
                    .block();
        }
        try (Stream<Path> files = Files.walk(workspace)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static String ledgerContent(Path workspace, String fileName) throws Exception {
        return Files.readString(workspace.resolve(WorkspaceConstants.MEMORY_DIR).resolve(fileName));
    }

    @Test
    void ledgerIsFiledUnderTheDateOfTheClockZone() throws Exception {
        RuntimeContext rc = context(Clock.fixed(AT, ZoneId.of("Asia/Tokyo")));

        List<Path> files = flush(rc);

        List<String> relative =
                files.stream()
                        .map(f -> workspace.relativize(f).toString().replace('\\', '/'))
                        .toList();
        assertEquals(
                List.of(WorkspaceConstants.MEMORY_DIR + "/2026-03-02.md"),
                relative,
                "22:30 UTC is already 2 March at UTC+09:00, so the ledger belongs to that day");
        assertTrue(ledgerContent(workspace, "2026-03-02.md").contains("user likes tea"));
    }

    @Test
    void entryTimestampIsRenderedInTheClockZone() throws Exception {
        RuntimeContext rc = context(Clock.fixed(AT, ZoneId.of("Asia/Tokyo")));

        flush(rc);

        String ledger = ledgerContent(workspace, "2026-03-02.md");
        assertTrue(
                ledger.contains("## Memory Flush — 2026-03-02T07:30:00+09:00"),
                "the heading must state the flush time in the ledger's zone, not as a UTC "
                        + "instant: "
                        + ledger);
        assertTrue(
                !ledger.contains("2026-03-01T22:30"),
                "a UTC instant next to a UTC+09:00 ledger date reads as the previous day: "
                        + ledger);
    }

    @Test
    void aClockInAnotherZoneFilesUnderItsOwnLocalDate() throws Exception {
        // 22:30 UTC on 1 March is still 1 March at UTC-08:00.
        RuntimeContext rc = context(Clock.fixed(AT, ZoneOffset.ofHours(-8)));

        List<Path> files = flush(rc);

        assertEquals(
                List.of(WorkspaceConstants.MEMORY_DIR + "/2026-03-01.md"),
                files.stream()
                        .map(f -> workspace.relativize(f).toString().replace('\\', '/'))
                        .toList());
    }

    @Test
    void consecutiveFlushesInTheSameZoneAccumulateInOneLedger() throws Exception {
        // The flush reads the day's existing ledger with the same clock it writes with, so a
        // second flush near a date boundary must not land in the other day's file.
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        FixedModel model = new FixedModel();
        try (WorkspaceManager workspaceManager = new WorkspaceManager(workspace)) {
            MemoryFlushManager flushManager = new MemoryFlushManager(workspaceManager, model);
            flushManager
                    .flushMemories(
                            context(Clock.fixed(AT.minusSeconds(600), tokyo)),
                            List.of(Msg.builder().role(MsgRole.USER).textContent("first").build()))
                    .block();
            flushManager
                    .flushMemories(
                            context(Clock.fixed(AT, tokyo)),
                            List.of(Msg.builder().role(MsgRole.USER).textContent("second").build()))
                    .block();
        }

        assertEquals(1, flushCount(), "both flushes belong to 2 March in Tokyo");
        String ledger = ledgerContent(workspace, "2026-03-02.md");
        assertTrue(ledger.contains("user likes tea"));
        assertEquals(
                2,
                ledger.split("## Memory Flush — ", -1).length - 1,
                "both entries must be in the single ledger: " + ledger);
    }

    private long flushCount() throws Exception {
        try (Stream<Path> files = Files.walk(workspace)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void withoutASuppliedClockTheSystemZoneIsUsed() throws Exception {
        // No Clock in the context: the ledger follows the JVM default zone, as before the clock
        // became injectable.
        RuntimeContext rc = context(null);

        List<Path> files = flush(rc);

        String today = java.time.LocalDate.now().toString();
        assertEquals(
                List.of(WorkspaceConstants.MEMORY_DIR + "/" + today + ".md"),
                files.stream()
                        .map(f -> workspace.relativize(f).toString().replace('\\', '/'))
                        .toList());
    }
}
