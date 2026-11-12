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
package io.agentcore.managed.server.toolworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.runtime.managed.ManagedAdmissionStore;
import io.agentcore.managed.server.runtime.managed.ManagedCheckpointOperations;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Subscription;
import io.agentcore.managed.server.runtime.managed.ManagedResumePublisher;
import io.agentcore.managed.server.runtime.managed.ManagedToolWorkerStore;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManagedToolWorkerContextTest {
    @TempDir Path objects;

    @Test
    void productionToolWorkerStartsWithoutAnyRuntimeWriterOrCheckpointCoordinator() {
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
            mq.when(() -> RocketMqManagedMessageBus.connect(any(), anyMap()))
                    .thenAnswer(
                            call -> {
                                RocketMqManagedMessageBus.Settings settings = call.getArgument(0);
                                assertThat(settings.publishChannels())
                                        .containsExactlyInAnyOrder(
                                                Channel.TOOL_EVENTS, Channel.TOOL_EXECUTE);
                                return bus;
                            });
            new ApplicationContextRunner()
                    .withUserConfiguration(ManagedToolWorkerApplication.class)
                    .withBean(DataSource.class, () -> source)
                    .withPropertyValues(
                            "managed-agents.runtime.role=tool-worker",
                            "managed-agents.object-store.root=" + objects,
                            "managed-agents.runtime.mq.endpoints=mq.fixture:8080",
                            "managed-agents.runtime.redis-uri=redis://redis.fixture:6379",
                            "managed-agents.vault.master-key=01234567890123456789012345678901",
                            "spring.jpa.hibernate.ddl-auto=none")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(context).hasSingleBean(ManagedToolWorkerStore.class);
                                assertThat(context).doesNotHaveBean(ManagedJournalStore.class);
                                assertThat(context).doesNotHaveBean(ManagedResumePublisher.class);
                                assertThat(context).doesNotHaveBean("managedRuntimeWriter");
                                assertThat(context)
                                        .doesNotHaveBean(ManagedCheckpointOperations.class);
                            });
        }
    }
}
