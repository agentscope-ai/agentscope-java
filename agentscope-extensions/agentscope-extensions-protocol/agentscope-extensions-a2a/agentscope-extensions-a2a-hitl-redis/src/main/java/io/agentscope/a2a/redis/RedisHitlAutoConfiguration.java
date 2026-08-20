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
package io.agentscope.a2a.redis;

import io.agentscope.core.a2a.server.hitl.A2aHitlDurabilityBinding;
import io.agentscope.spring.boot.a2a.AgentscopeA2aAutoConfiguration;
import io.agentscope.spring.boot.a2a.properties.A2aCommonProperties;
import java.util.List;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Optional Redis provider for the main A2A starter's durable HITL binding SPI. */
@AutoConfiguration(before = AgentscopeA2aAutoConfiguration.class)
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(
        prefix = "agentscope.a2a.server.hitl",
        name = "enabled",
        havingValue = "true")
@ConditionalOnProperty(
        prefix = "agentscope.a2a.server.hitl",
        name = "durability",
        havingValue = "durable")
@ConditionalOnProperty(
        prefix = "agentscope.a2a.server.hitl",
        name = "coordination-provider",
        havingValue = "redis")
@EnableConfigurationProperties({RedisHitlProperties.class, A2aCommonProperties.class})
public class RedisHitlAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(A2aHitlDurabilityBinding.class)
    public RedisA2aHitlDurabilityBinding redisA2aHitlDurabilityBinding(
            ObjectProvider<RedissonClient> redissonProvider,
            RedisHitlProperties redisProperties,
            A2aCommonProperties commonProperties) {
        RedissonClient redissonClient = requireOneRedissonClient(redissonProvider);
        redisProperties.setTaskTtl(commonProperties.getHitl().getTaskTtl());
        return new RedisA2aHitlDurabilityBinding(redissonClient, redisProperties);
    }

    private static RedissonClient requireOneRedissonClient(
            ObjectProvider<RedissonClient> provider) {
        List<RedissonClient> clients = provider.orderedStream().toList();
        if (clients.size() != 1) {
            throw new IllegalStateException(
                    "Redis durable HITL requires exactly one RedissonClient, but found "
                            + clients.size()
                            + '.');
        }
        return clients.get(0);
    }
}
