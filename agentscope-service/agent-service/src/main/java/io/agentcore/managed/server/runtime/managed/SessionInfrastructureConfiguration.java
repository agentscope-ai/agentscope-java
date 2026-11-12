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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.NativeCheckpointCodec;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Role-owned Spring composition, separate from shared infrastructure. */
@Configuration(proxyBeanMethods = false)
public class SessionInfrastructureConfiguration {
    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-service'")
    public ManagedCheckpointOperations managedCheckpointOperations(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore journal,
            ManagedCatalog catalog,
            NativeCheckpointCodec codec) {
        return new ManagedCheckpointOperations(source, mapper, journal, codec, catalog);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-service'")
    public ManagedApplicationUsage managedApplicationUsage(DataSource source) {
        return new ManagedApplicationUsage(source);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-service'")
    public ManagedProjectionPublisher managedProjectionPublisher(
            ManagedJournalStore journal,
            RocketMqManagedMessageBus bus,
            ObjectMapper mapper,
            @Value("${managed-agents.runtime.projection-notification-concurrency:16}")
                    int concurrency,
            @Value("${managed-agents.runtime.projection-coalesce-ms:200}") long coalesceMs) {
        return new ManagedProjectionPublisher(journal, bus, mapper, concurrency, coalesceMs);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-service'")
    public ManagedToolCommandPublisher managedToolCommandPublisher(
            DataSource source,
            ManagedJournalStore journal,
            RocketMqManagedMessageBus bus,
            ManagedBatchCodec codec,
            ObjectMapper mapper,
            @Value("${managed-agents.runtime.tool-publication-concurrency:32}") int concurrency,
            @Value("${managed-agents.runtime.tool-publication-repair-interval:30s}")
                    Duration repair) {
        var publisher =
                new ManagedToolCommandPublisher(source, bus, codec, mapper, concurrency, repair);
        journal.transport().onPublicationReady(publisher::wakeup);
        return publisher;
    }
}
