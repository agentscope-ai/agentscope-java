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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

class ManagedSessionPreviewsTest {
    final ManagedScope scope = new ManagedScope("tenant", "ns", "preview");
    final ObjectMapper mapper = new ObjectMapper();

    static final class Memory implements ManagedSessionPreviews.Transport {
        final Map<String, List<String>> interest = new ConcurrentHashMap<>();
        final Map<String, Consumer<String>> listeners = new ConcurrentHashMap<>();
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());

        public List<String> interest(ManagedScope scope) {
            return List.copyOf(interest.getOrDefault(scope.messageGroup(), List.of()));
        }

        public void register(ManagedScope scope, String token) {
            interest.compute(
                    scope.messageGroup(),
                    (key, old) -> {
                        var values = new ArrayList<>(old == null ? List.of() : old);
                        if (!values.contains(token)) values.add(token);
                        return values;
                    });
        }

        public void remove(ManagedScope scope, String token) {
            interest.computeIfPresent(
                    scope.messageGroup(),
                    (key, old) -> old.stream().filter(t -> !token.equals(t)).toList());
        }

        public void publish(String node, String payload) {
            sent.add(payload);
            listeners.get(node).accept(payload);
        }

        public AutoCloseable listen(String node, Consumer<String> receive) {
            listeners.put(node, receive);
            return () -> listeners.remove(node);
        }
    }

    ManagedSessionPreviews.TextDelta text(String value) {
        return new ManagedSessionPreviews.TextDelta("message", List.of(value));
    }

    @Test
    void noInterestProducesNoPayloads() {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper)) {
            previews.begin(scope, "turn", "call", 1, "REASONING").accept(text("hi"));
            assertTrue(transport.sent.isEmpty());
            assertFalse(previews.hasInterest(scope));
            assertFalse(
                    previews.publishText(scope, "turn", "call", 1, "message", 0, List.of("hi")));
        }
    }

    @Test
    void statelessBatchesPreserveSequenceAndDoNotAdmitLateSubscribers() {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var first = previews.open(scope)) {
            assertTrue(previews.hasInterest(scope));
            StepVerifier.create(first.events())
                    .then(
                            () ->
                                    assertTrue(
                                            previews.publishText(
                                                    scope,
                                                    "turn",
                                                    "call",
                                                    1,
                                                    "message",
                                                    0,
                                                    List.of("你好", "🙂"))))
                    .assertNext(frame -> assertEquals("event_start", frame.event()))
                    .expectNextCount(2)
                    .then(
                            () -> {
                                try (var late = previews.open(scope)) {
                                    StepVerifier.create(late.events())
                                            .then(
                                                    () ->
                                                            previews.publishText(
                                                                    scope,
                                                                    "turn",
                                                                    "call",
                                                                    1,
                                                                    "message",
                                                                    3,
                                                                    List.of("tail")))
                                            .expectNoEvent(Duration.ofMillis(100))
                                            .thenCancel()
                                            .verify(Duration.ofSeconds(3));
                                }
                            })
                    .assertNext(frame -> assertTrue(frame.data().contains("tail")))
                    .then(
                            () ->
                                    previews.publishText(
                                            scope,
                                            "turn",
                                            "call",
                                            1,
                                            "message",
                                            3,
                                            List.of("duplicate")))
                    .expectNoEvent(Duration.ofMillis(100))
                    .then(
                            () ->
                                    previews.publishText(
                                            scope,
                                            "turn",
                                            "call",
                                            1,
                                            "message",
                                            4,
                                            List.of("after duplicate")))
                    .expectNoEvent(Duration.ofMillis(100))
                    .thenCancel()
                    .verify(Duration.ofSeconds(3));
        }
    }

    @Test
    void oneModelCanPreviewTwoMessagesAndNewEpochRejectsTheOldProducer() {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var connection = previews.open(scope)) {
            StepVerifier.create(connection.events())
                    .then(
                            () ->
                                    previews.publishText(
                                            scope, "turn", "call", 1, "first", 0, List.of("a")))
                    .expectNextCount(2)
                    .then(
                            () ->
                                    previews.publishText(
                                            scope, "turn", "call", 1, "second", 0, List.of("b")))
                    .expectNextCount(2)
                    .then(
                            () ->
                                    previews.publishText(
                                            scope,
                                            "turn",
                                            "replacement",
                                            2,
                                            "third",
                                            0,
                                            List.of("c")))
                    .expectNextCount(2)
                    .then(
                            () ->
                                    previews.publishText(
                                            scope,
                                            "turn",
                                            "call",
                                            1,
                                            "second",
                                            2,
                                            List.of("stale")))
                    .expectNoEvent(Duration.ofMillis(100))
                    .then(
                            () -> {
                                connection.reconcile(
                                        Map.of(
                                                "type",
                                                "span.model_request_end",
                                                "id",
                                                ManagedSessionPreviews.modelEndId(
                                                        scope, "turn", "replacement")));
                                previews.publishText(
                                        scope,
                                        "turn",
                                        "replacement",
                                        2,
                                        "third",
                                        2,
                                        List.of("late"));
                            })
                    .expectNoEvent(Duration.ofMillis(100))
                    .thenCancel()
                    .verify(Duration.ofSeconds(3));
        }
    }

    @Test
    void lateConnectionsCannotJoinAndCompletedMessagesClosePreviews() {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var first = previews.open(scope)) {
            var producer = previews.begin(scope, "turn", "call", 1, "REASONING");
            try (var late = previews.open(scope)) {
                StepVerifier.create(first.events())
                        .then(() -> producer.accept(text("hello")))
                        .assertNext(event -> assertEquals("event_start", event.event()))
                        .assertNext(event -> assertEquals("event_delta", event.event()))
                        .then(
                                () -> {
                                    first.reconcile(
                                            Map.of(
                                                    "type",
                                                    "agent.message",
                                                    "id",
                                                    ManagedSessionPreviews.eventId(
                                                            scope, "turn", "message")));
                                    producer.accept(text("late"));
                                })
                        .expectNoEvent(Duration.ofMillis(100))
                        .thenCancel()
                        .verify(Duration.ofSeconds(3));
                StepVerifier.create(late.events())
                        .expectSubscription()
                        .expectNoEvent(Duration.ofMillis(100))
                        .thenCancel()
                        .verify(Duration.ofSeconds(3));
            }
        }
    }

    @Test
    void slowConnectionGetsOnlyAPrefixAndCannotDelayCompleteMessages() throws Exception {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var connection = previews.open(scope)) {
            var producer = previews.begin(scope, "turn", "call", 1, "REASONING");
            var full = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                full.append(i).append(",");
                producer.accept(text(i + ","));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (transport.sent.size() < 101 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(101, transport.sent.size());
            var frames =
                    connection
                            .events()
                            .take(Duration.ofMillis(100))
                            .collectList()
                            .block(Duration.ofSeconds(2));
            var prefix = new StringBuilder();
            for (var frame : frames) {
                var event = mapper.readTree(frame.data());
                if ("event_delta".equals(frame.event()))
                    prefix.append(event.path("delta").path("content").path("text").asText());
            }
            assertTrue(frames.size() < 101);
            assertTrue(full.toString().startsWith(prefix.toString()));
            var complete =
                    ServerSentEvent.builder(
                                    mapper.writeValueAsString(
                                            Map.of(
                                                    "type",
                                                    "agent.message",
                                                    "id",
                                                    ManagedSessionPreviews.eventId(
                                                            scope, "turn", "message"))))
                            .event("agent.message")
                            .build();
            assertTrue(connection.deliver(complete));
            assertFalse(
                    connection.deliver(
                            ServerSentEvent.builder(
                                            "{\"type\":\"event_delta\",\"event_id\":\""
                                                    + ManagedSessionPreviews.eventId(
                                                            scope, "turn", "message")
                                                    + "\"}")
                                    .event("event_delta")
                                    .build()));
        }
    }

    @Test
    void anotherTenantNeverReceivesThisSessionsPreviews() {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var connection = previews.open(scope)) {
            var other = new ManagedScope("other", scope.namespace(), scope.sessionId());
            previews.begin(other, "turn", "call", 1, "REASONING").accept(text("secret"));
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void transportGapStopsTheRemainingPreview() throws Exception {
        var transport = new Memory();
        try (var previews = new ManagedSessionPreviews(transport, mapper);
                var connection = previews.open(scope)) {
            var producer = previews.begin(scope, "turn", "call", 1, "REASONING");
            StepVerifier.create(connection.events())
                    .then(() -> producer.accept(text("prefix")))
                    .expectNextCount(2)
                    .then(
                            () -> {
                                try {
                                    Map<String, Object> frame =
                                            mapper.readValue(transport.sent.get(1), Map.class);
                                    frame.put("sequence", 3);
                                    transport
                                            .listeners
                                            .values()
                                            .iterator()
                                            .next()
                                            .accept(mapper.writeValueAsString(frame));
                                    producer.accept(text("must not append"));
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            })
                    .expectNoEvent(Duration.ofMillis(100))
                    .thenCancel()
                    .verify(Duration.ofSeconds(3));
        }
    }
}
