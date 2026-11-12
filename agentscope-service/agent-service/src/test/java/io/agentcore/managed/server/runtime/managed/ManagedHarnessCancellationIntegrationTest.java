/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel.Profile;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ManagedHarnessCancellationIntegrationTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    @Timeout(20)
    void durableCancelStopsAnUnresponsiveModelAndReleasesExecutionCapacity(
            boolean synthetic, boolean beforePreparation) throws Exception {
        var mapper = new ObjectMapper();
        var scope = new ManagedScope("t", "n", "cancel-" + UUID.randomUUID());
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:cancel-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = ProjectionTestSupport.admission(source, mapper);
        var journal =
                ProjectionTestSupport.journal(
                        source,
                        mapper,
                        new ManagedCheckpointObjects(mapper, root.resolve("checkpoints")));
        admission.createSession(scope, "{}");
        String turn =
                admission
                        .acceptTurn(
                                scope,
                                "actor",
                                "first",
                                "{\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"wait\"}]}]}")
                        .id();
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var delayed =
                new LoadTestModel(
                        "loadtest:cancel", new Profile(120000, 100, 40, 80, "", Map.of(), 0, 1));
        Model model =
                new Model() {
                    public String getModelName() {
                        return "unresponsive-model";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return (synthetic
                                        ? delayed.stream(messages, tools, options)
                                        : Flux.<ChatResponse>never())
                                .doOnSubscribe(ignored -> started.countDown())
                                .doOnCancel(cancelled::countDown);
                    }
                };
        var bus = new InMemoryManagedMessageBus(() -> System.nanoTime() / 1_000_000, 4);
        var leases = new InMemoryManagedLeaseStore(() -> System.nanoTime() / 1_000_000);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var codec = new ManagedBatchCodec(mapper, root.resolve("blobs"));
        journal.transport().configure(bus, codec);
        var scheduler = Executors.newScheduledThreadPool(4);
        var harnessBus = mock(MessageBus.class);
        when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
        when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
        var runtime =
                new ManagedHarnessRuntimeTurns(
                        bus,
                        leases,
                        admission,
                        journal,
                        ProjectionTestSupport.writer(journal, source, bus, codec, mapper),
                        mapper,
                        scheduler,
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(1),
                        1);
        var finished = new CountDownLatch(1);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        try (var agent =
                        HarnessAgent.builder()
                                .name("cancel-test")
                                .model(model)
                                .workspace(root.resolve("workspace"))
                                .sessionLogStore(new ManagedSessionLogStore(journal))
                                .messageBus(harnessBus)
                                .middleware(new ManagedRuntimeInputMiddleware(mapper))
                                .permissionContext(
                                        PermissionContextState.builder()
                                                .mode(PermissionMode.BYPASS)
                                                .build())
                                .disableCompaction()
                                .disableDefaultWorkspaceSkills()
                                .build();
                var subscription =
                        bus.subscribe(
                                ManagedMessageBus.Channel.TURNS,
                                1,
                                delivery -> {
                                    runtime.consume(
                                            delivery,
                                            execution -> {
                                                var context =
                                                        RuntimeContext.builder()
                                                                .userId("user")
                                                                .sessionId(scope.sessionId())
                                                                .put(ManagedScope.class, scope)
                                                                .put(
                                                                        ManagedTurnExecution.class,
                                                                        execution)
                                                                .put(
                                                                        SessionRecorder.TURN_ID_KEY,
                                                                        turn)
                                                                .put(
                                                                        MemoryFlushMiddleware
                                                                                .INLINE_CONTEXT_KEY,
                                                                        true)
                                                                .build();
                                                var turns =
                                                        new ManagedHarnessTurns(
                                                                mapper,
                                                                new ManagedInputDelivery(
                                                                        admission, inbox),
                                                                scheduler);
                                                if (beforePreparation)
                                                    assertTrue(
                                                            turns.cancelBeforePreparation(
                                                                    execution));
                                                else turns.run(execution, agent, context);
                                            });
                                    finished.countDown();
                                },
                                (delivery, failure) -> failures.add(failure))) {
            if (beforePreparation)
                admission.acceptInput(
                        scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
            new ManagedTurnPublisher(admission, bus, leases, mapper, Duration.ofSeconds(6))
                    .publish(scope, turn);
            // JVM/SDK startup is outside the cancellation latency checked below.
            if (!beforePreparation) {
                assertTrue(started.await(10, TimeUnit.SECONDS), () -> "model started: " + failures);
                admission.acceptInput(
                        scope, turn, "actor", "cancel", ManagedInputInbox.Type.CANCEL, null, "{}");
            }
            assertTrue(
                    finished.await(3, TimeUnit.SECONDS),
                    "cancel must not wait for another model event");
            if (beforePreparation) {
                assertEquals(1, started.getCount(), "No model preparation or invocation required");
            } else
                assertTrue(
                        cancelled.await(1, TimeUnit.SECONDS),
                        "upstream model subscription cancelled");
            assertEquals("cancelled", admission.turn(scope, turn).status());
            if (synthetic) assertEquals(0, delayed.stats().active.get());
        } finally {
            runtime.close();
            scheduler.shutdownNow();
            bus.close();
        }
    }
}
