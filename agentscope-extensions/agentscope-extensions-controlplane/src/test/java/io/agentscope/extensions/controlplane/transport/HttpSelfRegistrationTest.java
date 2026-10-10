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
package io.agentscope.extensions.controlplane.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class HttpSelfRegistrationTest {

    @Test
    void queuedHeartbeatDoesNotRetryRegistrationAfterClose() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/",
                exchange -> {
                    requests.incrementAndGet();
                    if (exchange.getRequestMethod().equals("DELETE")) {
                        respond(exchange, 204, "");
                    } else {
                        respond(
                                exchange,
                                201,
                                "{\"agent\":{\"id\":\"agent\"},\"binding\":{\"id\":\"binding\"},"
                                        + "\"instance\":{\"id\":\"instance\",\"generation\":7},"
                                        + "\"registrationCredential\":\"asreg_test\"}");
                    }
                });
        server.start();
        Handler handler = mock(Handler.class);
        Logger logger = Logger.getLogger(HttpSelfRegistration.class.getName());
        logger.addHandler(handler);
        try (HttpSelfRegistration registration =
                new HttpSelfRegistration(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "secret-token",
                        "",
                        "reviewer",
                        "tenant-a",
                        "namespace-a",
                        "runtime-instance-key",
                        "http://127.0.0.1:9191",
                        "agentscope-java",
                        "agentscope",
                        3,
                        List.of("sessions"),
                        20)) {
            Thread pending = null;
            // Hold the lifecycle lock until the scheduled callback is waiting for it, then
            // close before releasing it. Assertions check HTTP traffic and retry diagnostics.
            synchronized (registration) {
                registration.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (pending == null && System.nanoTime() < deadline) {
                    for (Thread thread : Thread.getAllStackTraces().keySet()) {
                        if (thread.getName().equals("controlplane-http-register")
                                && thread.getState() == Thread.State.BLOCKED) {
                            pending = thread;
                            break;
                        }
                    }
                    if (pending == null) {
                        Thread.sleep(5);
                    }
                }
                assertTrue(pending != null, "Heartbeat should be waiting before close");
                registration.close();
            }
            pending.join(2000);
            assertFalse(pending.isAlive(), "Registration worker should stop after close");
            assertEquals(2, requests.get(), "Only registration and unregister requests expected");
            verify(handler, never())
                    .publish(
                            argThat(
                                    record ->
                                            record.getLevel().intValue()
                                                    >= Level.WARNING.intValue()));
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    void registrationUsesTenantAndDurableIdentityForHeartbeatAndDelete() throws Exception {
        String durableId = "50b14458-e59b-4f53-bf51-bb96b7e4a1af";
        CountDownLatch heartbeat = new CountDownLatch(1);
        CountDownLatch releaseHeartbeat = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        AtomicBoolean heartbeatCompleted = new AtomicBoolean();
        AtomicBoolean deletedAfterHeartbeat = new AtomicBoolean();
        AtomicReference<JsonNode> registrationBody = new AtomicReference<>();
        AtomicReference<JsonNode> heartbeatBody = new AtomicReference<>();
        AtomicReference<JsonNode> deleteBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(
                "/api/v1/agent-registrations",
                exchange -> {
                    authHeader.set(
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    registrationBody.set(readJson(exchange));
                    respond(
                            exchange,
                            201,
                            "{\"agent\":{\"id\":\"11111111-1111-1111-1111-111111111111\"},"
                                + "\"binding\":{\"id\":\"22222222-2222-2222-2222-222222222222\"},"
                                + "\"instance\":{\"id\":\""
                                    + durableId
                                    + "\",\"generation\":7},"
                                    + "\"registrationCredential\":\"asreg_test\"}");
                });
        server.createContext(
                "/api/v1/dataplanes/" + durableId + "/heartbeat",
                exchange -> {
                    heartbeatBody.set(readJson(exchange));
                    heartbeat.countDown();
                    try {
                        if (!releaseHeartbeat.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Heartbeat was not released by the test");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    heartbeatCompleted.set(true);
                    respond(exchange, 200, "{\"generation\":7,\"status\":\"ok\"}");
                });
        server.createContext(
                "/api/v1/dataplanes/" + durableId,
                exchange -> {
                    deleteBody.set(readJson(exchange));
                    deletedAfterHeartbeat.set(heartbeatCompleted.get());
                    deleted.countDown();
                    respond(exchange, 204, "");
                });
        server.start();

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpSelfRegistration registration =
                new HttpSelfRegistration(
                        endpoint,
                        "secret-token",
                        "",
                        "reviewer",
                        "tenant-a",
                        "namespace-a",
                        "runtime-instance-key",
                        "http://127.0.0.1:9191",
                        "agentscope-java",
                        "agentscope",
                        3,
                        List.of("sessions"),
                        20)) {
            registration.start();
            assertTrue(heartbeat.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));

            assertEquals("tenant-a", registrationBody.get().path("tenant").asText());
            assertEquals("namespace-a", registrationBody.get().path("namespace").asText());
            assertEquals(
                    "runtime-instance-key", registrationBody.get().path("instanceKey").asText());
            assertEquals("reviewer", registrationBody.get().path("agentKey").asText());
            assertEquals("secret-token", authHeader.get());
            assertEquals(7, heartbeatBody.get().path("generation").asLong());
            Future<?> closing = executor.submit(registration::close);
            try {
                assertThrows(
                        TimeoutException.class,
                        () -> closing.get(100, TimeUnit.MILLISECONDS),
                        "Close must finish the in-flight heartbeat before unregistering");
            } finally {
                releaseHeartbeat.countDown();
            }
            closing.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHeartbeat.countDown();
            try {
                assertTrue(deleted.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
            } finally {
                server.stop(0);
                executor.shutdownNow();
            }
        }
        assertTrue(deletedAfterHeartbeat.get());
        assertEquals(7, deleteBody.get().path("generation").asLong());
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return ControlPlaneHttpClient.mapper().createObjectNode();
        }
        return ControlPlaneHttpClient.mapper().readTree(bytes);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
