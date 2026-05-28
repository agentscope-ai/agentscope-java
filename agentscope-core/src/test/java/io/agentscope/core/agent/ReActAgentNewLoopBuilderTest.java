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
import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.Middleware;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
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
                .modelConfig(ModelConfig.defaults())
                .contextConfig(ContextConfig.defaults())
                .reactConfig(ReactConfig.defaults())
                .build();
    }

    @Test
    void builderPopulatesAllNewCoreFields() {
        ChatModelBase model = newFakeModel();
        Toolkit toolkit = new Toolkit();
        AgentState state = newState();
        ModelConfig mc = ModelConfig.defaults();
        ContextConfig cc = ContextConfig.defaults();
        ReactConfig rc = ReactConfig.defaults();
        List<Middleware> mw = List.of();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("planner")
                        .sysPrompt("sys")
                        .model(model)
                        .toolkit(toolkit)
                        .middlewares(mw)
                        .modelConfig(mc)
                        .contextConfig(cc)
                        .reactConfig(rc)
                        .build();

        assertEquals("planner", agent.getName());
        assertEquals("sys", agent.getSystemPrompt());
        assertSame(model, agent.getModel());
        assertNotNull(agent.getToolkit());
        assertEquals(mw, agent.getMiddlewares());
        assertNotNull(agent.getState());
        assertSame(mc, agent.getModelConfig());
        assertSame(cc, agent.getContextConfig());
        assertSame(rc, agent.getReactConfig());
        assertNotNull(agent.getPermissionEngine());
        assertNotNull(agent.getAgentId());
        assertTrue(agent.getAgentId().length() > 0);
    }

    @Test
    void builderAppliesDefaultsWhenConfigsOmitted() {
        ReActAgent agent =
                ReActAgent.builder().name("a").model(newFakeModel()).toolkit(new Toolkit()).build();
        assertNotNull(agent.getModelConfig());
        assertNotNull(agent.getContextConfig());
        assertNotNull(agent.getReactConfig());
        assertEquals(List.of(), agent.getMiddlewares());
    }

    @Test
    void observeAddsMessagesToState() {
        ReActAgent agent =
                ReActAgent.builder().name("a").model(newFakeModel()).toolkit(new Toolkit()).build();

        Msg m1 = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        Msg m2 = Msg.builder().role(MsgRole.USER).textContent("again").build();

        StepVerifier.create(agent.observe(m1)).verifyComplete();
        StepVerifier.create(agent.observe(List.of(m2))).verifyComplete();

        assertEquals(2, agent.getState().getContext().size());
    }
}
