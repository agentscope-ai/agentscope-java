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

package io.agentscope.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.Snapshot;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventProjectionTest {
    private long sequence;

    @Test
    void lifecyclePayloadAndSnapshotMustBelongToTheirEnvelope() throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var events =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .path("resourceEvents")
                            .path("valid");
            var key = new SessionKey("tenant", "session");
            var projection = new EventProjection(key);
            projection.accept(DurableJson.convert(events.get(0), PublicEvent.class));
            var original = projection.value();
            var changed = ((ObjectNode) events.get(2)).deepCopy();
            changed.put("sequence", "2");
            ((ObjectNode) changed.path("data").path("definition").path("key"))
                    .put("threadId", "wrong");
            assertThrows(
                    DurableException.class,
                    () -> projection.accept(DurableJson.convert(changed, PublicEvent.class)));
            assertEquals("cursor-1", projection.cursor());
            assertEquals(original, projection.value());
            var foreign = ((ObjectNode) events.get(0)).deepCopy();
            foreign.put("sequence", "2");
            ((ObjectNode) foreign.path("data").path("key")).put("sessionId", "other");
            assertThrows(
                    DurableException.class,
                    () -> projection.accept(DurableJson.convert(foreign, PublicEvent.class)));
            var snapshot = (ObjectNode) original.deepCopy();
            snapshot.set("session", foreign.path("data"));
            assertThrows(
                    DurableException.class,
                    () -> projection.reset(new Snapshot(key, List.of(), snapshot, "2", "bad")));
            assertEquals(original, projection.value());
            assertEquals("cursor-1", projection.cursor());
        }
    }

    @Test
    void interactionRunChangesClearTheOldStepAndUnattributedUpdatesPreserveKnownOwnership() {
        var key = new SessionKey("n", "s");
        var projection = new EventProjection(key);
        var data =
                DurableJson.parse(
                        "{\"id\":\"approval\",\"requestId\":\"request\","
                            + "\"turnId\":\"turn\",\"key\":\"approval\",\"kind\":\"confirmation\","
                            + "\"status\":\"pending\",\"runId\":null,\"stepId\":null}");
        projection.accept(
                new PublicEvent(
                        "1",
                        "request",
                        key,
                        "main",
                        "turn",
                        "r1",
                        "s1",
                        "1",
                        "cursor1",
                        "interaction.requested",
                        data));
        assertEquals(
                "s1",
                projection.value().path("interactions").path("approval").path("stepId").asText());
        var resolved = ((ObjectNode) data.deepCopy()).put("status", "resolved");
        projection.accept(
                new PublicEvent(
                        "1",
                        "resolve",
                        key,
                        "main",
                        "turn",
                        "r2",
                        null,
                        "2",
                        "cursor2",
                        "interaction.resolved",
                        resolved));
        assertEquals(
                "r2",
                projection.value().path("interactions").path("approval").path("runId").asText());
        assertTrue(
                projection.value().path("interactions").path("approval").path("stepId").isNull());
        projection.accept(
                new PublicEvent(
                        "1",
                        "unattributed",
                        key,
                        "main",
                        "turn",
                        null,
                        null,
                        "3",
                        "cursor3",
                        "interaction.resolved",
                        resolved));
        assertEquals(
                "r2",
                projection.value().path("interactions").path("approval").path("runId").asText());
        assertTrue(
                projection.value().path("interactions").path("approval").path("stepId").isNull());
        DurableProtocol.validate(
                "snapshot", new Snapshot(key, List.of(), projection.value(), "3", "cursor3"));
    }

    @Test
    void localToolArgumentsAndProgressReplayWithoutInventingRemoteExecution() {
        var key = new SessionKey("n", "s");
        var projection = new EventProjection(key);
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.arguments",
                Map.of(
                        "callId",
                        "a",
                        "name",
                        "lookup",
                        "messageId",
                        "message",
                        "attempt",
                        1,
                        "revision",
                        0,
                        "text",
                        "old"));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.arguments",
                Map.of(
                        "callId",
                        "b",
                        "name",
                        "lookup",
                        "messageId",
                        "message",
                        "attempt",
                        1,
                        "revision",
                        0,
                        "text",
                        "B"));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.arguments",
                Map.of(
                        "callId",
                        "a",
                        "name",
                        "lookup",
                        "messageId",
                        "message",
                        "attempt",
                        1,
                        "revision",
                        1,
                        "text",
                        "new"));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.arguments",
                Map.of(
                        "callId",
                        "a",
                        "name",
                        "lookup",
                        "messageId",
                        "message",
                        "attempt",
                        1,
                        "revision",
                        0,
                        "text",
                        "stale"));
        var a = EventProjection.toolId("main", "turn", "a");
        assertEquals(
                "new",
                projection
                        .value()
                        .path("tools")
                        .path(a)
                        .path("data")
                        .path("argumentsText")
                        .asText());
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.updated",
                Map.of("callId", "a", "status", "REQUESTED", "arguments", Map.of("topic", "A")));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.progress",
                Map.of(
                        "callId",
                        "a",
                        "content",
                        List.of(Map.of("type", "text", "text", "working"))));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.updated",
                Map.of(
                        "callId",
                        "a",
                        "status",
                        "SUCCEEDED",
                        "content",
                        List.of(Map.of("type", "text", "text", "done"))));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.progress",
                Map.of("callId", "a", "content", List.of(Map.of("type", "text", "text", "late"))));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.updated",
                Map.of("callId", "a", "status", "WAITING"));
        emit(
                projection,
                key,
                "research",
                "turn-2",
                "tool.call.updated",
                Map.of("callId", "a", "status", "REQUESTED"));
        emit(
                projection,
                key,
                "main",
                "turn",
                "tool.call.updated",
                Map.of(
                        "callId",
                        "c",
                        "status",
                        "REQUESTED",
                        "arguments",
                        Map.of("topic", "complete-only")));
        var data = projection.value().path("tools").path(a).path("data");
        assertEquals("SUCCEEDED", data.path("status").asText());
        assertEquals(1, data.path("progress").size());
        assertEquals("done", data.path("content").get(0).path("text").asText());
        assertFalse(data.has("executionId"));
        assertEquals(4, projection.value().path("tools").size());
        assertEquals(
                "{\"topic\":\"complete-only\"}",
                projection
                        .value()
                        .path("tools")
                        .path(EventProjection.toolId("main", "turn", "c"))
                        .path("data")
                        .path("argumentsText")
                        .asText());
        assertEquals("1", projection.value().path("tools").path(a).path("sequence").asText());
        DurableProtocol.validate(
                "snapshot",
                new Snapshot(
                        key,
                        List.of(),
                        projection.value(),
                        Long.toString(sequence),
                        projection.cursor()));
    }

    private void emit(
            EventProjection projection,
            SessionKey key,
            String thread,
            String turn,
            String type,
            Map<String, Object> data) {
        String position = Long.toString(++sequence);
        var event =
                new PublicEvent(
                        "1",
                        "event-" + position,
                        key,
                        thread,
                        turn,
                        null,
                        null,
                        position,
                        "cursor-" + position,
                        type,
                        DurableJson.value(data));
        projection.accept(event);
        projection.accept(event);
    }

    @Test
    void unsupportedVersionsAndInvalidPayloadsDoNotAdvanceTheProjection() {
        var key = new SessionKey("n", "s");
        var projection = new EventProjection(key);
        var unknown =
                new PublicEvent(
                        "future",
                        "event",
                        key,
                        "main",
                        "turn",
                        null,
                        null,
                        "1",
                        "cursor",
                        "message.completed",
                        DurableJson.value(Map.of()));
        assertEquals(
                DurableException.Code.INCOMPATIBLE,
                assertThrows(DurableException.class, () -> projection.accept(unknown)).code());
        var invalid =
                new PublicEvent(
                        "1",
                        "event",
                        key,
                        "main",
                        "turn",
                        null,
                        null,
                        "1",
                        "cursor",
                        "message.completed",
                        DurableJson.value(Map.of()));
        assertEquals(
                DurableException.Code.INVALID,
                assertThrows(DurableException.class, () -> projection.accept(invalid)).code());
        assertEquals(null, projection.cursor());
        assertEquals(0, projection.value().path("messages").size());
    }

    @Test
    void sharedVectorCoversReplayLargeSequencesAttemptsFinalReplacementAndToolFacts()
            throws Exception {
        try (var input =
                getClass().getResourceAsStream("/durable-protocol/projection-vector.json")) {
            var vector =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var snapshot = DurableJson.convert(vector.path("snapshot"), Snapshot.class);
            var projection = new EventProjection(snapshot.session());
            projection.reset(snapshot);
            var first = DurableJson.convert(vector.path("events").get(0), PublicEvent.class);
            projection.accept(first);
            projection.accept(first);
            for (var node : vector.path("events"))
                projection.accept(DurableJson.convert(node, PublicEvent.class));
            assertEquals(vector.path("expected"), DurableJson.parse(projection.value().toString()));
            assertEquals("cursor-8", projection.cursor());
            // A replay-built projection must also be a valid reconnect snapshot over HTTP.
            var last = vector.path("events").get(vector.path("events").size() - 1);
            projection.reset(
                    new Snapshot(
                            snapshot.session(),
                            snapshot.turns(),
                            projection.value(),
                            last.path("sequence").asText(),
                            projection.cursor()));
            assertEquals(
                    "continued-run",
                    projection
                            .value()
                            .path("tools")
                            .path(EventProjection.toolId("main", "r", "call"))
                            .path("runId")
                            .asText());
            assertEquals(
                    "acting-step",
                    projection
                            .value()
                            .path("tools")
                            .path(EventProjection.toolId("main", "r", "call"))
                            .path("stepId")
                            .asText());
            projection.reset(snapshot);
            assertThrows(
                    DurableException.class,
                    () ->
                            projection.accept(
                                    DurableJson.convert(
                                            vector.path("events").get(2), PublicEvent.class)));
            assertEquals(snapshot.cursor(), projection.cursor());
        }
    }
}
