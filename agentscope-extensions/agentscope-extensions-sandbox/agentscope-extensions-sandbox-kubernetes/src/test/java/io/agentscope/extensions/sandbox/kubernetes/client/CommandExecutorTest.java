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
package io.agentscope.extensions.sandbox.kubernetes.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.extensions.sandbox.kubernetes.client.config.DirectConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxRequestException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.ExecutionResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private ExecutorService workers;
    private final AtomicReference<JsonNode> payload = new AtomicReference<>();
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicInteger capabilityRequests = new AtomicInteger();
    private boolean capabilitiesSupported = true;
    private int capabilityFailuresRemaining;
    private long delayMillis;
    private boolean headersBeforeDelay;
    private int responseStatus = 200;
    private String capabilities =
            "{\"execute\":{\"timeout_seconds\":true,\"max_timeout_seconds\":900,\"cleanup_timeout_seconds\":6}}";
    private String response = "{\"stdout\":\"ok\",\"stderr\":\"\",\"exit_code\":0}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext(
                "/capabilities",
                exchange -> {
                    int request = capabilityRequests.incrementAndGet();
                    byte[] body = capabilities.getBytes(StandardCharsets.UTF_8);
                    int status =
                            !capabilitiesSupported
                                    ? 404
                                    : request <= capabilityFailuresRemaining ? 503 : 200;
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/execute",
                exchange -> {
                    executions.incrementAndGet();
                    payload.set(MAPPER.readTree(exchange.getRequestBody()));
                    try {
                        byte[] body = response.getBytes(StandardCharsets.UTF_8);
                        if (headersBeforeDelay) {
                            exchange.sendResponseHeaders(responseStatus, body.length);
                            exchange.getResponseBody().flush();
                        }
                        Thread.sleep(delayMillis);
                        if (responseStatus == 307) {
                            exchange.getResponseHeaders().add("Location", "/replayed");
                        }
                        if (!headersBeforeDelay) {
                            exchange.sendResponseHeaders(responseStatus, body.length);
                        }
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                "/replayed",
                exchange -> {
                    executions.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        workers.shutdownNow();
    }

    private CommandExecutor executor(Duration networkBudget) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        SandboxConnector connector =
                new SandboxConnector(
                        "test",
                        "test",
                        new DirectConnectionConfig(url),
                        null,
                        null,
                        networkBudget,
                        networkBudget);
        return new CommandExecutor(connector);
    }

    @Test
    void sendsTimeoutAndWaitsBeyondConnectorDefault() {
        delayMillis = 1100;
        assertEquals(
                0,
                executor(Duration.ofMillis(500))
                        .run("sleep 150", Duration.ofSeconds(300))
                        .getExitCode());
        assertEquals("sleep 150", payload.get().path("command").asText());
        assertEquals(300, payload.get().path("timeout_seconds").asInt());
        assertEquals(1, executions.get());
    }

    @Test
    void legacyRuntimeIsRejectedBeforeCommandStarts() {
        capabilitiesSupported = false;
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () ->
                                executor(Duration.ofSeconds(2))
                                        .run("write-side-effect", Duration.ofSeconds(300)));
        assertTrue(error instanceof SandboxRequestException.RuntimeCapabilityException);
        assertEquals(404, error.getStatusCode());
        assertFalse(error.isExecutionOutcomeUnknown());
        assertTrue(error.getMessage().contains("timeout"));
        assertEquals(0, executions.get());
    }

    @Test
    void capabilityPreflightRetriesTransientFailureBeforeSubmittingOnce() {
        capabilityFailuresRemaining = 1;

        assertEquals(0, executor(Duration.ofSeconds(2)).run("side-effect").getExitCode());

        assertEquals(2, capabilityRequests.get());
        assertEquals(1, executions.get());
    }

    @Test
    void capabilityPreflightExhaustionDoesNotSubmitCommand() {
        capabilityFailuresRemaining = Integer.MAX_VALUE;

        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () -> executor(Duration.ofSeconds(2)).run("side-effect"));

        assertTrue(error instanceof SandboxRequestException.RuntimeCapabilityException);
        assertEquals(3, capabilityRequests.get());
        assertEquals(0, executions.get());
    }

    @Test
    void rejectsUnsupportedTimeoutBeforeCommandStarts() {
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () ->
                                executor(Duration.ofSeconds(2))
                                        .run("write-side-effect", Duration.ofSeconds(901)));
        assertEquals(422, error.getStatusCode());
        assertEquals(0, executions.get());
    }

    @Test
    void invalidCapabilitiesDoNotStartCommand() {
        for (String invalid :
                new String[] {
                    "{}",
                    "null",
                    "{\"execute\":{\"timeout_seconds\":false}}",
                    "{\"execute\":{\"timeout_seconds\":true,\"max_timeout_seconds\":900,\"cleanup_timeout_seconds\":-1}}"
                }) {
            capabilities = invalid;
            assertThrows(
                    SandboxRequestException.class,
                    () -> executor(Duration.ofSeconds(2)).run("side-effect"));
        }
        assertEquals(0, executions.get());
    }

    @Test
    void rejectsNonPositiveAndFractionalTimeoutsBeforeSendingCommand() {
        CommandExecutor executor = executor(Duration.ofSeconds(2));
        for (Duration timeout :
                new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1500)}) {
            assertThrows(
                    IllegalArgumentException.class, () -> executor.run("echo invalid", timeout));
        }
        assertEquals(0, executions.get());
    }

    @Test
    void preservesConfirmedTimeoutAndPartialOutput() {
        response =
                "{\"stdout\":\"before\",\"stderr\":\"Command timed out after 2"
                        + " seconds\",\"exit_code\":124,\"timed_out\":true}";
        ExecutionResult result =
                executor(Duration.ofSeconds(2)).run("echo before; sleep 10", Duration.ofSeconds(2));
        assertEquals(124, result.getExitCode());
        assertEquals("before", result.getStdout());
        assertTrue(result.getStderr().contains("2 seconds"));
        assertTrue(result.isTimedOut());
    }

    @Test
    void defaultTimeoutIsExplicitOnWire() {
        assertEquals(0, executor(Duration.ofSeconds(2)).run("echo default").getExitCode());
        assertEquals(60, payload.get().path("timeout_seconds").asInt());
    }

    @Test
    void nullTimeoutUsesDefaultAndIsExplicitOnWire() {
        assertEquals(0, executor(Duration.ofSeconds(2)).run("echo default", null).getExitCode());
        assertEquals(60, payload.get().path("timeout_seconds").asInt());
    }

    @Test
    void nonZeroProcessExitIsPreserved() {
        response = "{\"stdout\":\"\",\"stderr\":\"not found\",\"exit_code\":127}";
        ExecutionResult result = executor(Duration.ofSeconds(2)).run("nonexistent");
        assertEquals(127, result.getExitCode());
        assertEquals("not found", result.getStderr());
    }

    @Test
    void httpFailureIsUnknownAndIsNotRetried() {
        responseStatus = 504;
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () -> executor(Duration.ofSeconds(2)).run("side-effect"));
        assertTrue(error.isExecutionOutcomeUnknown());
        assertTrue(error.getMessage().contains("inspect command state"));
        assertEquals(1, executions.get());
    }

    @Test
    void redirectDoesNotReplayCommand() {
        responseStatus = 307;
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () -> executor(Duration.ofSeconds(2)).run("side-effect"));
        assertTrue(error.isExecutionOutcomeUnknown(), error.getMessage());
        assertEquals(1, executions.get());
    }

    @Test
    void missingExitCodeIsUnknown() {
        response = "{\"stdout\":\"partial\",\"stderr\":\"\"}";
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () -> executor(Duration.ofSeconds(2)).run("side-effect"));
        assertTrue(error.isExecutionOutcomeUnknown());
        assertEquals(1, executions.get());
    }

    @Test
    void responseBodyStallHonorsWholeHttpDeadline() {
        capabilities =
                "{\"execute\":{\"timeout_seconds\":true,\"max_timeout_seconds\":900,\"cleanup_timeout_seconds\":0}}";
        headersBeforeDelay = true;
        delayMillis = 5000;
        SandboxRequestException error =
                assertThrows(
                        SandboxRequestException.class,
                        () ->
                                executor(Duration.ofSeconds(2))
                                        .run("body-stall", Duration.ofSeconds(1)));
        assertTrue(error.isExecutionOutcomeUnknown());
        assertEquals(1, executions.get());
    }

    @Test
    void concurrentCallsKeepIndependentHttpDeadlines() throws Exception {
        capabilities =
                "{\"execute\":{\"timeout_seconds\":true,\"max_timeout_seconds\":900,\"cleanup_timeout_seconds\":0}}";
        delayMillis = 1800;
        CommandExecutor executor = executor(Duration.ofMillis(500));
        Future<SandboxRequestException> shortCall =
                workers.submit(
                        () ->
                                assertThrows(
                                        SandboxRequestException.class,
                                        () -> executor.run("short", Duration.ofSeconds(1))));
        Future<ExecutionResult> longCall =
                workers.submit(() -> executor.run("long", Duration.ofSeconds(3)));
        assertTrue(shortCall.get().isExecutionOutcomeUnknown());
        assertEquals(0, longCall.get().getExitCode());
        assertEquals(2, executions.get());
    }
}
