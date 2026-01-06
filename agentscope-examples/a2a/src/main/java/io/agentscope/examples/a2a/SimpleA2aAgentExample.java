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

package io.agentscope.examples.a2a;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;

/**
 * Example for A2aAgent with well-known AgentCard URI.
 *
 * <p>This example demonstrates how to request remote agent via the A2A protocol
 * using AgentScope A2aAgent which discovers AgentCard by well-known URI.
 */
public class SimpleA2aAgentExample {

    public static void main(String[] args) {
        // Create agent card resolver by well-known uri.
        AgentCardResolver agentCardResolver =
                WellKnownAgentCardResolver.builder().baseUrl("http://localhost:8888").build();
        // Create A2aAgent
        A2aAgent agent =
                A2aAgent.builder()
                        .name("agentscope-a2a-example-agent")
                        .agentCardResolver(agentCardResolver)
                        .build();
        A2aAgentExampleRunner exampleRunner = new A2aAgentExampleRunner(agent);
        exampleRunner.startExample();
    }
}
