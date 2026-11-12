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
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Role-owned Spring composition, separate from shared infrastructure. */
@Configuration(proxyBeanMethods = false)
public class AgentScopeInfrastructureConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-harness-runtime'")
    public ManagedSessionLogArchive managedSessionLogArchive(
            RuntimeObjectStore objects,
            ObjectMapper mapper,
            ManagedJournalStore journal,
            MeterRegistry metrics,
            @Value("${managed-agents.session-log.enabled:true}") boolean enabled,
            @Value("${managed-agents.session-log.chunks-enabled:false}") boolean chunks) {
        var archive = new ManagedSessionLogArchive(objects, mapper, enabled, chunks);
        journal.archive(archive);
        metrics.gauge(
                "managed.agents.session.log.buffered.bytes",
                archive,
                ManagedSessionLogArchive::bufferedBytes);
        metrics.gauge(
                "managed.agents.session.log.dropped", archive, ManagedSessionLogArchive::dropped);
        metrics.gauge(
                "managed.agents.session.log.uploads", archive, ManagedSessionLogArchive::uploads);
        metrics.gauge(
                "managed.agents.session.log.errors", archive, ManagedSessionLogArchive::errors);
        return archive;
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedToolRequests managedToolRequests(
            ObjectMapper mapper,
            ManagedToolResultDelivery results,
            ManagedBatchCodec codec,
            @Value("${managed-agents.runtime.inline-tool-wait-seconds:5}") long waitSeconds) {
        if (waitSeconds < 1 || waitSeconds > 30)
            throw new IllegalArgumentException("Managed inline tool wait must be 1..30 seconds");
        return new ManagedToolRequests(mapper, Duration.ofSeconds(waitSeconds), results, codec);
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-harness-runtime'")
    public SessionServiceClient sessionServiceClient(
            ObjectMapper mapper,
            @Value(
                            "${managed-agents.session-service.url:http://localhost:8084/internal/session-service/v0}")
                    String url,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        return new SessionServiceClient(URI.create(url), token, mapper, Duration.ofSeconds(30));
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-harness-runtime'")
    public ManagedRuntimeWriter managedRuntimeWriter(
            ManagedJournalStore journal, SessionServiceClient client, ObjectMapper mapper) {
        return new ManagedRuntimeWriter(journal, client, mapper);
    }
}
