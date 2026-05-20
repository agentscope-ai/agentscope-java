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

import io.agentscope.core.session.Session;
import io.agentscope.core.session.redis.RedisSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Optional Redis-backed {@link Session} for distributed deployments. Activated when both:
 *
 * <ul>
 *   <li>{@code agentscope-extensions-session-redis} is on the classpath, AND
 *   <li>{@code claw.session.redis.enabled=true} is set in {@code application.yml}
 * </ul>
 *
 * <p>If activated, this {@link Session} bean is picked up by {@link SandboxConfig} and wired
 * into {@link io.agentscope.harness.agent.sandbox.SandboxDistributedOptions}, switching the
 * sandbox into true distributed mode.
 *
 * <h2>Configuration</h2>
 *
 * <pre>{@code
 * claw:
 *   session:
 *     redis:
 *       enabled: true
 *       host: localhost
 *       port: 6379
 *       password:               # optional
 *       database: 0
 *       key-prefix: claw:session:
 * }</pre>
 *
 * <p>This implementation deliberately uses Jedis (the simplest client). Override this bean to
 * plug in Lettuce, Redisson, cluster mode, sentinel, or a custom connection pool.
 */
@Configuration
@ConditionalOnClass(RedisSession.class)
@ConditionalOnProperty(name = "claw.session.redis.enabled", havingValue = "true")
public class SessionBackendConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionBackendConfig.class);

    @Value("${claw.session.redis.host:localhost}")
    private String host;

    @Value("${claw.session.redis.port:6379}")
    private int port;

    @Value("${claw.session.redis.password:}")
    private String password;

    @Value("${claw.session.redis.database:0}")
    private int database;

    @Value("${claw.session.redis.key-prefix:claw:session:}")
    private String keyPrefix;

    @Bean(destroyMethod = "close")
    public UnifiedJedis clawRedisClient() {
        String authPart = (password == null || password.isBlank()) ? "" : "***@";
        log.info(
                "Connecting RedisSession client to redis://{}{}:{}/{} (prefix={})",
                authPart,
                host,
                port,
                database,
                keyPrefix);
        DefaultJedisClientConfig.Builder cfg =
                DefaultJedisClientConfig.builder().database(database);
        if (password != null && !password.isBlank()) {
            cfg.password(password);
        }
        return new JedisPooled(new HostAndPort(host, port), cfg.build());
    }

    @Bean
    public Session redisSession(UnifiedJedis clawRedisClient) {
        return RedisSession.builder().jedisClient(clawRedisClient).keyPrefix(keyPrefix).build();
    }
}
