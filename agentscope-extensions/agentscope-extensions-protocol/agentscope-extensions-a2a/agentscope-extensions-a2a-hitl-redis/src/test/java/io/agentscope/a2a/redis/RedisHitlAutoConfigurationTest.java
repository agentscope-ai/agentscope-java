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
import static org.mockito.Mockito.mock;

import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RedisHitlAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RedisHitlAutoConfiguration.class))
                    .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                    .withPropertyValues(
                            "agentscope.a2a.server.hitl.enabled=true",
                            "agentscope.a2a.server.hitl.durability=durable");

    @Test
    void providerIsExplicitAndDisabledOrLocalModesNeverCreateBinding() {
        contextRunner.run(
                context -> assertThat(context).doesNotHaveBean(A2aHitlDurabilityBinding.class));
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.hitl.enabled=false",
                        "agentscope.a2a.server.hitl.coordination-provider=redis")
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(A2aHitlDurabilityBinding.class));
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.hitl.durability=local",
                        "agentscope.a2a.server.hitl.coordination-provider=redis")
                .run(
                        context ->
                                assertThat(context)
                                        .doesNotHaveBean(A2aHitlDurabilityBinding.class));
    }

    @Test
    void contributesOnlyOneCompositeBindingAndIgnoresAgentStateStores() {
        contextRunner
                .withBean("firstStore", AgentStateStore.class, () -> mock(AgentStateStore.class))
                .withBean("secondStore", AgentStateStore.class, () -> mock(AgentStateStore.class))
                .withPropertyValues(
                        "agentscope.a2a.server.hitl.coordination-provider=redis",
                        "agentscope.a2a.server.hitl.redis.namespace=a2a:test:auto:")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(A2aHitlDurabilityBinding.class);
                            assertThat(context.getBean(A2aHitlDurabilityBinding.class))
                                    .isInstanceOf(RedisA2aHitlDurabilityBinding.class);
                            assertThat(context).doesNotHaveBean(RedisTaskStore.class);
                            assertThat(context).doesNotHaveBean(RedisHitlResumeCoordinator.class);
                            assertThat(context).doesNotHaveBean(RedisHitlSessionLease.class);
                        });
    }

    @Test
    void customBindingBacksOffAndRedissonClientMustBeUnique() {
        A2aHitlDurabilityBinding custom = mock(A2aHitlDurabilityBinding.class);
        contextRunner
                .withBean(A2aHitlDurabilityBinding.class, () -> custom)
                .withPropertyValues("agentscope.a2a.server.hitl.coordination-provider=redis")
                .run(
                        context ->
                                assertThat(context.getBean(A2aHitlDurabilityBinding.class))
                                        .isSameAs(custom));

        ApplicationContextRunner base =
                new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(RedisHitlAutoConfiguration.class))
                        .withPropertyValues(
                                "agentscope.a2a.server.hitl.enabled=true",
                                "agentscope.a2a.server.hitl.durability=durable",
                                "agentscope.a2a.server.hitl.coordination-provider=redis");
        base.run(context -> assertThat(context).hasFailed());
        base.withBean("one", RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean("two", RedissonClient.class, () -> mock(RedissonClient.class))
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "Redis durable HITL requires exactly one"
                                                    + " RedissonClient, but found 2.");
                        });
    }
}
