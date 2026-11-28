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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.EventInput;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;
import io.agentscope.durable.SessionStreamEvent;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeEventAdapterTest {
    private final SessionKey session = new SessionKey("tenant", "session");
    private final ClaudeEventAdapter adapter = new ClaudeEventAdapter();

    @Test
    void claudeMessageAdmissionUsesTheSameDurableTurnOnRetry(@TempDir Path directory) {
        var message =
                DurableJson.parse(
                        """
                        {"type":"user.message","content":[{"type":"text","text":"hello"},
                          {"type":"image","source":{"type":"base64","media_type":"image/png","data":"YWJj"}}]}
                        """);
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "v1")
                                .build()) {
            var nativeSession = runtime.createSession(session, "agent");
            var claude = new ClaudeSessionEvents(nativeSession);
            var first = claude.sendMessage("send-1", message);
            assertEquals(first, claude.sendMessage("send-1", message));
            assertTrue(first.required("processed_at").isNull());
            assertNotEquals(
                    first.required("id"), claude.sendMessage("send-2", message).required("id"));
            assertEquals(2, nativeSession.listTurns(null, 20).items().size());
            var persisted =
                    nativeSession.listEvents(null, 20).events().stream()
                            .filter(event -> first.required("id").asText().equals(event.eventId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "image/png",
                    persisted.data().at("/message/content/1/source/mimeType").asText());
            assertTrue(adapter.committed(persisted).isEmpty());
            var lease =
                    backend.claim(new ThreadKey(session, "main"), "fixture", Duration.ofMinutes(1))
                            .orElseThrow();
            backend.commit(
                    lease,
                    new Commit(
                            "apply",
                            backend.frame(lease).turn().revision(),
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(
                                    new EventInput(
                                            "input-applied",
                                            "input.applied",
                                            persisted.data().required("message"))),
                            TurnStatus.RUNNING,
                            null,
                            null,
                            null));
            var processed =
                    nativeSession.listEvents(null, 20).events().stream()
                            .flatMap(event -> adapter.committed(event).stream())
                            .filter(event -> "user.message".equals(event.path("type").asText()))
                            .toList();
            assertEquals(1, processed.size());
            assertEquals(first.required("id"), processed.get(0).required("id"));
            assertEquals(first.required("content"), processed.get(0).required("content"));
            assertTrue(processed.get(0).required("processed_at").isTextual());
            assertEquals(
                    DurableException.Code.CONFLICT,
                    assertThrows(
                                    DurableException.class,
                                    () ->
                                            claude.sendMessage(
                                                    "send-1",
                                                    DurableJson.parse(
                                                            """
                                                            {"type":"user.message","content":[{"type":"text","text":"different"}]}
                                                            """)))
                            .code());
        }
    }

    @Test
    void toolConfirmationRoutesToTheOriginalTurnAndReusesTheReceipt(@TempDir Path directory) {
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "v1")
                                .build()) {
            var nativeSession = runtime.createSession(session, "agent");
            var thread = nativeSession.createThread("child", "agent");
            var turn = thread.submit("approval", "start");
            var lease =
                    backend.claim(thread.key(), "fixture", Duration.ofSeconds(10)).orElseThrow();
            var approval =
                    new EventInput(
                            "native-event",
                            "interaction.requested",
                            DurableJson.value(
                                    Map.of(
                                            "id", "interaction-id",
                                            "requestId", "native-request",
                                            "turnId", turn.turnId(),
                                            "key", "interaction:native-request",
                                            "kind", "confirmation",
                                            "status", "pending",
                                            "automatic", false,
                                            "call",
                                                    Map.of(
                                                            "name",
                                                            "job",
                                                            "arguments",
                                                            Map.of("value", 9)))));
            backend.commit(
                    lease,
                    new Commit(
                            "request",
                            backend.frame(lease).turn().revision(),
                            DurableJson.value(Map.of("state", true)),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(approval),
                            TurnStatus.WAITING,
                            new WaitCondition("interaction:native-request", 0),
                            null,
                            null));
            var requested =
                    nativeSession.listEvents(null, 20).events().stream()
                            .filter(event -> event.eventId().equals("native-event"))
                            .findFirst()
                            .orElseThrow();
            var mapped = adapter.committed(requested).get(0);
            assertEquals("agent.tool_use", mapped.required("type").asText());
            assertEquals("ask", mapped.required("evaluated_permission").asText());
            assertEquals(9, mapped.at("/input/value").asInt());
            var response =
                    DurableJson.value(
                            Map.of(
                                    "type",
                                    "user.tool_confirmation",
                                    "tool_use_id",
                                    mapped.required("id").asText(),
                                    "result",
                                    "allow"));
            var claude = new ClaudeSessionEvents(nativeSession);
            var first = claude.sendToolConfirmation("respond", response);
            assertEquals(first, claude.sendToolConfirmation("respond", response));
            assertEquals("child", first.required("session_thread_id").asText());
            assertTrue(first.required("processed_at").isNull());
            var resumed =
                    backend.claim(thread.key(), "replacement", Duration.ofSeconds(10))
                            .orElseThrow();
            assertEquals(
                    1,
                    backend.frame(resumed).signals().stream()
                            .filter(signal -> signal.key().equals("interaction:native-request"))
                            .filter(
                                    signal ->
                                            signal.value().isBoolean()
                                                    && signal.value().asBoolean())
                            .count());
            assertEquals(
                    DurableException.Code.CONFLICT,
                    assertThrows(
                                    DurableException.class,
                                    () -> claude.sendToolConfirmation("another", response))
                            .code());
            var beforeApplication = nativeSession.snapshot().cursor();
            var frame = backend.frame(resumed);
            backend.commit(
                    resumed,
                    new Commit(
                            "apply",
                            frame.turn().revision(),
                            frame.checkpoint(),
                            List.of(),
                            List.of(),
                            List.of(frame.signals().get(0).id()),
                            List.of(),
                            TurnStatus.RUNNING,
                            null,
                            null,
                            null));
            var processed =
                    nativeSession.listEvents(beforeApplication, 20).events().stream()
                            .flatMap(event -> adapter.committed(event).stream())
                            .toList();
            assertEquals(1, processed.size());
            var expected = ((ObjectNode) first).deepCopy();
            expected.set("processed_at", processed.get(0).required("processed_at"));
            assertEquals(expected, processed.get(0));
            assertTrue(processed.get(0).required("processed_at").isTextual());
        }
    }

    @Test
    void customToolResultsReplayTheSameAcceptedEventWithMediaAndError(@TempDir Path directory) {
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "v1")
                                .build()) {
            var nativeSession = runtime.createSession(session, "agent");
            var child = nativeSession.createThread("child", "agent");
            var turn = child.submit("job", "call job");
            var lease = backend.claim(child.key(), "fixture", Duration.ofMinutes(1)).orElseThrow();
            var manual =
                    DurableJson.parse(
                            """
                            {"id":"manual-id","requestId":"manual-request","key":"interaction:manual-request",
                             "kind":"external_execution","status":"pending","automatic":false,
                             "call":{"name":"job","arguments":{}}}
                            """);
            ((ObjectNode) manual).put("turnId", turn.turnId());
            var automatic = (ObjectNode) manual.deepCopy();
            automatic
                    .put("id", "hands-id")
                    .put("requestId", "hands-request")
                    .put("key", "interaction:hands-request")
                    .put("automatic", true);
            backend.commit(
                    lease,
                    new Commit(
                            "request",
                            backend.frame(lease).turn().revision(),
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(
                                    new EventInput("manual", "interaction.requested", manual),
                                    new EventInput("hands", "interaction.requested", automatic)),
                            TurnStatus.WAITING,
                            new WaitCondition("interaction:manual-request", 0),
                            null,
                            null));
            var claude = new ClaudeSessionEvents(nativeSession);
            var response =
                    DurableJson.parse(
                            """
                            {"type":"user.custom_tool_result","custom_tool_use_id":"manual-id","is_error":true,
                             "content":[{"type":"text","text":"failed"},
                                        {"type":"image","source":{"type":"base64","media_type":"image/png",
                                                                  "data":"YWJj"}}]}
                            """);
            var receipt = claude.sendToolResult("response", response);
            assertEquals(receipt, claude.sendToolResult("response", response));
            assertTrue(receipt.required("processed_at").isTextual());
            assertTrue(receipt.required("is_error").asBoolean());
            assertEquals("child", receipt.required("session_thread_id").asText());
            var replayed =
                    nativeSession.listEvents(null, 30).events().stream()
                            .flatMap(event -> adapter.committed(event).stream())
                            .toList();
            assertEquals(
                    1,
                    replayed.stream()
                            .filter(
                                    event ->
                                            "agent.custom_tool_use"
                                                    .equals(event.path("type").asText()))
                            .count());
            assertTrue(replayed.contains(receipt));
            var forbidden = (ObjectNode) response.deepCopy();
            forbidden.put("custom_tool_use_id", "hands-id");
            assertEquals(
                    DurableException.Code.NOT_FOUND,
                    assertThrows(
                                    DurableException.class,
                                    () -> claude.sendToolResult("forged", forbidden))
                            .code());
        }
    }

    @Test
    void interruptIsProcessedOnlyAfterThePinnedTurnSettles(@TempDir Path directory) {
        var interrupt =
                DurableJson.value(Map.of("type", "user.interrupt", "session_thread_id", "main"));
        var thread = new ThreadKey(session, "main");
        JsonNode receipt;
        String following;
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "v1")
                                .build()) {
            var nativeSession = runtime.createSession(session, "agent");
            var first = nativeSession.submit("one", "first");
            following = nativeSession.submit("two", "next").turnId();
            var stopped = backend.claim(thread, "first", Duration.ofMinutes(1)).orElseThrow();
            var claude = new ClaudeSessionEvents(nativeSession);
            receipt = claude.sendInterrupt("interrupt", interrupt);
            assertTrue(receipt.required("processed_at").isNull());
            assertEquals(TurnStatus.CANCELLING, first.status().status());
            assertTrue(
                    nativeSession.listEvents(null, 30).events().stream()
                            .flatMap(event -> adapter.committed(event).stream())
                            .noneMatch(event -> event.path("id").equals(receipt.path("id"))));

            backend.commit(
                    stopped,
                    new Commit(
                            "settle",
                            backend.frame(stopped).turn().revision(),
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            TurnStatus.CANCELLED,
                            null,
                            null,
                            null));
        }
        try (var backend = LocalBackend.open(directory);
                var runtime = DurableRuntime.builder().backend(backend).build()) {
            var next = backend.claim(thread, "replacement", Duration.ofMinutes(1)).orElseThrow();
            assertEquals(following, next.turnId());
            var nativeSession = runtime.getSession(session);
            var claude = new ClaudeSessionEvents(nativeSession);
            assertEquals(receipt, claude.sendInterrupt("interrupt", interrupt));
            assertFalse(nativeSession.getTurn(following).status().cancellationRequested());
            var applied =
                    nativeSession.listEvents(null, 30).events().stream()
                            .flatMap(event -> adapter.committed(event).stream())
                            .filter(event -> event.path("id").equals(receipt.path("id")))
                            .toList();
            assertEquals(1, applied.size());
            assertEquals("main", applied.get(0).required("session_thread_id").asText());
            assertTrue(applied.get(0).required("processed_at").isTextual());
        }
    }

    @Test
    void historyPagesKeepTheirSelectionAndOriginalCommitBoundary(@TempDir Path directory) {
        var interrupt = DurableJson.value(Map.of("type", "user.interrupt"));
        var selected = Set.of("user.interrupt");
        try (var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "v1")
                                .build()) {
            var nativeSession = runtime.createSession(session, "agent");
            var claude = new ClaudeSessionEvents(nativeSession);
            var first = claude.sendInterrupt("first", interrupt);
            nativeSession.submit("unstarted", "still queued");
            var second = claude.sendInterrupt("second", interrupt);
            var page = claude.listEvents(null, 1, selected);
            assertEquals(DurableJson.value(List.of(first)), page.required("data"));
            String next = page.required("next_page").asText();

            var third = claude.sendInterrupt("third", interrupt);
            var resumed = new ClaudeSessionEvents(runtime.getSession(session));
            var tail = resumed.listEvents(next, 3, selected);
            assertEquals(DurableJson.value(List.of(second)), tail.required("data"));
            assertTrue(tail.required("next_page").isNull());
            assertEquals(
                    DurableJson.value(List.of(first, second, third)),
                    resumed.listEvents(null, 3, selected).required("data"));
            assertEquals(
                    DurableException.Code.INVALID,
                    assertThrows(
                                    DurableException.class,
                                    () -> resumed.listEvents(next, 3, Set.of()))
                            .code());
            var foreign =
                    new ClaudeSessionEvents(
                            runtime.createSession(
                                    new SessionKey("other", session.sessionId()), "agent"));
            assertEquals(
                    DurableException.Code.INVALID,
                    assertThrows(
                                    DurableException.class,
                                    () -> foreign.listEvents(next, 3, selected))
                            .code());
        }
    }

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
        assertEquals(
                completed, new ClaudeEventAdapter().committed(completed("message-1", "hello")));
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
                Instant.parse("2026-11-12T12:00:00Z").toEpochMilli(),
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
