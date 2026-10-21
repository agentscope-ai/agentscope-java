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
package io.agentscope.builder.hands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedCheckpointOperations;
import io.agentscope.builder.runtime.managed.ManagedHandsStore;
import io.agentscope.builder.runtime.managed.ManagedJournalStore;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Subscription;
import io.agentscope.builder.runtime.managed.ManagedResumePublisher;
import io.agentscope.builder.runtime.managed.ManagedRuntimeWriter;
import io.agentscope.builder.runtime.managed.RedisManagedInfrastructure;
import io.agentscope.builder.runtime.managed.RocketMqManagedMessageBus;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedHandsContextTest {
    @Test
    void productionHandsStartsWithoutAnyRuntimeWriterOrCheckpointCoordinator() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:hands-context-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        ManagedAdmissionStore.initialize(source);
        var bus = mock(RocketMqManagedMessageBus.class);
        when(bus.subscribe(any(), anyInt(), any(), any())).thenReturn(mock(Subscription.class));
        try (var mq = mockStatic(RocketMqManagedMessageBus.class);
                var redis = mockConstruction(RedisManagedInfrastructure.class)) {
            mq.when(() -> RocketMqManagedMessageBus.connect(any()))
                    .thenAnswer(
                            call -> {
                                RocketMqManagedMessageBus.Settings settings = call.getArgument(0);
                                assertThat(settings.publishChannels())
                                        .containsExactly(Channel.TOOL_EVENTS);
                                return bus;
                            });
            new ApplicationContextRunner()
                    .withUserConfiguration(ManagedHandsApplication.class)
                    .withBean(DataSource.class, () -> source)
                    .withPropertyValues(
                            "builder.managed.role=hands",
                            "builder.managed.mq.endpoints=mq.fixture:8080",
                            "builder.managed.redis-uri=redis://redis.fixture:6379",
                            "builder.vault.master-key=01234567890123456789012345678901",
                            "spring.jpa.hibernate.ddl-auto=none")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasSingleBean(ManagedHandsStore.class);
                                assertThat(context).doesNotHaveBean(ManagedJournalStore.class);
                                assertThat(context).doesNotHaveBean(ManagedResumePublisher.class);
                                assertThat(context).doesNotHaveBean(ManagedRuntimeWriter.class);
                                assertThat(context)
                                        .doesNotHaveBean(ManagedCheckpointOperations.class);
                            });
        }
    }
}
