/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.chat.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.session.InMemorySessionManager;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link SpringInMemorySessionManager}.
 *
 * <p>These tests verify that the Spring implementation correctly delegates to the core
 * InMemorySessionManager and implements the Spring interface correctly.
 */
@DisplayName("SpringInMemorySessionManager Tests")
class SpringInMemorySessionManagerTest {

    private SpringInMemorySessionManager sessionManager;
    private AtomicInteger agentCounter;

    @BeforeEach
    void setUp() {
        sessionManager = new SpringInMemorySessionManager();
        agentCounter = new AtomicInteger(0);
    }

    private Supplier<ReActAgent> createAgentSupplier() {
        return () -> {
            int id = agentCounter.incrementAndGet();
            return ReActAgent.builder()
                    .name("test-agent-" + id)
                    .sysPrompt("Test agent")
                    .model(
                            new Model() {
                                @Override
                                public reactor.core.publisher.Flux<ChatResponse> stream(
                                        List<Msg> messages,
                                        List<io.agentscope.core.model.ToolSchema> tools,
                                        io.agentscope.core.model.GenerateOptions options) {
                                    return reactor.core.publisher.Flux.just(
                                            ChatResponse.builder()
                                                    .content(
                                                            List.of(
                                                                    TextBlock.builder()
                                                                            .text("test response")
                                                                            .build()))
                                                    .build());
                                }

                                @Override
                                public String getModelName() {
                                    return "test-model";
                                }
                            })
                    .build();
        };
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create instance with default constructor")
        void shouldCreateInstanceWithDefaultConstructor() {
            SpringInMemorySessionManager manager = new SpringInMemorySessionManager();

            assertThat(manager).isNotNull();
            assertThat(manager).isInstanceOf(SpringChatCompletionsSessionManager.class);
        }

        @Test
        @DisplayName("Should create instance with custom delegate")
        void shouldCreateInstanceWithCustomDelegate() {
            InMemorySessionManager delegate = new InMemorySessionManager();
            SpringInMemorySessionManager manager = new SpringInMemorySessionManager(delegate);

            assertThat(manager).isNotNull();
        }
    }

    @Nested
    @DisplayName("getOrCreateAgent Tests")
    class GetOrCreateAgentTests {

        @Test
        @DisplayName("Should create new agent when sessionId is null")
        void shouldCreateNewAgentWhenSessionIdIsNull() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent(null, supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should create new agent when sessionId is empty")
        void shouldCreateNewAgentWhenSessionIdIsEmpty() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result = sessionManager.getOrCreateAgent("", supplier);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should reuse existing agent for same sessionId")
        void shouldReuseExistingAgentForSameSessionId() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            String sessionId = "existing-session";

            ReActAgent first = sessionManager.getOrCreateAgent(sessionId, supplier);
            String firstName = first.getName();

            ReActAgent second = sessionManager.getOrCreateAgent(sessionId, supplier);

            assertThat(second).isSameAs(first);
            assertThat(second.getName()).isEqualTo(firstName);
        }

        @Test
        @DisplayName("Should create different agents for different sessionIds")
        void shouldCreateDifferentAgentsForDifferentSessionIds() {
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent first = sessionManager.getOrCreateAgent("session-1", supplier);
            ReActAgent second = sessionManager.getOrCreateAgent("session-2", supplier);

            assertThat(first).isNotSameAs(second);
            assertThat(first.getName()).isNotEqualTo(second.getName());
        }

        @Test
        @DisplayName("Should throw IllegalStateException when agentSupplier returns null")
        void shouldThrowIllegalStateExceptionWhenAgentSupplierReturnsNull() {
            Supplier<ReActAgent> nullSupplier = () -> null;

            assertThatThrownBy(() -> sessionManager.getOrCreateAgent("session", nullSupplier))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("agentSupplier returned null");
        }
    }

    @Nested
    @DisplayName("Spring ObjectProvider Integration Tests")
    class SpringObjectProviderIntegrationTests {

        @Test
        @DisplayName("Should work with ObjectProvider via default method")
        void shouldWorkWithObjectProviderViaDefaultMethod() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            ObjectProvider<ReActAgent> objectProvider =
                    new ObjectProvider<ReActAgent>() {
                        @Override
                        public ReActAgent getObject() {
                            return supplier.get();
                        }

                        @Override
                        public ReActAgent getObject(Object... args) {
                            return supplier.get();
                        }
                    };

            ReActAgent result = sessionManager.getOrCreateAgent("session-id", objectProvider);

            assertThat(result).isNotNull();
            assertThat(result.getName()).startsWith("test-agent-");
        }

        @Test
        @DisplayName("Should reuse agent when using ObjectProvider multiple times")
        void shouldReuseAgentWhenUsingObjectProviderMultipleTimes() {
            Supplier<ReActAgent> supplier = createAgentSupplier();
            ObjectProvider<ReActAgent> objectProvider =
                    new ObjectProvider<ReActAgent>() {
                        @Override
                        public ReActAgent getObject() {
                            return supplier.get();
                        }

                        @Override
                        public ReActAgent getObject(Object... args) {
                            return supplier.get();
                        }
                    };

            String sessionId = "reuse-session";
            ReActAgent first = sessionManager.getOrCreateAgent(sessionId, objectProvider);
            ReActAgent second = sessionManager.getOrCreateAgent(sessionId, objectProvider);

            assertThat(second).isSameAs(first);
        }
    }

    @Nested
    @DisplayName("Interface Implementation Tests")
    class InterfaceImplementationTests {

        @Test
        @DisplayName("Should implement SpringChatCompletionsSessionManager interface")
        void shouldImplementSpringChatCompletionsSessionManagerInterface() {
            assertThat(sessionManager).isInstanceOf(SpringChatCompletionsSessionManager.class);
        }

        @Test
        @DisplayName("Should delegate to core InMemorySessionManager")
        void shouldDelegateToCoreInMemorySessionManager() {
            InMemorySessionManager delegate = new InMemorySessionManager();
            SpringInMemorySessionManager manager = new SpringInMemorySessionManager(delegate);
            Supplier<ReActAgent> supplier = createAgentSupplier();

            ReActAgent result1 = manager.getOrCreateAgent("test-session", supplier);
            ReActAgent result2 = delegate.getOrCreateAgent("test-session", supplier);

            // Both should return the same agent instance (same session)
            assertThat(result2).isSameAs(result1);
        }
    }
}
