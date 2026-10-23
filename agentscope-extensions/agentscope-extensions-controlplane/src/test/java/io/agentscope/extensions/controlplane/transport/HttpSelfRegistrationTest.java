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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.lang.reflect.Field;
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

    /**
     * A heartbeat re-register cycle can clear {@code registered} while the identity
     * is still set; close() must claim the surviving identity under the lifecycle
     * lock and DELETE it anyway, instead of skipping on the stale {@code registered}
     * flag (previously a flaky leak on loaded CI runners).
     */
    @Test
    void closeStillDeletesWhenHeartbeatCycleClearedRegisteredFlag() throws Exception {
        String durableId = "50b14458-e59b-4f53-bf51-bb96b7e4a1af";
        CountDownLatch heartbeat = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        AtomicInteger deleteCount = new AtomicInteger();
        AtomicReference<JsonNode> deleteBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/agent-registrations",
                exchange -> {
                    readJson(exchange);
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
                    readJson(exchange);
                    heartbeat.countDown();
                    respond(exchange, 200, "{\"generation\":7,\"status\":\"ok\"}");
                });
        server.createContext(
                "/api/v1/dataplanes/" + durableId,
                exchange -> {
                    deleteBody.set(readJson(exchange));
                    deleted.countDown();
                    deleteCount.incrementAndGet();
                    respond(exchange, 204, "");
                });
        server.start();

        // The registration variable stays in scope after the try block so the
        // assertions below can run once close() has deterministically dropped the
        // cached identity.
        HttpSelfRegistration registration = newRegistered(server);
        try {
            registration.start();
            assertTrue(heartbeat.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));

            // Force the exact state heartbeatSafe() leaves behind on a failed
            // heartbeat (404 / exception): registered cleared, identity still set.
            Field registeredField = HttpSelfRegistration.class.getDeclaredField("registered");
            registeredField.setAccessible(true);
            ((AtomicBoolean) registeredField.get(registration)).set(false);

            // close() must claim the surviving identity and DELETE it anyway
            registration.close();
        } finally {
            registration.close();
        }
        assertTrue(
                deleted.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                "close() must not skip the DELETE when `registered` was cleared by a"
                        + " heartbeat re-register cycle");
        assertEquals(7, deleteBody.get().path("generation").asLong());
        assertEquals(1, deleteCount.get(), "deregistration must be exactly-once");
        server.stop(0);
    }

    /**
     * Lifecycle is single-valued: after {@code close()}, {@code start()} is refused
     * (a restart would replay the registration credential of a deleted instance)
     * and {@code identity()} no longer reports the forgotten identity.
     */
    @Test
    void startAfterCloseIsRejectedAndIdentityIsCleared() throws Exception {
        String durableId = "50b14458-e59b-4f53-bf51-bb96b7e4a1af";
        CountDownLatch heartbeat = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        AtomicInteger deleteCount = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/agent-registrations",
                exchange -> {
                    readJson(exchange);
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
                    readJson(exchange);
                    heartbeat.countDown();
                    respond(exchange, 200, "{\"generation\":7,\"status\":\"ok\"}");
                });
        server.createContext(
                "/api/v1/dataplanes/" + durableId,
                exchange -> {
                    deleted.countDown();
                    deleteCount.incrementAndGet();
                    respond(exchange, 204, "");
                });
        server.start();

        HttpSelfRegistration registration = newRegistered(server);
        try {
            registration.start();
            assertTrue(heartbeat.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            assertNotNull(registration.identity(), "identity should be present while registered");
        } finally {
            registration.close();
        }
        assertTrue(
                deleted.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                "close() must send the control-plane DELETE");
        assertNull(registration.identity(), "identity must be cleared once the instance is closed");
        assertThrows(
                IllegalStateException.class,
                registration::start,
                "start() after close() must be refused, not replay the old credential");
        assertEquals(1, deleteCount.get(), "deregistration must be exactly-once");
        server.stop(0);
    }

    /**
     * Drives the race the lifecycle lock exists for: a registration round trip that
     * completes AFTER `closed` was set must be deleted instead of published (no
     * resurrection of registeredInstanceId/identity). `closed` is set via reflection
     * WITHOUT calling close(), because a real close() would interrupt the in-flight
     * registration request through shutdownNow() — here we only need the state
     * close() has already claimed: an identity still in flight, `closed` already set.
     */
    @Test
    @SuppressWarnings("unchecked")
    void registrationCompletedAfterCloseIsDeletedInsteadOfPublished() throws Exception {
        String durableId = "50b14458-e59b-4f53-bf51-bb96b7e4a1af";
        CountDownLatch firstHeartbeat = new CountDownLatch(1);
        CountDownLatch secondRegisterArrived = new CountDownLatch(1);
        CountDownLatch releaseSecondRegister = new CountDownLatch(1);
        CountDownLatch selfDeleted = new CountDownLatch(1);
        AtomicInteger deleteCount = new AtomicInteger();
        AtomicInteger registrations = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Registration handler: call 1 succeeds; call 2 signals arrival and holds its
        // response until released; later calls fail with 500 (no more publications)
        server.createContext(
                "/api/v1/agent-registrations",
                exchange -> {
                    readJson(exchange);
                    int n = registrations.incrementAndGet();
                    if (n == 1) {
                        respond(
                                exchange,
                                201,
                                "{\"agent\":{\"id\":\"11111111-1111-1111-1111-111111111111\"},"
                                    + "\"binding\":{\"id\":\"22222222-2222-2222-2222-222222222222\"},"
                                    + "\"instance\":{\"id\":\""
                                        + durableId
                                        + "\",\"generation\":7},"
                                        + "\"registrationCredential\":\"asreg_test\"}");
                        return;
                    }
                    secondRegisterArrived.countDown();
                    try {
                        releaseSecondRegister.await(
                                Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    respond(
                            exchange,
                            n == 2 ? 201 : 500,
                            n == 2
                                    ? "{\"agent\":{\"id\":\"11111111-1111-1111-1111-111111111111\"},"
                                          + "\"binding\":{\"id\":\"22222222-2222-2222-2222-222222222222\"},"
                                          + "\"instance\":{\"id\":\""
                                            + durableId
                                            + "\",\"generation\":8},"
                                            + "\"registrationCredential\":\"asreg_re\"}"
                                    : "{}");
                });
        // First heartbeat succeeds, later heartbeats return 404 to drive re-register
        server.createContext(
                "/api/v1/dataplanes/" + durableId + "/heartbeat",
                exchange -> {
                    readJson(exchange);
                    if (firstHeartbeat.getCount() == 1) {
                        firstHeartbeat.countDown();
                        respond(exchange, 200, "{\"generation\":7,\"status\":\"ok\"}");
                    } else {
                        respond(exchange, 404, "{\"error\":\"gone\"}");
                    }
                });
        server.createContext(
                "/api/v1/dataplanes/" + durableId,
                exchange -> {
                    readJson(exchange);
                    deleteCount.incrementAndGet();
                    selfDeleted.countDown();
                    respond(exchange, 204, "");
                });
        server.start();

        HttpSelfRegistration registration = newRegistered(server);
        try {
            registration.start();
            assertTrue(
                    firstHeartbeat.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(
                    secondRegisterArrived.await(
                            Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            // Simulate close() having claimed the previously registered identity: set
            // `closed` and clear the id via reflection WITHOUT shutdownNow, so the
            // in-flight registration is not interrupted and completes afterwards.
            Field closedField = HttpSelfRegistration.class.getDeclaredField("closed");
            closedField.setAccessible(true);
            ((AtomicBoolean) closedField.get(registration)).set(true);
            Field idField = HttpSelfRegistration.class.getDeclaredField("registeredInstanceId");
            idField.setAccessible(true);
            ((AtomicReference<String>) idField.get(registration)).set(null);
            // Release the held registration: it now completes after `closed` was set,
            // so the lifecycle lock must delete it instead of publishing it
            releaseSecondRegister.countDown();
            assertTrue(
                    selfDeleted.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                    "a registration completing after close() must be deleted, not published");
            assertEquals(
                    1,
                    deleteCount.get(),
                    "the self-delete is the only DELETE for the post-close registration");
        } finally {
            registration.close();
        }
        // Assert identity AFTER close(): the client thread clears it after receiving
        // the 204 response, so asserting inside the try block would race it; close()
        // is synchronized and deterministically drops the cached identity.
        assertNull(
                registration.identity(),
                "the discarded registration must not be published as identity");
        server.stop(0);
    }

    /** Build a registration bound to the given local test server. */
    private static HttpSelfRegistration newRegistered(HttpServer server) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HttpSelfRegistration(
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
                20);
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
