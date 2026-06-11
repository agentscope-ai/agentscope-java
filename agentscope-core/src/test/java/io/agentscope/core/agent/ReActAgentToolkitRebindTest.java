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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests that {@link MiddlewareBase#rebindToolkit(Toolkit)} is called correctly during
 * {@link ReActAgent.Builder#build()}, ensuring middlewares that dynamically register tools
 * operate on the agent's actual toolkit after the defensive deep copy.
 */
class ReActAgentToolkitRebindTest {

    private static final class FixedTextModel extends ChatModelBase {
        @Override
        public String getModelName() {
            return "fixed";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.<ContentBlock>of(TextBlock.builder().text("ok").build()))
                            .build());
        }
    }

    /** A simple AgentTool that records which toolkit it was registered on. */
    private static final class TestDynamicTool implements AgentTool {
        private final String name;
        private final AtomicReference<String> registeredOnToolkit = new AtomicReference<>("never");

        TestDynamicTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Test tool: " + name;
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.text("result from " + name));
        }

        void markRegistered(Toolkit tk) {
            registeredOnToolkit.set(tk.toString());
        }

        String getRegisteredOnToolkit() {
            return registeredOnToolkit.get();
        }
    }

    /**
     * A middleware that holds a toolkit reference and registers a tool on it during
     * onSystemPrompt, simulating the behavior of HarnessSkillMiddleware.
     */
    private static final class ToolkitHoldingMiddleware implements MiddlewareBase {
        private volatile Toolkit toolkit;
        private final TestDynamicTool tool;
        private boolean rebindCalled = false;

        ToolkitHoldingMiddleware(Toolkit toolkit, TestDynamicTool tool) {
            this.toolkit = toolkit;
            this.tool = tool;
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, String currentPrompt) {
            // Simulate dynamic tool registration during onSystemPrompt
            if (toolkit.getTool(tool.getName()) == null) {
                toolkit.registerAgentTool(tool);
                tool.markRegistered(toolkit);
            }
            return Mono.just(currentPrompt);
        }

        @Override
        public void rebindToolkit(Toolkit newToolkit) {
            this.toolkit = newToolkit;
            this.rebindCalled = true;
        }

        Toolkit getToolkit() {
            return toolkit;
        }

        boolean wasRebindCalled() {
            return rebindCalled;
        }
    }

    @Test
    void rebindToolkitCalledAfterDeepCopy() {
        Toolkit originalToolkit = new Toolkit();
        TestDynamicTool tool = new TestDynamicTool("test_dynamic_tool");
        ToolkitHoldingMiddleware middleware = new ToolkitHoldingMiddleware(originalToolkit, tool);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(new FixedTextModel())
                        .toolkit(originalToolkit)
                        .middleware(middleware)
                        .build();

        // rebindToolkit should have been called
        assertTrue(middleware.wasRebindCalled(), "rebindToolkit should be called during build()");

        // The middleware should now reference the agent's actual toolkit (the copy),
        // not the original toolkit passed to the builder
        assertNotSame(
                originalToolkit,
                middleware.getToolkit(),
                "middleware toolkit should be the copy, not the original");

        // The middleware's toolkit should be the same instance the agent uses
        assertSame(
                agent.getToolkit(),
                middleware.getToolkit(),
                "middleware toolkit should match agent's toolkit");
    }

    @Test
    void dynamicToolRegisteredOnCorrectToolkitAfterRebind() {
        Toolkit originalToolkit = new Toolkit();
        TestDynamicTool tool = new TestDynamicTool("test_dynamic_tool");
        ToolkitHoldingMiddleware middleware = new ToolkitHoldingMiddleware(originalToolkit, tool);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(new FixedTextModel())
                        .toolkit(originalToolkit)
                        .middleware(middleware)
                        .build();

        // Simulate onSystemPrompt being called (which registers the tool)
        middleware.onSystemPrompt(agent, "test").block();

        // The tool should be registered on the agent's actual toolkit
        assertEquals(
                agent.getToolkit().toString(),
                tool.getRegisteredOnToolkit(),
                "tool should be registered on the agent's actual toolkit (the copy)");

        // The agent's toolkit should contain the dynamically registered tool
        assertTrue(
                agent.getToolkit().getToolNames().contains("test_dynamic_tool"),
                "agent's toolkit should contain the dynamically registered tool");
    }

    @Test
    void defaultRebindToolkitIsNoOp() {
        // Verify that the default implementation is a no-op (doesn't throw)
        MiddlewareBase noOpMiddleware = new MiddlewareBase() {};
        Toolkit someToolkit = new Toolkit();

        // Should not throw
        noOpMiddleware.rebindToolkit(someToolkit);
    }

    @Test
    void multipleMiddlewaresAllRebind() {
        Toolkit originalToolkit = new Toolkit();
        TestDynamicTool tool1 = new TestDynamicTool("tool_1");
        TestDynamicTool tool2 = new TestDynamicTool("tool_2");
        ToolkitHoldingMiddleware middleware1 = new ToolkitHoldingMiddleware(originalToolkit, tool1);
        ToolkitHoldingMiddleware middleware2 = new ToolkitHoldingMiddleware(originalToolkit, tool2);

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("test")
                        .model(new FixedTextModel())
                        .toolkit(originalToolkit)
                        .middleware(middleware1)
                        .middleware(middleware2)
                        .build();

        // Both middlewares should have been rebound
        assertTrue(middleware1.wasRebindCalled(), "first middleware should be rebound");
        assertTrue(middleware2.wasRebindCalled(), "second middleware should be rebound");

        // Both should reference the same toolkit as the agent
        assertSame(agent.getToolkit(), middleware1.getToolkit());
        assertSame(agent.getToolkit(), middleware2.getToolkit());
    }
}
