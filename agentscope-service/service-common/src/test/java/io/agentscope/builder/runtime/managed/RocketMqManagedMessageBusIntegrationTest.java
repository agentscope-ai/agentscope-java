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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Message;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual producer and wildcard Push transport, including callback failure and FIFO retry. */
@EnabledIfEnvironmentVariable(named = "MANAGED_MQ_TEST_ENDPOINT", matches = ".+")
@Timeout(150)
class RocketMqManagedMessageBusIntegrationTest {
    @Test
    void failedCallbackRetriesItsHeadAndOtherSessionsContinueBeforeSuccessor() throws Exception {
        var scope = new ManagedScope("bus", "integration", UUID.randomUUID().toString());
        var other = new ManagedScope("bus", "integration", UUID.randomUUID().toString());
        var received = new LinkedBlockingQueue<String>();
        var firstRunning = new CountDownLatch(1);
        var failFirst = new CountDownLatch(1);
        var retryRunning = new CountDownLatch(1);
        var releaseRetry = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        try (var producer = connect(Map.of());
                var consumer = connect(Map.of(Channel.TURNS, "ma_push_bus_brain_it_v3"))) {
            // Both existing and later Session LiteTopics must be discovered by wildcard.
            producer.publish(
                    Channel.TURNS, new Message("first", scope.messageGroup(), new byte[] {1}));
            try (var subscription =
                    consumer.subscribe(
                            Channel.TURNS,
                            4,
                            delivery -> {
                                String id = delivery.message().id();
                                if ("first".equals(id)) {
                                    if (attempts.incrementAndGet() == 1) {
                                        firstRunning.countDown();
                                        assertTrue(failFirst.await(30, TimeUnit.SECONDS));
                                        throw new IllegalStateException(
                                                "Explicit failed first callback");
                                    }
                                    retryRunning.countDown();
                                    assertTrue(releaseRetry.await(30, TimeUnit.SECONDS));
                                }
                                received.add(id);
                            },
                            (d, e) -> {})) {
                assertTrue(firstRunning.await(20, TimeUnit.SECONDS));
                producer.publish(
                        Channel.TURNS, new Message("second", scope.messageGroup(), new byte[] {2}));
                producer.publish(
                        Channel.TURNS, new Message("other", other.messageGroup(), new byte[] {3}));
                assertEquals("other", received.poll(20, TimeUnit.SECONDS));
                assertNull(received.poll(200, TimeUnit.MILLISECONDS));
                failFirst.countDown();
                assertTrue(retryRunning.await(25, TimeUnit.SECONDS));
                assertNull(received.poll(200, TimeUnit.MILLISECONDS));
                releaseRetry.countDown();
                assertEquals("first", received.poll(10, TimeUnit.SECONDS));
                assertEquals("second", received.poll(10, TimeUnit.SECONDS));
                assertEquals(2, attempts.get());
            } finally {
                failFirst.countDown();
                releaseRetry.countDown();
            }
        }
    }

    @RepeatedTest(4)
    void oneSharedCallbackCompletesSixteenSuspendedDescendantsAndQueuedRoot() throws Exception {
        try (var bus = connect(Map.of(Channel.TURNS, "ma_push_bus_brain_it_v3"))) {
            ManagedDepthCapacityFixture.verify(bus);
        }
    }

    private RocketMqManagedMessageBus connect(Map<Channel, String> groups) {
        return RocketMqManagedMessageBus.connect(
                new RocketMqManagedMessageBus.Settings(
                        System.getenv("MANAGED_MQ_TEST_ENDPOINT"),
                        "ma_push_bus_turns_it_v3",
                        "ma_push_bus_resumes_it_v3",
                        "ma_push_bus_tools_it_v3",
                        "ma_push_bus_execute_it_v1",
                        "ma_push_bus_control_it_v1",
                        Set.of(Channel.TURNS),
                        groups,
                        32,
                        1048576,
                        false,
                        null));
    }
}
