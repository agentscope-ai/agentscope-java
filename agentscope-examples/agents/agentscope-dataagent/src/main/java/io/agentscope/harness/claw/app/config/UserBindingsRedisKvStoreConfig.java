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
package io.agentscope.harness.claw.app.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.claw.app.binding.UserBindingStore.UserBinding;
import io.agentscope.harness.claw.session.spi.KvStore;
import io.agentscope.harness.claw.session.spi.RedisKvStore;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

/**
 * Overrides the default file-backed {@code userBindingsKvStore} bean with a Redis-backed one when
 * {@code claw.session.redis.enabled=true} and a {@link UnifiedJedis} client is available (provided
 * by {@link SessionBackendConfig}).
 */
@Configuration
@ConditionalOnProperty(name = "claw.session.redis.enabled", havingValue = "true")
@ConditionalOnBean(UnifiedJedis.class)
public class UserBindingsRedisKvStoreConfig {

    @Value("${claw.session.redis.bindings-key-prefix:claw:bindings:}")
    private String keyPrefix;

    @Bean
    public KvStore<List<UserBinding>> userBindingsKvStore(UnifiedJedis jedis) {
        TypeReference<List<UserBinding>> typeRef = new TypeReference<>() {};
        return new RedisKvStore<>(jedis, new ObjectMapper(), typeRef, keyPrefix);
    }
}
