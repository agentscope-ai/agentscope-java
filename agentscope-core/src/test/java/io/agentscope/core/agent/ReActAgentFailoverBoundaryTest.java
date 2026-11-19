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
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class ReActAgentFailoverBoundaryTest {

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
