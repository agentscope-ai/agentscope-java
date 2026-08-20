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
package io.agentscope.a2a.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlLeaseHandle;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

class RedisHitlSessionLeaseTest {

    private static RedisServerSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisServerSupport.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
            assertThat(redis.processAlive()).isFalse();
        }
    }

    @Test
    void serializesAcrossInstancesRenewsAndBecomesInvalidAfterClose() throws Exception {
        RedisHitlSessionLease first = new RedisHitlSessionLease(redis.client(), "a2a:test:lease:");
        RedisHitlSessionLease second = new RedisHitlSessionLease(redis.client(), "a2a:test:lease:");
        HitlExecutionKey key = new HitlExecutionKey("u", "agent", "ctx");

        HitlLeaseHandle owner = first.acquire(key, Duration.ofMillis(240));
        assertThatThrownBy(() -> second.acquire(key, Duration.ofMillis(240)))
                .isInstanceOf(HitlResumeRejectedException.class);
        Thread.sleep(500);
        assertThat(owner.isValid()).isTrue();
        assertThatThrownBy(() -> second.acquire(key, Duration.ofMillis(240)))
                .isInstanceOf(HitlResumeRejectedException.class);

        owner.close();
        assertThat(owner.isValid()).isFalse();
        assertThatCode(() -> second.acquire(key, Duration.ofMillis(240)).close())
                .doesNotThrowAnyException();
        first.close();
        second.close();
        assertThat(redis.client().isShutdown()).isFalse();
    }

    @Test
    void oldOwnerCannotReleaseReplacementAndLossCallbackRunsExactlyOnce() throws Exception {
        RedisHitlSessionLease leases =
                new RedisHitlSessionLease(redis.client(), "a2a:test:lease:replacement:");
        HitlExecutionKey key = new HitlExecutionKey("u", "agent", "ctx");
        HitlLeaseHandle old = leases.acquire(key, Duration.ofMillis(300));
        String redisKey = leases.leaseKey(key);

        redis.client().getBucket(redisKey, StringCodec.INSTANCE).delete();
        HitlLeaseHandle replacement = leases.acquire(key, Duration.ofSeconds(2));
        CountDownLatch lost = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        old.onLost(
                () -> {
                    callbacks.incrementAndGet();
                    lost.countDown();
                });
        assertThat(lost.await(2, TimeUnit.SECONDS)).isTrue();
        old.close();
        old.close();
        assertThat(callbacks).hasValue(1);
        assertThat(old.isValid()).isFalse();
        assertThat(redis.client().getKeys().countExists(redisKey)).isEqualTo(1);

        replacement.close();
        assertThat(redis.client().getKeys().countExists(redisKey)).isZero();
        leases.close();
        leases.close();
    }
}
