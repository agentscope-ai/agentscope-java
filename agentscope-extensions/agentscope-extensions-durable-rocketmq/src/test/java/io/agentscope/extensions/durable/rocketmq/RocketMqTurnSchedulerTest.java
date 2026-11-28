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
package io.agentscope.extensions.durable.rocketmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.Executor;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.SessionBackend.ClaimStatus;
import io.agentscope.durable.TurnDispatch;
import io.agentscope.durable.TurnSchedulingStatistics.Source;
import io.agentscope.durable.TurnSignal;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import io.agentscope.extensions.durable.rocketmq.RocketMqTopology.Subscription;
import io.agentscope.extensions.durable.rocketmq.RocketMqTopology.SubscriptionMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumerBuilder;
import org.apache.rocketmq.client.apis.consumer.LiteSimpleConsumer;
import org.apache.rocketmq.client.apis.consumer.LiteSimpleConsumerBuilder;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocketMqTurnSchedulerTest {
    @TempDir Path directory;

    @Test
    void deploymentTopologyAndSdkUseTheSameGroupsAndConfiguredDispatchWait() throws Exception {
        var topology = RocketMqTopology.turns("app");
        assertEquals(
                List.of(
                        new Subscription(
                                "app-turn-dispatch", "app-turn-runner", SubscriptionMode.WILDCARD),
                        new Subscription(
                                "app-turn-signal", "app-turn-signal", SubscriptionMode.WILDCARD),
                        new Subscription(
                                "app-turn-owner", "app-turn-owner", SubscriptionMode.EXPLICIT)),
                topology.subscriptions());
        assertEquals(
                List.of(
                        new Subscription(
                                "app-echo-commands", "app-echo-worker", SubscriptionMode.WILDCARD),
                        new Subscription(
                                "app-echo-controls",
                                "app-echo-worker-controls",
                                SubscriptionMode.WILDCARD),
                        new Subscription(
                                "app-echo-updates", "app-echo-brain", SubscriptionMode.WILDCARD)),
                RocketMqTopology.route("app", "echo").subscriptions());
        var config = ClientConfiguration.newBuilder().setEndpoints("localhost:8081").build();
        var interval = Duration.ofSeconds(2);
        var wait = Duration.ofSeconds(4);
        assertEquals(
                new RocketMqTurnScheduler.Options(interval, Duration.ofSeconds(5), 32, 32, wait),
                new RocketMqTurnScheduler.Options(wait));
        var options = new RocketMqTurnScheduler.Options(interval, interval, 1, 1, wait);
        assertEquals(
                Duration.ofSeconds(1),
                new RocketMqTurnScheduler.Options(interval, interval, 1, 1).receiveWait());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RocketMqTurnScheduler.Options(interval, interval, 1, 1, Duration.ZERO));
        var provider = mock(ClientServiceProvider.class);
        var pulls = mock(LiteSimpleConsumerBuilder.class, RETURNS_SELF);
        var push = mock(LitePushConsumerBuilder.class, RETURNS_SELF);
        var producer = mock(ProducerBuilder.class, RETURNS_SELF);
        var publisher = mock(Producer.class);
        var dispatch = mock(LiteSimpleConsumer.class);
        var signals = mock(LitePushConsumer.class);
        when(provider.newLiteSimpleConsumerBuilder()).thenReturn(pulls);
        when(provider.newLitePushConsumerBuilder()).thenReturn(push);
        when(provider.newProducerBuilder()).thenReturn(producer);
        when(pulls.build()).thenReturn(dispatch);
        when(push.build()).thenReturn(signals);
        when(producer.build()).thenReturn(publisher);
        when(dispatch.receive(eq(1), any()))
                .thenAnswer(
                        call -> {
                            Thread.sleep(20);
                            return List.of();
                        });
        try (var service = mockStatic(ClientServiceProvider.class);
                var backend = LocalBackend.open(directory)) {
            service.when(ClientServiceProvider::loadService).thenReturn(provider);
            try (var scheduler = RocketMqTurnScheduler.create(config, "app", options)) {
                scheduler.start(
                        backend,
                        "runner",
                        ignored -> CompletableFuture.completedFuture(false),
                        ignored -> {});
                verify(pulls).bindTopic(topology.dispatch());
                verify(pulls).setConsumerGroup(topology.runnerGroup());
                verify(pulls).setAwaitDuration(wait);
                verify(push).bindTopic(topology.signal());
                verify(push).setConsumerGroup(topology.signalGroup());
                verify(push).bindTopic(topology.ownerSignal());
                verify(push).setConsumerGroup(topology.ownerGroup());
                verify(signals).subscribeLite(RocketMqLiteRouting.owner("runner"));
            }
        }
    }

    @Test
    void ownerSignalInterruptsADirectExecutorAndPreventsSiblingDispatch() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        var listeners = new ConcurrentHashMap<String, MessageListener>();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();
        var adapter =
                new HarnessAdapter() {
                    @Override
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                true,
                                List.of("text"));
                    }

                    @Override
                    public void execute(HarnessContext context) {
                        var frame = context.frame();
                        context.commit(
                                new Commit(
                                        "dispatch",
                                        frame.turn().revision(),
                                        DurableJson.value("checkpoint"),
                                        List.of(
                                                new ToolCall(
                                                        "a",
                                                        "direct",
                                                        "first",
                                                        DurableJson.value(null)),
                                                new ToolCall(
                                                        "b",
                                                        "direct",
                                                        "second",
                                                        DurableJson.value(null))),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.WAITING,
                                        null,
                                        null,
                                        null));
                    }
                };
        Executor direct =
                new Executor() {
                    @Override
                    public Capabilities capabilities() {
                        return new Capabilities(true, true, true);
                    }

                    @Override
                    public ExecutionResult startExecution(ExecutionRequest request) {
                        starts.incrementAndGet();
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                            throw new AssertionError(
                                    "Expected cancellation to interrupt the original start");
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                            interrupted.countDown();
                            return ExecutionResult.unknown("Start interrupted");
                        }
                    }

                    @Override
                    public ExecutionResult query(ExecutionRequest request, String handle) {
                        return ExecutionResult.unknown("Outcome not confirmed");
                    }

                    @Override
                    public ExecutionResult cancel(ExecutionRequest request, String handle) {
                        stops.incrementAndGet();
                        return new ExecutionResult(
                                ExecutionState.CANCELLED, request.executionId(), null, null);
                    }
                };
        try (var brain = transport(topology, sent, listeners);
                var publisher = transport(topology, sent, listeners);
                var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend.withWakeups(brain))
                                .leaseDuration(Duration.ofMinutes(1))
                                .harness("agent", "v1", adapter)
                                .executor("direct", direct)
                                .build();
                var api =
                        DurableRuntime.builder()
                                .backend(backend.withWakeups(publisher))
                                .definition("agent", "v1", "1")
                                .build()) {
            runtime.start();
            var key = new SessionKey("tenant", "s");
            var turn = api.createSession(key, "agent").mainThread().submit("run", "prompt");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            turn.cancel("cancel");
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            var outcome =
                    turn.await(Duration.ofSeconds(5))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            assertEquals(TurnStatus.CANCELLED, outcome.status());
            assertEquals(1, starts.get());
            assertEquals(1, stops.get());
            assertNull(outcome.recovery());
            assertNull(runtime.lastFailure(turn.threadKey()));
        }
    }

    @Test
    void stageConsumerAcknowledgesOnlyAfterTheDispatchCallbackSucceeds() throws Exception {
        var client = mock(LiteSimpleConsumer.class);
        var message = mock(MessageView.class);
        var result = new CompletableFuture<ConsumeResult>();
        var entered = new CountDownLatch(1);
        when(client.receive(eq(1), any()))
                .thenReturn(List.of(message))
                .thenAnswer(
                        call -> {
                            Thread.sleep(100);
                            return List.of();
                        });
        try (var consumer =
                new RocketMqStageConsumer(
                        client,
                        view -> {
                            entered.countDown();
                            return result.join();
                        },
                        1)) {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                verify(client, never()).ack(message);
                result.complete(ConsumeResult.SUCCESS);
                verify(client, timeout(5000)).ack(message);
            } finally {
                result.complete(ConsumeResult.FAILURE);
            }
        }
    }

    @Test
    void signalsAndDispatchesUseThreadLiteTopicAndPersistUntilClaim() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        var dispatched = new LinkedBlockingQueue<TurnDispatch>();
        try (var scheduler = transport(topology, sent);
                var backend = LocalBackend.open(directory).withWakeups(scheduler);
                var api =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            var thread = api.createSession(new SessionKey("tenant", "s"), "agent").mainThread();
            scheduler.start(
                    backend,
                    "runner",
                    dispatch -> CompletableFuture.completedFuture(dispatched.offer(dispatch)),
                    signal -> {});
            var turn = thread.submit("id", "prompt");
            var work = dispatched.poll(5, TimeUnit.SECONDS);
            assertTrue(work != null, "Turn dispatch was not delivered");
            assertEquals(turn.turnId(), work.turnId());
            assertEquals(thread.key(), work.thread());
            String route = RocketMqLiteRouting.thread(thread.key());
            var dispatchMessage = sent.poll(5, TimeUnit.SECONDS);
            var signalMessage = sent.poll(5, TimeUnit.SECONDS);
            assertEquals(topology.dispatch(), dispatchMessage.getTopic());
            assertEquals(route, dispatchMessage.getLiteTopic().orElseThrow());
            assertTrue(dispatchMessage.getKeys().contains(work.id()));
            assertEquals(topology.signal(), signalMessage.getTopic());
            assertEquals(route, signalMessage.getLiteTopic().orElseThrow());
            assertTrue(
                    backend.pendingDispatch(thread.key()).isEmpty(), "reserved send is backed off");
            var lease = backend.claim(work, "worker", Duration.ofSeconds(10)).orElseThrow();
            assertEquals(turn.turnId(), lease.turnId());
            assertEquals(TurnStatus.RUNNING, turn.status().status());
            assertFalse(backend.reserveDispatch(work, Duration.ofSeconds(1)));
            assertTrue(backend.claim(work, "another-worker", Duration.ofSeconds(10)).isEmpty());
        }
    }

    @Test
    void apiOnlyRuntimePublishesDispatchWithoutAConsumerOrSignalRoundTrip() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        try (var scheduler = transport(topology, sent);
                var backend = LocalBackend.open(directory).withWakeups(scheduler);
                var api =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            var thread = api.createSession(new SessionKey("tenant", "s"), "agent").mainThread();
            var turn = thread.submit("request", "prompt");
            assertTrue(backend.capabilities().messageDrivenScheduling());
            var message = sent.poll(5, TimeUnit.SECONDS);
            assertTrue(message != null, "API-only host did not publish the committed dispatch");
            assertEquals(topology.dispatch(), message.getTopic());
            var dispatch =
                    DurableJson.convert(
                            DurableJson.parse(
                                    StandardCharsets.UTF_8.decode(message.getBody()).toString()),
                            TurnDispatch.class);
            assertEquals(turn.turnId(), dispatch.turnId());
            assertEquals(thread.key(), dispatch.thread());
        }
    }

    @Test
    void runtimeExecutesQueuedTurnsThroughLiteTopicBeforeItsRepairScan() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        var listeners = new ConcurrentHashMap<String, MessageListener>();
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var adapter =
                new HarnessAdapter() {
                    @Override
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                false,
                                List.of("text"));
                    }

                    @Override
                    public void execute(HarnessContext context) throws InterruptedException {
                        started.countDown();
                        assertTrue(finish.await(8, TimeUnit.SECONDS));
                        var frame = context.frame();
                        context.commit(
                                new Commit(
                                        "finish",
                                        frame.turn().revision(),
                                        frame.checkpoint(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        TurnStatus.SUCCEEDED,
                                        null,
                                        DurableJson.value("done"),
                                        null));
                    }
                };
        try (var scheduler = transport(topology, sent, listeners);
                var backend = LocalBackend.open(directory).withWakeups(scheduler);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("agent", "v1", adapter)
                                .build()) {
            runtime.start();
            var thread = runtime.createSession(new SessionKey("tenant", "s"), "agent").mainThread();
            var first = thread.submit("first", "first");
            var second = thread.submit("second", "second");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var original =
                    sent.stream()
                            .filter(message -> message.getTopic().equals(topology.dispatch()))
                            .findFirst()
                            .orElseThrow();
            var duplicate =
                    CompletableFuture.supplyAsync(
                            () -> listeners.get(topology.dispatch()).consume(view(original)));
            long admittedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.schedulingStatistics().activeDispatchDuplicates() == 0
                    && System.nanoTime() < admittedBy) Thread.sleep(10);
            assertEquals(1, runtime.schedulingStatistics().activeDispatchDuplicates());
            assertFalse(duplicate.isDone(), "MQ must retain the delivery while its phase runs");
            finish.countDown();
            assertEquals(ConsumeResult.SUCCESS, duplicate.get(8, TimeUnit.SECONDS));
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    first.await(Duration.ofSeconds(8))
                            .toCompletableFuture()
                            .get(8, TimeUnit.SECONDS)
                            .status());
            assertEquals(
                    TurnStatus.SUCCEEDED,
                    second.await(Duration.ofSeconds(8))
                            .toCompletableFuture()
                            .get(8, TimeUnit.SECONDS)
                            .status());
            assertEquals(
                    2,
                    sent.stream()
                            .filter(message -> message.getTopic().equals(topology.dispatch()))
                            .map(message -> message.getKeys().iterator().next())
                            .distinct()
                            .count());
            var claims = runtime.schedulingStatistics().claims().get(Source.MQ);
            assertEquals(2L, claims.outcomes().get(ClaimStatus.ACQUIRED));
            assertEquals(2, claims.discovery().count());
            assertEquals(
                    2, claims.discovery().bucketCounts().stream().mapToLong(Long::longValue).sum());
            assertTrue(runtime.drain(Duration.ofSeconds(5)));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (listeners.containsKey(topology.dispatch()) && System.nanoTime() < until)
                Thread.sleep(10);
            assertFalse(listeners.containsKey(topology.dispatch()));
            assertTrue(listeners.containsKey(topology.signal()));
        }
    }

    @Test
    void retryPublishesWhenDueWithRepairScanAsleep() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        var dispatched = new LinkedBlockingQueue<TurnDispatch>();
        var options =
                new RocketMqTurnScheduler.Options(
                        Duration.ofMinutes(5), Duration.ofSeconds(10), 32, 32);
        try (var scheduler = transport(topology, sent, new ConcurrentHashMap<>(), options);
                var backend = LocalBackend.open(directory).withWakeups(scheduler);
                var api =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            scheduler.start(
                    backend,
                    "runner",
                    dispatch -> CompletableFuture.completedFuture(dispatched.offer(dispatch)),
                    signal -> {});
            var thread = api.createSession(new SessionKey("tenant", "s"), "agent").mainThread();
            thread.submit("retry", "prompt");
            var first = dispatched.poll(5, TimeUnit.SECONDS);
            assertTrue(first != null);
            var lease = backend.claim(first, "runner:1", Duration.ofSeconds(20)).orElseThrow();

            backend.release(lease, Duration.ofMillis(300));

            var next = dispatched.poll(3, TimeUnit.SECONDS);
            assertTrue(next != null, "The deadline must publish before the repair scan");
            assertEquals(first.turnId(), next.turnId());
            assertTrue(next.generation() > first.generation());
            assertEquals(0, scheduler.statistics().repairPublications());
        }
    }

    @Test
    void apiHostRoutesCancellationToTheOwnerBeforeItsHeartbeat() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        var listeners = new ConcurrentHashMap<String, MessageListener>();
        var invoking = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var adapter =
                new HarnessAdapter() {
                    @Override
                    public Capabilities capabilities() {
                        return new Capabilities(
                                "1",
                                RecoveryBoundary.CHECKPOINT,
                                EventDelivery.COMPLETED,
                                false,
                                List.of("text"));
                    }

                    @Override
                    public void execute(HarnessContext context) throws InterruptedException {
                        invoking.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException cancelled) {
                            interrupted.countDown();
                            throw cancelled;
                        }
                    }
                };
        try (var brain = transport(topology, sent, listeners);
                var publisher = transport(topology, sent, listeners);
                var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend.withWakeups(brain))
                                .leaseDuration(Duration.ofMinutes(1))
                                .harness("agent", "v1", adapter)
                                .build();
                var api =
                        DurableRuntime.builder()
                                .backend(backend.withWakeups(publisher))
                                .definition("agent", "v1", "1")
                                .build()) {
            runtime.start();
            var key = new SessionKey("tenant", "s");
            var turn = api.createSession(key, "agent").mainThread().submit("run", "prompt");
            assertTrue(invoking.await(5, TimeUnit.SECONDS));
            turn.cancel("cancel");

            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            var message =
                    sent.stream()
                            .filter(item -> topology.ownerSignal().equals(item.getTopic()))
                            .findFirst()
                            .orElseThrow();
            var signal =
                    DurableJson.convert(
                            DurableJson.parse(
                                    StandardCharsets.UTF_8.decode(message.getBody()).toString()),
                            TurnSignal.class);
            assertEquals(turn.turnId(), signal.turnId());
            assertEquals(
                    RocketMqLiteRouting.owner(signal.runnerId()),
                    message.getLiteTopic().orElseThrow());
            assertEquals(
                    TurnStatus.CANCELLED,
                    turn.await(Duration.ofSeconds(5))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .status());
        }
    }

    @Test
    void repairCountsConfirmedDispatchForWorkWithoutWakeups() throws Exception {
        var topology = RocketMqTopology.turns("durable");
        var sent = new LinkedBlockingQueue<Message>();
        try (var scheduler = transport(topology, sent);
                var backend = LocalBackend.open(directory);
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .definition("agent", "v1", "1")
                                .build()) {
            var thread = runtime.createSession(new SessionKey("tenant", "s"), "agent").mainThread();
            var turn = thread.submit("request", "prompt");
            var dispatched = new LinkedBlockingQueue<TurnDispatch>();
            scheduler.start(
                    backend,
                    "runner",
                    dispatch -> CompletableFuture.completedFuture(dispatched.offer(dispatch)),
                    signal -> {});
            var work = dispatched.poll(8, TimeUnit.SECONDS);
            assertTrue(work != null, "repair did not publish the pending dispatch");
            assertEquals(turn.turnId(), work.turnId());
            // The message callback can run before the producer receives the send response.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (scheduler.statistics().repairPublications() == 0 && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(1, scheduler.statistics().repairPublications());
            assertTrue(scheduler.statistics().repairScans() >= 1);
            assertEquals(0, scheduler.statistics().failedPublications());
        }
    }

    private static RocketMqTurnScheduler transport(
            RocketMqTopology.Turn topology, LinkedBlockingQueue<Message> sent) throws Exception {
        return transport(topology, sent, new ConcurrentHashMap<>());
    }

    private static RocketMqTurnScheduler transport(
            RocketMqTopology.Turn topology,
            LinkedBlockingQueue<Message> sent,
            Map<String, MessageListener> listeners)
            throws Exception {
        return transport(
                topology,
                sent,
                listeners,
                new RocketMqTurnScheduler.Options(
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 32, 32));
    }

    private static RocketMqTurnScheduler transport(
            RocketMqTopology.Turn topology,
            LinkedBlockingQueue<Message> sent,
            Map<String, MessageListener> listeners,
            RocketMqTurnScheduler.Options options)
            throws Exception {
        var producer = mock(Producer.class);
        when(producer.send(any()))
                .thenAnswer(
                        call -> {
                            Message message = call.getArgument(0);
                            sent.add(message);
                            String destination =
                                    message.getTopic().equals(topology.ownerSignal())
                                            ? message.getLiteTopic().orElseThrow()
                                            : message.getTopic();
                            var listener = listeners.get(destination);
                            if (listener != null)
                                CompletableFuture.runAsync(() -> listener.consume(view(message)));
                            return null;
                        });
        return new RocketMqTurnScheduler(
                ClientServiceProvider.loadService(),
                null,
                topology,
                producer,
                listener -> bind(listeners, topology.dispatch(), listener),
                listener -> bind(listeners, topology.signal(), listener),
                route -> listener -> bind(listeners, route, listener),
                options);
    }

    private static MessageView view(Message message) {
        var view = mock(MessageView.class);
        when(view.getTopic()).thenReturn(message.getTopic());
        when(view.getLiteTopic()).thenReturn(message.getLiteTopic());
        when(view.getKeys()).thenReturn(message.getKeys());
        when(view.getBody()).thenReturn(message.getBody());
        return view;
    }

    private static AutoCloseable bind(
            Map<String, MessageListener> listeners, String topic, MessageListener listener) {
        listeners.put(topic, listener);
        return () -> listeners.remove(topic, listener);
    }
}
