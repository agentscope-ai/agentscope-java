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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Unit tests for FanoutPipeline scheduler functionality.
 *
 * <p>These tests verify custom scheduler behavior in both concurrent and sequential execution modes.
 */
@Tag("unit")
@DisplayName("FanoutPipeline Scheduler Tests")
class FanoutPipelineSchedulerTest {

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
    @DisplayName("Should use custom scheduler in concurrent mode")
    void shouldUseCustomSchedulerInConcurrentMode() {
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
                    new FanoutPipeline(List.of(agent1, agent2), true, customScheduler);

            Msg input = TestUtils.createUserMessage("User", "custom scheduler test");

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
    @DisplayName("Should not use custom scheduler in sequential mode")
    void shouldNotUseCustomSchedulerInSequentialMode() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        // Create a custom scheduler that tracks usage
        AtomicReference<String> schedulerUsed = new AtomicReference<>();
        Scheduler customScheduler = Schedulers.immediate();

        FanoutPipeline pipeline =
                new FanoutPipeline(List.of(agent1, agent2), false, customScheduler);

        Msg input = TestUtils.createUserMessage("User", "sequential scheduler test");

        // In sequential mode, the scheduler should not be used for actual execution
        // but the pipeline should still work correctly
        List<Msg> results = pipeline.execute(input).block(TIMEOUT);

        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("Agent1", results.get(0).getName());
        assertEquals("Agent2", results.get(1).getName());
    }

    @Test
    @DisplayName("Should work with VirtualTimeScheduler for testing")
    void shouldWorkWithVirtualTimeScheduler() {
        ReActAgent agent1 = createAgent("Agent1", model1);
        ReActAgent agent2 = createAgent("Agent2", model2);

        VirtualTimeScheduler virtualTimeScheduler = VirtualTimeScheduler.create();

        FanoutPipeline pipeline =
                new FanoutPipeline(List.of(agent1, agent2), true, virtualTimeScheduler);

        Msg input = TestUtils.createUserMessage("User", "virtual time scheduler test");

        // Use StepVerifier.withVirtualTime to test with virtual time
        StepVerifier.withVirtualTime(() -> pipeline.execute(input))
                .expectSubscription()
                .expectNextCount(1) // Expect one list result
                .expectComplete()
                .verify(TIMEOUT);

        virtualTimeScheduler.dispose();
    }

    @Test
    @DisplayName("Should use scheduler threads for concurrent execution")
    void shouldUseSchedulerThreadsForConcurrentExecution() throws InterruptedException {
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
                new FanoutPipeline(List.of(agent1, agent2), true, trackingScheduler);

        Msg input = TestUtils.createUserMessage("User", "thread tracking test");

        // Execute the pipeline
        Mono<List<Msg>> result = pipeline.execute(input);

        // Wait for completion
        List<Msg> results = result.block(TIMEOUT);

        // Wait for all tasks to complete
        completionLatch.await();

        assertNotNull(results);
        assertEquals(2, results.size());
        // Verify that scheduler threads were used
        assertEquals(2, schedulerThreadCount.get());

        trackingScheduler.dispose();
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
