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

import io.agentscope.core.a2a.server.hitl.HitlDurabilityVerification;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RedisA2aHitlDurabilityBindingTest {

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
    void verifiesControlPlaneTopologyAndOwnsOnlyItsLifecycle() {
        RedisHitlProperties properties = new RedisHitlProperties();
        properties.setNamespace("a2a:test:binding:");
        properties.setTaskTtl(Duration.ofDays(1));
        properties.setReconcilerInterval(Duration.ofMillis(50));
        RedisA2aHitlDurabilityBinding binding =
                new RedisA2aHitlDurabilityBinding(redis.client(), properties);

        HitlDurabilityVerification verification = binding.verify();
        assertThat(verification.coordinationProvider()).isEqualTo("redis");
        assertThat(verification.coordinationStoreId()).isEqualTo("a2a:test:binding:");
        assertThatCode(
                        () -> {
                            binding.start();
                            binding.start();
                            binding.close();
                            binding.close();
                        })
                .doesNotThrowAnyException();
        assertThat(redis.client().isShutdown()).isFalse();
        assertThatThrownBy(binding::start).isInstanceOf(IllegalStateException.class);
        RedisNamespaceAssertions.containsNone(
                redis.client(),
                "a2a:test:binding:",
                "__a2a_hitl_secret_probe__",
                "authenticated",
                "AgentState");
    }

    @Test
    void verificationHandoffProbeFitsWithinShortTaskTtl() {
        RedisHitlProperties properties = new RedisHitlProperties();
        properties.setNamespace("a2a:test:binding:short-task-ttl:");
        properties.setTaskTtl(Duration.ofMinutes(2));
        RedisA2aHitlDurabilityBinding binding =
                new RedisA2aHitlDurabilityBinding(redis.client(), properties);

        assertThatCode(binding::verify).doesNotThrowAnyException();
        binding.close();
    }
}
