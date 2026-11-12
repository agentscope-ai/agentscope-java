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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ManagedConsumerLoopTest {
    @Test
    void toolEventsReserveBeforeCallbackAndAckOnlyAfterFactsCommit() throws Exception {
        var delivered = new LinkedBlockingQueue<ManagedPushDelivery>();
        var failures = new LinkedBlockingQueue<Exception>();
        var allowCommit = new CountDownLatch(1);
        var committed = new AtomicBoolean();
        var handler =
                ManagedConsumerLoop.bounded(
                        1,
                        delivery -> {
                            delivered.add(delivery);
                            if (delivery.message().id().equals("first")) {
                                if (!allowCommit.await(3, TimeUnit.SECONDS))
                                    throw new IllegalStateException("Commit blocked");
                                committed.set(true);
                            }
                        });
        var capacity = handler.receiveCapacity(ManagedMessageBus.Channel.TOOL_EVENTS);
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 3);
                var loop =
                        new ManagedConsumerLoop(
                                bus,
                                ManagedMessageBus.Channel.TOOL_EVENTS,
                                2,
                                handler,
                                (delivery, failure) -> failures.add(failure))) {
            loop.start();
            bus.publish(
                    ManagedMessageBus.Channel.TOOL_EVENTS,
                    new ManagedMessageBus.Message("first", "one", new byte[0]));
            var first = delivered.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            bus.publish(
                    ManagedMessageBus.Channel.TOOL_EVENTS,
                    new ManagedMessageBus.Message("second", "two", new byte[0]));
            assertNull(delivered.poll(100, TimeUnit.MILLISECONDS));
            assertEquals(0, capacity.availableExecutions());
            assertThrows(
                    TimeoutException.class, () -> first.handoff().await(Duration.ofMillis(10)));
            allowCommit.countDown();
            first.handoff().await(Duration.ofSeconds(2));
            assertTrue(committed.get());
            var second = delivered.poll(2, TimeUnit.SECONDS);
            assertNotNull(second);
            second.handoff().await(Duration.ofSeconds(2));
            // ACK completion wakes awaiters before confirm finishes releasing its credits.
            synchronized (second.handoff()) {
                assertEquals(1, capacity.availableExecutions());
                assertEquals(1, capacity.availableHandoffs());
            }
            assertTrue(failures.isEmpty());
        } finally {
            allowCommit.countDown();
        }
    }
}
