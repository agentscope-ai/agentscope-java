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
package io.agentscope.builder.portal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.runtime.managed.ManagedInfrastructureConfiguration;
import io.agentscope.builder.web.managed.service.SessionEventNotifier;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import io.agentscope.builder.web.persistence.ServiceSchema;
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
@EntityScan(basePackageClasses = PortalSessionEntity.class)
@EnableJpaRepositories(basePackageClasses = PortalSessionRepository.class)
@Import({
    VaultCrypto.class,
    SessionEventNotifier.class,
    ManagedInfrastructureConfiguration.class,
    ManagedCatalog.class
})
public class PortalPersistenceConfig {
    @Bean
    public InitializingBean portalSchemaVerifier(DataSource source) {
        return () -> ServiceSchema.validatePostgres(source, "portal");
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper portalObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public TransactionTemplate portalTransactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
