/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.responses.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ResponsesRequestMiddlewareTest {

    @Test
    void shouldAppendOnlyNonBlankSystemFragments() {
        ResponsesRequestMiddleware middleware =
                new ResponsesRequestMiddleware(
                        List.of("request instructions", "", "developer note"), null);

        String prompt =
                middleware
                        .onSystemPrompt(
                                mock(Agent.class),
                                RuntimeContext.empty(),
                                "configured system prompt")
                        .block();

        assertEquals("configured system prompt\nrequest instructions\ndeveloper note", prompt);
    }

    @Test
    void shouldMergeRequestGenerationOptionsOverConfiguredOptions() {
        GenerateOptions requestOptions =
                GenerateOptions.builder().temperature(0.2).maxTokens(256).build();
        GenerateOptions configuredOptions =
                GenerateOptions.builder().temperature(0.8).topP(0.9).build();
        ResponsesRequestMiddleware middleware =
                new ResponsesRequestMiddleware(List.of(), requestOptions);
        ReasoningInput input = new ReasoningInput(List.of(), List.of(), configuredOptions);
        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();

        middleware
                .onReasoning(
                        mock(Agent.class),
                        RuntimeContext.empty(),
                        input,
                        nextInput -> {
                            forwarded.set(nextInput);
                            return Flux.empty();
                        })
                .blockLast();

        ReasoningInput result = forwarded.get();
        assertSame(input.messages(), result.messages());
        assertSame(input.tools(), result.tools());
        assertEquals(0.2, result.options().getTemperature());
        assertEquals(256, result.options().getMaxTokens());
        assertEquals(0.9, result.options().getTopP());
    }
}
