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
package io.agentscope.builder.hands;

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
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.InMemoryManagedLeaseStore;
import io.agentscope.builder.runtime.managed.InMemoryManagedMessageBus;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedBatchCodec;
import io.agentscope.builder.runtime.managed.ManagedBrainToolEvents;
import io.agentscope.builder.runtime.managed.ManagedCheckpointObjects;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch;
import io.agentscope.builder.runtime.managed.ManagedRuntimeBatch.ToolIntent;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.managed.ManagedStateException;
import io.agentscope.builder.runtime.managed.ManagedSubagents;
import io.agentscope.builder.runtime.managed.ManagedToolCommand;
import io.agentscope.builder.runtime.managed.ManagedToolExecutor;
import io.agentscope.builder.runtime.managed.ManagedToolResultPublisher;
import io.agentscope.builder.runtime.managed.ManagedWorkerProtocol;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.core.session.SessionEvent;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Two Hands protocol objects, real authentication HTTP, H2 and deterministic MQ/Redis fixtures. */
@Timeout(20)
class ManagedWorkerServiceTest {
    @TempDir private Path managedCheckpointRoot;

    @TempDir Path directory;

    @Test
    void renewIsIndependentOfReplicaAndOriginalLateResultSurvivesTakeover() throws Exception {
        var mapper = new ObjectMapper();
        var scope = new ManagedScope("tenant", "namespace", "session");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:worker-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var journal =
                new ManagedJournalStore(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, managedCheckpointRoot));
        admission.createSession(scope, "{}");
        String turn = admission.acceptTurn(scope, "u", "turn", "{}").id();
        admission.reservePublication(scope, turn);
        admission.confirmPublication(scope, turn, "broker");
        admission.activate(scope, turn, 1, "brain");
        var checkpoint =
                new SessionEvent(
                        1,
                        "checkpoint",
                        1,
                        1,
                        "state/checkpoint",
                        "run",
                        turn,
                        true,
                        "{\"stateJson\":\"{\\\"context\\\":[]}\"}");
        journal.commit(
                journal.objects()
                        .prepare(
                                new ManagedRuntimeBatch(
                                        scope,
                                        turn,
                                        1,
                                        "brain",
                                        "writer",
                                        "intent",
                                        0,
                                        List.of(checkpoint),
                                        List.of(
                                                new ToolIntent("call", "sandbox.exec", "{}"),
                                                new ToolIntent(
                                                        "child-call",
                                                        ManagedSubagents.OPERATION,
                                                        "{}")),
                                        List.of(),
                                        null)));
        var env =
                new EnvironmentDto(
                        "environment",
                        "borrowed",
                        "self_hosted",
                        Map.of("sandboxId", "box", "apiKey", "ENV_PROVIDER_SECRET"),
                        "owner",
                        null,
                        1,
                        1,
                        null);
        var session =
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
                        session,
                        Map.of("model_api_key", "MODEL_SECRET"),
                        null,
                        null,
                        null,
                        Map.of("AGENTS.md", "instructions"),
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
                .thenAnswer(call -> leases.acquire(call.getArgument(0), call.getArgument(1)));
        when(redis.renew(any(), any()))
                .thenAnswer(call -> leases.renew(call.getArgument(0), call.getArgument(1)));
        when(redis.owns(any())).thenAnswer(call -> leases.owns(call.getArgument(0)));
        when(redis.release(any())).thenAnswer(call -> leases.release(call.getArgument(0)));
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 8);
        var mq = mock(RocketMqManagedMessageBus.class);
        when(mq.publish(any(), any()))
                .thenAnswer(call -> bus.publish(call.getArgument(0), call.getArgument(1)));
        var codec = new ManagedBatchCodec(mapper, directory.resolve("private-blobs"));
        var auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth.createContext(
                "/api/internal/environments/environment/verify-key",
                exchange -> {
                    assertEquals(
                            "internal-test-token",
                            exchange.getRequestHeaders().getFirst("X-Builder-Internal-Token"));
                    var input = mapper.readTree(exchange.getRequestBody());
                    boolean valid = "worker-test-key".equals(input.path("key").asText());
                    byte[] body = mapper.writeValueAsBytes(Map.of("ok", valid));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        auth.start();
        String url = "http://127.0.0.1:" + auth.getAddress().getPort();
        var physical = new ManagedHandsStore(source, mapper);
        var taskId = "tool_" + ManagedScope.fingerprint(scope.messageGroup(), turn, "call");
        physical.receive(
                new ManagedToolCommand(
                        scope,
                        "execute_" + taskId,
                        "execute",
                        journal.tool(scope, taskId).orElseThrow(),
                        null));
        var first =
                new ManagedWorkerService(
                        catalog,
                        physical,
                        redis,
                        new ManagedToolResultPublisher(physical, mq, codec),
                        mapper,
                        source,
                        url,
                        "internal-test-token");
        var second =
                new ManagedWorkerService(
                        catalog,
                        physical,
                        redis,
                        new ManagedToolResultPublisher(physical, mq, codec),
                        mapper,
                        source,
                        url,
                        "internal-test-token");
        var events = new ManagedBrainToolEvents(codec, journal, result -> {});
        var subscription =
                bus.subscribe(
                        Channel.TOOL_EVENTS,
                        2,
                        events::consume,
                        (d, e) -> {
                            throw new AssertionError(e);
                        });
        try {
            assertEquals(
                    HttpStatus.UNAUTHORIZED,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            first.claim(
                                                    env.id(),
                                                    "wrong",
                                                    new ManagedWorkerProtocol.Claim("a", 1)))
                            .getStatusCode());
            var assignment =
                    first.claim(
                                    env.id(),
                                    "worker-test-key",
                                    new ManagedWorkerProtocol.Claim("a", 1))
                            .get(0);
            assertTrue(assignment.firstLaunch());
            assertEquals("sandbox.exec", assignment.task().name());
            assertFalse(mapper.writeValueAsString(assignment).contains("MODEL_SECRET"));
            assertTrue(
                    second.claim(
                                    env.id(),
                                    "worker-test-key",
                                    new ManagedWorkerProtocol.Claim("b", 1))
                            .isEmpty());
            second.renew(env.id(), "worker-test-key", assignment.lease());
            assertEquals(
                    "unknown",
                    first.unknown(env.id(), "worker-test-key", assignment.lease()).get("status"));
            assertTrue(physical.history(scope, assignment.task().id()).contains("unknown"));
            clock.addAndGet(61000);
            var takeover =
                    second.claim(
                                    env.id(),
                                    "worker-test-key",
                                    new ManagedWorkerProtocol.Claim("b", 1))
                            .get(0);
            assertFalse(takeover.firstLaunch());
            assertEquals(assignment.lease().executionId(), takeover.lease().executionId());
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            first.renew(
                                                    env.id(),
                                                    "worker-test-key",
                                                    assignment.lease()))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            first.unknown(
                                                    env.id(),
                                                    "worker-test-key",
                                                    assignment.lease()))
                            .getStatusCode());
            second.started(env.id(), "worker-test-key", takeover.lease());
            var finalOutput = new ManagedToolExecutor.OutputPage(0, 0, "", 0, true, null);
            assertEquals(
                    HttpStatus.CONFLICT,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () ->
                                            first.output(
                                                    env.id(),
                                                    "worker-test-key",
                                                    new ManagedWorkerProtocol.Output(
                                                            assignment.lease(), finalOutput)))
                            .getStatusCode());
            second.output(
                    env.id(),
                    "worker-test-key",
                    new ManagedWorkerProtocol.Output(takeover.lease(), finalOutput));
            var result =
                    new ManagedWorkerProtocol.Result(
                            assignment.lease(),
                            "succeeded",
                            "{\"output\":\"PRIVATE_TOOL_OUTPUT\",\"exitCode\":0,\"truncated\":false}");
            assertEquals(
                    "captured", first.result(env.id(), "worker-test-key", result).get("status"));
            assertEquals(
                    "captured", second.result(env.id(), "worker-test-key", result).get("status"));
            assertEquals(
                    "succeeded",
                    second.renew(env.id(), "worker-test-key", takeover.lease()).get("status"));
            assertEquals(
                    "succeeded",
                    physical.tool(scope, assignment.task().id()).orElseThrow().status());
            assertFalse(
                    journal.publicEvents(scope, 0, 100).toString().contains("PRIVATE_TOOL_OUTPUT"));
            assertEquals(
                    "ready",
                    journal.tool(
                                    scope,
                                    "tool_"
                                            + ManagedScope.fingerprint(
                                                    scope.messageGroup(), turn, "child-call"))
                            .orElseThrow()
                            .status());
            assertThrows(
                    ManagedStateException.class,
                    () ->
                            first.result(
                                    env.id(),
                                    "worker-test-key",
                                    new ManagedWorkerProtocol.Result(
                                            assignment.lease(), "succeeded", "{}")));
        } finally {
            subscription.close();
            auth.stop(0);
            bus.close();
        }
    }
}
