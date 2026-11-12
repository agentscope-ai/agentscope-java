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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** All supported depths share one callback; suspended parents retain their Session FIFO head. */
final class ManagedDepthCapacityFixture {
    private ManagedDepthCapacityFixture() {}

    static void verify(ManagedMessageBus bus) throws Exception {
        String test = UUID.randomUUID().toString();
        var order = new ConcurrentLinkedQueue<String>();
        var received = new ConcurrentLinkedQueue<String>();
        var published = new ConcurrentLinkedQueue<String>();
        var errors = new ConcurrentLinkedQueue<Exception>();
        var finished = new CountDownLatch(1);
        var successor = new AtomicReference<ManagedPushDelivery>();
        var started = ConcurrentHashMap.<Integer>newKeySet();
        var activeCallbacks = new AtomicInteger();
        var maximumCallbacks = new AtomicInteger();
        var topics = ConcurrentHashMap.<String>newKeySet();
        var groups = ConcurrentHashMap.<String>newKeySet();
        var done = new CountDownLatch[ManagedTurnLanes.MAX_DEPTH + 1];
        for (int depth = 0; depth < done.length; depth++) done[depth] = new CountDownLatch(1);
        try (var subscription =
                bus.subscribe(
                        Channel.TURNS,
                        1,
                        delivery -> {
                            maximumCallbacks.accumulateAndGet(
                                    activeCallbacks.incrementAndGet(), Math::max);
                            try {
                                topics.add(delivery.topic());
                                groups.add(delivery.consumerGroup());
                                int depth = delivery.message().depth();
                                received.add(
                                        delivery.message().id()
                                                + ":"
                                                + delivery.brokerMessageId()
                                                + ":depth="
                                                + depth
                                                + ":attempt="
                                                + delivery.attempt());
                                assertEquals(
                                        message(test, depth, false).messageGroup(),
                                        delivery.message().messageGroup(),
                                        "Unexpected Session LiteTopic");
                                if (delivery.message().id().equals(test + "-next")) {
                                    order.add("next");
                                    successor.set(delivery);
                                    finished.countDown();
                                    return;
                                }
                                assertEquals(
                                        test + "-" + depth,
                                        delivery.message().id(),
                                        "Another probe's message reached this callback: "
                                                + received);
                                if (started.add(depth)) {
                                    order.add("start-" + depth);
                                    if (depth < ManagedTurnLanes.MAX_DEPTH)
                                        bus.publish(Channel.TURNS, message(test, depth + 1, false));
                                }
                                if (depth < ManagedTurnLanes.MAX_DEPTH
                                        && done[depth + 1].getCount() != 0)
                                    throw new ManagedDeliverySuspended(Duration.ofMillis(25));
                                order.add("end-" + depth);
                                done[depth].countDown();
                            } catch (AssertionError failed) {
                                throw new IllegalStateException(
                                        "Probe " + test + ", received=" + received, failed);
                            } finally {
                                activeCallbacks.decrementAndGet();
                            }
                        },
                        (delivery, error) -> {
                            errors.add(error);
                            for (var latch : done) latch.countDown();
                            finished.countDown();
                        })) {
            published.add(
                    "root="
                            + bus.publish(Channel.TURNS, message(test, 0, false))
                                    .brokerMessageId());
            published.add(
                    "next=" + bus.publish(Channel.TURNS, message(test, 0, true)).brokerMessageId());
            assertTrue(
                    finished.await(40, TimeUnit.SECONDS),
                    "Nested Turns exhausted callback capacity: order="
                            + order
                            + ", received="
                            + received
                            + ", errors="
                            + errors);
            assertTrue(
                    errors.isEmpty(),
                    () -> "Probe " + test + ", received=" + received + ", errors=" + errors);
            long callbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (successor.get() != null
                    && bus.active(successor.get())
                    && System.nanoTime() < callbackDeadline)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(successor.get() != null, "Successor callback is missing");
            // The listener must return before close(), otherwise the wrapper intentionally returns
            // FAILURE.
            assertFalse(bus.active(successor.get()), "Successor listener has not returned");
            assertEquals(1, maximumCallbacks.get(), "Depths must share the single callback slot");
            assertEquals(1, topics.size(), "Depth must not create physical Topics");
            assertEquals(1, groups.size(), "Depth must not create consumer groups");
            var expected = new ArrayList<String>();
            for (int depth = 0; depth <= ManagedTurnLanes.MAX_DEPTH; depth++)
                expected.add("start-" + depth);
            for (int depth = ManagedTurnLanes.MAX_DEPTH; depth >= 0; depth--)
                expected.add("end-" + depth);
            expected.add("next");
            assertEquals(
                    expected,
                    List.copyOf(order),
                    () -> "Probe " + test + ", published=" + published + ", received=" + received);
        }
    }

    private static Message message(String test, int depth, boolean next) {
        var scope = new ManagedScope(test, "depth-test", "depth-" + depth);
        return new Message(
                test + (next ? "-next" : "-" + depth),
                scope.messageGroup(),
                new byte[] {(byte) depth},
                depth);
    }
}
