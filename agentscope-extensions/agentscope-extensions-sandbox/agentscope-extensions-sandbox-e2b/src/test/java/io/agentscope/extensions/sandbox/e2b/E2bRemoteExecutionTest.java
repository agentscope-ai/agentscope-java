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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class E2bRemoteExecutionTest {
    @Test
    void silentCommandOutlivesReadTimeout() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.delayMillis = 1500;
            var options = f.options();
            options.setReadTimeoutSeconds(1);
            var result =
                    new E2bEnvdProcessClient(options).runShell(f.state(), "/workspace", "sleep", 5);
            assertEquals(0, result.exitCode());
            assertFalse(f.stopped.getCount() == 0);
        }
    }

    @Test
    void deadlineKillsRemoteProcessAndConfirmsItsAbsence() throws Exception {
        try (var f = new Fixture(true, false)) {
            assertThrows(
                    SandboxException.ExecTimeoutException.class,
                    () ->
                            new E2bEnvdProcessClient(f.options())
                                    .runShell(f.state(), "/workspace", "sleep", 1));
            assertTrue(f.stopped.await(1, TimeUnit.SECONDS));
            assertTrue(
                    f.signal.get().path("process").path("tag").asText().startsWith("agentscope-"));
            assertEquals("SIGNAL_SIGTERM", f.signal.get().path("signal").asText());
        }
    }

    @Test
    void interruptionKillsTheProcessRatherThanOnlyDisconnecting() throws Exception {
        try (var f = new Fixture(true, false)) {
            var failure = new AtomicReference<Throwable>();
            var thread =
                    new Thread(
                            () -> {
                                try {
                                    new E2bEnvdProcessClient(f.options())
                                            .runShell(f.state(), "/workspace", "sleep", 30);
                                } catch (Throwable error) {
                                    failure.set(error);
                                }
                            });
            thread.start();
            assertTrue(f.started.await(3, TimeUnit.SECONDS));
            thread.interrupt();
            thread.join(10000);
            assertFalse(thread.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertEquals(0, f.stopped.getCount());
        }
    }

    @Test
    void unconfirmedTerminationNeverReportsAKnownTimeout() throws Exception {
        try (var f = new Fixture(true, true)) {
            var failure =
                    assertThrows(
                            IOException.class,
                            () ->
                                    new E2bEnvdProcessClient(f.options())
                                            .runShell(f.state(), "/workspace", "sleep", 1));
            assertTrue(failure.getMessage().contains("outcome unknown"));
        }
    }

    @Test
    void borrowedInstanceUsesLookupAndNeverCreatesRenewsOrDeletes() throws Exception {
        try (var f = new Fixture(false, false)) {
            var methods = new CopyOnWriteArrayList<String>();
            f.server.createContext(
                    "/sandboxes",
                    exchange -> {
                        methods.add(
                                exchange.getRequestMethod()
                                        + " "
                                        + exchange.getRequestURI().getPath());
                        byte[] reply =
                                "{\"sandboxID\":\"borrowed\",\"domain\":\"example.test\",\"envdAccessToken\":\"lookup-token\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, reply.length);
                        exchange.getResponseBody().write(reply);
                        exchange.close();
                    });
            var sandbox =
                    new E2bSandboxClient(f.options(), null).connectExisting("borrowed", "access");
            sandbox.start();
            sandbox.close();
            assertEquals(List.of("GET /sandboxes/borrowed"), methods);
            assertFalse(((E2bSandboxState) sandbox.getState()).isSandboxOwned());
            assertEquals("lookup-token", f.accessToken.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newCachedThreadPool();
        final CountDownLatch started = new CountDownLatch(1), stopped = new CountDownLatch(1);
        final AtomicReference<JsonNode> signal = new AtomicReference<>();
        final AtomicReference<String> accessToken = new AtomicReference<>();
        long delayMillis;

        Fixture(boolean waitForStop, boolean rejectStop) throws Exception {
            server.setExecutor(executor);
            server.createContext(
                    "/process.Process/Start",
                    exchange -> {
                        accessToken.set(exchange.getRequestHeaders().getFirst("X-Access-Token"));
                        exchange.getRequestBody().readAllBytes();
                        exchange.getResponseHeaders()
                                .set("Content-Type", "application/connect+json");
                        exchange.sendResponseHeaders(200, 0);
                        try {
                            exchange.getResponseBody()
                                    .write(
                                            E2bEnvdProcessClient.encodeUnaryEnvelope(
                                                    "{\"event\":{\"start\":{\"pid\":42}}}"
                                                            .getBytes(StandardCharsets.UTF_8)));
                            exchange.getResponseBody().flush();
                            started.countDown();
                            if (waitForStop) stopped.await(15, TimeUnit.SECONDS);
                            else Thread.sleep(delayMillis);
                            exchange.getResponseBody()
                                    .write(
                                            E2bEnvdProcessClient.encodeUnaryEnvelope(
                                                    "{\"event\":{\"end\":{\"exitCode\":0}}}"
                                                            .getBytes(StandardCharsets.UTF_8)));
                        } catch (Exception ignored) {
                            /* The client may cancel after requesting termination. */
                        } finally {
                            exchange.close();
                        }
                    });
            server.createContext(
                    "/process.Process/SendSignal",
                    exchange -> {
                        signal.set(new ObjectMapper().readTree(exchange.getRequestBody()));
                        if (!rejectStop) stopped.countDown();
                        byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(rejectStop ? 503 : 200, reply.length);
                        exchange.getResponseBody().write(reply);
                        exchange.close();
                    });
            server.createContext(
                    "/process.Process/List",
                    exchange -> {
                        byte[] reply = "{\"processes\":[]}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, reply.length);
                        exchange.getResponseBody().write(reply);
                        exchange.close();
                    });
            server.start();
        }

        E2bSandboxClientOptions options() {
            var options = new E2bSandboxClientOptions();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            options.setApiBaseUrl(base);
            options.setEnvdBaseUrl(base);
            options.setApiKey("test");
            options.setCodec(E2bCodec.JSON);
            options.setWorkspaceRoot("/workspace");
            options.setMaxRetries(0);
            return options;
        }

        E2bSandboxState state() {
            var state = new E2bSandboxState();
            state.setSandboxId("test");
            return state;
        }

        public void close() {
            stopped.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
