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
package io.agentscope.quarkus.deployment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Simple unit test for AgentScopeProcessor.
 * Full integration tests are covered by the runtime module tests.
 */
class AgentScopeProcessorSimpleTest {

    @Test
    void testProcessorClassExists() {
        // Verify the processor class can be loaded
        Assertions.assertNotNull(AgentScopeProcessor.class);
    }

    @Test
    void testProcessorHasBuildStepMethods() throws NoSuchMethodException {
        // Verify key build step methods exist
        Assertions.assertNotNull(AgentScopeProcessor.class.getDeclaredMethod("feature"));
        Assertions.assertNotNull(
                AgentScopeProcessor.class.getDeclaredMethod(
                        "registerForReflection",
                        io.quarkus.deployment.annotations.BuildProducer.class));
    }

    @Test
    void testProcessorCanBeInstantiated() {
        // Verify processor can be instantiated
        AgentScopeProcessor processor = new AgentScopeProcessor();
        Assertions.assertNotNull(processor);
    }
}
