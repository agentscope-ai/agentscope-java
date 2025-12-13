/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.examples.micronaut;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Simple test to verify Micronaut context and bean injection.
 */
@MicronautTest
@DisplayName("Micronaut Integration Test")
class ApplicationTest {

    @Inject Model model;

    @Inject ReActAgent agent;

    @Test
    @DisplayName("Should inject Model bean")
    void shouldInjectModelBean() {
        assertNotNull(model, "Model bean should be injected");
        assertNotNull(model.getModelName(), "Model name should be set");
    }

    @Test
    @DisplayName("Should inject ReActAgent bean")
    void shouldInjectAgentBean() {
        assertNotNull(agent, "ReActAgent bean should be injected");
    }
}
