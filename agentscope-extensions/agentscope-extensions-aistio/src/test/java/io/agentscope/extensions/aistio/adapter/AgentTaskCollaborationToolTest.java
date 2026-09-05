/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentTaskCollaborationToolTest {

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
            assertTrue(((TextBlock) result.getOutput().get(0)).getText().contains("comment-1"));
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
