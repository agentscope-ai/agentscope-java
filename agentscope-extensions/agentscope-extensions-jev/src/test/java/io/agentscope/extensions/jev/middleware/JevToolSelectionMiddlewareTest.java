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

package io.agentscope.extensions.jev.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.jev.Answer;
import io.agentscope.extensions.jev.ChoiceAnswer;
import io.agentscope.extensions.jev.SystemOneRequest;
import io.agentscope.extensions.jev.SystemOneResult;
import io.agentscope.extensions.jev.Usage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JevToolSelectionMiddlewareTest {

    @Test
    void filtersOptionalToolsAndPreservesCoreTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools_0",
                                            choice(
                                                    "search",
                                                    Map.of(
                                                            "search",
                                                            0.7,
                                                            "read_file",
                                                            0.2,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.1),
                                                    0.9))));
                };

        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall)
                        .maxTools(1)
                        .alwaysIncludeTools(java.util.Set.of("load_skill_through_path"))
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        List.of(
                                tool("load_skill_through_path", "Load a skill"),
                                tool("search", "Search the web"),
                                tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        input,
                        next -> {
                            captured.set(next);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals(1, calls.get());
        assertEquals(
                List.of("load_skill_through_path", "search"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void callsJevForEachReasoningStepWithSameOptionalToolSet() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools_0",
                                            choice(
                                                    "search",
                                                    Map.of(
                                                            "search",
                                                            0.7,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.3),
                                                    0.9))));
                };
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall)
                        .maxTools(1)
                        .alwaysIncludeTools(java.util.Set.of("load_skill_through_path"))
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        List.of(
                                tool("load_skill_through_path", "Load a skill"),
                                tool("search", "Search the web"),
                                tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();
        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        assertEquals(2, calls.get());
    }

    @Test
    void reselectsToolsForEachReasoningStepUsingFullMessages() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        List<SystemOneRequest> requests = new java.util.ArrayList<>();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    requests.add(request);
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools_0",
                                            choice(
                                                    "search",
                                                    Map.of(
                                                            "search",
                                                            0.7,
                                                            "read_file",
                                                            0.2,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.1),
                                                    0.9))));
                };

        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall).maxTools(1).build();

        ReasoningInput first =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());
        ReasoningInput second =
                new ReasoningInput(
                        List.of(
                                new UserMessage("Search the web"),
                                new UserMessage("Then read the result")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, first, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();
        middleware
                .onReasoning(null, ctx, second, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        assertEquals(2, calls.get());
        assertEquals(2, requests.size());
        Object state = requests.get(1).state();
        assertTrue(state instanceof Map);
        Object messages = ((Map<?, ?>) state).get("messages");
        assertTrue(messages instanceof List);
        assertEquals(2, ((List<?>) messages).size());
    }

    @Test
    void keepsAllToolsWhenThereIsNoUserText() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .maxTools(1)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        input,
                        next -> {
                            captured.set(next);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals(0, calls.get());
        assertEquals(2, captured.get().tools().size());
    }

    @Test
    void failsOpenAndKeepsAllTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .maxTools(1)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        input,
                        next -> {
                            captured.set(next);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals(2, captured.get().tools().size());
    }

    @Test
    void chunksAndReranksLargeToolSets() {
        List<ToolSchema> tools = new java.util.ArrayList<>();
        for (int i = 0; i < 255; i++) {
            tools.add(tool("tool_" + i, "Tool " + i));
        }
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    if (calls.incrementAndGet() == 1) {
                        return Mono.just(
                                result(
                                        Map.of(
                                                "tools_0",
                                                choice(
                                                        "tool_0",
                                                        Map.of(
                                                                "tool_0",
                                                                0.7,
                                                                JevSelectionSupport.NONE_OPTION,
                                                                0.3),
                                                        0.9),
                                                "tools_1",
                                                choice(
                                                        "tool_254",
                                                        Map.of(
                                                                "tool_254",
                                                                0.7,
                                                                JevSelectionSupport.NONE_OPTION,
                                                                0.3),
                                                        0.9))));
                    }
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools",
                                            choice(
                                                    "tool_0",
                                                    Map.of(
                                                            "tool_0",
                                                            0.8,
                                                            "tool_254",
                                                            0.1,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.1),
                                                    0.95))));
                };

        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall).maxTools(1).build();
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(
                                List.of(new UserMessage("Use tool 0")),
                                tools,
                                GenerateOptions.builder().build()),
                        next -> {
                            captured.set(next);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals(2, calls.get());
        assertEquals(
                List.of("tool_0"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
    }

    private static ToolSchema tool(String name, String description) {
        return ToolSchema.builder().name(name).description(description).build();
    }

    private static ChoiceAnswer choice(
            String selected, Map<String, Double> probabilities, double confidence) {
        return new ChoiceAnswer(selected, probabilities, confidence);
    }

    private static SystemOneResult result(Map<String, Answer> answers) {
        return new SystemOneResult("jev-test", answers, new Usage(1, 1));
    }
}
