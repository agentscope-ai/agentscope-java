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
package io.agentscope.builder.web.persistence.jpa;

import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Wires the JPA-backed {@link UserStore} and {@link UserAgentDefinitionStore}. This is the only
 * persistence backend the builder ships with.
 *
 * <h2>Default DataSource</h2>
 *
 * <p>The default {@code application.yml} points {@code spring.datasource.url} at an embedded H2
 * file under {@code ${user.home}/.agentscope-builder/db} — kept deliberately separate from
 * {@code builder.workspace} so workspace volumes never include the catalog tables. No external
 * services or extra setup are required for a single-node deployment.
 *
 * <h2>Switching to MySQL / PostgreSQL</h2>
 *
 * <p>Activate the bundled {@code jdbc} Spring profile to flip the DataSource defaults to
 * MySQL-shaped values:
 *
 * <pre>{@code
 * --spring.profiles.active=jdbc
 *
 * # Or override individual settings without the profile:
 * BUILDER_DB_URL=jdbc:postgresql://host:5432/agentscope_builder
 * BUILDER_DB_DRIVER=org.postgresql.Driver
 * BUILDER_DB_USER=...
 * BUILDER_DB_PASSWORD=...
 * BUILDER_JPA_DDL_AUTO=validate          # once Flyway / Liquibase manage the schema
 * }</pre>
 *
 * <p>The MySQL ({@code com.mysql:mysql-connector-j}) and PostgreSQL ({@code org.postgresql:postgresql})
 * JDBC drivers are bundled at runtime scope; the active Hibernate dialect is resolved from the
 * {@code spring.datasource.url}.
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = JpaPersistenceConfig.class)
@EntityScan(basePackageClasses = JpaPersistenceConfig.class)
@EnableTransactionManagement
public class JpaPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(JpaPersistenceConfig.class);

    @Bean
    public UserStore jpaUserStore(UserEntityRepository repository) {
        log.info("Persistence: user store backed by JPA");
        return new JpaUserStore(repository);
    }

    @Bean
    public UserAgentDefinitionStore jpaUserAgentDefinitionStore(AgentEntityRepository repository) {
        log.info("Persistence: agent definition store backed by JPA");
        return new JpaUserAgentDefinitionStore(repository);
    }
}
