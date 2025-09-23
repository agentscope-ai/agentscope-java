/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class AgentHooksTest {

    private TestAgent testAgent;
    private List<String> hookExecutionOrder;

    @BeforeEach
    public void setUp() {
        testAgent = new TestAgent("TestAgent");
        hookExecutionOrder = new ArrayList<>();
        // Clear all instance hooks before each test
        testAgent.clearInstanceHooks(null);
    }

    @Test
    public void testPreHookExecution() {
        // Register a pre-hook that modifies input
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "input_modifier",
                (agent, args) -> {
                    hookExecutionOrder.add("pre_reply_hook");
                    return Mono.just(args);
                });

        Msg input = createTestMessage("original input");
        Msg result = testAgent.reply(input).block();

        assertTrue(hookExecutionOrder.contains("pre_reply_hook"));
        assertEquals("Test response to: original input", result.getTextContent());
    }

    @Test
    public void testPostHookExecution() {
        // Register a post-hook that modifies output
        testAgent.registerInstancePostHook(
                AgentHookType.POST_REPLY,
                "output_modifier",
                (agent, args, output) -> {
                    hookExecutionOrder.add("post_reply_hook");
                    if (output instanceof Msg msg) {
                        return Mono.just(
                                Msg.builder()
                                        .name(msg.getName())
                                        .role(msg.getRole())
                                        .content(
                                                TextBlock.builder()
                                                        .text("Modified: " + msg.getTextContent())
                                                        .build())
                                        .build());
                    }
                    return Mono.just(output);
                });

        Msg input = createTestMessage("test input");
        Msg result = testAgent.reply(input).block();

        assertTrue(hookExecutionOrder.contains("post_reply_hook"));
        assertEquals("Modified: Test response to: test input", result.getTextContent());
    }

    @Test
    public void testHookExecutionOrder() {
        // Register multiple hooks to test execution order
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "pre_hook_1",
                (agent, args) -> {
                    hookExecutionOrder.add("pre_hook_1");
                    return Mono.just(args);
                });

        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "pre_hook_2",
                (agent, args) -> {
                    hookExecutionOrder.add("pre_hook_2");
                    return Mono.just(args);
                });

        testAgent.registerInstancePostHook(
                AgentHookType.POST_REPLY,
                "post_hook_1",
                (agent, args, output) -> {
                    hookExecutionOrder.add("post_hook_1");
                    return Mono.just(output);
                });

        testAgent.registerInstancePostHook(
                AgentHookType.POST_REPLY,
                "post_hook_2",
                (agent, args, output) -> {
                    hookExecutionOrder.add("post_hook_2");
                    return Mono.just(output);
                });

        Msg input = createTestMessage("test");
        testAgent.reply(input).block();

        // Verify execution order: pre-hooks first, then post-hooks
        assertEquals(
                List.of("pre_hook_1", "pre_hook_2", "core_reply", "post_hook_1", "post_hook_2"),
                hookExecutionOrder);
    }

    @Test
    public void testClassLevelHooks() {
        AtomicInteger classHookExecutions = new AtomicInteger(0);

        // Register class-level hook
        // Class-level hooks removed; simulate by registering on each instance instead

        // Test with first agent instance
        TestAgent agent1 = new TestAgent("Agent1");
        agent1.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "class_hook_sim",
                (agent, args) -> {
                    classHookExecutions.incrementAndGet();
                    return Mono.just(args);
                });
        agent1.reply(createTestMessage("test1")).block();

        // Test with second agent instance
        TestAgent agent2 = new TestAgent("Agent2");
        agent2.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "class_hook_sim",
                (agent, args) -> {
                    classHookExecutions.incrementAndGet();
                    return Mono.just(args);
                });
        agent2.reply(createTestMessage("test2")).block();

        // Class hook should execute for both instances
        assertEquals(2, classHookExecutions.get());
    }

    @Test
    public void testHookErrorHandling() {
        // Register a hook that throws an exception
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "failing_hook",
                (agent, args) -> {
                    throw new RuntimeException("Hook failure");
                });

        // The operation should still complete despite hook failure
        Msg input = createTestMessage("test with failing hook");
        Msg result = testAgent.reply(input).block();

        assertNotNull(result);
        assertEquals("Test response to: test with failing hook", result.getTextContent());
    }

    @Test
    public void testHookRemoval() {
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "removable_hook",
                (agent, args) -> {
                    hookExecutionOrder.add("removable_hook");
                    return Mono.just(args);
                });

        // Execute once with hook
        testAgent.reply(createTestMessage("test1")).block();
        assertTrue(hookExecutionOrder.contains("removable_hook"));

        // Remove hook and execute again
        hookExecutionOrder.clear();
        boolean removed = testAgent.removeInstanceHook(AgentHookType.PRE_REPLY, "removable_hook");
        assertTrue(removed);

        testAgent.reply(createTestMessage("test2")).block();
        assertFalse(hookExecutionOrder.contains("removable_hook"));
    }

    @Test
    public void testHookClearance() {
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY, "hook1", (agent, args) -> Mono.just(args));
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY, "hook2", (agent, args) -> Mono.just(args));
        testAgent.registerInstancePostHook(
                AgentHookType.POST_REPLY, "hook3", (agent, args, output) -> Mono.just(output));

        // Clear all hooks of a specific type
        testAgent.clearInstanceHooks(AgentHookType.PRE_REPLY);

        // Clear all hooks
        testAgent.clearInstanceHooks(null);

        // No hooks should execute now
        testAgent.reply(createTestMessage("test")).block();
        assertEquals(List.of("core_reply"), hookExecutionOrder);
    }

    @Test
    public void testNullHookReturnValue() {
        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "null_returning_hook",
                (agent, args) ->
                        Mono.justOrEmpty(null)); // Return null to pass original args unchanged

        Msg input = createTestMessage("null test");
        Msg result = testAgent.reply(input).block();

        assertEquals("Test response to: null test", result.getTextContent());
    }

    @Test
    public void testMultipleHookTypesOnSameAgent() {
        AtomicInteger replyHooks = new AtomicInteger(0);
        AtomicInteger observeHooks = new AtomicInteger(0);
        AtomicInteger printHooks = new AtomicInteger(0);

        testAgent.registerInstancePreHook(
                AgentHookType.PRE_REPLY,
                "reply_hook",
                (agent, args) -> {
                    replyHooks.incrementAndGet();
                    return Mono.just(args);
                });

        // Execute different operations
        testAgent.reply(createTestMessage("test")).block();

        assertEquals(1, replyHooks.get());
    }

    // Helper methods and test implementation

    private Msg createTestMessage(String content) {
        return Msg.builder()
                .name("test")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    /**
     * Test agent implementation for hook testing.
     */
    private class TestAgent extends AgentBase {
        public TestAgent(String name) {
            super(name, new InMemoryMemory());
        }

        @Override
        protected Flux<Msg> doStream(Msg x) {
            return Flux.defer(
                    () -> {
                        hookExecutionOrder.add("core_reply");
                        Msg out =
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text(
                                                                "Test response to: "
                                                                        + x.getTextContent())
                                                        .build())
                                        .build();
                        return Flux.just(out);
                    });
        }

        @Override
        protected Flux<Msg> doStream(java.util.List<Msg> x) {
            return Flux.defer(
                    () -> {
                        hookExecutionOrder.add("core_reply");
                        Msg last = x.isEmpty() ? null : x.get(x.size() - 1);
                        String text =
                                (last != null && last.getTextContent() != null)
                                        ? last.getTextContent()
                                        : x.toString();
                        Msg out =
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Test response to: " + text)
                                                        .build())
                                        .build();
                        return Flux.just(out);
                    });
        }
    }
}
