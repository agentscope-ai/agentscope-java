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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.runtime.managed.ManagedInputInbox.Signal;
import io.agentscope.builder.runtime.managed.ManagedInputInbox.Type;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MANAGED_REDIS_TEST_URI", matches = ".+")
@Timeout(20)
class RedisManagedInfrastructureIntegrationTest {
    private final String uri = System.getenv("MANAGED_REDIS_TEST_URI");
    private final ManagedScope scope =
            new ManagedScope("integration", "redis", UUID.randomUUID().toString());

    @Test
    void replacementTokenCannotBeReleasedOrRenewedByOldHolder() throws Exception {
        try (var redis = new RedisManagedInfrastructure(uri, 2)) {
            var first = redis.acquire(scope.key("execution"), Duration.ofMillis(150)).orElseThrow();
            assertTrue(redis.acquire(scope.key("execution"), Duration.ofSeconds(5)).isEmpty());
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (redis.owns(first) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(redis.owns(first));
            var replacement =
                    redis.acquire(scope.key("execution"), Duration.ofSeconds(5)).orElseThrow();
            assertNotEquals(first.token(), replacement.token());
            assertFalse(redis.release(first));
            assertFalse(redis.renew(first, Duration.ofSeconds(5)));
            assertTrue(redis.owns(replacement));
            assertTrue(redis.release(replacement));
        }
    }

    @Test
    void streamDeduplicatesAndKeepsTransportOrderSeparateFromInputSequence() {
        try (var redis = new RedisManagedInfrastructure(uri, 2)) {
            var second = new Signal("input2", 2, Type.INJECT, null, "input2");
            String cursor = redis.append(scope, "turn", second);
            assertEquals(cursor, redis.append(scope, "turn", second));
            redis.append(scope, "turn", new Signal("input1", 1, Type.STEER, null, "input1"));
            assertEquals(2, redis.read(scope, "turn", "0-0", 10, Duration.ZERO).size());
            assertEquals(
                    1,
                    redis.read(scope, "turn", cursor, 10, Duration.ZERO)
                            .get(0)
                            .signal()
                            .sequence());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            redis.append(
                                    scope,
                                    "turn",
                                    new Signal("input2", 2, Type.CANCEL, null, "input2")));
            assertFalse(redis.cancelled(scope, "turn"));
            redis.append(scope, "turn", new Signal("cancel", 3, Type.CANCEL, null, "cancel"));
            assertTrue(redis.cancelled(scope, "turn"));
            assertTrue(redis.cancelled(scope, "turn"));
            assertFalse(
                    redis.cancelled(new ManagedScope("other", "redis", scope.sessionId()), "turn"));
            redis.expire(scope, "turn", Duration.ofMillis(1));
        }
    }

    @Test
    void blockedStreamReadDoesNotDelayControlConnectionOrLeaseRenewal() throws Exception {
        var inspector = RedisClient.create(uri);
        var executor = Executors.newSingleThreadExecutor();
        try (var connection = inspector.connect();
                var redis = new RedisManagedInfrastructure(uri, 1)) {
            var lease = redis.acquire(scope.key("execution"), Duration.ofSeconds(5)).orElseThrow();
            var read =
                    executor.submit(
                            () -> redis.read(scope, "turn", "0-0", 10, Duration.ofSeconds(2)));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!connection.sync().clientList().contains("cmd=xread")
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(connection.sync().clientList().contains("cmd=xread"));
            assertFalse(read.isDone());
            assertTrue(redis.renew(lease, Duration.ofSeconds(5)));
            assertTrue(redis.owns(lease));
            assertFalse(read.isDone());
            assertThrows(
                    ManagedTransportException.class,
                    () -> redis.read(scope, "turn", "0-0", 10, Duration.ZERO));
            assertTrue(read.get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(redis.release(lease));
            assertTrue(redis.read(scope, "turn", "0-0", 10, Duration.ZERO).isEmpty());
        } finally {
            executor.shutdownNow();
            inspector.shutdown();
        }
    }
}
