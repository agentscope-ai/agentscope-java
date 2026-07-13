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
package io.agentscope.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link AgentEventType} and the event-stream emitted by
 * {@code ReActAgent.replyStream(...)}.
 *
 * <p>The {@link JsonAliasRoundTrip} suite exercises the legacy-name aliases
 * declared on {@link AgentEventType}.
 *
 * <p>The {@link StreamOrdering} suite is {@link Disabled} until the Agent
 * re-design is complete. It documents the canonical event sequence so the
 * implementation can drop in assertions without re-deriving it.
 */
class AgentEventStreamTest {

    @Nested
    @DisplayName("Legacy event-name aliases round-trip via Jackson")
    class JsonAliasRoundTrip {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("RUN_STARTED deserializes to AGENT_START and re-serializes as AGENT_START")
        void runStartedAlias() throws Exception {
            AgentEventType parsed = mapper.readValue("\"RUN_STARTED\"", AgentEventType.class);
            assertEquals(AgentEventType.AGENT_START, parsed);
            assertEquals("\"AGENT_START\"", mapper.writeValueAsString(parsed));
        }

        @Test
        @DisplayName("RUN_FINISHED → AGENT_END")
        void runFinishedAlias() throws Exception {
            AgentEventType parsed = mapper.readValue("\"RUN_FINISHED\"", AgentEventType.class);
            assertEquals(AgentEventType.AGENT_END, parsed);
            assertEquals("\"AGENT_END\"", mapper.writeValueAsString(parsed));
        }

        @Test
        @DisplayName("MODEL_CALL_STARTED / MODEL_CALL_ENDED aliases")
        void modelCallAliases() throws Exception {
            assertEquals(
                    AgentEventType.MODEL_CALL_START,
                    mapper.readValue("\"MODEL_CALL_STARTED\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.MODEL_CALL_END,
                    mapper.readValue("\"MODEL_CALL_ENDED\"", AgentEventType.class));
        }

        @Test
        @DisplayName("BINARY_BLOCK_* aliases map to DATA_BLOCK_*")
        void binaryBlockAliases() throws Exception {
            assertEquals(
                    AgentEventType.DATA_BLOCK_START,
                    mapper.readValue("\"BINARY_BLOCK_START\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.DATA_BLOCK_DELTA,
                    mapper.readValue("\"BINARY_BLOCK_DELTA\"", AgentEventType.class));
            assertEquals(
                    AgentEventType.DATA_BLOCK_END,
                    mapper.readValue("\"BINARY_BLOCK_END\"", AgentEventType.class));
        }

        @Test
        @DisplayName("TOOL_RESULT_BINARY_DELTA → TOOL_RESULT_DATA_DELTA")
        void toolResultBinaryDeltaAlias() throws Exception {
            assertEquals(
                    AgentEventType.TOOL_RESULT_DATA_DELTA,
                    mapper.readValue("\"TOOL_RESULT_BINARY_DELTA\"", AgentEventType.class));
        }

        @Test
        @DisplayName("Canonical names round-trip unchanged")
        void javaNativeNamesRoundTrip() throws Exception {
            for (AgentEventType type : AgentEventType.values()) {
                String json = mapper.writeValueAsString(type);
                assertEquals("\"" + type.getValue() + "\"", json);
                assertEquals(type, mapper.readValue(json, AgentEventType.class));
            }
        }

        @Test
        @DisplayName("fromValue rejects unknown strings")
        void fromValueRejectsUnknown() {
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> AgentEventType.fromValue("NOT_A_TYPE"));
            assertTrue(ex.getMessage().contains("Unknown AgentEventType value"));
        }

        @Test
        @DisplayName("fromValue rejects null")
        void fromValueRejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> AgentEventType.fromValue(null));
        }
    }

    @Nested
    @DisplayName("AgentEventType enumeration covers the full event surface")
    class EnumCoverage {

        @Test
        @DisplayName("All 25 supported event names resolve to a Java enum constant")
        void allPythonValuesResolve() {
            // Canonical and legacy event-name surface:
            String[] pythonValues = {
                "RUN_STARTED",
                "RUN_FINISHED",
                "MODEL_CALL_STARTED",
                "MODEL_CALL_ENDED",
                "TEXT_BLOCK_START",
                "TEXT_BLOCK_DELTA",
                "TEXT_BLOCK_END",
                "THINKING_BLOCK_START",
                "THINKING_BLOCK_DELTA",
                "THINKING_BLOCK_END",
                "BINARY_BLOCK_START",
                "BINARY_BLOCK_DELTA",
                "BINARY_BLOCK_END",
                "TOOL_CALL_START",
                "TOOL_CALL_DELTA",
                "TOOL_CALL_END",
                "TOOL_RESULT_START",
                "TOOL_RESULT_TEXT_DELTA",
                "TOOL_RESULT_BINARY_DELTA",
                "TOOL_RESULT_END",
                "EXCEED_MAX_ITERS",
                "REQUIRE_USER_CONFIRM",
                "REQUIRE_EXTERNAL_EXECUTION",
                "USER_CONFIRM_RESULT",
                "EXTERNAL_EXECUTION_RESULT",
            };
            for (String name : pythonValues) {
                AgentEventType resolved = AgentEventType.fromValue(name);
                assertNotNull(resolved, "EventType " + name + " must resolve");
            }
        }
    }

    @Nested
    @DisplayName("Compaction events serialize and deserialize correctly")
    class CompactionEventRoundTrip {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("CompactionStartEvent round-trips through AgentEvent polymorphism")
        void compactionStartRoundTrip() throws Exception {
            CompactionStartEvent original =
                    new CompactionStartEvent(
                            CompactionStartEvent.TriggerReason.TOKEN_THRESHOLD, 80000, 42000, 12);
            String json = mapper.writeValueAsString(original);

            assertTrue(json.contains("\"type\":\"COMPACTION_START\""));
            assertTrue(json.contains("\"triggerReason\":\"TOKEN_THRESHOLD\""));
            assertTrue(json.contains("\"thresholdValue\":80000"));
            assertTrue(json.contains("\"estimatedTokens\":42000"));
            assertTrue(json.contains("\"messageCount\":12"));

            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertNotNull(deserialized);
            assertEquals(AgentEventType.COMPACTION_START, deserialized.getType());
            CompactionStartEvent start = (CompactionStartEvent) deserialized;
            assertEquals(
                    CompactionStartEvent.TriggerReason.TOKEN_THRESHOLD, start.getTriggerReason());
            assertEquals(80000, start.getThresholdValue());
            assertEquals(42000, start.getEstimatedTokens());
            assertEquals(12, start.getMessageCount());
            assertEquals(original.getId(), start.getId());
        }

        @Test
        @DisplayName("CompactionStartEvent accepts legacy triggerThreshold JSON alias")
        void compactionStartLegacyAlias() throws Exception {
            String legacyJson =
                    "{\"type\":\"COMPACTION_START\","
                            + "\"id\":\"abc\",\"createdAt\":\"2026-07-13T00:00:00Z\","
                            + "\"estimatedTokens\":100,\"triggerThreshold\":200}";
            AgentEvent deserialized = mapper.readValue(legacyJson, AgentEvent.class);
            CompactionStartEvent start = (CompactionStartEvent) deserialized;
            assertEquals(200, start.getThresholdValue());
            assertEquals(100, start.getEstimatedTokens());
        }

        @Test
        @DisplayName("CompactionEndEvent round-trips through AgentEvent polymorphism")
        void compactionEndRoundTrip() throws Exception {
            CompactionEndEvent original =
                    new CompactionEndEvent(
                            CompactionEndEvent.Outcome.COMPACTED, 20, 5, 20000, 5000);
            String json = mapper.writeValueAsString(original);

            assertTrue(json.contains("\"type\":\"COMPACTION_END\""));
            assertTrue(json.contains("\"outcome\":\"COMPACTED\""));
            assertTrue(json.contains("\"originalMessageCount\":20"));
            assertTrue(json.contains("\"compactedMessageCount\":5"));
            assertTrue(json.contains("\"beforeTokens\":20000"));
            assertTrue(json.contains("\"afterTokens\":5000"));

            AgentEvent deserialized = mapper.readValue(json, AgentEvent.class);
            assertNotNull(deserialized);
            assertEquals(AgentEventType.COMPACTION_END, deserialized.getType());
            CompactionEndEvent end = (CompactionEndEvent) deserialized;
            assertEquals(CompactionEndEvent.Outcome.COMPACTED, end.getOutcome());
            assertEquals(20, end.getOriginalMessageCount());
            assertEquals(5, end.getCompactedMessageCount());
            assertEquals(20000, end.getBeforeTokens());
            assertEquals(5000, end.getAfterTokens());
            assertEquals(15000, end.getEstimatedTokensSaved());
            assertEquals(original.getId(), end.getId());
        }

        @Test
        @DisplayName("COMPACTION_START and COMPACTION_END are in AgentEventType enum")
        void compactionTypesRegistered() {
            assertEquals(
                    AgentEventType.COMPACTION_START, AgentEventType.fromValue("COMPACTION_START"));
            assertEquals(AgentEventType.COMPACTION_END, AgentEventType.fromValue("COMPACTION_END"));
        }
    }

    @Nested
    @DisplayName("replyStream emits the canonical event order")
    @Disabled("Stage 7 lands the new Agent main class; this suite locks the stream contract.")
    class StreamOrdering {

        @Test
        @DisplayName(
                "Single-iteration reply: AGENT_START → MODEL_CALL_* → TEXT_BLOCK_* → AGENT_END")
        void singleIterationOrder() {
            // GIVEN Agent.builder().model(...).build()
            //   AND user message "hello"
            // WHEN  agent.streamEvents(userMsg).collectList().block()
            // THEN  emitted event types in order are:
            //         AGENT_START,
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TEXT_BLOCK_START, TEXT_BLOCK_DELTA(*), TEXT_BLOCK_END,
            //         AGENT_END
        }

        @Test
        @DisplayName("Thinking model emits THINKING_BLOCK_* before TEXT_BLOCK_*")
        void thinkingBeforeText() {
            // GIVEN a thinking-capable model
            // WHEN  agent.replyStream(userMsg)
            // THEN  THINKING_BLOCK_START/DELTA/END precede TEXT_BLOCK_START
        }

        @Test
        @DisplayName("Tool call cycle: TOOL_CALL_* → TOOL_RESULT_* → second MODEL_CALL_*")
        void toolCallCycle() {
            // GIVEN tool-enabled agent + user prompt that triggers a tool
            // WHEN  replyStream(...)
            // THEN  ordering contains:
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TOOL_CALL_START, TOOL_CALL_DELTA(*), TOOL_CALL_END,
            //         TOOL_RESULT_START, TOOL_RESULT_TEXT_DELTA(*), TOOL_RESULT_END,
            //         MODEL_CALL_START, MODEL_CALL_END,
            //         TEXT_BLOCK_*, AGENT_END
        }

        @Test
        @DisplayName(
                "HITL: TOOL_CALL_END → REQUIRE_USER_CONFIRM → (await sink) → USER_CONFIRM_RESULT →"
                        + " TOOL_RESULT_*")
        void hitlReentry() {
            // GIVEN tool with checkPermissions returning ASK
            //   AND HitlContextKey.KEY bound to a Sinks.Many<HitlResponse>
            // WHEN  replyStream(...)
            // THEN  REQUIRE_USER_CONFIRM emitted; stream pauses
            //   WHEN  sink.tryEmitNext(allow)
            //   THEN  USER_CONFIRM_RESULT emitted, followed by TOOL_RESULT_*
            // See docs/v2-design/RFC-002-event-stream-hitl.md
        }

        @Test
        @DisplayName("DataBlock turn: DATA_BLOCK_* fired for image/audio/video output")
        void dataBlockEmission() {
            // GIVEN model returning an image block
            // WHEN  replyStream(...)
            // THEN  DATA_BLOCK_START, DATA_BLOCK_DELTA(*), DATA_BLOCK_END emitted in order
        }

        @Test
        @DisplayName("Max-iters guard emits EXCEED_MAX_ITERS before AGENT_END")
        void exceedMaxIters() {
            // GIVEN agent with maxIters=1 and a model that keeps requesting tools
            // WHEN  streamEvents(...)
            // THEN  EXCEED_MAX_ITERS emitted, then AGENT_END
        }

        @Test
        @DisplayName("Every stream begins with AGENT_START and ends with AGENT_END")
        void streamBoundaries() {
            // FOR ANY invocation: first event is AGENT_START, last is AGENT_END
        }
    }
}
