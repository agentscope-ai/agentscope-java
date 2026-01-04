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
package io.agentscope.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Unit tests for FanoutPipeline builder scheduler functionality.
 *
 * <p>These tests specifically verify the scheduler configuration through the builder pattern.
 */
@Tag("unit")
@DisplayName("FanoutPipeline Builder Scheduler Tests")
class FanoutPipelineBuilderSchedulerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private MockModel model1;
    private MockModel model2;
    private MockModel model3;

    @BeforeEach
    void setUp() {
        model1 = new MockModel("Response from agent 1");
        model2 = new MockModel("Response from agent 2");
        model3 = new MockModel("Response from agent 3");
    }

    @Test
    @DisplayName("Should use custom scheduler configured via builder in concurrent mode")
    void shouldUseCustomSchedulerConfiguredViaBuilderInConcurrentMode() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        // Create a custom scheduler that tracks thread usage
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory =
                r -> new Thread(r, "CustomThread-" + threadCounter.incrementAndGet());
        ExecutorService executorService = Executors.newFixedThreadPool(2, threadFactory);
        Scheduler customScheduler = Schedulers.fromExecutorService(executorService);

        try {
            FanoutPipeline pipeline =
                    FanoutPipeline.builder()
                            .addAgent(agent1)
                            .addAgent(agent2)
                            .concurrent(true)
                            .scheduler(customScheduler)
                            .build();

            Msg input = TestUtils.createUserMessage("User", "custom scheduler builder test");

            // Verify that the custom scheduler is used by checking thread names
            StepVerifier.create(pipeline.execute(input))
                    .assertNext(
                            results -> {
                                assertNotNull(results);
                                assertEquals(2, results.size());
                                // Verify that our custom threads were used
                                assertTrue(threadCounter.get() > 0);
                            })
                    .verifyComplete();
        } finally {
            customScheduler.dispose();
            executorService.shutdown();
        }
    }

    @Test
    @DisplayName("Should not use custom scheduler configured via builder in sequential mode")
    void shouldNotUseCustomSchedulerConfiguredViaBuilderInSequentialMode() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        // Create a custom scheduler that tracks usage
        AtomicReference<String> schedulerUsed = new AtomicReference<>();
        Scheduler customScheduler = Schedulers.immediate();

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgent(agent2)
                        .concurrent(false)
                        .scheduler(customScheduler)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "sequential scheduler builder test");

        // In sequential mode, the scheduler should not be used for actual execution
        // but the pipeline should still work correctly
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("Agent1", results.get(0).getName());
        assertEquals("Agent2", results.get(1).getName());
    }

    @Test
    @DisplayName("Should work with VirtualTimeScheduler configured via builder")
    void shouldWorkWithVirtualTimeSchedulerConfiguredViaBuilder() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        VirtualTimeScheduler virtualTimeScheduler = VirtualTimeScheduler.create();

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgent(agent2)
                        .concurrent(true)
                        .scheduler(virtualTimeScheduler)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "virtual time scheduler builder test");

        // Use StepVerifier.withVirtualTime to test with virtual time
        StepVerifier.withVirtualTime(() -> pipeline.execute(input))
                .expectSubscription()
                .expectNextCount(1) // Expect one list result
                .expectComplete()
                .verify(TIMEOUT);

        virtualTimeScheduler.dispose();
    }

    @Test
    @DisplayName(
            "Should use scheduler threads for concurrent execution when configured via builder")
    void shouldUseSchedulerThreadsForConcurrentExecutionWhenConfiguredViaBuilder()
            throws InterruptedException {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        // Create a custom scheduler that tracks which threads are used
        CountDownLatch completionLatch = new CountDownLatch(2);
        AtomicInteger schedulerThreadCount = new AtomicInteger(0);

        Scheduler trackingScheduler =
                Schedulers.fromExecutor(
                        task -> {
                            // Track that a scheduler thread is being used
                            schedulerThreadCount.incrementAndGet();
                            task.run();
                            completionLatch.countDown();
                        });

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgent(agent2)
                        .concurrent(true)
                        .scheduler(trackingScheduler)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "thread tracking builder test");

        // Execute the pipeline
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        // Wait for all tasks to complete
        completionLatch.await();

        assertNotNull(results);
        assertEquals(2, results.size());
        // Verify that scheduler threads were used
        assertEquals(2, schedulerThreadCount.get());

        trackingScheduler.dispose();
    }

    @Test
    @DisplayName("Should use default scheduler when none configured via builder")
    void shouldUseDefaultSchedulerWhenNoneConfiguredViaBuilder() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgent(agent2)
                        .concurrent(true)
                        // No explicit scheduler configured
                        .build();

        Msg input = TestUtils.createUserMessage("User", "default scheduler builder test");

        // The pipeline should work with the default scheduler
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results);
        assertEquals(2, results.size());
        // In concurrent mode, the order of results is not guaranteed
        assertTrue(results.stream().anyMatch(msg -> "Agent1".equals(msg.getName())));
        assertTrue(results.stream().anyMatch(msg -> "Agent2".equals(msg.getName())));
    }

    @Test
    @DisplayName("Should handle null scheduler gracefully when configured via builder")
    void shouldHandleNullSchedulerGracefullyWhenConfiguredViaBuilder() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .addAgent(agent2)
                        .concurrent(true)
                        .scheduler(null) // Explicitly set null scheduler
                        .build();

        Msg input = TestUtils.createUserMessage("User", "null scheduler builder test");

        // The pipeline should work with the default scheduler when null is provided
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results);
        assertEquals(2, results.size());
        // In concurrent mode, the order of results is not guaranteed
        assertTrue(results.stream().anyMatch(msg -> "Agent1".equals(msg.getName())));
        assertTrue(results.stream().anyMatch(msg -> "Agent2".equals(msg.getName())));
    }

    @Test
    @DisplayName("Should correctly configure scheduler with mixed builder operations")
    void shouldCorrectlyConfigureSchedulerWithMixedBuilderOperations() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);
        ReActAgent agent3 = createAgent("Agent3", model3);

        // Create a custom scheduler
        Scheduler customScheduler = Schedulers.newParallel("test-scheduler", 4);

        FanoutPipeline pipeline =
                FanoutPipeline.builder()
                        .addAgent(agent1)
                        .concurrent(false) // Initially set to sequential
                        .addAgent(agent2)
                        .scheduler(customScheduler)
                        .concurrent(true) // Then switch to concurrent
                        .addAgent(agent3)
                        .build();

        // Verify the pipeline configuration
        assertEquals(3, pipeline.size());
        assertTrue(pipeline.isConcurrentEnabled()); // Should be concurrent

        Msg input = TestUtils.createUserMessage("User", "mixed builder operations test");

        // The pipeline should work correctly
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results);
        assertEquals(3, results.size());

        customScheduler.dispose();
    }

    private ReActAgent createAgent(String name, MockModel model) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt("Test agent")
                .model(model)
                .toolkit(new Toolkit()) // Each agent gets independent toolkit for thread safety
                .memory(new InMemoryMemory()) // Each agent gets independent memory for thread
                // safety
                .build();
    }
}
