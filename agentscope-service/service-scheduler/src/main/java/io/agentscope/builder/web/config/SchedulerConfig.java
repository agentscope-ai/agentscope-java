/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.outbound.OutboundService;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.JdbcCoordinationStore;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordHitlTicketEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordLeaseEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkItemEntityRepository;
import io.agentscope.builder.web.persistence.jpa.CoordWorkerHeartbeatEntityRepository;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.share.AgentVisibilityResolver;
import io.agentscope.builder.web.share.JpaAgentVisibilityResolver;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.ChannelManager;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Bean assembly for the builder scheduler plane.
 *
 * <ul>
 *   <li>{@link ObjectMapper} — lenient mapper (unknown properties ignored) shared by the channel
 *       runtime and the session bridge when talking to the other planes;
 *   <li>{@link ChannelManager} — harness channel registry owning every live IM channel adapter;
 *   <li>{@link OutboundService} — outbound delivery into registered channels;
 *   <li>{@link AgentVisibilityResolver} — JPA-only read view over the shared database, backing
 *       {@code AgentAccessGuard} on the outbound endpoint;
 *   <li>{@code controlPlaneWebClient} / {@code dataPlaneWebClient} — plane-to-plane HTTP clients
 *       pre-authenticated with the shared internal token.
 * </ul>
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    /** Lenient mapper: tolerates fields added by newer control/data planes. */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Harness channel registry; populated by {@code SchedulerChannelRuntime} at startup. */
    @Bean
    public ChannelManager channelManager() {
        return new ChannelManager();
    }

    /** Outbound delivery service shared by the HTTP controller. */
    @Bean
    public OutboundService outboundService(ChannelManager channelManager) {
        return new OutboundService(channelManager);
    }

    /**
     * JPA-only {@link AgentVisibilityResolver} read view over the shared database. The scheduler
     * only reads; the control plane owns the authoritative catalog-backed resolver.
     */
    @Bean
    @ConditionalOnMissingBean(AgentVisibilityResolver.class)
    public AgentVisibilityResolver agentVisibilityResolver(
            AgentVersionEntityRepository versionRepository,
            AgentVersionService versionService,
            UserAgentDefinitionStore userAgentDefinitionStore,
            UserStore userStore,
            AgentAclService aclService) {
        return new JpaAgentVisibilityResolver(
                versionRepository, versionService, userAgentDefinitionStore, userStore, aclService);
    }

    /**
     * Default {@link BaseStore} backed by the Spring-managed {@link DataSource}, mirroring the
     * control/data planes — all planes point at the same JDBC database, so definition files stay
     * visible across planes. Required by {@code BaseStoreDefinitionStore} on the component-scan
     * path from service-common. Operators can override by declaring their own {@link BaseStore}.
     */
    @Bean
    @ConditionalOnMissingBean(BaseStore.class)
    public BaseStore baseStore(DataSource dataSource) {
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    /** Shared coordination store for cron fire leases (and future scheduler coordination). */
    @Bean
    @ConditionalOnMissingBean(CoordinationStore.class)
    public CoordinationStore coordinationStore(
            CoordLeaseEntityRepository leaseRepository,
            CoordHitlTicketEntityRepository hitlRepository,
            CoordWorkItemEntityRepository workRepository,
            CoordWorkerHeartbeatEntityRepository workerRepository,
            TransactionTemplate transactionTemplate) {
        log.info("Wiring default JdbcCoordinationStore on the Spring DataSource / JPA schema");
        return new JdbcCoordinationStore(
                leaseRepository,
                hitlRepository,
                workRepository,
                workerRepository,
                transactionTemplate);
    }

    /** HTTP client for the control plane, pre-authenticated with the internal token. */
    @Bean
    @Qualifier("controlPlaneWebClient")
    public WebClient controlPlaneWebClient(
            @Value("${builder.control-plane-url:http://localhost:8081}") String baseUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        return internalClient(baseUrl, internalToken);
    }

    /** HTTP client for the data plane, pre-authenticated with the internal token. */
    @Bean
    @Qualifier("dataPlaneWebClient")
    public WebClient dataPlaneWebClient(
            @Value("${builder.data-plane-url:http://localhost:8082}") String baseUrl,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        return internalClient(baseUrl, internalToken);
    }

    private static WebClient internalClient(String baseUrl, String internalToken) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (internalToken != null && !internalToken.isBlank()) {
            builder.defaultHeader(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken);
        }
        return builder.build();
    }
}
