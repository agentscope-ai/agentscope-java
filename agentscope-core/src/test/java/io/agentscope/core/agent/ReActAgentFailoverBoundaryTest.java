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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.ModelInvocation;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class ReActAgentFailoverBoundaryTest {

    private static class DelegatingInvocation implements ModelInvocation {
        @Override
        public Flux<ChatResponse> stream(
                String model,
                String purpose,
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options,
                Function<String, Flux<ChatResponse>> next) {
            return next.apply(UUID.randomUUID().toString());
        }
    }

    @Test
    void recoveredFallbackBypassesPrimaryAndFailureListener() {
        var primary = new MockModel("primary");
        var fallback = new MockModel("fallback");
        var failures = new ArrayList<Throwable>();
        var invocation =
                new DelegatingInvocation() {
                    @Override
                    public boolean resumingFallback(String purpose) {
                        assertEquals("REASONING", purpose);
                        return true;
                    }
                };
        try (var agent =
                ReActAgent.builder()
                        .name("recovered-fallback")
                        .model(primary)
                        .fallbackModel(fallback)
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .failoverListener((model, error) -> failures.add(error))
                        .build()) {
            var response =
                    agent.call(
                                    List.of(TestUtils.createUserMessage("user", "hello")),
                                    RuntimeContext.builder()
                                            .put(ModelInvocation.class, invocation)
                                            .build())
                            .block(Duration.ofSeconds(5));
            assertEquals("fallback", TestUtils.extractTextContent(response));
            assertEquals(0, primary.getCallCount());
            assertEquals(1, fallback.getCallCount());
            assertEquals(List.of(), failures);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hostFailurePolicyControlsFallback(boolean allowFallback) {
        var failure = new IllegalStateException("reply protocol failure");
        var primary =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(failure);
                    }
                };
        ModelInvocation invocation =
                allowFallback
                        ? new DelegatingInvocation()
                        : new DelegatingInvocation() {
                            @Override
                            public boolean beginFallback(String purpose, Throwable cause) {
                                assertEquals("REASONING", purpose);
                                assertSame(failure, cause);
                                return false;
                            }
                        };
        var fallback = new MockModel("fallback");
        var failures = new ArrayList<Throwable>();
        try (var agent =
                ReActAgent.builder()
                        .name("host-failure-policy")
                        .model(primary)
                        .fallbackModel(fallback)
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .failoverListener((model, error) -> failures.add(error))
                        .build()) {
            var response =
                    agent.call(
                            List.of(TestUtils.createUserMessage("user", "hello")),
                            RuntimeContext.builder()
                                    .put(ModelInvocation.class, invocation)
                                    .build());
            if (allowFallback) {
                assertEquals(
                        "fallback",
                        TestUtils.extractTextContent(response.block(Duration.ofSeconds(5))));
                assertEquals(1, fallback.getCallCount());
                assertEquals(List.of(failure), failures);
            } else {
                assertSame(
                        failure,
                        assertThrows(
                                IllegalStateException.class,
                                () -> response.block(Duration.ofSeconds(5))));
                assertEquals(0, fallback.getCallCount());
                assertEquals(List.of(), failures);
            }
        }
    }

    @Test
    void hostPausePreservesPendingCallWithoutSelectingFallback() {
        var paused = new AgentExecutionHost.Paused();
        var primary =
                new MockModel("unused") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(paused);
                    }
                };
        var fallback = new MockModel("fallback");
        var failures = new ArrayList<Throwable>();
        try (var agent =
                ReActAgent.builder()
                        .name("host-pause")
                        .model(primary)
                        .fallbackModel(fallback)
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .failoverListener((model, error) -> failures.add(error))
                        .build()) {
            var response =
                    agent.call(
                                    List.of(TestUtils.createUserMessage("user", "hello")),
                                    RuntimeContext.builder()
                                            .put(ModelInvocation.class, new DelegatingInvocation())
                                            .build())
                            .block(Duration.ofSeconds(5));
            assertEquals(
                    io.agentscope.core.message.GenerateReason.HOST_PAUSED,
                    response.getGenerateReason());
            assertEquals("", response.getTextContent());
            assertEquals(0, fallback.getCallCount());
            assertEquals(List.of(), failures);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fallsBackForFailureBeforeFirstResponse(boolean throwSynchronously) {
        IllegalStateException failure = new IllegalStateException("primary unavailable");
        Model primary =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (throwSynchronously) {
                            throw failure;
                        }
                        return Flux.error(failure);
                    }

                    @Override
                    public String getModelName() {
                        return "primary";
                    }
                };
        MockModel fallback = new MockModel("fallback response");
        List<Throwable> observed = new ArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("failover-boundary")
                        .model(primary)
                        .fallbackModel(fallback)
                        .failoverListener(
                                (model, error) -> {
                                    assertSame(primary, model);
                                    observed.add(error);
                                })
                        .build();

        Msg response =
                agent.call(TestUtils.createUserMessage("user", "hello"))
                        .block(Duration.ofSeconds(5));

        assertEquals("fallback response", TestUtils.extractTextContent(response));
        assertEquals(1, fallback.getCallCount());
        assertEquals(List.of(failure), observed);
    }

    @Test
    void doesNotSwitchAfterPrimaryEmitsAResponse() {
        IllegalStateException failure = new IllegalStateException("stream interrupted");
        MockModel primary =
                new MockModel("partial") {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return super.stream(messages, tools, options)
                                .concatWith(Flux.error(failure));
                    }
                };
        MockModel fallback = new MockModel("fallback response");
        List<Throwable> observed = new ArrayList<>();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("partial-response")
                        .model(primary)
                        .fallbackModel(fallback)
                        .failoverListener((model, error) -> observed.add(error))
                        .build();

        assertThrows(
                RuntimeException.class,
                () ->
                        agent.call(TestUtils.createUserMessage("user", "hello"))
                                .block(Duration.ofSeconds(5)));
        assertEquals(0, fallback.getCallCount());
        assertEquals(List.of(), observed);
    }
}
