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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.HarnessAdapter;
import io.agentscope.durable.HarnessContext;
import io.agentscope.durable.SessionBackend.ClaimStatus;
import io.agentscope.durable.TurnDispatch;
import io.agentscope.durable.TurnSchedulingStatistics.Source;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RocketMqTurnSchedulerTest {
    @TempDir Path directory;

    @Test
    void signalsAndDispatchesUseThreadLiteTopicAndPersistUntilClaim() throws Exception {
        var topology = RocketMqTurnTopology.namespace("durable");
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
            scheduler.start(backend, dispatched::offer);
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
        var topology = RocketMqTurnTopology.namespace("durable");
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
        var topology = RocketMqTurnTopology.namespace("durable");
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
            listeners.get(topology.dispatch()).consume(view(original));
            assertEquals(1, runtime.schedulingStatistics().activeDispatchDuplicates());
            finish.countDown();
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
    void repairCountsConfirmedDispatchForWorkWithoutWakeups() throws Exception {
        var topology = RocketMqTurnTopology.namespace("durable");
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
            scheduler.start(backend, dispatched::offer);
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
            RocketMqTurnTopology topology, LinkedBlockingQueue<Message> sent) throws Exception {
        return transport(topology, sent, new ConcurrentHashMap<>());
    }

    private static RocketMqTurnScheduler transport(
            RocketMqTurnTopology topology,
            LinkedBlockingQueue<Message> sent,
            Map<String, MessageListener> listeners)
            throws Exception {
        var producer = mock(Producer.class);
        when(producer.send(any()))
                .thenAnswer(
                        call -> {
                            Message message = call.getArgument(0);
                            sent.add(message);
                            var listener = listeners.get(message.getTopic());
                            if (listener != null) listener.consume(view(message));
                            return null;
                        });
        return new RocketMqTurnScheduler(
                ClientServiceProvider.loadService(),
                null,
                topology,
                producer,
                listener -> bind(listeners, topology.dispatch(), listener),
                listener -> bind(listeners, topology.signal(), listener));
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
