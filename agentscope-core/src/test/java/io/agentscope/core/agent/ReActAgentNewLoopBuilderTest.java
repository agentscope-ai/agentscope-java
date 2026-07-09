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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Builder/getter tests for the new-core fields exposed by {@link ReActAgent}. */
class ReActAgentNewLoopBuilderTest {

    private static ChatModelBase newFakeModel() {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "fake-model";
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }
        };
    }

    private static AgentState newState() {
        return AgentState.builder().sessionId("test-session").build();
    }

    private static ReActAgent newAgent() {
        return ReActAgent.builder()
                .name("assistant")
                .sysPrompt("you are helpful")
                .model(newFakeModel())
                .toolkit(new Toolkit())
                .build();
    }

    @Test
    void builderPopulatesAllNewCoreFields() {
        ChatModelBase model = newFakeModel();
        Toolkit toolkit = new Toolkit();
        List<MiddlewareBase> mw = List.of();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("planner")
                        .sysPrompt("sys")
                        .model(model)
                        .toolkit(toolkit)
                        .middlewares(mw)
                        .build();

        assertEquals("planner", agent.getName());
        assertEquals("sys", agent.getSystemPrompt());
        assertSame(model, agent.getModel());
        assertNotNull(agent.getToolkit());
        // ReActAgent prepends a system-level GracefulShutdownMiddleware to the chain; user-
        // supplied middlewares follow.
        List<MiddlewareBase> registered = agent.getMiddlewares();
        assertTrue(registered.stream().anyMatch(m -> m instanceof GracefulShutdownMiddleware));
        assertTrue(registered.containsAll(mw));
        assertNotNull(agent.getAgentState());
        assertNotNull(agent.getModelConfig());
        assertNotNull(agent.getReactConfig());
        assertNotNull(agent.getPermissionEngine());
        assertNotNull(agent.getAgentId());
        assertTrue(agent.getAgentId().length() > 0);
    }

    @Test
    void builderAppliesDefaultsWhenConfigsOmitted() {
        ReActAgent agent =
                ReActAgent.builder().name("a").model(newFakeModel()).toolkit(new Toolkit()).build();
        assertNotNull(agent.getModelConfig());
        assertNotNull(agent.getReactConfig());
        // Only the system GracefulShutdownMiddleware is present when the user adds none.
        assertEquals(1, agent.getMiddlewares().size());
        assertTrue(agent.getMiddlewares().get(0) instanceof GracefulShutdownMiddleware);
    }

    @Test
    void fromAgentCopiesModelResilienceConfig() {
        ChatModelBase model = newFakeModel();
        ChatModelBase fallback = newFakeModel();

        ReActAgent source =
                ReActAgent.builder()
                        .name("source")
                        .sysPrompt("sys")
                        .model(model)
                        .fallbackModel(fallback)
                        .maxRetries(7)
                        .toolkit(new Toolkit())
                        .build();

        ReActAgent copy = ReActAgent.Builder.fromAgent(source).build();

        assertNotNull(copy.getModelConfig());
        assertEquals(7, copy.getModelConfig().maxRetries());
        assertSame(fallback, copy.getModelConfig().fallbackModel());
        assertSame(model, copy.getModel());
    }

    @Test
    void observeAddsMessagesToState() {
        ReActAgent agent =
                ReActAgent.builder().name("a").model(newFakeModel()).toolkit(new Toolkit()).build();

        Msg m1 = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        Msg m2 = Msg.builder().role(MsgRole.USER).textContent("again").build();

        StepVerifier.create(agent.observe(m1)).verifyComplete();
        StepVerifier.create(agent.observe(List.of(m2))).verifyComplete();

        assertEquals(2, agent.getAgentState().getContext().size());
    }
}
