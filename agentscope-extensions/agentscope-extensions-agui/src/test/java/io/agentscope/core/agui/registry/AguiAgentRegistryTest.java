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
package io.agentscope.core.agui.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AguiAgentRegistry}.
 */
class AguiAgentRegistryTest {

    private AguiAgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AguiAgentRegistry();
    }

    @Nested
    @DisplayName("Singleton Agent Tests")
    class SingletonAgentTests {

        @Test
        @DisplayName("Should register and retrieve singleton agent")
        void testRegisterAndGetSingletonAgent() {
            Agent mockAgent = mock(Agent.class);
            registry.register("test-agent", mockAgent);

            Optional<Agent> result = registry.getAgent("test-agent");

            assertTrue(result.isPresent());
            assertSame(mockAgent, result.get());
        }

        @Test
        @DisplayName("Should return same instance for singleton agent")
        void testSingletonAgentReturnsSameInstance() {
            Agent mockAgent = mock(Agent.class);
            registry.register("test-agent", mockAgent);

            Agent agent1 = registry.getAgent("test-agent").orElse(null);
            Agent agent2 = registry.getAgent("test-agent").orElse(null);

            assertSame(agent1, agent2);
        }

        @Test
        @DisplayName("Should allow overwriting singleton agent")
        void testOverwriteSingletonAgent() {
            Agent agent1 = mock(Agent.class);
            Agent agent2 = mock(Agent.class);

            registry.register("agent", agent1);
            registry.register("agent", agent2);

            Optional<Agent> result = registry.getAgent("agent");
            assertTrue(result.isPresent());
            assertSame(agent2, result.get());
        }
    }

    @Nested
    @DisplayName("Factory Agent Tests")
    class FactoryAgentTests {

        @Test
        @DisplayName("Should register and retrieve factory agent")
        void testRegisterAndGetFactoryAgent() {
            registry.registerFactory("factory-agent", () -> mock(Agent.class));

            Optional<Agent> result = registry.getAgent("factory-agent");

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Should return new instance for each factory agent call")
        void testFactoryAgentReturnsNewInstances() {
            registry.registerFactory("factory-agent", () -> mock(Agent.class));

            Agent agent1 = registry.getAgent("factory-agent").orElse(null);
            Agent agent2 = registry.getAgent("factory-agent").orElse(null);

            assertNotSame(agent1, agent2);
        }

        @Test
        @DisplayName("Factory should take priority over singleton with same ID")
        void testFactoryTakesPriorityOverSingleton() {
            Agent singletonAgent = mock(Agent.class);
            registry.register("agent", singletonAgent);
            registry.registerFactory("agent", () -> mock(Agent.class));

            Agent result = registry.getAgent("agent").orElse(null);

            assertNotSame(singletonAgent, result);
        }

        @Test
        @DisplayName("Should track factory invocation count")
        void testFactoryInvocationCount() {
            AtomicInteger counter = new AtomicInteger(0);
            registry.registerFactory(
                    "counter-agent",
                    () -> {
                        counter.incrementAndGet();
                        return mock(Agent.class);
                    });

            registry.getAgent("counter-agent");
            registry.getAgent("counter-agent");
            registry.getAgent("counter-agent");

            assertEquals(3, counter.get());
        }
    }

    @Nested
    @DisplayName("Query and Lookup Tests")
    class QueryTests {

        @Test
        @DisplayName("Should return empty for non-existent agent")
        void testGetNonExistentAgentReturnsEmpty() {
            Optional<Agent> result = registry.getAgent("non-existent");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should correctly check if agent exists")
        void testHasAgent() {
            Agent mockAgent = mock(Agent.class);
            registry.register("singleton-agent", mockAgent);
            registry.registerFactory("factory-agent", () -> mock(Agent.class));

            assertTrue(registry.hasAgent("singleton-agent"));
            assertTrue(registry.hasAgent("factory-agent"));
            assertFalse(registry.hasAgent("non-existent"));
        }

        @Test
        @DisplayName("Should return correct size")
        void testSize() {
            assertEquals(0, registry.size());

            registry.register("agent1", mock(Agent.class));
            assertEquals(1, registry.size());

            registry.registerFactory("agent2", () -> mock(Agent.class));
            assertEquals(2, registry.size());

            // Same ID for both singleton and factory counts as 2
            registry.register("agent3", mock(Agent.class));
            registry.registerFactory("agent3", () -> mock(Agent.class));
            assertEquals(4, registry.size());
        }
    }

    @Nested
    @DisplayName("Modification Tests")
    class ModificationTests {

        @Test
        @DisplayName("Should unregister singleton agent")
        void testUnregisterSingletonAgent() {
            registry.register("test-agent", mock(Agent.class));

            assertTrue(registry.unregister("test-agent"));
            assertFalse(registry.hasAgent("test-agent"));
        }

        @Test
        @DisplayName("Should unregister factory agent")
        void testUnregisterFactoryAgent() {
            registry.registerFactory("test-agent", () -> mock(Agent.class));

            assertTrue(registry.unregister("test-agent"));
            assertFalse(registry.hasAgent("test-agent"));
        }

        @Test
        @DisplayName("Should unregister both singleton and factory with same ID")
        void testUnregisterBoth() {
            registry.register("agent", mock(Agent.class));
            registry.registerFactory("agent", () -> mock(Agent.class));

            assertTrue(registry.unregister("agent"));
            assertFalse(registry.hasAgent("agent"));
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("Should return false when unregistering non-existent agent")
        void testUnregisterNonExistent() {
            assertFalse(registry.unregister("non-existent"));
        }

        @Test
        @DisplayName("Should clear all registered agents")
        void testClear() {
            registry.register("agent1", mock(Agent.class));
            registry.registerFactory("agent2", () -> mock(Agent.class));

            registry.clear();

            assertEquals(0, registry.size());
            assertFalse(registry.hasAgent("agent1"));
            assertFalse(registry.hasAgent("agent2"));
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should throw when registering null agent ID")
        void testRegisterNullAgentIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.register(null, mock(Agent.class)));
        }

        @Test
        @DisplayName("Should throw when registering empty agent ID")
        void testRegisterEmptyAgentIdThrows() {
            assertThrows(
                    IllegalArgumentException.class, () -> registry.register("", mock(Agent.class)));
        }

        @Test
        @DisplayName("Should throw when registering null agent")
        void testRegisterNullAgentThrows() {
            assertThrows(IllegalArgumentException.class, () -> registry.register("agent", null));
        }

        @Test
        @DisplayName("Should throw when registering null factory agent ID")
        void testRegisterFactoryNullAgentIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.registerFactory(null, () -> mock(Agent.class)));
        }

        @Test
        @DisplayName("Should throw when registering empty factory agent ID")
        void testRegisterFactoryEmptyAgentIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.registerFactory("", () -> mock(Agent.class)));
        }

        @Test
        @DisplayName("Should throw when registering null factory")
        void testRegisterNullFactoryThrows() {
            assertThrows(
                    IllegalArgumentException.class, () -> registry.registerFactory("agent", null));
        }
    }

    @Nested
    @DisplayName("Context-Aware Factory Tests")
    class ContextAwareFactoryTests {

        @Test
        @DisplayName("Should register and retrieve agent with context-aware factory")
        void testRegisterAndGetContextAwareFactory() {
            RunAgentInput input =
                    RunAgentInput.builder().threadId("test-thread").runId("test-run").build();

            registry.registerFactoryWithInput("context-agent", (inp) -> mock(Agent.class));

            Optional<Agent> result = registry.getAgent("context-agent", input);

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Should pass input to context-aware factory")
        void testContextAwareFactoryReceivesInput() {
            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("test-thread-123")
                            .runId("test-run-456")
                            .build();

            final String[] capturedThreadId = new String[1];
            final String[] capturedRunId = new String[1];

            registry.registerFactoryWithInput(
                    "context-agent",
                    (inp) -> {
                        capturedThreadId[0] = inp.getThreadId();
                        capturedRunId[0] = inp.getRunId();
                        return mock(Agent.class);
                    });

            registry.getAgent("context-agent", input);

            assertEquals("test-thread-123", capturedThreadId[0]);
            assertEquals("test-run-456", capturedRunId[0]);
        }

        @Test
        @DisplayName("Should create new instance for each call with context-aware factory")
        void testContextAwareFactoryReturnsNewInstances() {
            RunAgentInput input =
                    RunAgentInput.builder().threadId("test-thread").runId("test-run").build();

            registry.registerFactoryWithInput("context-agent", (inp) -> mock(Agent.class));

            Agent agent1 = registry.getAgent("context-agent", input).orElse(null);
            Agent agent2 = registry.getAgent("context-agent", input).orElse(null);

            assertNotSame(agent1, agent2);
        }

        @Test
        @DisplayName("Should handle forwarded properties in context-aware factory")
        void testContextAwareFactoryWithForwardedProps() {
            Map<String, Object> forwardedProps = new HashMap<>();
            forwardedProps.put("user", "test-user");
            forwardedProps.put("apiKey", "test-key");

            RunAgentInput input =
                    RunAgentInput.builder()
                            .threadId("test-thread")
                            .runId("test-run")
                            .forwardedProps(forwardedProps)
                            .build();

            final String[] capturedUser = new String[1];
            final String[] capturedApiKey = new String[1];

            registry.registerFactoryWithInput(
                    "context-agent",
                    (inp) -> {
                        capturedUser[0] = (String) inp.getForwardedProp("user");
                        capturedApiKey[0] = (String) inp.getForwardedProp("apiKey");
                        return mock(Agent.class);
                    });

            registry.getAgent("context-agent", input);

            assertEquals("test-user", capturedUser[0]);
            assertEquals("test-key", capturedApiKey[0]);
        }

        @Test
        @DisplayName("Should handle null input gracefully")
        void testContextAwareFactoryWithNullInput() {
            registry.registerFactoryWithInput("context-agent", (inp) -> mock(Agent.class));

            Optional<Agent> result = registry.getAgent("context-agent", null);

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Context-aware factory should take priority over regular factory")
        void testContextAwareFactoryTakesPriority() {
            RunAgentInput input =
                    RunAgentInput.builder().threadId("test-thread").runId("test-run").build();

            Agent regularAgent = mock(Agent.class);
            registry.registerFactory("agent", () -> regularAgent);
            registry.registerFactoryWithInput("agent", (inp) -> mock(Agent.class));

            Agent result = registry.getAgent("agent", input).orElse(null);

            assertNotSame(regularAgent, result);
        }

        @Test
        @DisplayName("Should fall back to regular factory when no context factory exists")
        void testFallbackToRegularFactory() {
            RunAgentInput input =
                    RunAgentInput.builder().threadId("test-thread").runId("test-run").build();

            Agent expectedAgent = mock(Agent.class);
            registry.registerFactory("agent", () -> expectedAgent);

            Agent result = registry.getAgent("agent", input).orElse(null);

            assertSame(expectedAgent, result);
        }

        @Test
        @DisplayName("Should track context-aware factory invocation count")
        void testContextAwareFactoryInvocationCount() {
            AtomicInteger counter = new AtomicInteger(0);
            RunAgentInput input =
                    RunAgentInput.builder().threadId("test-thread").runId("test-run").build();

            registry.registerFactoryWithInput(
                    "counter-agent",
                    (inp) -> {
                        counter.incrementAndGet();
                        return mock(Agent.class);
                    });

            registry.getAgent("counter-agent", input);
            registry.getAgent("counter-agent", input);
            registry.getAgent("counter-agent", input);

            assertEquals(3, counter.get());
        }

        @Test
        @DisplayName("Should unregister context-aware factory")
        void testUnregisterContextAwareFactory() {
            registry.registerFactoryWithInput("context-agent", (inp) -> mock(Agent.class));

            assertTrue(registry.unregister("context-agent"));
            assertFalse(registry.hasAgent("context-agent"));
        }

        @Test
        @DisplayName("Should include context-aware factories in size count")
        void testSizeIncludesContextAwareFactories() {
            assertEquals(0, registry.size());

            registry.register("agent1", mock(Agent.class));
            assertEquals(1, registry.size());

            registry.registerFactory("agent2", () -> mock(Agent.class));
            assertEquals(2, registry.size());

            registry.registerFactoryWithInput("agent3", (inp) -> mock(Agent.class));
            assertEquals(3, registry.size());
        }

        @Test
        @DisplayName("Should throw when registering null context-aware factory")
        void testRegisterNullContextAwareFactoryThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.registerFactoryWithInput("agent", null));
        }

        @Test
        @DisplayName("Should throw when registering context-aware factory with null ID")
        void testRegisterContextAwareFactoryNullIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.registerFactoryWithInput(null, (inp) -> mock(Agent.class)));
        }

        @Test
        @DisplayName("Should throw when registering context-aware factory with empty ID")
        void testRegisterContextAwareFactoryEmptyIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.registerFactoryWithInput("", (inp) -> mock(Agent.class)));
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should handle concurrent registrations")
        void testConcurrentRegistrations() throws InterruptedException {
            int threadCount = 10;
            int agentsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < agentsPerThread; i++) {
                                    String agentId = "agent-" + threadId + "-" + i;
                                    registry.register(agentId, mock(Agent.class));
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * agentsPerThread, registry.size());
        }

        @Test
        @DisplayName("Should handle concurrent reads and writes")
        void testConcurrentReadsAndWrites() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // Pre-register some agents
            for (int i = 0; i < 50; i++) {
                registry.register("agent-" + i, mock(Agent.class));
            }

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < 100; i++) {
                                    // Mix of reads and writes
                                    if (i % 2 == 0) {
                                        registry.getAgent("agent-" + (i % 50));
                                    } else {
                                        registry.register(
                                                "new-agent-" + threadId + "-" + i,
                                                mock(Agent.class));
                                    }
                                    successCount.incrementAndGet();
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount * 100, successCount.get());
        }
    }
}
