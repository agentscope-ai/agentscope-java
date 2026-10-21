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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.portal.session.PortalRuntimeHosts;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PortalRuntimeHostsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void approvalPollingPreservesTaskIdentityMethodAndEmptyResponse() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/internal/runtime/agent-tasks/task/runtime-approvals/approval",
                exchange -> {
                    calls.incrementAndGet();
                    assertEquals(
                            "task-lease",
                            exchange.getRequestHeaders().getFirst("X-Agent-Task-Token"));
                    assertEquals(
                            "Bearer task-lease",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals(
                            "portal-service",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    if (exchange.getRequestURI().getPath().endsWith("decision")) {
                        assertEquals("GET", exchange.getRequestMethod());
                        assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                        var body = "{\"status\":\"pending\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(202, body.length);
                        exchange.getResponseBody().write(body);
                    } else {
                        assertEquals("POST", exchange.getRequestMethod());
                        assertEquals(
                                2,
                                JSON.readTree(exchange.getRequestBody())
                                        .get("decisionVersion")
                                        .asInt());
                        exchange.sendResponseHeaders(204, -1);
                    }
                    exchange.close();
                });
        server.start();
        try {
            var transport =
                    new PortalRuntimeHosts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "portal-service",
                            1,
                            1);
            assertThrows(
                    ResponseStatusException.class,
                    () -> transport.approval(HttpMethod.GET, null, null, "task").block());
            var pending =
                    transport
                            .approval(
                                    HttpMethod.GET,
                                    "task-lease",
                                    null,
                                    "task",
                                    "runtime-approvals",
                                    "approval",
                                    "decision")
                            .block(Duration.ofSeconds(5));
            assertEquals(HttpStatus.ACCEPTED, pending.getStatusCode());
            var ack =
                    transport
                            .approval(
                                    HttpMethod.POST,
                                    "task-lease",
                                    Map.of("decisionVersion", 2),
                                    "task",
                                    "runtime-approvals",
                                    "approval",
                                    "ack")
                            .block(Duration.ofSeconds(5));
            assertEquals(HttpStatus.NO_CONTENT, ack.getStatusCode());
            assertTrue(ack.getBody() == null);
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void collaborationRetainsTheTaskBearerAlongsideServiceTransport() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/internal/runtime/mcp/collaboration",
                exchange -> {
                    assertEquals(
                            "Bearer task-credential",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals(
                            "portal-service",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    var body = JSON.readTree(exchange.getRequestBody());
                    assertEquals("tools/list", body.get("method").asText());
                    var response =
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var transport =
                    new PortalRuntimeHosts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "portal-service",
                            1,
                            1);
            var result =
                    transport
                            .collaboration(
                                    "Bearer task-credential",
                                    Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                            .block(Duration.ofSeconds(5));
            assertEquals(HttpStatus.OK, result.getStatusCode());
            assertEquals(Map.of("tools", List.of()), result.getBody().get("result"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardingPreservesTheHostCredentialScopeAndFencedError() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/internal/runtime/runtime-hosts",
                exchange -> {
                    calls.incrementAndGet();
                    assertEquals(
                            "Bearer host-credential",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    assertEquals(
                            "portal-service",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    assertEquals(
                            "attempt-credential",
                            exchange.getRequestHeaders().getFirst("X-Execution-Attempt-Token"));
                    assertEquals(
                            "/api/internal/runtime/runtime-hosts/host/execution-attempts/attempt/events",
                            exchange.getRequestURI().getPath());
                    var body = JSON.readTree(exchange.getRequestBody());
                    assertEquals(9, body.get("dispatchGeneration").asInt());
                    assertEquals("scoped-task", body.get("taskId").asText());
                    var response = "{\"error\":\"stale attempt\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Set-Cookie", "private-runtime=ignored");
                    exchange.sendResponseHeaders(409, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            var hosts =
                    new PortalRuntimeHosts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "portal-service",
                            1,
                            1);
            var body = Map.<String, Object>of("dispatchGeneration", 9, "taskId", "scoped-task");
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> hosts.call(null, body, "events", "register").block())
                            .getStatusCode());
            assertEquals(0, calls.get());
            var reply =
                    hosts.call(
                                    "Bearer host-credential",
                                    "attempt-credential",
                                    body,
                                    "events",
                                    "host",
                                    "execution-attempts",
                                    "attempt",
                                    "events")
                            .block(Duration.ofSeconds(5));
            assertEquals(HttpStatus.CONFLICT, reply.getStatusCode());
            assertEquals("stale attempt", reply.getBody().get("error"));
            assertTrue(reply.getHeaders().get("Set-Cookie") == null);
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void saturatedClaimsLeaveHeartbeatCapacityAndCancellationReleasesTheSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var claims = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext(
                "/api/internal/runtime/runtime-hosts",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    if (exchange.getRequestURI().getPath().endsWith("claim")
                            && claims.getAndIncrement() == 0) {
                        entered.countDown();
                        try {
                            finish.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    try {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var hosts =
                    new PortalRuntimeHosts(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "portal-service",
                            1,
                            1);
            var body = Map.<String, Object>of();
            var pending =
                    hosts.call(
                                    "Bearer host-credential",
                                    body,
                                    "claim",
                                    "host",
                                    "execution-attempts",
                                    "claim")
                            .toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure =
                    assertThrows(
                            ResponseStatusException.class,
                            () ->
                                    hosts.call(
                                                    "Bearer host-credential",
                                                    body,
                                                    "claim",
                                                    "host",
                                                    "execution-attempts",
                                                    "claim")
                                            .block());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.getStatusCode());
            assertEquals(
                    HttpStatus.OK,
                    hosts.call("Bearer host-credential", body, "heartbeat", "host", "heartbeat")
                            .block(Duration.ofSeconds(5))
                            .getStatusCode());
            pending.cancel(true);
            assertEquals(
                    HttpStatus.OK,
                    hosts.call(
                                    "Bearer host-credential",
                                    body,
                                    "claim",
                                    "host",
                                    "execution-attempts",
                                    "claim")
                            .block(Duration.ofSeconds(5))
                            .getStatusCode());
        } finally {
            finish.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
