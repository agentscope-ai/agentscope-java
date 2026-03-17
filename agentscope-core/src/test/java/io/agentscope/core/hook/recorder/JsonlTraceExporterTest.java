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
package io.agentscope.core.hook.recorder;

import static io.agentscope.core.hook.HookEventType.PRE_CALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JsonlTraceExporterTest {

    @TempDir Path tempDir;

    @Test
    void writesJsonlLinesForEnabledEvents() throws Exception {
        Path output = tempDir.resolve("trace.jsonl");

        TestAgent agent = new TestAgent("agent-1", "TestAgent");
        Toolkit toolkit = new Toolkit();

        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-1")
                        .name("search")
                        .input(Map.of("q", "hello"))
                        .build();
        ToolResultBlock toolChunk = ToolResultBlock.text("chunk");
        ToolResultBlock toolResult = ToolResultBlock.text("done");

        GenerateOptions options = GenerateOptions.builder().build();

        Msg userMsg = textMsg(MsgRole.USER, "hello");
        Msg assistantMsg = textMsg(MsgRole.ASSISTANT, "world");

        Msg reasoningChunk = textMsg(MsgRole.ASSISTANT, "a");
        Msg reasoningAccum = textMsg(MsgRole.ASSISTANT, "ab");

        Msg summaryChunk = textMsg(MsgRole.ASSISTANT, "s1");
        Msg summaryAccum = textMsg(MsgRole.ASSISTANT, "s12");

        try (JsonlTraceExporter exporter =
                JsonlTraceExporter.builder(output)
                        .append(false)
                        .flushEveryLine(true)
                        .includeReasoningChunks(true)
                        .includeActingChunks(true)
                        .includeSummary(true)
                        .includeSummaryChunks(true)
                        .build()) {
            exporter.onEvent(new PreCallEvent(agent, List.of(userMsg))).block();
            exporter.onEvent(new PreReasoningEvent(agent, "mock-model", options, List.of(userMsg)))
                    .block();
            exporter.onEvent(
                            new ReasoningChunkEvent(
                                    agent, "mock-model", options, reasoningChunk, reasoningAccum))
                    .block();
            exporter.onEvent(new PostReasoningEvent(agent, "mock-model", options, assistantMsg))
                    .block();
            exporter.onEvent(new PreActingEvent(agent, toolkit, toolUse)).block();
            exporter.onEvent(new ActingChunkEvent(agent, toolkit, toolUse, toolChunk)).block();
            exporter.onEvent(new PostActingEvent(agent, toolkit, toolUse, toolResult)).block();
            exporter.onEvent(
                            new PreSummaryEvent(
                                    agent,
                                    "mock-model",
                                    options,
                                    List.of(userMsg, assistantMsg),
                                    10,
                                    10))
                    .block();
            exporter.onEvent(
                            new SummaryChunkEvent(
                                    agent, "mock-model", options, summaryChunk, summaryAccum))
                    .block();
            exporter.onEvent(new PostSummaryEvent(agent, "mock-model", options, assistantMsg))
                    .block();
            exporter.onEvent(new ErrorEvent(agent, new IllegalStateException("boom"))).block();
            exporter.onEvent(new PostCallEvent(agent, assistantMsg)).block();
        }

        List<Map<String, Object>> records = readAll(output);
        assertTrue(records.size() >= 11);

        Map<String, Object> preCall = records.get(0);
        assertEquals("PRE_CALL", preCall.get("event_type"));
        assertEquals("agent-1", preCall.get("agent_id"));
        assertEquals("TestAgent", preCall.get("agent_name"));
        assertNotNull(preCall.get("run_id"));
        assertEquals(0, ((Number) preCall.get("step_id")).intValue());

        assertTrue(containsKeyValue(records, "event_type", "PRE_REASONING"));
        assertTrue(containsKeyValue(records, "event_type", "POST_REASONING"));
        assertTrue(containsKeyValue(records, "event_type", "REASONING_CHUNK"));
        assertTrue(containsKeyValue(records, "event_type", "PRE_ACTING"));
        assertTrue(containsKeyValue(records, "event_type", "ACTING_CHUNK"));
        assertTrue(containsKeyValue(records, "event_type", "POST_ACTING"));
        assertTrue(containsKeyValue(records, "event_type", "PRE_SUMMARY"));
        assertTrue(containsKeyValue(records, "event_type", "SUMMARY_CHUNK"));
        assertTrue(containsKeyValue(records, "event_type", "POST_SUMMARY"));
        assertTrue(containsKeyValue(records, "event_type", "ERROR"));
        assertTrue(containsKeyValue(records, "event_type", "POST_CALL"));
    }

    @Test
    void filtersDisabledEvents() throws Exception {
        Path output = tempDir.resolve("filtered.jsonl");
        TestAgent agent = new TestAgent("agent-1", "TestAgent");

        try (JsonlTraceExporter exporter =
                JsonlTraceExporter.builder(output)
                        .append(false)
                        .flushEveryLine(true)
                        .enabledEvents(Set.of(PRE_CALL))
                        .build()) {
            exporter.onEvent(new PreCallEvent(agent, List.of(textMsg(MsgRole.USER, "hi")))).block();
            exporter.onEvent(new PostCallEvent(agent, textMsg(MsgRole.ASSISTANT, "bye"))).block();
        }

        List<Map<String, Object>> records = readAll(output);
        assertEquals(1, records.size());
        assertEquals("PRE_CALL", records.get(0).get("event_type"));
    }

    @Test
    void runIdChangesAcrossTurns() throws Exception {
        Path output = tempDir.resolve("runs.jsonl");
        TestAgent agent = new TestAgent("agent-1", "TestAgent");

        try (JsonlTraceExporter exporter =
                JsonlTraceExporter.builder(output).append(false).flushEveryLine(true).build()) {
            exporter.onEvent(new PreCallEvent(agent, List.of(textMsg(MsgRole.USER, "t1")))).block();
            exporter.onEvent(new PostCallEvent(agent, textMsg(MsgRole.ASSISTANT, "o1"))).block();
            exporter.onEvent(new PreCallEvent(agent, List.of(textMsg(MsgRole.USER, "t2")))).block();
        }

        List<Map<String, Object>> records = readAll(output);
        String run1 = (String) records.get(0).get("run_id");
        String run2 = (String) records.get(2).get("run_id");
        assertNotNull(run1);
        assertNotNull(run2);
        assertNotEquals(run1, run2);
    }

    @Test
    void failFastControlsErrorPropagation() throws Exception {
        Path output = tempDir.resolve("failfast.jsonl");
        TestAgent agent = new TestAgent("agent-1", "TestAgent");

        JsonlTraceExporter bestEffort =
                JsonlTraceExporter.builder(output)
                        .append(false)
                        .flushEveryLine(true)
                        .failFast(false)
                        .build();
        bestEffort.close();
        bestEffort.onEvent(new PreCallEvent(agent, List.of(textMsg(MsgRole.USER, "hi")))).block();

        JsonlTraceExporter failFast =
                JsonlTraceExporter.builder(output)
                        .append(true)
                        .flushEveryLine(true)
                        .failFast(true)
                        .build();
        failFast.close();
        assertThrows(
                RuntimeException.class,
                () ->
                        failFast.onEvent(
                                        new PreCallEvent(
                                                agent, List.of(textMsg(MsgRole.USER, "hi"))))
                                .block());
    }

    @Test
    void exportsOpenTelemetryIdsWhenAvailable() throws Exception {
        io.opentelemetry.api.trace.Span.setCurrent(
                new io.opentelemetry.api.trace.Span(
                        new io.opentelemetry.api.trace.SpanContext(true, "trace-abc", "span-xyz")));

        Path output = tempDir.resolve("otel.jsonl");
        TestAgent agent = new TestAgent("agent-1", "TestAgent");

        try (JsonlTraceExporter exporter =
                JsonlTraceExporter.builder(output).append(false).flushEveryLine(true).build()) {
            exporter.onEvent(new PreCallEvent(agent, List.of(textMsg(MsgRole.USER, "hi")))).block();
        }

        Map<String, Object> record = readAll(output).get(0);
        assertEquals("trace-abc", record.get("trace_id"));
        assertEquals("span-xyz", record.get("span_id"));
    }

    private static Msg textMsg(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    private static List<Map<String, Object>> readAll(Path output) throws IOException {
        List<String> lines = Files.readAllLines(output);
        return lines.stream()
                .filter(l -> l != null && !l.isBlank())
                .map(
                        l ->
                                JsonUtils.getJsonCodec()
                                        .fromJson(l, new TypeReference<Map<String, Object>>() {}))
                .toList();
    }

    private static boolean containsKeyValue(
            List<Map<String, Object>> records, String key, String value) {
        for (Map<String, Object> r : records) {
            Object v = r.get(key);
            if (value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    private static final class TestAgent implements Agent {
        private final String agentId;
        private final String name;

        private TestAgent(String agentId, String name) {
            this.agentId = agentId;
            this.name = name;
        }

        @Override
        public String getAgentId() {
            return agentId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void interrupt() {}

        @Override
        public void interrupt(Msg msg) {}

        @Override
        public Mono<Msg> call(Msg msg) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Flux<Event> stream(Msg msg, StreamOptions options) {
            return Flux.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
            return Flux.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Flux<Event> stream(Msg msg, StreamOptions options, Class<?> structuredModel) {
            return Flux.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
            return Flux.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Mono<Void> observe(Msg msg) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }

        @Override
        public Mono<Void> observe(List<Msg> msgs) {
            return Mono.error(new UnsupportedOperationException("not used"));
        }
    }
}
