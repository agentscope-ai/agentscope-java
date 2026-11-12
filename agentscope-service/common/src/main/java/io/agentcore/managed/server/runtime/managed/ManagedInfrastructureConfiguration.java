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
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.web.managed.SessionUsagePricer;
import io.agentcore.managed.server.web.managed.SessionUsagePricingConfiguration;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentcore.managed.server.web.persistence.ServiceSchema;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Shared physical dependencies. Missing/unavailable infrastructure never selects a fake backend. */
@Configuration(proxyBeanMethods = false)
@Import({SessionUsagePricingConfiguration.class, ManagedQueryStoreConfiguration.class})
public class ManagedInfrastructureConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(
            "('${managed-agents.runtime.role:agent-harness-runtime}' == 'agent-harness-runtime'"
                    + " || ${managed-agents.runtime.process-ownership:false}) &&"
                    + " '${managed-agents.runtime.role:agent-harness-runtime}' != 'agent-service'")
    ManagedProcessOwnership managedProcessOwnership(
            DataSource source,
            ApplicationEventPublisher availability,
            ObjectProvider<SessionServiceClient> sessionService,
            @Value("${managed-agents.runtime.role:agent-harness-runtime}") String role,
            @Value("${managed-agents.runtime.fragment-deadline-seconds:600}") long fragment,
            @Value("${managed-agents.runtime.tool-collector-deadline-seconds:3600}") long tool) {
        var ownership =
                "agent-harness-runtime".equals(role)
                        ? new ManagedProcessOwnership(
                                sessionService.getObject(),
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(fragment),
                                Duration.ofSeconds(tool))
                        : new ManagedProcessOwnership(
                                source,
                                role,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(fragment),
                                Duration.ofSeconds(tool));
        ownership.onExpired(
                () -> {
                    AvailabilityChangeEvent.publish(
                            availability, ownership, ReadinessState.REFUSING_TRAFFIC);
                    AvailabilityChangeEvent.publish(availability, ownership, LivenessState.BROKEN);
                });
        return ownership;
    }

    @Bean
    @ConditionalOnProperty(name = "managed-agents.runtime.stable-routing", havingValue = "true")
    ManagedStableRouting managedStableRouting(
            DataSource source,
            @Value("${managed-agents.runtime.route-cell:default}") String cell,
            @Value("${managed-agents.runtime.route-layout:1}") int layout,
            @Value("${managed-agents.runtime.route-shards:64}") int shards) {
        return new ManagedStableRouting(source, cell, layout, shards);
    }

    @Bean
    public InitializingBean managedSchemaValidation(DataSource source) {
        return () -> ServiceSchema.validatePostgres(source, "managed");
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedAdmissionStore managedAdmissionStore(
            DataSource source,
            ObjectMapper mapper,
            RedisManagedInfrastructure redis,
            @Value("${managed-agents.agent-service.max-queued-per-application:1000}") int group,
            @Value("${managed-agents.agent-service.max-queued-per-session:128}") int session,
            @Value("${managed-agents.agent-service.max-pending-inputs-per-session:128}") int inputs,
            @Value("${managed-agents.agent-service.requests-per-second:100}") int rate,
            ObjectProvider<ManagedProcessOwnership> ownership,
            ObjectProvider<ManagedStableRouting> routing,
            ObjectProvider<ManagedQueryViews> queryViews,
            ObjectProvider<SessionServiceClient> sessionService) {
        if (sessionService.getIfAvailable() != null)
            return new ManagedAdmissionStore(
                    sessionService.getObject(), mapper, ownership.getObject());
        var admission =
                new ManagedAdmissionStore(
                        source,
                        mapper,
                        new ManagedAdmissionStore.Limits(group, session, inputs, rate),
                        redis::allowApplicationRequest);
        ownership.ifAvailable(admission::processOwnership);
        routing.ifAvailable(admission::routing);
        queryViews.ifAvailable(admission::queryViews);
        return admission;
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedJournalStore managedJournalStore(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects,
            SessionEventNotifier notifications,
            ObjectProvider<ManagedQueryViews> queryViews,
            ObjectProvider<ManagedProcessOwnership> ownership,
            ObjectProvider<SessionServiceClient> sessionService) {
        var journal = new ManagedJournalStore(source, mapper, budgets, objects, notifications);
        queryViews.ifAvailable(journal::queryViews);
        ownership.ifAvailable(journal::processOwnership);
        sessionService.ifAvailable(journal::remoteReads);
        return journal;
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedSubagents managedSubagents(
            DataSource source,
            ObjectMapper mapper,
            ManagedAdmissionStore admission,
            ManagedJournalStore journal,
            @Value("${managed-agents.runtime.max-active-child-calls-per-root:256}") int capacity,
            ObjectProvider<SessionServiceClient> sessionService) {
        if (sessionService.getIfAvailable() != null)
            return new ManagedSubagents(sessionService.getObject(), mapper);
        return new ManagedSubagents(source, mapper, admission, journal, capacity);
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedBudgetStore managedBudgetStore(
            DataSource source,
            ObjectMapper mapper,
            SessionUsagePricer prices,
            ObjectProvider<SessionServiceClient> sessionService) {
        var budgets = new ManagedBudgetStore(source, mapper, prices);
        sessionService.ifAvailable(budgets::remote);
        return budgets;
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedLifecycles managedLifecycles(DataSource source) {
        return new ManagedLifecycles(source);
    }

    @Bean(destroyMethod = "close")
    public RedisManagedInfrastructure managedRedis(
            @Value("${managed-agents.runtime.redis-uri:${MANAGED_AGENTS_RUNTIME_REDIS_URI:}}")
                    String uri,
            @Value("${managed-agents.runtime.blocking-reads:64}") int readers) {
        return new RedisManagedInfrastructure(uri, readers);
    }

    @Bean(destroyMethod = "close")
    public RocketMqManagedMessageBus managedMessageBus(
            @Value("${managed-agents.runtime.role}") String role,
            @Value("${managed-agents.runtime.session-queue.enabled:false}") boolean sessionQueue,
            @Value("${managed-agents.runtime.mq.session-ready-topic:ma_session_ready_v1}")
                    String readyTopic,
            @Value("${managed-agents.projection.role:both}") String projectionRole,
            @Value("${managed-agents.runtime.mq.projection-topic:ma_session_projections_v3}")
                    String projectionTopic,
            @Value("${managed-agents.runtime.mq.projection-group:ma_session_projections_v3}")
                    String projectionGroup,
            @Value("${managed-agents.runtime.mq.session-ready-group:ma_session_ready_v1}")
                    String readyGroup,
            @Value("${managed-agents.runtime.mq.endpoints:${MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS:}}")
                    String endpoints,
            @Value("${managed-agents.runtime.mq.turn-topic:ma_turns_v3}") String turns,
            @Value("${managed-agents.runtime.mq.tool-event-topic:ma_tool_events_v3}") String tools,
            @Value("${managed-agents.runtime.mq.resume-topic:ma_turn_resumes_v3}") String resumes,
            @Value("${managed-agents.runtime.mq.tool-execute-topic:ma_tool_execute_v1}")
                    String executes,
            @Value("${managed-agents.runtime.mq.tool-control-topic:ma_tool_control_v1}")
                    String controls,
            @Value("${managed-agents.runtime.mq.harness-runtime-group:ma_brain_v3}")
                    String harnessRuntimeGroup,
            @Value("${managed-agents.runtime.mq.resume-group:ma_brain_resumes_v3}")
                    String resumeGroup,
            @Value("${managed-agents.runtime.mq.tool-events-group:ma_brain_tool_events_v3}")
                    String toolGroup,
            @Value("${managed-agents.runtime.mq.tool-execute-group:ma_hands_execute_v1}")
                    String executeGroup,
            @Value("${managed-agents.runtime.mq.tool-control-group:ma_hands_control_v1}")
                    String controlGroup,
            @Value("${managed-agents.runtime.mq.cache-messages:512}") int cacheMessages,
            @Value("${managed-agents.runtime.mq.cache-bytes:33554432}") int cacheBytes,
            @Value("${managed-agents.runtime.mq.ssl:false}") boolean ssl,
            @Value(
                            "${managed-agents.runtime.mq.access-key:${MANAGED_AGENTS_RUNTIME_MQ_ACCESS_KEY:}}")
                    String accessKey,
            @Value(
                            "${managed-agents.runtime.mq.secret-key:${MANAGED_AGENTS_RUNTIME_MQ_SECRET_KEY:}}")
                    String secretKey,
            ObjectProvider<ManagedStableRouting> routing) {
        ManagedRuntimeLog.info(
                LoggerFactory.getLogger(ManagedInfrastructureConfiguration.class),
                role + ".instance.configured",
                null,
                null,
                "role",
                role);
        if (!Set.of("api-only", "both").contains(projectionRole))
            throw new IllegalArgumentException("Projection role must be api-only or both");
        var groups =
                switch (role) {
                    case "agent-service" -> Map.of(Channel.TOOL_EVENTS, toolGroup);
                    case "agent-harness-runtime" ->
                            Map.of(
                                    Channel.TURNS,
                                    harnessRuntimeGroup,
                                    Channel.TURN_RESUMES,
                                    resumeGroup);
                    case "tool-worker" ->
                            Map.of(
                                    Channel.TOOL_EXECUTE,
                                    executeGroup,
                                    Channel.TOOL_CONTROL,
                                    controlGroup);
                    default ->
                            throw new IllegalArgumentException(
                                    "Managed role must be agent-service, agent-harness-runtime, or"
                                            + " tool-worker");
                };
        var consumerGroups = new HashMap<Channel, String>(groups);
        if ("agent-service".equals(role) && !"api-only".equals(projectionRole))
            consumerGroups.put(Channel.SESSION_PROJECTIONS, projectionGroup);
        if (sessionQueue && "agent-harness-runtime".equals(role))
            consumerGroups.put(Channel.SESSION_READY, readyGroup);
        var bus =
                RocketMqManagedMessageBus.connect(
                        new RocketMqManagedMessageBus.Settings(
                                endpoints,
                                turns,
                                resumes,
                                tools,
                                executes,
                                controls,
                                publishChannels(role, sessionQueue),
                                consumerGroups,
                                cacheMessages,
                                cacheBytes,
                                ssl,
                                mqCredentials(accessKey, secretKey),
                                readyTopic),
                        Map.of(Channel.SESSION_PROJECTIONS, projectionTopic));
        routing.ifAvailable(bus::routing);
        return bus;
    }

    static Set<Channel> publishChannels(String role, boolean sessionQueue) {
        return switch (role) {
            case "agent-service" ->
                    sessionQueue
                            ? Set.of(
                                    Channel.TURNS,
                                    Channel.SESSION_READY,
                                    Channel.TOOL_EXECUTE,
                                    Channel.TOOL_CONTROL,
                                    Channel.TOOL_EVENTS,
                                    Channel.TURN_RESUMES,
                                    Channel.SESSION_PROJECTIONS)
                            : Set.of(
                                    Channel.TURNS,
                                    Channel.TOOL_EXECUTE,
                                    Channel.TOOL_CONTROL,
                                    Channel.TOOL_EVENTS,
                                    Channel.TURN_RESUMES,
                                    Channel.SESSION_PROJECTIONS);
            case "agent-harness-runtime" ->
                    Set.of(
                            Channel.TOOL_EXECUTE,
                            Channel.TOOL_CONTROL,
                            Channel.TURN_RESUMES,
                            Channel.TOOL_EVENTS,
                            Channel.SESSION_PROJECTIONS);
            case "tool-worker" -> Set.of(Channel.TOOL_EVENTS, Channel.TOOL_EXECUTE);
            default -> throw new IllegalArgumentException("Unknown Managed producer role");
        };
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

    @Bean(destroyMethod = "close")
    public RuntimeObjectStore runtimeObjectStore(
            @Value("${managed-agents.object-store.provider:filesystem}") String provider,
            @Value("${managed-agents.object-store.backend-id:runtime}") String backendId,
            @Value("${managed-agents.object-store.root:}") String root,
            @Value("${managed-agents.object-store.endpoint:}") String endpoint,
            @Value("${managed-agents.object-store.bucket:}") String bucket,
            @Value("${managed-agents.object-store.region:us-east-1}") String region,
            @Value("${managed-agents.object-store.access-key:}") String access,
            @Value("${managed-agents.object-store.secret-key:}") String secret,
            @Value("${managed-agents.object-store.max-requests:32}") int requests,
            @Value("${managed-agents.object-store.max-inflight-bytes:268435456}") long bytes,
            @Value("${managed-agents.object-store.capacity-wait-ms:5000}") long waitMs,
            @Value("${managed-agents.object-store.cache-bytes:67108864}") long cacheBytes,
            @Value("${managed-agents.object-store.cache-item-bytes:262144}") int cacheItemBytes,
            MeterRegistry metrics,
            @Value("${managed-agents.runtime.role:agent-harness-runtime}") String role,
            ObjectProvider<SessionServiceClient> sessionService) {
        if ("agent-harness-runtime".equals(role))
            return new RemoteRuntimeObjectStore(
                    sessionService.getObject(), backendId, new ObjectMapper());
        if (requests < 1 || requests > 1024)
            throw new IllegalArgumentException("Invalid object-store connection budget");
        if (bytes < RuntimeObjectStore.MAX_BYTES
                || bytes > 128L * RuntimeObjectStore.MAX_BYTES
                || waitMs < 1
                || waitMs > 60000)
            throw new IllegalArgumentException("Invalid object-store byte or wait budget");
        if (cacheBytes < 0
                || cacheBytes > 1024L * 1024 * 1024
                || cacheItemBytes < 1
                || cacheItemBytes > RuntimeObjectStore.MAX_BYTES)
            throw new IllegalArgumentException("Invalid immutable cache budget");
        RuntimeObjectStore backend =
                switch (provider) {
                    case "filesystem" -> {
                        if (root.isBlank())
                            throw new IllegalArgumentException(
                                    "Object-store root is required; replicas must share this"
                                            + " persistent directory");
                        yield new FileRuntimeObjectStore(backendId, Path.of(root));
                    }
                    case "s3" ->
                            new S3RuntimeObjectStore(
                                    backendId, endpoint, bucket, region, access, secret, requests);
                    case "oss" ->
                            new OssRuntimeObjectStore(
                                    backendId, endpoint, bucket, access, secret, requests);
                    default ->
                            throw new IllegalArgumentException(
                                    "Object-store provider must be filesystem, s3 or oss");
                };
        var bounded =
                new BoundedRuntimeObjectStore(
                        backend,
                        requests,
                        bytes,
                        Duration.ofMillis(waitMs),
                        metrics,
                        "shared",
                        true);
        return new CachedRuntimeObjectStore(bounded, cacheBytes, cacheItemBytes, metrics);
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedCheckpointObjects managedCheckpointObjects(
            ObjectMapper mapper,
            RuntimeObjectStore objects,
            @Value("${managed-agents.object-store.state-cache-entries:16384}")
                    int stateCacheEntries) {
        return new ManagedCheckpointObjects(mapper, objects, stateCacheEntries);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedSessionPreviews managedSessionPreviews(
            RedisManagedInfrastructure redis, ObjectMapper mapper, ManagedJournalStore journal) {
        var previews = new ManagedSessionPreviews(redis, mapper);
        journal.previews(previews);
        return previews;
    }

    @Bean
    public ManagedBatchCodec managedBatchCodec(ObjectMapper mapper, RuntimeObjectStore objects) {
        return new ManagedBatchCodec(mapper, objects);
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedTurnPublisher managedTurnPublisher(
            ManagedAdmissionStore store,
            RocketMqManagedMessageBus bus,
            RedisManagedInfrastructure redis,
            ObjectMapper mapper,
            @Value("${managed-agents.brain.http-enabled:false}") boolean httpBrain) {
        var publisher = new ManagedTurnPublisher(store, bus, redis, mapper, Duration.ofSeconds(60));
        publisher.httpBrain(httpBrain);
        return publisher;
    }

    @Bean
    @ConditionalOnExpression(
            "'${managed-agents.runtime.role:agent-harness-runtime}' != 'tool-worker'")
    public ManagedInputDelivery managedInputDelivery(
            ManagedAdmissionStore store, RedisManagedInfrastructure redis) {
        return new ManagedInputDelivery(store, redis);
    }

    @Bean
    public ManagedToolResultDelivery managedToolResultDelivery(RedisManagedInfrastructure redis) {
        return new RedisManagedToolResultDelivery(redis);
    }
}
