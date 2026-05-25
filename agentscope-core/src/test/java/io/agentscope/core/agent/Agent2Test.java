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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
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

/** Stage 7c skeleton tests for {@link Agent2}. */
class Agent2Test {

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

    private static Agent2 newAgent() {
        return new Agent2(
                "assistant",
                "you are helpful",
                newFakeModel(),
                new Toolkit(),
                List.of(),
                newState(),
                ModelConfig.defaults(),
                ContextConfig.defaults(),
                ReactConfig.defaults());
    }

    @Test
    void constructorPopulatesAllFields() {
        ChatModelBase model = newFakeModel();
        Toolkit toolkit = new Toolkit();
        AgentState state = newState();
        ModelConfig mc = ModelConfig.defaults();
        ContextConfig cc = ContextConfig.defaults();
        ReactConfig rc = ReactConfig.defaults();
        List<MiddlewareBase> mw = List.of();

        Agent2 agent = new Agent2("planner", "sys", model, toolkit, mw, state, mc, cc, rc);

        assertEquals("planner", agent.getName());
        assertEquals("sys", agent.getSystemPrompt());
        assertSame(model, agent.getModel());
        assertSame(toolkit, agent.getToolkit());
        assertEquals(mw, agent.getMiddlewares());
        assertSame(state, agent.getState());
        assertSame(mc, agent.getModelConfig());
        assertSame(cc, agent.getContextConfig());
        assertSame(rc, agent.getReactConfig());
        assertNotNull(agent.getPermissionEngine());
        assertNotNull(agent.getAgentId());
        assertTrue(agent.getAgentId().length() > 0);
    }

    @Test
    void constructorAppliesDefaultsWhenNullConfigsPassed() {
        Agent2 agent =
                new Agent2(
                        "a",
                        null,
                        newFakeModel(),
                        new Toolkit(),
                        null,
                        newState(),
                        null,
                        null,
                        null);
        assertNotNull(agent.getModelConfig());
        assertNotNull(agent.getContextConfig());
        assertNotNull(agent.getReactConfig());
        assertEquals(List.of(), agent.getMiddlewares());
    }

    @Test
    void constructorRejectsNullRequiredFields() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new Agent2(
                                null,
                                "sys",
                                newFakeModel(),
                                new Toolkit(),
                                List.of(),
                                newState(),
                                null,
                                null,
                                null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new Agent2(
                                "a",
                                "sys",
                                null,
                                new Toolkit(),
                                List.of(),
                                newState(),
                                null,
                                null,
                                null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new Agent2(
                                "a",
                                "sys",
                                newFakeModel(),
                                null,
                                List.of(),
                                newState(),
                                null,
                                null,
                                null));
        assertThrows(
                NullPointerException.class,
                () ->
                        new Agent2(
                                "a",
                                "sys",
                                newFakeModel(),
                                new Toolkit(),
                                List.of(),
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void callIsNotYetImplemented() {
        Agent2 agent = newAgent();
        StepVerifier.create(agent.call(List.of()))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    @Test
    void streamIsNotYetImplemented() {
        Agent2 agent = newAgent();
        StepVerifier.create(agent.stream(List.of(), StreamOptions.defaults()))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    @Test
    void observeAddsMessagesToState() {
        AgentState state = newState();
        Agent2 agent =
                new Agent2(
                        "a",
                        null,
                        newFakeModel(),
                        new Toolkit(),
                        List.of(),
                        state,
                        null,
                        null,
                        null);

        Msg m1 = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        Msg m2 = Msg.builder().role(MsgRole.USER).textContent("again").build();

        StepVerifier.create(agent.observe(m1)).verifyComplete();
        StepVerifier.create(agent.observe(List.of(m2))).verifyComplete();

        assertEquals(2, state.getContext().size());
    }

    @Test
    void interruptSetsFlag() {
        Agent2 agent = newAgent();
        assertTrue(!agent.interruptFlag().get());
        agent.interrupt();
        assertTrue(agent.interruptFlag().get());
    }

    @Test
    void interruptWithMsgStoresMessage() {
        Agent2 agent = newAgent();
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("stop").build();
        agent.interrupt(userMsg);
        assertTrue(agent.interruptFlag().get());
        assertSame(userMsg, agent.userInterruptMessage().get());
    }
}
