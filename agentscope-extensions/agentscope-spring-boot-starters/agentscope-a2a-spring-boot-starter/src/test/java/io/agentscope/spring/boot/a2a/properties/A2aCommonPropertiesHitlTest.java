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
package io.agentscope.spring.boot.a2a.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class A2aCommonPropertiesHitlTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertyConfiguration.class);

    @Test
    void hitlDefaultsToDisabledLocalWithSafeTtls() {
        contextRunner.run(
                context -> {
                    A2aCommonProperties.Hitl hitl =
                            context.getBean(A2aCommonProperties.class).getHitl();

                    assertThat(hitl.isEnabled()).isFalse();
                    assertThat(hitl.getDurability())
                            .isEqualTo(A2aCommonProperties.Hitl.Durability.LOCAL);
                    assertThat(hitl.getCoordinationProvider()).isNull();
                    assertThat(hitl.getTaskTtl()).isEqualTo(Duration.ofDays(30));
                    assertThat(hitl.getHandoffTtl()).isEqualTo(Duration.ofDays(7));
                    assertThat(hitl.getExecutionLeaseTtl()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void bindsDurableHitlSettings() {
        contextRunner
                .withPropertyValues(
                        "agentscope.a2a.server.hitl.enabled=true",
                        "agentscope.a2a.server.hitl.durability=durable",
                        "agentscope.a2a.server.hitl.coordination-provider=custom",
                        "agentscope.a2a.server.hitl.task-ttl=12h",
                        "agentscope.a2a.server.hitl.handoff-ttl=30m",
                        "agentscope.a2a.server.hitl.execution-lease-ttl=20s")
                .run(
                        context -> {
                            A2aCommonProperties.Hitl hitl =
                                    context.getBean(A2aCommonProperties.class).getHitl();

                            assertThat(hitl.isEnabled()).isTrue();
                            assertThat(hitl.getDurability())
                                    .isEqualTo(A2aCommonProperties.Hitl.Durability.DURABLE);
                            assertThat(hitl.getCoordinationProvider()).isEqualTo("custom");
                            assertThat(hitl.getTaskTtl()).isEqualTo(Duration.ofHours(12));
                            assertThat(hitl.getHandoffTtl()).isEqualTo(Duration.ofMinutes(30));
                            assertThat(hitl.getExecutionLeaseTtl())
                                    .isEqualTo(Duration.ofSeconds(20));
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(A2aCommonProperties.class)
    static class PropertyConfiguration {}
}
