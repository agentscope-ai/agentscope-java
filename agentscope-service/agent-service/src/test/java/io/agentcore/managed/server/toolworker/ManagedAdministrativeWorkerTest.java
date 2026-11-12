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
package io.agentcore.managed.server.toolworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedStateException;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedWorkerProtocol;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.web.managed.EnvironmentDto;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Workspace operations use the same sealed MQ command and Worker protocol as normal tools. */
@Timeout(30)
class ManagedAdministrativeWorkerTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"workspace.snapshot", "workspace.restore"})
    void unknownTakeoverAndLateResultDoNotNeedAHarnessRuntimeStoreInToolWorker(String operation)
            throws Exception {
        var mapper = new ObjectMapper();
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:admin-worker-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var scope = new ManagedScope("tenant", "namespace", "session");
        var admission = new ManagedAdmissionStore(source, mapper);
        admission.createSession(scope, "{}");
        var harnessRuntime =
                new ManagedJournalStore(source, mapper, new ManagedCheckpointObjects(mapper, root));
        var env =
                new EnvironmentDto(
                        "environment",
                        "environment",
                        "self_hosted",
                        Map.of("sandboxId", "box", "apiKey", "PROVIDER_SECRET"),
                        "owner",
                        null,
                        1,
                        1,
                        null);
        var identity =
                new ManagedSessionDto(
                        scope.sessionId(),
                        "owner",
                        "agent",
                        "owner",
                        1,
                        null,
                        null,
                        env.id(),
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        "created",
                        null,
                        1,
                        1,
                        null);
        var resolved =
                new SessionResolveResult(
                        identity,
                        Map.of("model_api_key", "MODEL_SECRET"),
                        null,
                        null,
                        null,
                        Map.of(),
                        env,
                        List.of(),
                        List.of(),
                        null,
                        null);
        var catalog = mock(ManagedCatalog.class);
        when(catalog.resolve(scope)).thenReturn(resolved);
        var clock = new AtomicLong();
        var leases = new InMemoryManagedLeaseStore(clock::get);
        var redis = mock(RedisManagedInfrastructure.class);
        when(redis.acquire(anyString(), any()))
                .thenAnswer(c -> leases.acquire(c.getArgument(0), c.getArgument(1)));
        when(redis.renew(any(), any()))
                .thenAnswer(c -> leases.renew(c.getArgument(0), c.getArgument(1)));
        when(redis.owns(any())).thenAnswer(c -> leases.owns(c.getArgument(0)));
        when(redis.release(any())).thenAnswer(c -> leases.release(c.getArgument(0)));
        var auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth.createContext(
                "/api/internal/environments/environment/verify-key",
                exchange -> {
                    assertEquals(
                            "internal-token",
                            exchange.getRequestHeaders()
                                    .getFirst("X-Managed-Agents-Internal-Token"));
                    boolean valid =
                            "worker-key"
                                    .equals(
                                            mapper.readTree(exchange.getRequestBody())
                                                    .path("key")
                                                    .asText());
                    byte[] body = mapper.writeValueAsBytes(Map.of("ok", valid));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        auth.start();
        try (var pipeline =
                new ManagedToolWorkerTestPipeline(
                        source,
                        mapper,
                        harnessRuntime,
                        catalog,
                        leases,
                        ignored -> null,
                        root.resolve("blobs"))) {
            String url = "http://127.0.0.1:" + auth.getAddress().getPort();
            var first =
                    new ManagedWorkerService(
                            pipeline.targets,
                            pipeline.toolWorker,
                            redis,
                            pipeline.results,
                            mapper,
                            source,
                            url,
                            "internal-token");
            var second =
                    new ManagedWorkerService(
                            pipeline.targets,
                            pipeline.toolWorker,
                            redis,
                            pipeline.results,
                            mapper,
                            source,
                            url,
                            "internal-token");
            var task =
                    new ManagedJournalStore.ToolTask(
                            "workspace-operation",
                            "administrative",
                            "call",
                            "workspace-operation",
                            operation,
                            "{}",
                            0,
                            "ready",
                            null,
                            null,
                            null);
            var coordinator = pipeline.administrative.executor(scope);
            coordinator.start(scope, task);
            assertThrows(
                    ResponseStatusException.class,
                    () ->
                            first.claim(
                                    env.id(),
                                    "wrong",
                                    new ManagedWorkerProtocol.Claim("first", 1)));
            var original = claim(first, pipeline);
            assertTrue(original.firstLaunch());
            assertFalse(mapper.writeValueAsString(original).contains("MODEL_SECRET"));
            first.unknown(env.id(), "worker-key", original.lease());
            clock.addAndGet(61000);
            var replacement = claim(second, pipeline);
            assertFalse(replacement.firstLaunch());
            assertEquals(original.lease().executionId(), replacement.lease().executionId());
            assertThrows(
                    ResponseStatusException.class,
                    () -> first.started(env.id(), "worker-key", original.lease()));
            second.started(env.id(), "worker-key", replacement.lease());
            var result =
                    new ManagedWorkerProtocol.Result(
                            original.lease(), "succeeded", "{\"sha256\":\"original\"}");
            assertEquals("captured", first.result(env.id(), "worker-key", result).get("status"));
            assertEquals("captured", second.result(env.id(), "worker-key", result).get("status"));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!coordinator.query(scope, task).terminal()) {
                pipeline.check();
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(10);
            }
            assertEquals(
                    ManagedToolExecutor.State.SUCCEEDED, coordinator.query(scope, task).state());
            assertEquals(
                    0,
                    harnessRuntime.head(scope).sequence(),
                    "Tool Worker result must not commit a checkpoint");
            assertTrue(pipeline.toolWorker.history(scope, task.id()).contains("unknown"));
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            second.result(
                                    env.id(),
                                    "worker-key",
                                    new ManagedWorkerProtocol.Result(
                                            original.lease(), "succeeded", "{}")));
            for (String id : List.of("next-1", "next-2")) {
                coordinator.start(
                        scope,
                        new ManagedJournalStore.ToolTask(
                                id,
                                "administrative",
                                id,
                                id,
                                operation,
                                "{}",
                                0,
                                "ready",
                                null,
                                null,
                                null));
            }
            deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (pipeline.toolWorker.tool(scope, "next-2").isEmpty()) {
                pipeline.check();
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(10);
            }
            assertEquals(
                    1,
                    second.claim(
                                    env.id(),
                                    "worker-key",
                                    new ManagedWorkerProtocol.Claim("worker", 32))
                            .size(),
                    "One claim must not aggregate multiple large workspace archives");

        } finally {
            auth.stop(0);
        }
    }

    private ManagedWorkerProtocol.Assignment claim(
            ManagedWorkerService workers, ManagedToolWorkerTestPipeline pipeline) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            pipeline.check();
            var tasks =
                    workers.claim(
                            "environment",
                            "worker-key",
                            new ManagedWorkerProtocol.Claim("worker", 1));
            if (!tasks.isEmpty()) return tasks.get(0);
            assertTrue(System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }
}
