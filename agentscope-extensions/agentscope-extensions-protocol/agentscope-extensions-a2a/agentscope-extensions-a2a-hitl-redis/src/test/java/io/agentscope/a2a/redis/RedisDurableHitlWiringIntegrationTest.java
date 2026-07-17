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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.spring.boot.a2a.AgentscopeA2aAutoConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class RedisDurableHitlWiringIntegrationTest {

    private static RedisServerSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisServerSupport.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
            assertThat(redis.processAlive()).isFalse();
        }
    }

    @Test
    void independentDurableAgentStateStoreAndRedisControlPlaneStartTogether() {
        AgentStateStore stateStore = new IndependentDurableStore();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getStateStore()).thenReturn(stateStore);
        when(agent.getName()).thenReturn("durable-agent");
        when(agent.getDescription()).thenReturn("durable test agent");

        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                RedisHitlAutoConfiguration.class,
                                AgentscopeA2aAutoConfiguration.class))
                .withBean(RedissonClient.class, redis::client)
                .withBean("firstAgentStateStore", AgentStateStore.class, () -> stateStore)
                .withBean(
                        "unrelatedAgentStateStore",
                        AgentStateStore.class,
                        IndependentDurableStore::new)
                .withBean(ReActAgent.class, () -> agent)
                .withPropertyValues(
                        "agentscope.a2a.server.enabled=true",
                        "agentscope.a2a.server.hitl.enabled=true",
                        "agentscope.a2a.server.hitl.durability=durable",
                        "agentscope.a2a.server.hitl.coordination-provider=redis",
                        "agentscope.a2a.server.hitl.redis.namespace=a2a:test:wiring:",
                        "server.port=18082")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(AgentScopeA2aServer.class);
                            AgentRunner runner = context.getBean(AgentRunner.class);
                            assertThat(runner.hitlDurabilityCapability())
                                    .isEqualTo(HitlDurabilityCapability.DURABLE);
                            assertThat(runner.actualAgentStateStore()).containsSame(stateStore);
                        });
        assertThat(redis.client().isShutdown()).isFalse();
    }

    private static final class IndependentDurableStore implements AgentStateStore {
        private final Map<String, State> states = new ConcurrentHashMap<>();

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            states.put(storageKey(userId, sessionId, key), value);
        }

        @Override
        public void save(
                String userId, String sessionId, String key, List<? extends State> values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            State value = states.get(storageKey(userId, sessionId, key));
            return value == null ? Optional.empty() : Optional.of(type.cast(value));
        }

        @Override
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> type) {
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return states.keySet().stream()
                    .anyMatch(key -> key.startsWith(userId + '/' + sessionId + ':'));
        }

        @Override
        public void delete(String userId, String sessionId) {
            states.keySet().removeIf(key -> key.startsWith(userId + '/' + sessionId + ':'));
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return Set.of();
        }

        private String storageKey(String userId, String sessionId, String key) {
            return userId + '/' + sessionId + ':' + key;
        }
    }
}
