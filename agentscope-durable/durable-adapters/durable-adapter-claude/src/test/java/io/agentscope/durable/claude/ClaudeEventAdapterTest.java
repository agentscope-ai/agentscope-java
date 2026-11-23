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
package io.agentscope.durable.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaudeEventAdapterTest {
    private final SessionKey session = new SessionKey("tenant", "session");
    private final ClaudeEventAdapter adapter =
            new ClaudeEventAdapter(
                    Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void previewAndCommittedMessageShareTheSameClaudeEventId() {
        var start = adapter.map(started("message-1"));
        var delta = adapter.map(delta("message-1", "hello"));
        var completed =
                adapter.map(new SessionStreamEvent.Committed(completed("message-1", "hello")));

        assertEquals("event_start", start.get(0).path("type").asText());
        assertEquals("message-1", start.get(0).path("event").path("id").asText());
        assertEquals("event_delta", delta.get(0).path("type").asText());
        assertEquals("message-1", delta.get(0).path("event_id").asText());
        assertEquals(0, delta.get(0).path("delta").path("index").asInt());
        assertEquals("agent.message", completed.get(0).path("type").asText());
        assertEquals("message-1", completed.get(0).path("id").asText());
        assertEquals("2026-11-12T12:00:00Z", completed.get(0).path("processed_at").asText());
    }

    @Test
    void previewsAreConnectionOptInAndNeverPartOfCommittedMapping() {
        assertTrue(
                adapter.live(live("message.delta", Map.of("messageId", "m", "text", "x")), Set.of())
                        .isEmpty());
        assertEquals(1, adapter.committed(completed("m", "x")).size());
    }

    @Test
    void durableImageSourcesAreConvertedToClaudeFieldNames() {
        var mapped =
                adapter.committed(
                                completed(
                                        "image-message",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "image",
                                                        "source",
                                                        Map.of(
                                                                "type",
                                                                "base64",
                                                                "mimeType",
                                                                "image/png",
                                                                "data",
                                                                "YWJj")),
                                                Map.of(
                                                        "type",
                                                        "image",
                                                        "source",
                                                        Map.of(
                                                                "type",
                                                                "url",
                                                                "url",
                                                                "https://example.test/image.png",
                                                                "mimeType",
                                                                "image/png")))))
                        .get(0);

        assertEquals(
                "image/png",
                mapped.path("content").get(0).path("source").path("media_type").asText());
        assertFalse(mapped.path("content").get(0).path("source").has("mimeType"));
        assertEquals(
                "https://example.test/image.png",
                mapped.path("content").get(1).path("source").path("url").asText());
        assertFalse(mapped.path("content").get(1).path("source").has("mimeType"));
    }

    private LiveEvent live(String type, Map<String, ?> data) {
        return new LiveEvent(
                "1",
                type + "-id",
                session,
                "main",
                "turn",
                "run",
                "step",
                1,
                type,
                DurableJson.value(data));
    }

    private SessionStreamEvent.PreviewStarted started(String id) {
        return new SessionStreamEvent.PreviewStarted(
                id,
                EventDeltaType.MESSAGE,
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id)));
    }

    private SessionStreamEvent.EventDelta delta(String id, String text) {
        return new SessionStreamEvent.EventDelta(
                id,
                EventDeltaType.MESSAGE,
                "text-1",
                "main",
                "turn",
                "run",
                "step",
                DurableJson.value(Map.of("messageId", id, "blockId", "text-1", "text", text)));
    }

    private PublicEvent completed(String id, String text) {
        return completed(id, List.of(Map.of("type", "text", "text", text)));
    }

    private PublicEvent completed(String id, List<?> content) {
        return new PublicEvent(
                "1",
                "event",
                session,
                "main",
                "turn",
                "run",
                "step",
                "1",
                "cursor",
                "message.completed",
                DurableJson.value(
                        Map.of(
                                "messageId",
                                id,
                                "attempt",
                                1,
                                "revision",
                                0,
                                "text",
                                "",
                                "message",
                                Map.of(
                                        "schemaVersion",
                                        "1",
                                        "messageId",
                                        id,
                                        "role",
                                        "assistant",
                                        "content",
                                        content))));
    }
}
