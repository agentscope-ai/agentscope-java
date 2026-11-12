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
package io.agentcore.managed.server.agentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.agentcore.managed.server.runtime.managed.BoundedRuntimeObjectStore;
import io.agentcore.managed.server.runtime.managed.ManagedProjectionConsumer;
import io.agentcore.managed.server.runtime.managed.ManagedProjectionWakeups;
import io.agentcore.managed.server.runtime.managed.ManagedQueryViews;
import io.agentcore.managed.server.runtime.managed.ManagedSessionProjections;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.runtime.managed.RocketMqManagedMessageBus;
import io.agentcore.managed.server.runtime.managed.RuntimeObjectStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Query-side projection is hosted here, with bounded MQ/object IO and no execution SQL during delivery. */
@Configuration(proxyBeanMethods = false)
public class AgentServiceProjectionConfiguration {
    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource apiDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password:}") String password) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(user)
                .password(password)
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ManagedProjectionWakeups projectionWakeups(
            RedisManagedInfrastructure redis, SessionEventNotifier notifier) {
        return new ManagedProjectionWakeups(redis, notifier);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnExpression("'${managed-agents.projection.role:both}' != 'api-only'")
    ManagedProjectionConsumer agentServiceProjectionConsumer(
            @Qualifier("dataSource") DataSource source,
            ObjectMapper mapper,
            RocketMqManagedMessageBus bus,
            SessionEventNotifier notifier,
            ManagedProjectionWakeups wakeups,
            RuntimeObjectStore objects,
            @Value("${managed-agents.projection.concurrency:4}") int concurrency,
            @Value("${managed-agents.projection.object-max-requests:4}") int objectRequests,
            @Value("${managed-agents.projection.object-max-inflight-bytes:134217728}")
                    long objectBytes,
            MeterRegistry metrics,
            ObjectProvider<ManagedQueryViews> queryViews) {
        var projectionObjects =
                new BoundedRuntimeObjectStore(
                        objects,
                        objectRequests,
                        objectBytes,
                        Duration.ofSeconds(5),
                        metrics,
                        "projection",
                        false);
        var projections =
                new ManagedSessionProjections(source, mapper, notifier, projectionObjects);
        projections.queryViews(queryViews.getObject());
        projections.visible(wakeups::visible);
        return new ManagedProjectionConsumer(projections, bus, mapper, concurrency, metrics);
    }
}
