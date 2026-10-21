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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MANAGED_REDIS_TEST_URI", matches = ".+")
@Timeout(20)
class ManagedRedisMessageBusIntegrationTest {
    private final ManagedScope scope = new ManagedScope("t", "n", UUID.randomUUID().toString());
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void queueDrainsOnceAcrossInstancesAndDoesNotShareTheReplayKeySpace() {
        try (var redis =
                new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 2)) {
            var first = new ManagedRedisMessageBus(scope, redis, mapper);
            var second = new ManagedRedisMessageBus(scope, redis, mapper);
            var other =
                    new ManagedRedisMessageBus(
                            new ManagedScope("other", "n", scope.sessionId()), redis, mapper);
            first.queuePush("same", Map.of("text", "queued")).block();
            String cursor = first.logAppend("same", Map.of("text", "logged"), 100).block();
            assertTrue(second.queuePeek("same").block());
            assertEquals(
                    "queued", second.queueDrain("same", 10).block().get(0).payload().get("text"));
            assertTrue(first.queueDrain("same", 10).block().isEmpty());
            assertTrue(other.queueDrain("same", 10).block().isEmpty());
            assertEquals(
                    "logged", first.logRead("same", null, 10).block().get(0).payload().get("text"));
            assertTrue(first.logRead("same", cursor, 10).block().isEmpty());
            first.logTrim("same").block();
            assertTrue(first.logRead("same", null, 10).block().isEmpty());
        }
    }

    @Test
    void transientSignalsCrossConnectionsWithoutCrossingSessionScopes() throws Exception {
        try (var one = new RedisManagedInfrastructure(System.getenv("MANAGED_REDIS_TEST_URI"), 2);
                var two =
                        new RedisManagedInfrastructure(
                                System.getenv("MANAGED_REDIS_TEST_URI"), 2)) {
            var first = new ManagedRedisMessageBus(scope, one, mapper);
            var second = new ManagedRedisMessageBus(scope, two, mapper);
            var other =
                    new ManagedRedisMessageBus(
                            new ManagedScope("other", "n", scope.sessionId()), two, mapper);
            var received = second.subscribe("signal").next().toFuture();
            var leaked = new AtomicInteger();
            var subscription =
                    other.subscribe("signal").subscribe(value -> leaked.incrementAndGet());
            try {
                long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (!received.isDone() && System.nanoTime() < end) {
                    first.publish("signal", Map.of("value", "wake")).block();
                    Thread.sleep(20);
                }
                assertEquals("wake", received.get(1, TimeUnit.SECONDS).get("value"));
                assertEquals(0, leaked.get());
            } finally {
                subscription.dispose();
            }
        }
    }
}
