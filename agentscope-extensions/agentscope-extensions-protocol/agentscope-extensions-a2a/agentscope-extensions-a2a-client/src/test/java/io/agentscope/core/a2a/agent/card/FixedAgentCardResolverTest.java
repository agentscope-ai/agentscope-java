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
package io.agentscope.core.a2a.agent.card;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for FixedAgentCardResolver.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Builder pattern functionality</li>
 *   <li>Agent card resolution</li>
 *   <li>Builder method chaining</li>
 * </ul>
 */
@DisplayName("FixedAgentCardResolver Tests")
class FixedAgentCardResolverTest {

    @Test
    @DisplayName("Should create builder instance")
    void testBuilderCreation() {
        FixedAgentCardResolver.FixedAgentCardProducerBuilder builder =
                FixedAgentCardResolver.builder();
        assertNotNull(builder);
    }

    @Test
    @DisplayName("Should set agent card")
    void testSetAgentCard() {
        FixedAgentCardResolver.FixedAgentCardProducerBuilder builder =
                FixedAgentCardResolver.builder();
        AgentCard agentCard = mock(AgentCard.class);

        FixedAgentCardResolver.FixedAgentCardProducerBuilder result = builder.agentCard(agentCard);

        assertSame(builder, result); // Check method chaining
    }

    @Test
    @DisplayName("Should build resolver with fixed agent card")
    void testBuildResolver() {
        AgentCard agentCard = mock(AgentCard.class);
        FixedAgentCardResolver resolver =
                FixedAgentCardResolver.builder().agentCard(agentCard).build();

        assertNotNull(resolver);
    }

    @Test
    @DisplayName("Should resolve same agent card regardless of agent name")
    void testResolveAgentCard() {
        AgentCard agentCard = mock(AgentCard.class);
        FixedAgentCardResolver resolver =
                FixedAgentCardResolver.builder().agentCard(agentCard).build();

        AgentCard resolvedCard1 = resolver.getAgentCard("agent-1");
        AgentCard resolvedCard2 = resolver.getAgentCard("agent-2");
        AgentCard resolvedCard3 = resolver.getAgentCard("agent-3");

        assertSame(agentCard, resolvedCard1);
        assertSame(agentCard, resolvedCard2);
        assertSame(agentCard, resolvedCard3);
    }

    @Test
    @DisplayName("Should support builder method chaining")
    void testBuilderMethodChaining() {
        FixedAgentCardResolver.FixedAgentCardProducerBuilder builder =
                FixedAgentCardResolver.builder();
        AgentCard agentCard = mock(AgentCard.class);

        FixedAgentCardResolver.FixedAgentCardProducerBuilder result = builder.agentCard(agentCard);
        FixedAgentCardResolver resolver = builder.build();

        assertNotNull(result);
        assertSame(builder, result);
        assertNotNull(resolver);
    }

    @Test
    @DisplayName("Should build multiple independent resolver instances")
    void testMultipleResolverInstances() {
        AgentCard agentCard1 = mock(AgentCard.class);
        AgentCard agentCard2 = mock(AgentCard.class);

        FixedAgentCardResolver resolver1 =
                FixedAgentCardResolver.builder().agentCard(agentCard1).build();

        FixedAgentCardResolver resolver2 =
                FixedAgentCardResolver.builder().agentCard(agentCard2).build();

        assertNotNull(resolver1);
        assertNotNull(resolver2);

        AgentCard resolvedCard1 = resolver1.getAgentCard("any-agent-name");
        AgentCard resolvedCard2 = resolver2.getAgentCard("any-agent-name");

        assertSame(agentCard1, resolvedCard1);
        assertSame(agentCard2, resolvedCard2);
        assertNotSame(resolvedCard1, resolvedCard2);
    }
}
