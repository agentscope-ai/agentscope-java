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
package io.agentscope.extensions.aistio.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-process HTTP server for the {@code /agentscope/*} contract, letting the control plane pull
 * on demand and issue session commands.
 *
 * <p>Routes mirror the Go {@code connector.ContractServer} and the Python SDK one for one:
 *
 * <pre>
 *   GET  /agentscope/info
 *   GET  /agentscope/health
 *   GET  /agentscope/sessions
 *   GET  /agentscope/sessions/{id}/state
 *   GET  /agentscope/sessions/{id}/context
 *   GET  /agentscope/sessions/{id}/messages?offset=&amp;limit=
 *   GET  /agentscope/sessions/{id}/tasks
 *   GET  /agentscope/subagents
 *   GET  /agentscope/workspaces
 *   POST /agentscope/sessions/{id}/compress
 *   POST /agentscope/sessions/{id}/terminate
 *   POST /agentscope/sessions/{id}/abort
 *   POST /agentscope/teams/join
 *   POST /agentscope/teams/leave
 * </pre>
 *
 * <p>Built on the JDK's {@code com.sun.net.httpserver} so that instrumenting an agent never drags
 * in a web framework or collides with the application's own server.
 */
public final class ContractHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ContractHttpServer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MESSAGE_LIMIT = 100;

    private final HttpServer server;
    private final ContractProvider provider;

    public ContractHttpServer(String host, int port, ContractProvider provider) throws IOException {
        this.provider = provider;
        InetSocketAddress address =
                (host == null || host.isBlank())
                        ? new InetSocketAddress(port)
                        : new InetSocketAddress(host, port);
        this.server = HttpServer.create(address, 0);
        this.server.createContext("/agentscope", this::handle);
        this.server.setExecutor(
                Executors.newCachedThreadPool(
                        r -> {
                            Thread t = new Thread(r, "aistio-contract-http");
                            t.setDaemon(true);
                            return t;
                        }));
    }

    /** Actual bound port, which matters when the configured port was 0. */
    public int getPort() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ContractProvider.NotFoundException e) {
            error(exchange, 404, message(e, "not found"), "not_found", null);
        } catch (UnsupportedOperationException e) {
            error(
                    exchange,
                    501,
                    message(e, "data plane does not support this operation"),
                    "unsupported",
                    null);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "aistio: contract request failed", e);
            error(exchange, 500, message(e, "internal error"), "failed", null);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        List<String> parts = splitPath(exchange.getRequestURI().getPath());
        if (parts.isEmpty() || !"agentscope".equals(parts.get(0))) {
            error(exchange, 404, "not found", "not_found", null);
            return;
        }

        if (parts.size() == 2 && "GET".equals(method)) {
            switch (parts.get(1)) {
                case "info" -> {
                    json(exchange, 200, provider.info());
                    return;
                }
                case "health" -> {
                    json(exchange, 200, Map.of("status", "ok"));
                    return;
                }
                case "sessions" -> {
                    json(exchange, 200, Map.of("sessions", orEmpty(provider.sessions())));
                    return;
                }
                case "subagents" -> {
                    json(exchange, 200, Map.of("subagents", orEmpty(provider.subagents())));
                    return;
                }
                case "workspaces" -> {
                    json(exchange, 200, Map.of("workspaces", orEmpty(provider.workspaces())));
                    return;
                }
                default -> {
                    // Falls through to the 404 below.
                }
            }
        }

        if (parts.size() == 3 && "teams".equals(parts.get(1)) && "POST".equals(method)) {
            String action = parts.get(2);
            if ("join".equals(action) || "leave".equals(action)) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if ("join".equals(action)) {
                    provider.teamJoin(body);
                } else {
                    provider.teamLeave(body);
                }
                json(exchange, 200, Map.of("ok", true));
                return;
            }
        }

        if (parts.size() == 4 && "sessions".equals(parts.get(1))) {
            String sessionId = parts.get(2);
            String action = parts.get(3);
            if ("GET".equals(method)) {
                switch (action) {
                    case "state" -> {
                        json(exchange, 200, provider.sessionState(sessionId));
                        return;
                    }
                    case "context" -> {
                        json(exchange, 200, provider.context(sessionId));
                        return;
                    }
                    case "messages" -> {
                        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
                        int offset = queryInt(query, "offset", 0);
                        int limit = queryInt(query, "limit", DEFAULT_MESSAGE_LIMIT);
                        json(exchange, 200, provider.messages(sessionId, offset, limit));
                        return;
                    }
                    case "tasks" -> {
                        json(exchange, 200, provider.tasks(sessionId));
                        return;
                    }
                    case "subagent-tasks" -> {
                        json(exchange, 200, provider.subagentTasks(sessionId));
                        return;
                    }
                    default -> {
                        // Falls through to the 404 below.
                    }
                }
            } else if ("POST".equals(method)) {
                if ("compress".equals(action)) {
                    provider.compress(sessionId);
                    json(exchange, 200, commandAccepted(exchange, sessionId));
                    return;
                }
                if ("terminate".equals(action)) {
                    provider.terminate(sessionId);
                    json(exchange, 200, commandAccepted(exchange, sessionId));
                    return;
                }
                if ("abort".equals(action)) {
                    provider.abort(sessionId);
                    json(exchange, 200, commandAccepted(exchange, sessionId));
                    return;
                }
                if ("plan-mode".equals(action)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    provider.planMode(sessionId, body);
                    json(exchange, 200, commandAccepted(exchange, sessionId));
                    return;
                }
            } else if ("DELETE".equals(method) && "subagent-tasks".equals(action)) {
                error(exchange, 400, "taskId path segment required", "invalid_argument", null);
                return;
            }
        }

        if (parts.size() == 5
                && "sessions".equals(parts.get(1))
                && "subagent-tasks".equals(parts.get(3))) {
            String sessionId = parts.get(2);
            String taskId = parts.get(4);
            if ("DELETE".equals(method)) {
                provider.cancelSubagentTask(sessionId, taskId);
                json(exchange, 200, commandAccepted(exchange, sessionId));
                return;
            }
        }

        error(exchange, 404, "not found", "not_found", null);
    }

    private Map<String, Object> commandAccepted(HttpExchange exchange, String sessionId) {
        String commandId = exchange.getRequestHeaders().getFirst("X-Command-Id");
        if (commandId == null || commandId.isBlank()) {
            commandId = "cmd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("commandId", commandId);
        out.put("phase", provider.sessionPhase(sessionId));
        out.put("result", Map.of());
        return out;
    }

    private static List<Map<String, Object>> orEmpty(List<Map<String, Object>> value) {
        return value == null ? List.of() : value;
    }

    private static String message(RuntimeException e, String fallback) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? fallback : msg;
    }

    private static List<String> splitPath(String path) {
        List<String> parts = new ArrayList<>(4);
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                parts.add(segment);
            }
        }
        return parts;
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    private static int queryInt(Map<String, String> query, String key, int fallback) {
        String raw = query.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static void error(
            HttpExchange exchange, int status, String message, String code, String hint)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (code != null && !code.isEmpty()) {
            body.put("code", code);
        }
        if (hint != null && !hint.isEmpty()) {
            body.put("hint", hint);
        }
        json(exchange, status, body);
    }
}
