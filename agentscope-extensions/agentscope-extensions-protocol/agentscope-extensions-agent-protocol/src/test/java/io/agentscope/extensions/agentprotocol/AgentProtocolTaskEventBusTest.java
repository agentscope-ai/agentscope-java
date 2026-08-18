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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import io.agentscope.harness.agent.subagent.protocol.RemoteEventType;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class AgentProtocolTaskEventBusTest {

    private AgentProtocolTaskEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new AgentProtocolTaskEventBus();
    }

    @Test
    void publish_assignsMonotonicSeqAndTaskId() {
        RemoteAgentEvent first = bus.publish("task-a", event(RemoteEventType.RUN_STARTED));
        RemoteAgentEvent second = bus.publish("task-a", event(RemoteEventType.TEXT_DELTA));
        RemoteAgentEvent other = bus.publish("task-b", event(RemoteEventType.STATUS));

        assertEquals(1L, first.getSeq());
        assertEquals("task-a", first.getTaskId());
        assertEquals(2L, second.getSeq());
        assertEquals("task-a", second.getTaskId());
        assertEquals(1L, other.getSeq());
        assertEquals("task-b", other.getTaskId());
    }

    @Test
    void implementsEventBusContract() {
        assertTrue(bus instanceof AgentProtocolEventBus);
    }

    @Test
    void publish_assignsUniqueSequencesForConcurrentPublishers() {
        int eventCount = 200;

        List<RemoteAgentEvent> published =
                LongStream.range(0, eventCount)
                        .parallel()
                        .mapToObj(
                                ignored ->
                                        bus.publish(
                                                "task-concurrent", event(RemoteEventType.STATUS)))
                        .toList();

        assertEquals(
                eventCount, published.stream().map(RemoteAgentEvent::getSeq).distinct().count());
        assertEquals(
                LongStream.rangeClosed(1, eventCount).boxed().toList(),
                published.stream().map(RemoteAgentEvent::getSeq).sorted().toList());
        assertTrue(published.stream().allMatch(e -> "task-concurrent".equals(e.getTaskId())));
    }

    @Test
    void subscribe_fromSeq_skipsOlderEventsOnReplay() {
        bus.publish("task-replay", event(RemoteEventType.RUN_STARTED));
        bus.publish("task-replay", event(RemoteEventType.TEXT_DELTA));
        bus.publish("task-replay", event(RemoteEventType.STATUS));

        List<RemoteAgentEvent> received =
                bus.subscribe("task-replay", 1L).take(2).collectList().block(Duration.ofSeconds(2));

        assertEquals(2, received.size());
        assertEquals(2L, received.get(0).getSeq());
        assertEquals(RemoteEventType.TEXT_DELTA, received.get(0).getType());
        assertEquals(3L, received.get(1).getSeq());
        assertEquals(RemoteEventType.STATUS, received.get(1).getType());
    }

    @Test
    void complete_stopsFurtherDelivery() throws Exception {
        List<RemoteAgentEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch gotFirst = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        Disposable sub =
                bus.subscribe("task-done", 0L)
                        .subscribe(
                                e -> {
                                    received.add(e);
                                    gotFirst.countDown();
                                },
                                err -> {},
                                () -> completed.set(true));

        bus.publish("task-done", event(RemoteEventType.RUN_STARTED));
        assertTrue(gotFirst.await(2, TimeUnit.SECONDS));

        bus.complete("task-done");
        awaitCondition(completed::get, 2_000);

        int sizeAfterComplete = received.size();
        bus.publish("task-done", event(RemoteEventType.TEXT_DELTA));
        Thread.sleep(100);

        assertEquals(sizeAfterComplete, received.size());
        assertTrue(completed.get());
        sub.dispose();
    }

    private static RemoteAgentEvent event(RemoteEventType type) {
        RemoteAgentEvent e = new RemoteAgentEvent();
        e.setType(type);
        return e;
    }

    private static void awaitCondition(Condition condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.get()) {
            if (System.currentTimeMillis() >= deadline) {
                throw new AssertionError("Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean get() throws Exception;
    }
}
