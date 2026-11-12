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
package io.agentcore.managed.server.runtime.loadtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.loadtest.LoadTestModel.Profile;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedInputInbox;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedLeaseStore;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.InMemoryManagedToolResultDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedBatchCodec;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointObjects;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeToolEvents;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessRuntimeTurns;
import io.agentcore.managed.server.runtime.managed.ManagedHarnessTurns;
import io.agentcore.managed.server.runtime.managed.ManagedInputDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpTool;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedPushDelivery;
import io.agentcore.managed.server.runtime.managed.ManagedResumePublisher;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeInputMiddleware;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionLogStore;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutions;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor;
import io.agentcore.managed.server.runtime.managed.ManagedToolLoop;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedToolResult;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultPublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolResultRelay;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.ManagedTurnPublisher;
import io.agentcore.managed.server.runtime.managed.ProjectionTestSupport;
import io.agentcore.managed.server.runtime.managed.SessionCommitTransactions;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;

/** Distinct Brain runtimes and freshly created Models recover through SQL, tool events and Resume. */
@Timeout(25)
class LoadTestModelRecoveryIntegrationTest {
    @TempDir Path root;

    @Test
    void freshBrainResumesOriginalRemoteToolResultAndReplayDoesNotExecuteOrCompleteAgain()
            throws Exception {
        var mapper = new ObjectMapper();
        var scope = new ManagedScope("tenant", "namespace", "loadtest");
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:loadtest-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var admission = new ManagedAdmissionStore(source, mapper);
        var objects = new ManagedCheckpointObjects(mapper, root.resolve("objects"));
        var firstJournal = ProjectionTestSupport.journal(source, mapper, objects);
        var secondJournal = ProjectionTestSupport.journal(source, mapper, objects);
        admission.createSession(scope, "{}");
        String turn =
                admission
                        .acceptTurn(
                                scope,
                                "actor",
                                "first",
                                mapper.writeValueAsString(
                                        Map.of(
                                                "input",
                                                List.of(
                                                        Map.of(
                                                                "role",
                                                                "user",
                                                                "content",
                                                                List.of(
                                                                        Map.of(
                                                                                "type",
                                                                                "text",
                                                                                "text",
                                                                                "LOADTEST"
                                                                                    + " {\"request_id\":\"recover-request\",\"marker\":\"recover-marker\"}")))))))
                        .id();
        var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 4);
        var leases = new InMemoryManagedLeaseStore(System::currentTimeMillis);
        var inbox = new InMemoryManagedInputInbox(System::currentTimeMillis);
        var codec = new ManagedBatchCodec(mapper, root.resolve("blobs"));
        firstJournal.transport().configure(bus, codec);
        secondJournal.transport().configure(bus, codec);
        var deliveries = new InMemoryManagedToolResultDelivery();
        var relay = new ManagedToolResultRelay(firstJournal, codec, deliveries);
        var requests = new ManagedToolRequests(mapper, Duration.ofMillis(30), deliveries, codec);
        var scheduler = Executors.newScheduledThreadPool(4);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var physical = new ConcurrentHashMap<String, Long>();
        var starts = new AtomicInteger();
        var initialSegments = new AtomicInteger();
        var resumedSegments = new AtomicInteger();
        var subscriptions = new ArrayList<ManagedMessageBus.Subscription>();
        var lastDelivery = new AtomicReference<ManagedPushDelivery>();
        var harnessBus = mock(MessageBus.class);
        when(harnessBus.inboxDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.queueDrain(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(harnessBus.inboxHasMessages(anyString())).thenReturn(Mono.just(false));
        when(harnessBus.queuePeek(anyString())).thenReturn(Mono.just(false));
        var profile =
                new Profile(
                        1,
                        1,
                        4,
                        16,
                        "longwait__long_wait",
                        Map.of("marker", "{{marker}}", "seconds", 1),
                        1,
                        1);
        var descriptor =
                new ManagedMcpCatalog.Tool(
                        "longwait",
                        "longwait__long_wait",
                        "long_wait",
                        "fixture",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "marker",
                                        Map.of("type", "string"),
                                        "seconds",
                                        Map.of("type", "integer"))),
                        null,
                        true);
        var ttl = Duration.ofSeconds(5);
        var publisher = new ManagedToolResultPublisher(firstJournal, bus, codec);
        var work =
                new ManagedToolExecutions(
                        leases,
                        inbox,
                        firstJournal,
                        publisher,
                        scheduler,
                        ttl,
                        Duration.ofMillis(300),
                        Duration.ofMillis(10));
        var toolLoop =
                new ManagedToolLoop(
                        firstJournal,
                        work,
                        1,
                        false,
                        (owner, taskId) ->
                                new ManagedToolExecutor() {
                                    public boolean idempotentStart() {
                                        return true;
                                    }

                                    public String start(ManagedScope owner, ToolTask task) {
                                        assertEquals("mcp.call", task.name());
                                        physical.computeIfAbsent(
                                                task.executionId(),
                                                id -> {
                                                    starts.incrementAndGet();
                                                    return System.nanoTime()
                                                            + Duration.ofMillis(600).toNanos();
                                                });
                                        return task.executionId();
                                    }

                                    public Outcome query(ManagedScope owner, ToolTask task) {
                                        if (System.nanoTime() < physical.get(task.executionId()))
                                            return new Outcome(State.RUNNING, null);
                                        return new Outcome(
                                                State.SUCCEEDED,
                                                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"call_id\\\":\\\"physical-original\\\",\\\"marker\\\":\\\"recover-marker\\\"}\"}],\"isError\":false}");
                                    }

                                    public Outcome cancel(ManagedScope owner, ToolTask task) {
                                        return query(owner, task);
                                    }
                                },
                        (ref, error) -> errors.add(error));
        try (var first =
                        new ManagedHarnessRuntimeTurns(
                                bus,
                                leases,
                                admission,
                                firstJournal,
                                new ManagedRuntimeWriter(
                                        firstJournal, new SessionCommitTransactions(firstJournal)),
                                mapper,
                                scheduler,
                                ttl,
                                Duration.ofMillis(300),
                                1);
                var replacement =
                        new ManagedHarnessRuntimeTurns(
                                bus,
                                leases,
                                admission,
                                secondJournal,
                                new ManagedRuntimeWriter(
                                        secondJournal,
                                        new SessionCommitTransactions(secondJournal)),
                                mapper,
                                scheduler,
                                ttl,
                                Duration.ofMillis(300),
                                1)) {
            var handler =
                    new ManagedHarnessRuntimeToolEvents(codec, firstJournal, relay::delivered);
            subscriptions.add(
                    bus.subscribe(
                            Channel.TOOL_EVENTS,
                            2,
                            d -> {
                                lastDelivery.set(d);
                                handler.consume(d);
                            },
                            (d, e) -> errors.add(e)));
            subscriptions.add(
                    bus.subscribe(
                            Channel.TURNS,
                            1,
                            d ->
                                    first.consume(
                                            d,
                                            execution -> {
                                                initialSegments.incrementAndGet();
                                                run(
                                                        execution,
                                                        firstJournal,
                                                        admission,
                                                        mapper,
                                                        scheduler,
                                                        inbox,
                                                        requests,
                                                        harnessBus,
                                                        descriptor,
                                                        profile);
                                            }),
                            (d, e) -> errors.add(e)));
            subscriptions.add(
                    bus.subscribe(
                            Channel.TURN_RESUMES,
                            1,
                            d ->
                                    replacement.consume(
                                            d,
                                            execution -> {
                                                resumedSegments.incrementAndGet();
                                                run(
                                                        execution,
                                                        secondJournal,
                                                        admission,
                                                        mapper,
                                                        scheduler,
                                                        inbox,
                                                        requests,
                                                        harnessBus,
                                                        descriptor,
                                                        profile);
                                            }),
                            (d, e) -> errors.add(e)));
            var resumes =
                    new ManagedResumePublisher(firstJournal.suspensions(), bus, leases, mapper);
            var retry =
                    scheduler.scheduleWithFixedDelay(
                            () -> {
                                try {
                                    resumes.retry(8);
                                } catch (Throwable error) {
                                    errors.add(error);
                                }
                            },
                            5,
                            10,
                            TimeUnit.MILLISECONDS);
            try {
                toolLoop.start();
                new ManagedTurnPublisher(admission, bus, leases, mapper, ttl).publish(scope, turn);
                await(
                        () ->
                                "succeeded".equals(admission.turn(scope, turn).status())
                                        || !errors.isEmpty());
                assertTrue(errors.isEmpty(), () -> errors.toString());
                assertEquals("succeeded", admission.turn(scope, turn).status());
                assertEquals(1, starts.get());
                assertEquals(1, initialSegments.get());
                assertTrue(resumedSegments.get() >= 1);
                while (firstJournal.projections().project(scope, 128) > 0) {
                    /* Query-side catch-up. */
                }
                assertTrue(
                        firstJournal.publicEvents(scope, 0, 2000).stream()
                                .anyMatch(e -> e.payload().contains("physical-original")));
                var jdbc = new JdbcTemplate(source);
                assertTrue(
                        jdbc.queryForObject(
                                        "SELECT count(*) FROM ma.turn_resume WHERE scope_key=?",
                                        Integer.class,
                                        scope.messageGroup())
                                >= 1);
                var task = firstJournal.pendingTools(8);
                assertTrue(task.isEmpty());
                var rows =
                        jdbc.queryForList(
                                "SELECT task_id,execution_id FROM ma.tool_task WHERE scope_key=?"
                                        + " AND tool_name='mcp.call'",
                                scope.messageGroup());
                assertEquals(1, rows.size());
                var replay =
                        new ManagedToolResult(
                                scope,
                                (String) rows.get(0).get("task_id"),
                                (String) rows.get(0).get("execution_id"),
                                "succeeded",
                                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"call_id\\\":\\\"physical-original\\\",\\\"marker\\\":\\\"recover-marker\\\"}\"}],\"isError\":false}");
                publisher.capture(replay);
                publisher.publish(replay);
                handler.consume(lastDelivery.get());
                Thread.sleep(80);
                assertEquals(1, starts.get());
                assertEquals("succeeded", admission.turn(scope, turn).status());
                assertFalse(
                        firstJournal.suspensions().pending(10).stream()
                                .anyMatch(s -> s.scope().equals(scope)));
            } finally {
                retry.cancel(false);
                resumes.close();
            }
        } finally {
            subscriptions.forEach(ManagedMessageBus.Subscription::close);
            toolLoop.close();
            publisher.close();
            scheduler.shutdownNow();
            bus.close();
        }
    }

    private void run(
            ManagedTurnExecution execution,
            ManagedJournalStore journal,
            ManagedAdmissionStore admission,
            ObjectMapper mapper,
            ScheduledExecutorService scheduler,
            InMemoryManagedInputInbox inbox,
            ManagedToolRequests requests,
            MessageBus harnessBus,
            ManagedMcpCatalog.Tool descriptor,
            Profile profile) {
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(new ManagedMcpTool(descriptor, requests, mapper));
        try (var agent =
                HarnessAgent.builder()
                        .agentId("stable-loadtest-agent")
                        .name("loadtest")
                        .model(new LoadTestModel("loadtest:tool", profile))
                        .toolkit(toolkit)
                        .workspace(root.resolve("workspace"))
                        .sessionLogStore(new ManagedSessionLogStore(journal))
                        .messageBus(harnessBus)
                        .middleware(new ManagedRuntimeInputMiddleware(mapper))
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .disableMemoryHooks()
                        .disableCompaction()
                        .disableDefaultWorkspaceSkills()
                        .disableWebTools()
                        .build()) {
            var context =
                    RuntimeContext.builder()
                            .userId("user")
                            .sessionId(execution.scope().sessionId())
                            .put(ManagedScope.class, execution.scope())
                            .put(ManagedTurnExecution.class, execution)
                            .put(
                                    ManagedToolRequests.Operations.class,
                                    new ManagedToolRequests.Operations())
                            .put(ToolCallParam.CALL_CONTEXT_KEY, true)
                            .put(SessionRecorder.TURN_ID_KEY, execution.turnId())
                            .build();
            new ManagedHarnessTurns(mapper, new ManagedInputDelivery(admission, inbox), scheduler)
                    .run(execution, agent, context);
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "durable completion deadline");
    }
}
