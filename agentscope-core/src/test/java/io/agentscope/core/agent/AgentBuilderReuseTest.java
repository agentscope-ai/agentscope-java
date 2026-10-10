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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

class AgentBuilderReuseTest {
    @Test
    void repeatedBuildDoesNotAccumulateGeneratedMiddleware() {
        Model model = mock(Model.class);
        var store = new InMemoryAgentStateStore();
        var builder =
                ReActAgent.builder()
                        .name("shared-builder")
                        .model(model)
                        .stateStore(store)
                        .maxIters(17)
                        .skillRepository(mock(AgentSkillRepository.class));
        try (var first = builder.build();
                var second = builder.build()) {
            assertEquals(
                    1,
                    first.getMiddlewares().stream()
                            .filter(DynamicSkillMiddleware.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    second.getMiddlewares().stream()
                            .filter(DynamicSkillMiddleware.class::isInstance)
                            .count());
            assertNotSame(first.getToolkit(), second.getToolkit());
            assertSame(model, second.getModel());
            assertSame(store, second.getStateStore());
            assertEquals(17, second.getMaxIters());
        }
    }

    @Test
    void configurationCopyDoesNotChangeSourceRegistrations() {
        var builder =
                ReActAgent.builder()
                        .name("original")
                        .agentId("original-id")
                        .model(mock(Model.class));
        var copy = builder.copy().name("copy").skillRepository(mock(AgentSkillRepository.class));
        try (var original = builder.build();
                var changed = copy.build()) {
            assertEquals("original", original.getName());
            assertEquals("copy", changed.getName());
            assertEquals("original-id", original.getAgentId());
            assertEquals("original-id", changed.getAgentId());
            assertEquals(
                    0,
                    original.getMiddlewares().stream()
                            .filter(DynamicSkillMiddleware.class::isInstance)
                            .count());
            assertEquals(
                    1,
                    changed.getMiddlewares().stream()
                            .filter(DynamicSkillMiddleware.class::isInstance)
                            .count());
        }
    }
}
