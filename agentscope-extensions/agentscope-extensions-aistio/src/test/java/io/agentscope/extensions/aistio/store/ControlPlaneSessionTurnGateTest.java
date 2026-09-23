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
package io.agentscope.extensions.aistio.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ControlPlaneSessionTurnGate} against an in-process lock stub. */
class ControlPlaneSessionTurnGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "test-token";
    private static final String AGENT = "demo-agent";
    private static final String NS = "default";

    private HttpServer server;
    private String baseUrl;
    private ControlPlaneSessionTurnGate gate;
    private final ConcurrentHashMap<String, HeldLock> locks = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        locks.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/dp/locks/acquire", this::handleAcquire);
        server.createContext("/api/v1/dp/locks/renew", this::handleRenew);
        server.createContext("/api/v1/dp/locks/release", this::handleRelease);
        server.createContext("/api/v1/dp/locks", this::handlePeek);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        gate =
                (ControlPlaneSessionTurnGate)
                        ControlPlaneStores.create(baseUrl, TOKEN, AGENT, NS).sessionTurnGate();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acquireSucceedsAndReleases() throws Exception {
        TurnLease lease = gate.acquire("session-1");
        assertNotNull(lease);
        assertTrue(gate.isRunning("session-1"));
        lease.close();
        assertFalse(gate.isRunning("session-1"));
    }

    @Test
    void acquireConflictThrowsTurnBusy() throws Exception {
        TurnLease first = gate.acquire("busy-session");
        assertThrows(TurnBusyException.class, () -> gate.acquire("busy-session"));
        first.close();
    }

    @Test
    void isRunningUsesLockPeek() throws Exception {
        assertFalse(gate.isRunning("peek-session"));
        TurnLease lease = gate.acquire("peek-session");
        assertTrue(gate.isRunning("peek-session"));
        lease.close();
        assertFalse(gate.isRunning("peek-session"));
    }

    private void handleAcquire(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> body = readJson(exchange);
        String tenant = tenant(body);
        String name = string(body.get("name"));
        String key = tenant + "/" + name;

        HeldLock existing = locks.get(key);
        if (existing != null) {
            writeJson(exchange, 409, Map.of("error", "conflict", "holder", existing.holder));
            return;
        }

        String ownerToken = UUID.randomUUID().toString();
        locks.put(key, new HeldLock(name, ownerToken, string(body.get("holder"))));
        writeJson(
                exchange,
                200,
                Map.of(
                        "ownerToken",
                        ownerToken,
                        "fencingToken",
                        1,
                        "expiresAt",
                        "2099-01-01T00:00:00Z"));
    }

    private void handleRenew(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> body = readJson(exchange);
        String key = tenant(body) + "/" + string(body.get("name"));
        HeldLock held = locks.get(key);
        if (held == null || !held.ownerToken.equals(string(body.get("ownerToken")))) {
            writeJson(exchange, 409, Map.of("error", "conflict"));
            return;
        }
        writeJson(exchange, 200, Map.of("expiresAt", "2099-01-01T00:00:00Z"));
    }

    private void handleRelease(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        Map<String, Object> body = readJson(exchange);
        String key = tenant(body) + "/" + string(body.get("name"));
        HeldLock held = locks.get(key);
        if (held != null && held.ownerToken.equals(string(body.get("ownerToken")))) {
            locks.remove(key);
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void handlePeek(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQuery(query);
        String agentName = params.get("agentName");
        String namespace = params.getOrDefault("namespace", "default");
        String name = params.get("name");
        String key = namespace + "/" + agentName + "/" + name;

        if (locks.containsKey(key)) {
            HeldLock held = locks.get(key);
            writeJson(
                    exchange,
                    200,
                    Map.of(
                            "name",
                            held.name,
                            "ownerToken",
                            held.ownerToken,
                            "holder",
                            held.holder));
            return;
        }
        writeJson(exchange, 404, Map.of("error", "not held"));
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token");
        if (!TOKEN.equals(token)) {
            exchange.sendResponseHeaders(401, -1);
            return false;
        }
        return true;
    }

    private static String tenant(Map<String, Object> body) {
        String agentName = string(body.get("agentName"));
        String namespace = string(body.get("namespace"));
        if (namespace.isBlank()) {
            namespace = "default";
        }
        return namespace + "/" + agentName;
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        return MAPPER.readValue(bytes, Map.class);
    }

    private static void writeJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(k, v);
        }
        return params;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record HeldLock(String name, String ownerToken, String holder) {}
}
