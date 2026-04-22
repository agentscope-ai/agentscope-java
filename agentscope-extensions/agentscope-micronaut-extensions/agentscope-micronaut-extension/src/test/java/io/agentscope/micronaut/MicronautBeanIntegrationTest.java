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
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.tool.Toolkit;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

/**
 * Test to verify that AgentScope core classes can be managed as Micronaut beans.
 */
@MicronautTest
class MicronautBeanIntegrationTest {

    @Inject Provider<Memory> memoryProvider;

    @Inject Provider<Toolkit> toolkitProvider;

    @Inject SimpleService service;

    @Test
    void testPrototypeBeanCreation() {
        // Prototype beans should create new instances each time
        Memory memory1 = memoryProvider.get();
        Memory memory2 = memoryProvider.get();

        assertNotNull(memory1);
        assertNotNull(memory2);
        assertNotSame(memory1, memory2, "Prototype beans should create different instances");
    }

    @Test
    void testToolkitBeanCreation() {
        Toolkit toolkit1 = toolkitProvider.get();
        Toolkit toolkit2 = toolkitProvider.get();

        assertNotNull(toolkit1);
        assertNotNull(toolkit2);
        assertNotSame(
                toolkit1, toolkit2, "Prototype Toolkit beans should create different instances");
    }

    @Test
    void testCoreClassesCanBeInjectedIntoServices() {
        assertNotNull(service);
        service.verifyBeans();
    }

    @Factory
    static class TestBeanFactory {

        @Prototype
        Memory memory() {
            return new InMemoryMemory();
        }

        @Prototype
        Toolkit toolkit() {
            return new Toolkit();
        }
    }

    @Singleton
    static class SimpleService {

        private final Provider<Memory> memoryProvider;
        private final Provider<Toolkit> toolkitProvider;

        SimpleService(Provider<Memory> memoryProvider, Provider<Toolkit> toolkitProvider) {
            this.memoryProvider = memoryProvider;
            this.toolkitProvider = toolkitProvider;
        }

        void verifyBeans() {
            assertNotNull(memoryProvider.get(), "Memory should be injectable");
            assertNotNull(toolkitProvider.get(), "Toolkit should be injectable");
        }
    }
}
