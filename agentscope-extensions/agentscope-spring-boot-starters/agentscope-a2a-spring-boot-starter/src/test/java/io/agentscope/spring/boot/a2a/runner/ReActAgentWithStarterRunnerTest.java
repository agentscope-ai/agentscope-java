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
package io.agentscope.spring.boot.a2a.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ReActAgentWithStarterRunnerTest {

    @Test
    void exposesApplicationOwnedPersistentStoreAsDurable() {
        AgentStateStore stateStore = mock(AgentStateStore.class);
        AgentRunner runner = runnerWith(stateStore);

        assertThat(runner.actualAgentStateStore()).containsSame(stateStore);
        assertThat(runner.hitlDurabilityCapability()).isEqualTo(HitlDurabilityCapability.DURABLE);
    }

    @Test
    void declaresInMemoryStoreAsLocal() {
        AgentRunner runner = runnerWith(new InMemoryAgentStateStore());

        assertThat(runner.hitlDurabilityCapability()).isEqualTo(HitlDurabilityCapability.LOCAL);
    }

    private AgentRunner runnerWith(AgentStateStore stateStore) {
        ReActAgent agent = ReActAgent.builder().name("runner-test").stateStore(stateStore).build();
        @SuppressWarnings("unchecked")
        ObjectProvider<ReActAgent> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(agent);
        return ReActAgentWithStarterRunner.newInstance(provider);
    }
}
