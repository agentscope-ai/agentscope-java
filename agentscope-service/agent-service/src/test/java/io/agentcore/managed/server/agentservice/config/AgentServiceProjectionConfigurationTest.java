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
package io.agentcore.managed.server.agentservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedProjectionConsumer;
import io.agentcore.managed.server.runtime.managed.ManagedQueryViews;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentServiceProjectionConfigurationTest {
    private final RocketMqManagedMessageBus bus = mock(RocketMqManagedMessageBus.class);

    private ApplicationContextRunner runner() {
        when(bus.subscribe(any(), anyInt(), any(), any()))
                .thenReturn(mock(ManagedMessageBus.Subscription.class));
        return new ApplicationContextRunner()
                .withUserConfiguration(AgentServiceProjectionConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:projection-owner;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "managed-agents.projection.pool-size=3")
                .withBean(
                        RedisManagedInfrastructure.class,
                        () -> mock(RedisManagedInfrastructure.class))
                .withBean(ManagedQueryViews.class, () -> mock(ManagedQueryViews.class))
                .withBean(RuntimeObjectStore.class, () -> mock(RuntimeObjectStore.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RocketMqManagedMessageBus.class, () -> bus)
                .withBean(SessionEventNotifier.class, () -> mock(SessionEventNotifier.class));
    }

    @Test
    void bothRoleOwnsConsumerWithoutAnExecutionDatabaseProjectionPool() {
        runner().withPropertyValues("managed-agents.projection.role=both")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ManagedProjectionConsumer.class);
                            var api = context.getBean("dataSource", HikariDataSource.class);
                            assertThat(context).doesNotHaveBean("projectionDataSource");
                            assertThat(context).hasSingleBean(HikariDataSource.class);
                            verify(bus)
                                    .subscribe(
                                            eq(ManagedMessageBus.Channel.SESSION_PROJECTIONS),
                                            eq(4),
                                            any(),
                                            any());
                        });
    }

    @Test
    void apiOnlyDoesNotCreateConsumerOrProjectionConnections() {
        runner().withPropertyValues("managed-agents.projection.role=api-only")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ManagedProjectionConsumer.class);
                            assertThat(context).doesNotHaveBean("projectionDataSource");
                            assertThat(context).hasSingleBean(HikariDataSource.class);
                        });
    }
}
