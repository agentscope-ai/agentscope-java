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
package io.agentscope.examples.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ChatControllerTest {
    @TempDir Path workspace;
    @TempDir Path stateHome;
    private String previousStateHome;

    @BeforeEach
    void isolateLegacyState() {
        previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", stateHome.toString());
    }

    @AfterEach
    void restoreLegacyStateHome() {
        if (previousStateHome == null) System.clearProperty("agentscope.state.home");
        else System.setProperty("agentscope.state.home", previousStateHome);
    }

    @Test
    void httpUsesExplicitCreationAndTargetedIdempotentControls() throws Exception {
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(2), Duration.ofMillis(10)))) {
            var web = WebTestClient.bindToController(new ChatController(sessions)).build();
            web.get().uri("/api/sessions/conversation").exchange().expectStatus().isNotFound();
            assertTrue(sessions.list().isEmpty());
            web.post().uri("/api/sessions/conversation").exchange().expectStatus().isOk();
            var body = Map.of("requestId", "submit", "message", "/ask preference");
            var first =
                    web.post()
                            .uri("/api/sessions/conversation/turns")
                            .bodyValue(body)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody()
                            .returnResult()
                            .getResponseBody();
            String turn =
                    DurableJson.parse(new String(first, StandardCharsets.UTF_8))
                            .path("turnId")
                            .asText();
            web.post()
                    .uri("/api/sessions/conversation/turns")
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.turnId")
                    .isEqualTo(turn);
            ChatSessionsTest.await(
                    sessions, s -> ChatSessionsTest.status(s, turn) == TurnStatus.WAITING);
            String target = "/api/sessions/conversation/turns/" + turn;
            web.post()
                    .uri(target + "/pause")
                    .bodyValue(Map.of("requestId", "pause"))
                    .exchange()
                    .expectStatus()
                    .isOk();
            var paused =
                    ChatSessionsTest.await(
                            sessions, s -> ChatSessionsTest.status(s, turn) == TurnStatus.PAUSED);
            web.post()
                    .uri(target + "/resume")
                    .bodyValue(Map.of("requestId", "invalid"))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
            web.post()
                    .uri(target + "/resume")
                    .bodyValue(
                            Map.of(
                                    "requestId",
                                    "stale",
                                    "expectedRevision",
                                    paused.turns().get(0).revision() - 1))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(409);
            web.post()
                    .uri(target + "/cancel")
                    .bodyValue(Map.of("requestId", "cancel"))
                    .exchange()
                    .expectStatus()
                    .isOk();
            ChatSessionsTest.await(
                    sessions, s -> ChatSessionsTest.status(s, turn) == TurnStatus.CANCELLED);
            web.get()
                    .uri(target + "/runs")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.items.length()")
                    .isEqualTo(1);
        }
    }

    @Test
    void expiredSseCursorResynchronizesWithPublicSnapshot() throws Exception {
        try (var sessions = new ChatSessions(workspace, new DemoChatModel())) {
            sessions.create("conversation");
            String oldCursor = sessions.snapshot("conversation").cursor();
            String turn = sessions.submit("conversation", "submit", "hello").turnId();
            var done =
                    ChatSessionsTest.await(
                            sessions,
                            view -> ChatSessionsTest.status(view, turn) == TurnStatus.SUCCEEDED);
            // Administrative retention is outside the application API; use the local store fixture.
            try (var retention = LocalBackend.open(workspace.resolve("durable"))) {
                retention.pruneEvents(new SessionKey("local-user", "conversation"), done.cursor());
            }
            var reset =
                    new ChatController(sessions)
                            .stream("conversation", oldCursor, null)
                                    .next()
                                    .block(Duration.ofSeconds(5));
            assertNotNull(reset);
            assertEquals("reset", reset.event());
            assertEquals(done.cursor(), reset.id());
            assertEquals(
                    done, DurableJson.convert(DurableJson.parse(reset.data()), Snapshot.class));
        }
    }

    @Test
    void sseUsesReconnectHeaderAndDisconnectDoesNotCancelTask() throws Exception {
        try (var sessions =
                new ChatSessions(
                        workspace,
                        new DemoChatModel(Duration.ofMillis(3), Duration.ofMillis(10)))) {
            sessions.create("conversation");
            String cursor = sessions.snapshot("conversation").cursor();
            String turn = sessions.submit("conversation", "submit", "/slow stream").turnId();
            var controller = new ChatController(sessions);
            var event =
                    controller.stream("conversation", "invalid-url-cursor", cursor)
                            .filter(value -> "committed".equals(value.event()))
                            .next()
                            .block(Duration.ofSeconds(10));
            assertNotNull(event);
            var payload = DurableJson.parse(event.data());
            assertEquals(event.id(), payload.path("cursor").asText());
            assertEquals(turn, payload.path("turnId").asText());
            ChatSessionsTest.await(
                    sessions, s -> ChatSessionsTest.status(s, turn) == TurnStatus.SUCCEEDED);
            var web = WebTestClient.bindToController(controller).build();
            web.get()
                    .uri("/api/sessions/conversation/events?after=invalid")
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
    }
}
