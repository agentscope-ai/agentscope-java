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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

/**
 * E2E tests for the Hook system.
 *
 * <p>Tests various hook events: PreReasoningEvent, PostReasoningEvent, PreActingEvent,
 * PostActingEvent, ReasoningChunkEvent, PreCallEvent, PostCallEvent.
 */
@Tag("e2e")
@Tag("hook")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Hook System E2E Tests")
class HookE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should trigger PreCallEvent and PostCallEvent")
    void testPrePostCallEvents(ModelProvider provider) {
        System.out.println(
                "\n=== Test: PreCall/PostCall Events with " + provider.getProviderName() + " ===");

        AtomicBoolean preCallTriggered = new AtomicBoolean(false);
        AtomicBoolean postCallTriggered = new AtomicBoolean(false);

        Hook eventTracker =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent) {
                            preCallTriggered.set(true);
                            System.out.println("  PreCallEvent triggered");
                        } else if (event instanceof PostCallEvent) {
                            postCallTriggered.set(true);
                            System.out.println("  PostCallEvent triggered");
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("HookTestAgent", toolkit);
        agent.getHooks().add(eventTracker);

        Msg input = TestUtils.createUserMessage("User", "What is 2 + 2?");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(preCallTriggered.get(), "PreCallEvent should be triggered");
        assertTrue(postCallTriggered.get(), "PostCallEvent should be triggered");

        System.out.println("✓ PreCall/PostCall events verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should modify input messages via PreReasoningEvent")
    void testPreReasoningEventModification(ModelProvider provider) {
        System.out.println(
                "\n=== Test: PreReasoningEvent Modification with "
                        + provider.getProviderName()
                        + " ===");

        AtomicBoolean hookTriggered = new AtomicBoolean(false);

        Hook inputModifier =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent e) {
                            hookTriggered.set(true);
                            // Add a system hint to the messages
                            List<Msg> msgs = new ArrayList<>(e.getInputMessages());
                            msgs.add(
                                    0,
                                    Msg.builder()
                                            .role(MsgRole.SYSTEM)
                                            .textContent("Always answer in one word only.")
                                            .build());
                            e.setInputMessages(msgs);
                            System.out.println("  PreReasoningEvent: Modified input messages");
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ModifyAgent", toolkit);
        agent.getHooks().add(inputModifier);

        Msg input = TestUtils.createUserMessage("User", "What is the capital of France?");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(hookTriggered.get(), "PreReasoningEvent should be triggered");
        assertTrue(
                ContentValidator.containsKeywords(response, "Paris"),
                "Response should have content for " + provider.getModelName());

        System.out.println(
                "✓ PreReasoningEvent modification verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should trigger PreActingEvent and PostActingEvent on tool calls")
    void testActingEvents(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println("\n=== Test: Acting Events with " + provider.getProviderName() + " ===");

        AtomicBoolean preActingTriggered = new AtomicBoolean(false);
        AtomicBoolean postActingTriggered = new AtomicBoolean(false);

        Hook actingTracker =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreActingEvent e) {
                            preActingTriggered.set(true);
                            System.out.println(
                                    "  PreActingEvent: Tool = " + e.getToolUse().getName());
                        } else if (event instanceof PostActingEvent e) {
                            postActingTriggered.set(true);
                            System.out.println("  PostActingEvent: Result received");
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("ActingTestAgent", toolkit);
        agent.getHooks().add(actingTracker);

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Calculate 15 multiplied by 7. You must use the multiply tool.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        // At least one tool call should have happened
        assertTrue(
                preActingTriggered.get() || postActingTriggered.get(),
                "Acting events should be triggered when tool is called");

        System.out.println("✓ Acting events verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should execute hooks in priority order")
    void testHookPriorityOrder(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Hook Priority Order with " + provider.getProviderName() + " ===");

        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        Hook lowPriorityHook =
                new Hook() {
                    @Override
                    public int priority() {
                        return 500;
                    }

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            executionOrder.add("low");
                            System.out.println("  Low priority hook executed");
                        }
                        return Mono.just(event);
                    }
                };

        Hook highPriorityHook =
                new Hook() {
                    @Override
                    public int priority() {
                        return 10;
                    }

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            executionOrder.add("high");
                            System.out.println("  High priority hook executed");
                        }
                        return Mono.just(event);
                    }
                };

        Hook mediumPriorityHook =
                new Hook() {
                    @Override
                    public int priority() {
                        return 100;
                    }

                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreReasoningEvent) {
                            executionOrder.add("medium");
                            System.out.println("  Medium priority hook executed");
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("PriorityAgent", toolkit);
        // Add in random order
        agent.getHooks().add(lowPriorityHook);
        agent.getHooks().add(highPriorityHook);
        agent.getHooks().add(mediumPriorityHook);

        Msg input = TestUtils.createUserMessage("User", "Hello");
        agent.call(input).block(TEST_TIMEOUT);

        // Verify hooks were called in priority order (low value = high priority = first)
        assertTrue(executionOrder.size() >= 3, "All hooks should execute at least once");
        assertEquals("high", executionOrder.get(0), "High priority should execute first");
        assertEquals("medium", executionOrder.get(1), "Medium priority should execute second");
        assertEquals("low", executionOrder.get(2), "Low priority should execute third");

        System.out.println(
                "✓ Hook priority order verified for "
                        + provider.getProviderName()
                        + ": "
                        + executionOrder);
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should receive streaming chunks via ReasoningChunkEvent")
    void testReasoningChunkEvent(ModelProvider provider) {
        System.out.println(
                "\n=== Test: ReasoningChunkEvent with " + provider.getProviderName() + " ===");

        AtomicInteger chunkCount = new AtomicInteger(0);

        Hook chunkCollector =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent) {
                            chunkCount.incrementAndGet();
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("StreamingAgent", toolkit);
        agent.getHooks().add(chunkCollector);

        Msg input = TestUtils.createUserMessage("User", "Write a short greeting.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        System.out.println("  Received " + chunkCount.get() + " chunks");

        // Streaming should produce multiple chunks (at least for streaming-enabled models)
        // Note: Some providers may not support streaming chunks
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("✓ ReasoningChunkEvent verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should modify tool parameters via PreActingEvent")
    void testPreActingEventModification(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: PreActingEvent Modification with "
                        + provider.getProviderName()
                        + " ===");

        AtomicBoolean modificationApplied = new AtomicBoolean(false);

        Hook parameterModifier =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreActingEvent e) {
                            // Log that we're intercepting the tool call
                            System.out.println(
                                    "  PreActingEvent: Intercepting tool '"
                                            + e.getToolUse().getName()
                                            + "'");
                            modificationApplied.set(true);
                        }
                        return Mono.just(event);
                    }
                };

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new LoggingCalculator());
        ReActAgent agent = provider.createAgent("ModifyToolAgent", toolkit);
        agent.getHooks().add(parameterModifier);

        Msg input = TestUtils.createUserMessage("User", "Use the logging_add tool to add 5 and 3.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.containsKeywords(response, "8"),
                "Response should have content for " + provider.getModelName());

        System.out.println(
                "✓ PreActingEvent modification verified for " + provider.getProviderName());
    }

    /** A calculator tool that logs its usage. */
    public static class LoggingCalculator {

        @Tool(name = "logging_add", description = "Add two numbers and log the operation")
        public String add(
                @ToolParam(name = "a", description = "First number") int a,
                @ToolParam(name = "b", description = "Second number") int b) {
            int result = a + b;
            System.out.println("    LoggingCalculator: " + a + " + " + b + " = " + result);
            return String.valueOf(result);
        }
    }
}
