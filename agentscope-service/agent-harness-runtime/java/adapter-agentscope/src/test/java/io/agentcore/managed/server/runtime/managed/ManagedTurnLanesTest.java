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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(50)
class ManagedTurnLanesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void depthComesFromImmutableSessionConfigurationAndHasOneBound() {
        assertEquals(0, ManagedTurnLanes.depth("{}", mapper));
        assertEquals(1, ManagedTurnLanes.depth("{\"subagent\":{\"depth\":1}}", mapper));
        assertEquals(16, ManagedTurnLanes.depth("{\"subagent\":{\"depth\":16}}", mapper));
        for (String value : new String[] {"0", "17", "-1", "1.5", "\"1\"", "null", "4294967297"})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ManagedTurnLanes.depth(
                                    "{\"subagent\":{\"depth\":" + value + "}}", mapper));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManagedTurnLanes.validate(Channel.TURN_RESUMES, 1));
    }

    @Test
    void oneSharedCallbackCompletesAllSuspendedDescendantsAndPreservesRootFifo() throws Exception {
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 1)) {
            ManagedDepthCapacityFixture.verify(bus);
            assertEquals(0, bus.deadLetters().size());
        }
    }

    @Test
    void depthMetadataCannotBypassSessionFifoEvenWhenAnotherCallbackIsFree() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        try (var bus = new InMemoryManagedMessageBus(System::currentTimeMillis, 1);
                var subscription =
                        bus.subscribe(
                                Channel.TURNS,
                                2,
                                delivery -> {
                                    if ("first".equals(delivery.message().id())) {
                                        first.countDown();
                                        assertTrue(release.await(5, TimeUnit.SECONDS));
                                    } else second.countDown();
                                },
                                (delivery, error) -> {
                                    throw new AssertionError(error);
                                })) {
            try {
                bus.publish(Channel.TURNS, new Message("first", "same-session", new byte[] {1}, 0));
                assertTrue(first.await(5, TimeUnit.SECONDS));
                bus.publish(
                        Channel.TURNS, new Message("second", "same-session", new byte[] {1}, 16));
                assertFalse(second.await(150, TimeUnit.MILLISECONDS));
                release.countDown();
                assertTrue(second.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }
}
