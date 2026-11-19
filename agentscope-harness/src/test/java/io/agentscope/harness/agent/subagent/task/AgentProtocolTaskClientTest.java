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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HTTP status handling of {@link AgentProtocolTaskClient#submitTask}.
 *
 * <p>{@code 409} means the server refused a continuation. A busy session surfaces as {@link
 * RemoteSessionBusyException} ("wait for the previous turn"), while a paused turn surfaces as
 * {@link RemoteSessionAwaitingConfirmException} ("resume or cancel it") — the two need different
 * actions. Collapsing either into a generic {@link IOException} would make the agent retry and hit
 * the same conflict again. The mapping is exercised against a real HTTP endpoint, not a stubbed
 * client method, because the status code is exactly what the bug would be in.
 */
@DisplayName("AgentProtocolTaskClient submit status handling")
class AgentProtocolTaskClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("409 becomes RemoteSessionBusyException naming the busy task")
    void conflictBecomesStillRunningWithTheParentTaskId() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String baseUrl = startServer(409, "{\"error\":\"parent task is still running\"}", body);

        AgentProtocolTaskClient client = new AgentProtocolTaskClient();
        RemoteSubmitContext context = RemoteSubmitContext.builder().sessionId("task_busy").build();

        RemoteSessionBusyException thrown =
                assertThrows(
                        RemoteSessionBusyException.class,
                        () ->
                                client.submitTask(
                                        baseUrl,
                                        Map.of(),
                                        "task_new",
                                        "worker",
                                        "next turn",
                                        context));

        assertEquals(
                "task_busy", thrown.sessionId(), "the caller must be told which task to wait for");
        assertTrue(
                thrown.getMessage().contains("parent task is still running"),
                "the server's reason must survive: " + thrown.getMessage());
        assertTrue(
                body.get().contains("\"session_id\":\"task_busy\""),
                "the continuation must be on the wire, got " + body.get());
    }

    @Test
    @DisplayName("409 without a continuation context still reports the conflict")
    void conflictWithoutParentTaskIdIsStillReported() throws Exception {
        String baseUrl = startServer(409, "{\"error\":\"task run is in progress\"}", null);

        AgentProtocolTaskClient client = new AgentProtocolTaskClient();

        RemoteSessionBusyException thrown =
                assertThrows(
                        RemoteSessionBusyException.class,
                        () ->
                                client.submitTask(
                                        baseUrl, Map.of(), "task_dup", "worker", "go", null));

        assertNull(thrown.sessionId(), "no session_id was submitted, so none may be invented");
    }

    @Test
    @DisplayName("409 awaiting_confirm becomes a resumable pause, not a busy run")
    void awaitingConfirmIsReportedSeparatelyFromABusyRun() throws Exception {
        String body =
                "{\"error\":\"session conv-1 has a turn awaiting confirmation\","
                        + "\"status\":\"awaiting_confirm\",\"session_id\":\"conv-1\","
                        + "\"pending_task_id\":\"task_pending\","
                        + "\"confirm_expires_at\":\"2026-11-20T18:00:00Z\"}";
        String baseUrl = startServer(409, body, null);
        AgentProtocolTaskClient client = new AgentProtocolTaskClient();
        RemoteSubmitContext context = RemoteSubmitContext.builder().sessionId("conv-1").build();

        RemoteSessionAwaitingConfirmException thrown =
                assertThrows(
                        RemoteSessionAwaitingConfirmException.class,
                        () ->
                                client.submitTask(
                                        baseUrl,
                                        Map.of(),
                                        "task_new",
                                        "worker",
                                        "next turn",
                                        context));

        assertEquals("conv-1", thrown.sessionId());
        assertEquals("task_pending", thrown.pendingTaskId());
        assertEquals("2026-11-20T18:00:00Z", thrown.expiresAt());
    }

    @Test
    @DisplayName("409 without a status stays a busy-run conflict")
    void conflictWithoutStatusIsNotMistakenForAPause() throws Exception {
        String body = "{\"error\":\"session conv-1 is still running\",\"session_id\":\"conv-1\"}";
        String baseUrl = startServer(409, body, null);
        AgentProtocolTaskClient client = new AgentProtocolTaskClient();

        assertThrows(
                RemoteSessionBusyException.class,
                () -> client.submitTask(baseUrl, Map.of(), "task_new", "worker", "go", null));
    }

    @Test
    @DisplayName("other rejections stay IOExceptions carrying the status and body")
    void badRequestIsNotMistakenForAStillRunningParent() throws Exception {
        String baseUrl = startServer(400, "{\"error\":\"parent task not found: task_x\"}", null);

        AgentProtocolTaskClient client = new AgentProtocolTaskClient();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                client.submitTask(
                                        baseUrl,
                                        Map.of(),
                                        "task_new",
                                        "worker",
                                        "go",
                                        RemoteSubmitContext.builder().sessionId("task_x").build()));

        assertTrue(thrown.getMessage().contains("HTTP 400"), thrown.getMessage());
        assertTrue(
                thrown.getMessage().contains("parent task not found"),
                "the body must reach the caller: " + thrown.getMessage());
    }

    private String startServer(int status, String responseBody, AtomicReference<String> requestBody)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/tasks",
                exchange -> {
                    String received =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    if (requestBody != null) {
                        requestBody.set(received);
                    }
                    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
