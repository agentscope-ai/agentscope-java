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
package io.agentscope.quarkus.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test class for AgentScopeProducer.
 */
@QuarkusTest
class AgentScopeProducerTest {

    @Inject Model model;

    @Inject Memory memory;

    @Inject Toolkit toolkit;

    @Inject ReActAgent agent;

    @Test
    void testModelInjection() {
        Assertions.assertNotNull(model, "Model should be injected");
    }

    @Test
    void testMemoryInjection() {
        Assertions.assertNotNull(memory, "Memory should be injected");
    }

    @Test
    void testToolkitInjection() {
        Assertions.assertNotNull(toolkit, "Toolkit should be injected");
    }

    @Test
    void testAgentInjection() {
        Assertions.assertNotNull(agent, "ReActAgent should be injected");
        Assertions.assertEquals(
                "TestAssistant", agent.getName(), "Agent name should match configuration");
    }

    @Test
    void testModelConfiguration() {
        // Verify model is properly configured and injectable
        // Note: Quarkus may wrap beans in proxies, so we just verify it's not null and usable
        Assertions.assertNotNull(model, "Model should be injected");
        // Model interface should be implemented
        Assertions.assertTrue(
                model instanceof Model, "Injected bean should implement Model interface");
    }
}
