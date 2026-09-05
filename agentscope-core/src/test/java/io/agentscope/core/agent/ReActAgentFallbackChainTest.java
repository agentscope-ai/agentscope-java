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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.FallbackChainModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Integration tests for the {@code ReActAgent.Builder.fallbackModels(...)} switch: when a chain
 * is configured, a failing primary model transparently falls back to the next candidate for the
 * whole ReAct loop.
 */
@Tag("unit")
@DisplayName("ReActAgent fallbackModels switch Integration Tests")
class ReActAgentFallbackChainTest {

    @Test
    @DisplayName("Agent falls back to the secondary model when the primary fails (503)")
    void agentFallsBackToSecondaryModel() {
        FailingModel primary =
                new FailingModel("primary", new HttpTransportException("503", 503, ""));
        SimpleModel fallback = new SimpleModel("fallback");

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .fallbackModels(List.of(fallback))
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        String text = TestUtils.extractTextContent(response);
        assertNotNull(text, "Response text should not be null");
        assertTrue(
                text.contains("fallback"), "Response should come from the fallback model: " + text);

        assertEquals(1, primary.callCount.get(), "Primary should be attempted once");
        assertEquals(1, fallback.callCount.get(), "Fallback should serve the turn");
    }

    @Test
    @DisplayName("Agent keeps primary-only behaviour when no chain is configured")
    void primaryOnlyWithoutChain() {
        SimpleModel primary = new SimpleModel("primary");
        SimpleModel unusedFallback = new SimpleModel("unused");

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertTrue(
                TestUtils.extractTextContent(response).contains("primary"),
                "Response should come from the primary model");
        assertEquals(0, unusedFallback.callCount.get(), "No fallback should be invoked");
    }

    @Test
    @DisplayName("Request-side failure fails fast without consuming the chain")
    void requestSideErrorDoesNotConsumeChain() {
        FailingModel primary =
                new FailingModel("primary", new HttpTransportException("400", 400, ""));
        SimpleModel fallback = new SimpleModel("fallback");

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .fallbackModels(List.of(fallback))
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        // The agent surfaces the request-side failure; the fallback must never be attempted.
        try {
            agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        } catch (RuntimeException expected) {
            // Expected: request-side errors propagate without fallback switching.
        }

        assertEquals(1, primary.callCount.get(), "Primary should be attempted once");
        assertEquals(
                0, fallback.callCount.get(), "Request-side failure must not consume fallbacks");
    }

    @Test
    @DisplayName("FallbackChainModel is exposed as a pluggable wrapper on the agent")
    void fallbackChainExposedAsPluggableWrapper() {
        FailingModel primary =
                new FailingModel("primary", new HttpTransportException("502", 502, ""));
        SimpleModel fallback = new SimpleModel("fallback");

        // Extension point usage: users can bypass the builder switch and wire the wrapper directly.
        FallbackChainModel wrapper = new FallbackChainModel(primary, List.of(fallback));

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(wrapper)
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertTrue(
                TestUtils.extractTextContent(response).contains("fallback"),
                "Direct wrapper wiring should fall back too");
        assertEquals("fallback", wrapper.getModelName(), "Active model should be the fallback");
    }

    @Test
    @DisplayName("Varargs fallbackModels(Model...) overload works")
    void varargsFallbackModelsOverloadWorks() {
        FailingModel primary =
                new FailingModel("primary", new HttpTransportException("503", 503, ""));
        SimpleModel fallback1 = new SimpleModel("fallback1");
        SimpleModel fallback2 = new SimpleModel("fallback2");

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .fallbackModels(fallback1, fallback2)
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertTrue(
                TestUtils.extractTextContent(response).contains("fallback1"),
                "First varargs fallback should serve the turn: "
                        + TestUtils.extractTextContent(response));
        assertEquals(1, fallback1.callCount.get(), "fallback1 should be called once");
        assertEquals(0, fallback2.callCount.get(), "fallback2 should not be needed");
    }

    @Test
    @DisplayName("Empty varargs disables the chain (primary-only behaviour)")
    void emptyVarargsDisablesChain() {
        SimpleModel primary = new SimpleModel("primary");
        SimpleModel fallback = new SimpleModel("fallback");

        ReActAgent agent =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .fallbackModels() // empty: clears any configured chain
                        .toolkit(new Toolkit())
                        .build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                agent.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertEquals(0, fallback.callCount.get(), "No fallback should be invoked");
    }

    @Test
    @DisplayName("Builder.fromAgent copies the configured fallback chain")
    void fromAgentCopiesFallbackChain() {
        FailingModel primary =
                new FailingModel("primary", new HttpTransportException("503", 503, ""));
        SimpleModel fallback = new SimpleModel("fallback");

        ReActAgent original =
                ReActAgent.builder()
                        .name(TestConstants.TEST_REACT_AGENT_NAME)
                        .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                        .model(primary)
                        .fallbackModels(List.of(fallback))
                        .toolkit(new Toolkit())
                        .build();

        ReActAgent copy = ReActAgent.Builder.fromAgent(original).build();

        Msg userMsg = TestUtils.createUserMessage("User", TestConstants.TEST_USER_INPUT);
        Msg response =
                copy.call(userMsg).block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(response, "Response should not be null");
        assertTrue(
                TestUtils.extractTextContent(response).contains("fallback"),
                "fromAgent copy should preserve the fallback chain");
        assertEquals(1, fallback.callCount.get(), "Fallback should serve the copied agent");
    }

    /** Model that always fails with a fixed throwable and tracks call count. */
    private static class FailingModel implements Model {

        private final String name;
        private final Throwable error;
        private final AtomicInteger callCount = new AtomicInteger();

        FailingModel(String name, Throwable error) {
            this.name = name;
            this.error = error;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount.incrementAndGet();
            return Flux.error(error);
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    /** Model that always succeeds with a fixed text and tracks call count. */
    private static class SimpleModel implements Model {

        private final String name;
        private final AtomicInteger callCount = new AtomicInteger();

        SimpleModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            callCount.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .id("msg-" + name)
                            .content(
                                    List.of(TextBlock.builder().text("reply from " + name).build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }
}
