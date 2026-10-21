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
package io.agentscope.builder.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor.State;
import io.agentscope.builder.runtime.managed.ManagedWorkerProtocol;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(10)
class ManagedEnvironmentWorkerCancellationTest {
    @TempDir Path root;

    @ParameterizedTest
    @EnumSource(
            value = State.class,
            names = {"UNKNOWN", "NOT_FOUND"})
    void recoveredUnknownExecutionCanBeConfirmedCancelled(State state) throws Exception {
        exercise(state, new Outcome(State.CANCELLED, "{}"), true, "captured");
    }

    @Test
    void unconfirmedCancellationIsReportedAsUnknown() throws Exception {
        exercise(State.UNKNOWN, new Outcome(State.UNKNOWN, null), false, "captured");
    }

    @Test
    void aRetiredWorkspaceReceiptCannotDiscardTheDurableResult() throws Exception {
        exercise(State.UNKNOWN, new Outcome(State.CANCELLED, "{}"), true, "committed");
    }

    void exercise(State observation, Outcome cancellation, boolean confirmed, String receipt)
            throws Exception {
        var mapper = new ObjectMapper();
        var reply = new AtomicReference<ManagedWorkerProtocol.Result>();
        var unknowns = new AtomicInteger();
        var cancels = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/handss/environments/",
                exchange -> {
                    String action =
                            exchange.getRequestURI()
                                    .getPath()
                                    .substring(
                                            exchange.getRequestURI().getPath().lastIndexOf('/')
                                                    + 1);
                    Object value;
                    switch (action) {
                        case "renew" -> value = Map.of("ttl_seconds", 60, "cancel_requested", true);
                        case "unknown" -> {
                            unknowns.incrementAndGet();
                            value = Map.of("status", "unknown");
                        }
                        case "result" -> {
                            reply.set(
                                    mapper.readValue(
                                            exchange.getRequestBody(),
                                            ManagedWorkerProtocol.Result.class));
                            value = Map.of("status", receipt);
                        }
                        default -> throw new IllegalArgumentException(action);
                    }
                    byte[] body = mapper.writeValueAsBytes(value);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        var scope = new ManagedScope("t", "n", "s");
        var lease = new ManagedWorkerProtocol.Lease(scope, "task", "task", "token", "worker");
        var assignment =
                new ManagedWorkerProtocol.Assignment(
                        lease,
                        new ManagedWorkerProtocol.Task("task", "turn", "fs.read", "{}", null),
                        false,
                        Map.of(),
                        Map.of());
        ManagedEnvironmentWorker.SandboxExecutors providers =
                ignored ->
                        new ManagedToolExecutor() {
                            public String start(ManagedScope owner, ToolTask task) {
                                fail("A recovered unknown execution must never start again");
                                return null;
                            }

                            public Outcome query(ManagedScope owner, ToolTask task) {
                                return new Outcome(observation, null);
                            }

                            public Outcome cancel(ManagedScope owner, ToolTask task) {
                                cancels.incrementAndGet();
                                return cancellation;
                            }
                        };
        try (var worker =
                new ManagedEnvironmentWorker(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        "env",
                        "key",
                        root,
                        1,
                        providers)) {
            if (confirmed && "captured".equals(receipt)) worker.execute(assignment);
            else assertThrows(IllegalStateException.class, () -> worker.execute(assignment));
            assertEquals(1, cancels.get());
            if (confirmed) {
                assertNotNull(reply.get());
                assertEquals("cancelled", reply.get().status());
                assertEquals(0, unknowns.get());
                try (var files = Files.list(root.resolve("results"))) {
                    assertEquals("captured".equals(receipt) ? 0 : 1, files.count());
                }
            } else {
                assertNull(reply.get());
                assertEquals(1, unknowns.get());
            }
        } finally {
            server.stop(0);
        }
    }
}
