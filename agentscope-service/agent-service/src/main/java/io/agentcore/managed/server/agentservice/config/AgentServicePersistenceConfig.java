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
package io.agentcore.managed.server.agentservice.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.agentscope.AgentScopeCheckpointCodec;
import io.agentcore.managed.harness.runtime.NativeCheckpointCodec;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedInfrastructureConfiguration;
import io.agentcore.managed.server.runtime.managed.SessionInfrastructureConfiguration;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import io.agentcore.managed.server.web.persistence.ServiceSchema;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = AgentServiceSessionEntity.class)
@EnableJpaRepositories(basePackageClasses = AgentServiceSessionRepository.class)
@Import({
    VaultCrypto.class,
    SessionEventNotifier.class,
    ManagedInfrastructureConfiguration.class,
    SessionInfrastructureConfiguration.class,
    ManagedCatalog.class
})
public class AgentServicePersistenceConfig {
    @Bean
    public NativeCheckpointCodec agentScopeCheckpointCodec() {
        return new AgentScopeCheckpointCodec();
    }

    @Bean
    public InitializingBean agentServiceSchemaVerifier(DataSource source) {
        return () -> ServiceSchema.validatePostgres(source, "portal");
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper agentServiceObjectMapper() {
        var mapper =
                new ObjectMapper()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .findAndRegisterModules();
        mapper.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(90 * 1024 * 1024).build());
        return mapper;
    }

    @Bean
    public TransactionTemplate agentServiceTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
