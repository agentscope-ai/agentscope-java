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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DynamicSkillMiddlewareRuntimeContextTest {

    private static final class CapturingDynamicSkillMiddleware extends DynamicSkillMiddleware {
        private final AtomicReference<RuntimeContext> seen = new AtomicReference<>();

        private CapturingDynamicSkillMiddleware(List<AgentSkillRepository> repositories) {
            super(repositories, null);
        }

        @Override
        protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
            seen.set(ctx);
            return raw;
        }
    }

    @Test
    void onSystemPromptFallsBackToEmptyContextWhenAgentIsNull() {
        CapturingDynamicSkillMiddleware middleware =
                new CapturingDynamicSkillMiddleware(List.of(skillRepo()));

        String prompt = middleware.onSystemPrompt(null, RuntimeContext.empty(), "BASE").block();

        assertNotNull(prompt);
        assertTrue(prompt.startsWith("BASE"));
        assertNotNull(middleware.seen.get());
    }

    @Test
    void onSystemPromptUsesSuppliedRuntimeContext() {
        RuntimeContext runtimeContext =
                RuntimeContext.builder().sessionId("dynamic-skill-session").build();
        Agent agent = mock(Agent.class);
        CapturingDynamicSkillMiddleware middleware =
                new CapturingDynamicSkillMiddleware(List.of(skillRepo()));

        String prompt = middleware.onSystemPrompt(agent, runtimeContext, "BASE").block();

        assertNotNull(prompt);
        assertSame(runtimeContext, middleware.seen.get());
    }

    private static AgentSkillRepository skillRepo() {
        AgentSkillRepository repo = mock(AgentSkillRepository.class);
        when(repo.getAllSkills())
                .thenReturn(
                        List.of(
                                new AgentSkill(
                                        "sample",
                                        "Sample skill",
                                        "You can use the sample skill.",
                                        Map.of())));
        return repo;
    }
}
