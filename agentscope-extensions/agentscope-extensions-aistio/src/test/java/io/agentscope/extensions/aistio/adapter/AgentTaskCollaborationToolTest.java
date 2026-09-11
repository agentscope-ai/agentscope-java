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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentTaskCollaborationToolTest {

    @Test
    void roleInstructionsSeparateLeaderHandoffFromWorkerExecution() throws Exception {
        JsonNode worker =
                ControlPlaneHttpClient.mapper()
                        .readTree("{\"task\":{\"teamId\":\"team-1\",\"teamRole\":\"specialist\"}}");
        JsonNode leader =
                ControlPlaneHttpClient.mapper()
                        .readTree("{\"task\":{\"teamId\":\"team-1\",\"leaderTask\":true}}");

        assertTrue(
                HarnessAgentTaskStarter.roleInstructions(worker, List.of())
                        .contains("Team worker, not its coordinator"));
        assertTrue(
                HarnessAgentTaskStarter.roleInstructions(leader, List.of())
                        .contains("return immediately after issue.child.create succeeds"));
        String followUp = HarnessAgentTaskStarter.roleInstructions(leader, List.of("input-1"));
        assertTrue(followUp.contains("leader follow-up"));
        assertTrue(followUp.contains("Never send those mutations in parallel"));
        assertTrue(followUp.contains("run.node.complete also completes this leader AgentTask"));
    }

    @Test
    void startFallbackRefreshesFullTaskContext() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger contexts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/agent-tasks/task-1/",
                exchange -> {
                    if (exchange.getRequestURI().getPath().endsWith("/start")) {
                        starts.incrementAndGet();
                        respond(exchange, "{\"task\":{\"status\":\"running\",\"version\":3}}");
                    } else if (exchange.getRequestURI().getPath().endsWith("/context")) {
                        contexts.incrementAndGet();
                        respond(
                                exchange,
                                "{\"task\":{\"status\":\"running\",\"version\":3},"
                                        + "\"issue\":{\"title\":\"authoritative context\"},"
                                        + "\"availableActions\":[\"issue.get\"]}");
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                    }
                });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            CollaborationClient client =
                    new CollaborationClient(new ControlPlaneHttpClient(endpoint, "internal-token"));
            HarnessAgentTaskStarter starter = new HarnessAgentTaskStarter(() -> null, client);
            AgentTaskAssignment assignment =
                    new AgentTaskAssignment(
                            "attempt-1",
                            "task-1",
                            "run-1",
                            "node-1",
                            1,
                            "dispatch",
                            "",
                            "task-token",
                            "attempt-token",
                            "assigned-session",
                            new byte[0],
                            1);
            JsonNode dispatched =
                    ControlPlaneHttpClient.mapper()
                            .readTree("{\"task\":{\"status\":\"dispatched\",\"version\":2}}");

            JsonNode running = starter.ensureRunning(assignment, dispatched, 2);

            assertEquals(1, starts.get());
            assertEquals(1, contexts.get());
            assertEquals("authoritative context", running.path("issue").path("title").asText());
            assertEquals("issue.get", running.path("availableActions").get(0).asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversAndCallsTaskScopedMcpTool() throws Exception {
        AtomicReference<JsonNode> call = new AtomicReference<>();
        AtomicReference<String> taskToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp/collaboration",
                exchange -> {
                    taskToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Task-Token"));
                    JsonNode request = readJson(exchange);
                    if ("tools/list".equals(request.path("method").asText())) {
                        respond(
                                exchange,
                                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[{\"name\":\"issue.comment.add\",\"description\":\"Add"
                                    + " a comment\","
                                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                                    + "\"content\":{\"type\":\"string\"}},\"required\":[\"content\"]}}]}}");
                        return;
                    }
                    call.set(request);
                    respond(
                            exchange,
                            "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{"
                                    + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                                    + "\"structuredContent\":{\"commentId\":\"comment-1\"}}}");
                });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            CollaborationClient client =
                    new CollaborationClient(new ControlPlaneHttpClient(endpoint, "internal-token"));
            JsonNode definitions = client.tools("task-1", "task-token");
            AgentTaskCollaborationTool tool =
                    new AgentTaskCollaborationTool(client, definitions.get(0));
            RuntimeContext context =
                    RuntimeContext.builder()
                            .sessionId("session-1")
                            .put(
                                    AgentTaskToolContext.class,
                                    new AgentTaskToolContext("task-1", "task-token"))
                            .build();

            ToolResultBlock result =
                    tool.callAsync(
                                    ToolCallParam.builder()
                                            .toolUseBlock(
                                                    new ToolUseBlock(
                                                            "call-1",
                                                            "issue.comment.add",
                                                            Map.of("content", "working")))
                                            .input(Map.of("content", "working"))
                                            .runtimeContext(context)
                                            .build())
                            .block();

            assertNotNull(result);
            assertEquals("issue.comment.add", tool.getName());
            assertEquals("task-token", taskToken.get());
            assertEquals("tools/call", call.get().path("method").asText());
            assertEquals(
                    "task-1", call.get().path("params").path("arguments").path("taskId").asText());
            assertEquals(
                    "working",
                    call.get().path("params").path("arguments").path("content").asText());
            assertEquals(
                    "call-1",
                    call.get().path("params").path("arguments").path("_toolCallId").asText());
            assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("comment-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void externalRuntimeApprovalUsesTaskScopedRequestDecisionAndAck() throws Exception {
        AtomicInteger requested = new AtomicInteger();
        AtomicInteger acknowledged = new AtomicInteger();
        AtomicReference<String> taskToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/agent-tasks/task-1/runtime-approvals",
                exchange -> {
                    taskToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Task-Token"));
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/decision")) {
                        respond(
                                exchange,
                                "{\"approvalId\":\"approval-1\",\"decisionVersion\":2,\"status\":\"approved\",\"allow\":true}");
                    } else if (path.endsWith("/ack")) {
                        acknowledged.incrementAndGet();
                        respond(exchange, "{}");
                    } else {
                        requested.incrementAndGet();
                        JsonNode body = readJson(exchange);
                        assertEquals("call-1", body.path("toolUseId").asText());
                        assertEquals("shell", body.path("toolName").asText());
                        respond(exchange, "{\"approval\":{\"id\":\"approval-1\"}}");
                    }
                });
        server.start();
        try {
            CollaborationClient client =
                    new CollaborationClient(
                            new ControlPlaneHttpClient(
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "internal-token"));

            CollaborationClient.RuntimeApprovalDecision decision =
                    client.awaitRuntimeToolApproval(
                            "task-1", "task-token", "call-1", "shell", Map.of("command", "date"));

            assertTrue(decision.allow());
            assertEquals(2, decision.decisionVersion());
            assertEquals(1, requested.get());
            assertEquals(1, acknowledged.get());
            assertEquals("task-token", taskToken.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mcpToolFailureSurfacesTheControlPlaneReason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp/collaboration",
                exchange ->
                        respond(
                                exchange,
                                "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{"
                                    + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"error\\\":\\\"coordinator"
                                    + " has active worker task"
                                    + " task-2\\\"}\"}],\"structuredContent\":{\"error\":\"coordinator"
                                    + " has active worker task task-2\"},\"isError\":true}}"));
        server.start();
        try {
            CollaborationClient client =
                    new CollaborationClient(
                            new ControlPlaneHttpClient(
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "internal-token"));

            CollaborationClient.CollaborationHttpException error =
                    assertThrows(
                            CollaborationClient.CollaborationHttpException.class,
                            () ->
                                    client.callTool(
                                            "task-1", "task-token", "run.node.complete", Map.of()));

            assertTrue(error.getMessage().contains("coordinator has active worker task task-2"));
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        return ControlPlaneHttpClient.mapper().readTree(exchange.getRequestBody().readAllBytes());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
