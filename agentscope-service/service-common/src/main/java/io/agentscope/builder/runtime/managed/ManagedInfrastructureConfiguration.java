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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import io.agentscope.builder.web.managed.SessionUsagePricer;
import io.agentscope.builder.web.managed.SessionUsagePricingConfiguration;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.builder.web.persistence.ServiceSchema;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Shared physical dependencies. Missing/unavailable infrastructure never selects a fake backend. */
@Configuration(proxyBeanMethods = false)
@Import(SessionUsagePricingConfiguration.class)
public class ManagedInfrastructureConfiguration {
    @Bean
    public InitializingBean managedSchemaValidation(DataSource source) {
        return () -> ServiceSchema.validatePostgres(source, "managed");
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedAdmissionStore managedAdmissionStore(
            DataSource source,
            ObjectMapper mapper,
            @Value("${builder.portal.max-queued-per-application:1000}") int group,
            @Value("${builder.portal.max-queued-per-session:128}") int session,
            @Value("${builder.portal.max-pending-inputs-per-session:128}") int inputs,
            @Value("${builder.portal.requests-per-second:100}") int rate) {
        return new ManagedAdmissionStore(
                source, mapper, new ManagedAdmissionStore.Limits(group, session, inputs, rate));
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedJournalStore managedJournalStore(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects,
            SessionEventNotifier notifications) {
        return new ManagedJournalStore(source, mapper, budgets, objects, notifications);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedSubagents managedSubagents(
            DataSource source,
            ObjectMapper mapper,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            @Value("${builder.managed.max-active-child-calls-per-root:256}") int capacity) {
        return new ManagedSubagents(source, mapper, admission, journal, capacity);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedBudgetStore managedBudgetStore(
            DataSource source, ObjectMapper mapper, SessionUsagePricer prices) {
        return new ManagedBudgetStore(source, mapper, prices);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedLifecycles managedLifecycles(DataSource source) {
        return new ManagedLifecycles(source);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedCheckpointOperations managedCheckpointOperations(
            DataSource source,
            ObjectMapper mapper,
            ManagedJournalStore journal,
            ManagedCatalog catalog) {
        return new ManagedCheckpointOperations(source, mapper, journal, catalog);
    }

    @Bean(destroyMethod = "close")
    public RedisManagedInfrastructure managedRedis(
            @Value("${builder.managed.redis-uri:${BUILDER_MANAGED_REDIS_URI:}}") String uri,
            @Value("${builder.managed.blocking-reads:64}") int readers) {
        return new RedisManagedInfrastructure(uri, readers);
    }

    @Bean(destroyMethod = "close")
    public RocketMqManagedMessageBus managedMessageBus(
            @Value("${builder.managed.role}") String role,
            @Value("${builder.managed.mq.endpoints:${BUILDER_MANAGED_MQ_ENDPOINTS:}}")
                    String endpoints,
            @Value("${builder.managed.mq.turn-topic:ma_turns_v3}") String turns,
            @Value("${builder.managed.mq.tool-event-topic:ma_tool_events_v3}") String tools,
            @Value("${builder.managed.mq.resume-topic:ma_turn_resumes_v3}") String resumes,
            @Value("${builder.managed.mq.tool-execute-topic:ma_tool_execute_v1}") String executes,
            @Value("${builder.managed.mq.tool-control-topic:ma_tool_control_v1}") String controls,
            @Value("${builder.managed.mq.brain-group:ma_brain_v3}") String brainGroup,
            @Value("${builder.managed.mq.resume-group:ma_brain_resumes_v3}") String resumeGroup,
            @Value("${builder.managed.mq.tool-events-group:ma_brain_tool_events_v3}")
                    String toolGroup,
            @Value("${builder.managed.mq.tool-execute-group:ma_hands_execute_v1}")
                    String executeGroup,
            @Value("${builder.managed.mq.tool-control-group:ma_hands_control_v1}")
                    String controlGroup,
            @Value("${builder.managed.mq.cache-messages:512}") int cacheMessages,
            @Value("${builder.managed.mq.cache-bytes:33554432}") int cacheBytes,
            @Value("${builder.managed.mq.ssl:false}") boolean ssl,
            @Value("${builder.managed.mq.access-key:${BUILDER_MANAGED_MQ_ACCESS_KEY:}}")
                    String accessKey,
            @Value("${builder.managed.mq.secret-key:${BUILDER_MANAGED_MQ_SECRET_KEY:}}")
                    String secretKey) {
        ManagedRuntimeLog.info(
                LoggerFactory.getLogger(ManagedInfrastructureConfiguration.class),
                role + ".instance.configured",
                null,
                null,
                "role",
                role);
        var groups =
                switch (role) {
                    case "portal" -> Map.<Channel, String>of();
                    case "brain" ->
                            Map.of(
                                    Channel.TURNS,
                                    brainGroup,
                                    Channel.TURN_RESUMES,
                                    resumeGroup,
                                    Channel.TOOL_EVENTS,
                                    toolGroup);
                    case "hands" ->
                            Map.of(
                                    Channel.TOOL_EXECUTE,
                                    executeGroup,
                                    Channel.TOOL_CONTROL,
                                    controlGroup);
                    default ->
                            throw new IllegalArgumentException(
                                    "Managed role must be portal, brain, or" + " hands");
                };
        return RocketMqManagedMessageBus.connect(
                new RocketMqManagedMessageBus.Settings(
                        endpoints,
                        turns,
                        resumes,
                        tools,
                        executes,
                        controls,
                        switch (role) {
                            case "portal" -> Set.of(Channel.TURNS);
                            case "brain" ->
                                    Set.of(
                                            Channel.TOOL_EXECUTE,
                                            Channel.TOOL_CONTROL,
                                            Channel.TURN_RESUMES,
                                            Channel.TOOL_EVENTS);
                            case "hands" -> Set.of(Channel.TOOL_EVENTS);
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown Managed producer role");
                        },
                        groups,
                        cacheMessages,
                        cacheBytes,
                        ssl,
                        mqCredentials(accessKey, secretKey)));
    }

    static SessionCredentialsProvider mqCredentials(String accessKey, String secretKey) {
        boolean hasAccessKey = accessKey != null && !accessKey.isBlank();
        boolean hasSecretKey = secretKey != null && !secretKey.isBlank();
        if (hasAccessKey != hasSecretKey) {
            throw new IllegalArgumentException(
                    "RocketMQ access-key and secret-key must be configured together");
        }
        return hasAccessKey ? new StaticSessionCredentialsProvider(accessKey, secretKey) : null;
    }

    @Bean
    public ManagedPostgresObjects managedPostgresObjects(DataSource source) {
        return new ManagedPostgresObjects(source);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedCheckpointObjects managedCheckpointObjects(
            ObjectMapper mapper, ManagedPostgresObjects objects) {
        return new ManagedCheckpointObjects(mapper, objects);
    }

    @Bean
    public ManagedBatchCodec managedBatchCodec(
            ObjectMapper mapper, ManagedPostgresObjects objects) {
        return new ManagedBatchCodec(mapper, objects);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedTurnPublisher managedTurnPublisher(
            ManagedAdmissionStore store,
            RocketMqManagedMessageBus bus,
            RedisManagedInfrastructure redis,
            ObjectMapper mapper) {
        return new ManagedTurnPublisher(store, bus, redis, mapper, Duration.ofSeconds(60));
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedInputDelivery managedInputDelivery(
            ManagedAdmissionStore store, RedisManagedInfrastructure redis) {
        return new ManagedInputDelivery(store, redis);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedToolRequests managedToolRequests(
            ObjectMapper mapper,
            ManagedToolResultDelivery results,
            ManagedBatchCodec codec,
            @Value("${builder.managed.inline-tool-wait-seconds:5}") long waitSeconds) {
        if (waitSeconds < 1 || waitSeconds > 30)
            throw new IllegalArgumentException("Managed inline tool wait must be 1..30 seconds");
        return new ManagedToolRequests(mapper, Duration.ofSeconds(waitSeconds), results, codec);
    }

    @Bean
    public ManagedToolResultDelivery managedToolResultDelivery(RedisManagedInfrastructure redis) {
        return new RedisManagedToolResultDelivery(redis);
    }

    @Bean
    @ConditionalOnExpression("'${builder.managed.role:brain}' != 'hands'")
    public ManagedRuntimeWriter managedRuntimeWriter(
            ManagedJournalStore journal, RocketMqManagedMessageBus bus, ManagedBatchCodec codec) {
        journal.transport().configure(bus, codec);
        return new ManagedRuntimeWriter(journal);
    }
}
