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

import com.alicloud.openservices.tablestore.ClientConfiguration;
import com.alicloud.openservices.tablestore.SyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.TableStoreSessionProjectionStore;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit independent query backend, selected identically for API and projector processes. */
@Configuration(proxyBeanMethods = false)
public class ManagedQueryStoreConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "managed-agents.query-store.provider", havingValue = "tablestore")
    SyncClient queryTableStoreClient(
            @Value("${managed-agents.query-store.endpoint}") String endpoint,
            @Value("${managed-agents.query-store.instance}") String instance,
            @Value("${managed-agents.query-store.access-key}") String accessKey,
            @Value("${managed-agents.query-store.secret-key}") String secretKey,
            @Value("${managed-agents.query-store.max-connections:256}") int connections) {
        if (connections < 1 || connections > 2048)
            throw new IllegalArgumentException("Invalid query connection limit");
        var config = new ClientConfiguration();
        config.setMaxConnections(connections);
        config.setSocketTimeoutInMillisecond(5000);
        config.setConnectionTimeoutInMillisecond(3000);
        return new SyncClient(endpoint, accessKey, secretKey, instance, config);
    }

    @Bean
    @ConditionalOnProperty(name = "managed-agents.query-store.provider", havingValue = "tablestore")
    SessionProjectionStore tableStoreProjectionStore(
            SyncClient client,
            ObjectMapper mapper,
            @Value("${managed-agents.query-store.table-prefix:Ma}") String prefix) {
        return new TableStoreSessionProjectionStore(client, mapper, prefix);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "managed-agents.query-store.provider", havingValue = "postgres")
    PostgresSessionProjectionStore postgresProjectionStore(
            ObjectMapper mapper,
            @Value("${managed-agents.query-store.jdbc-url}") String url,
            @Value("${managed-agents.query-store.user}") String user,
            @Value("${managed-agents.query-store.password:}") String password,
            @Value("${managed-agents.query-store.pool-size:16}") int size) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setPoolName("session-query-store");
        return new PostgresSessionProjectionStore(new HikariDataSource(config), mapper, true);
    }

    @Bean
    @ConditionalOnProperty(name = "managed-agents.query-store.enabled", havingValue = "true")
    ManagedQueryViews managedQueryViews(
            SessionProjectionStore store,
            ObjectMapper mapper,
            DataSource executionSource,
            @Value("${managed-agents.query-store.signing-key}") String signingKey,
            @Value("${managed-agents.query-store.build:v1}") String build,
            @Value("${managed-agents.query-store.segment-span:100000}") int span) {
        return new ManagedQueryViews(
                store,
                mapper,
                signingKey.getBytes(StandardCharsets.UTF_8),
                build,
                span,
                new ManagedPublicResources(executionSource, mapper));
    }
}
