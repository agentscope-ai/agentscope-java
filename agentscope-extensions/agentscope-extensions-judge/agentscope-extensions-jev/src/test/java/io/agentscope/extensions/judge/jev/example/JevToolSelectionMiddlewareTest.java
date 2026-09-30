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

package io.agentscope.extensions.judge.jev.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link JevToolSelectionMiddleware}. Jev is mocked with a stub function; the
 * filtered tool list is verified through the {@code ReasoningInput} captured by {@code next}.
 */
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
    void fullConversationStrategySendsCompleteMessages() {
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
                JevToolSelectionMiddleware.builder(jevCall)
                        .maxTools(1)
                        .contextStrategy(
                                JevToolSelectionMiddleware.ContextStrategy.FULL_CONVERSATION)
                        .build();

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
        assertTrue(((List<?>) messages).get(0) instanceof Msg);
    }

    @Test
    void usesRecentWindowStateByDefault() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    captured.set(request);
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
                JevToolSelectionMiddleware.builder(jevCall).maxTools(1).build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                new UserMessage("Search the web"),
                                new UserMessage("Then read the result")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        Object state = captured.get().state();
        assertTrue(state instanceof Map);
        Object messages = ((Map<?, ?>) state).get("messages");
        assertTrue(messages instanceof List);
        List<?> entries = (List<?>) messages;
        assertEquals(2, entries.size());
        Object first = entries.get(0);
        assertTrue(first instanceof Map, "window entries must be role/text projections");
        assertEquals("user", ((Map<?, ?>) first).get("role"));
        assertEquals("Search the web", ((Map<?, ?>) first).get("text"));
        assertEquals("Then read the result", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowKeepsOnlyNewestMessages() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    captured.set(request);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tools_0",
                                                            choice(
                                                                    "search",
                                                                    Map.of(
                                                                            "search",
                                                                            0.7,
                                                                            JevSelectionSupport
                                                                                    .NONE_OPTION,
                                                                            0.3),
                                                                    0.9))));
                                })
                        .maxTools(1)
                        .maxContextMessages(2)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                new UserMessage("one"),
                                new UserMessage("two"),
                                new UserMessage("three"),
                                new UserMessage("four")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        List<?> entries = (List<?>) ((Map<?, ?>) captured.get().state()).get("messages");
        assertEquals(2, entries.size());
        assertEquals("three", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("four", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowTruncatesOldestKeptMessageAtCharBudget() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    captured.set(request);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tools_0",
                                                            choice(
                                                                    "search",
                                                                    Map.of(
                                                                            "search",
                                                                            0.7,
                                                                            JevSelectionSupport
                                                                                    .NONE_OPTION,
                                                                            0.3),
                                                                    0.9))));
                                })
                        .maxTools(1)
                        .maxContextChars(7)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("aaaaaaaaaa"), new UserMessage("bbbbb")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        List<?> entries = (List<?>) ((Map<?, ?>) captured.get().state()).get("messages");
        assertEquals(2, entries.size());
        assertEquals("aa", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("bbbbb", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void recentWindowDropsMessagesBeyondCharBudget() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    captured.set(request);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tools_0",
                                                            choice(
                                                                    "search",
                                                                    Map.of(
                                                                            "search",
                                                                            0.7,
                                                                            JevSelectionSupport
                                                                                    .NONE_OPTION,
                                                                            0.3),
                                                                    0.9))));
                                })
                        .maxTools(1)
                        .maxContextChars(7)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                new UserMessage("aaaaaaaaaa"),
                                new UserMessage("bbbbb"),
                                new UserMessage("cc")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        List<?> entries = (List<?>) ((Map<?, ?>) captured.get().state()).get("messages");
        assertEquals(2, entries.size());
        assertEquals("bbbbb", ((Map<?, ?>) entries.get(0)).get("text"));
        assertEquals("cc", ((Map<?, ?>) entries.get(1)).get("text"));
    }

    @Test
    void latestUserMessageStrategySendsOnlyUserRequest() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    captured.set(request);
                                    return Mono.just(
                                            result(
                                                    Map.of(
                                                            "tools_0",
                                                            choice(
                                                                    "search",
                                                                    Map.of(
                                                                            "search",
                                                                            0.7,
                                                                            JevSelectionSupport
                                                                                    .NONE_OPTION,
                                                                            0.3),
                                                                    0.9))));
                                })
                        .maxTools(1)
                        .contextStrategy(
                                JevToolSelectionMiddleware.ContextStrategy.LATEST_USER_MESSAGE)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(
                                new UserMessage("Search the web"),
                                new UserMessage("Then read the result")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        middleware
                .onReasoning(null, ctx, input, next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        Map<?, ?> state = (Map<?, ?>) captured.get().state();
        assertEquals("Then read the result", state.get("userRequest"));
        assertFalse(state.containsKey("messages"));
        ChoiceQuestion question = (ChoiceQuestion) captured.get().questions().get("tools_0");
        assertTrue(String.valueOf(question.instructions()).contains("userRequest"));
    }

    @Test
    void latestUserMessageStrategyUsesUserRequestInRerankInstructions() {
        List<ToolSchema> tools = new java.util.ArrayList<>();
        for (int i = 0; i < 255; i++) {
            tools.add(tool("tool_" + i, "Tool " + i));
        }
        List<SystemOneRequest> requests = new java.util.ArrayList<>();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    requests.add(request);
                    if (requests.size() == 1) {
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
                JevToolSelectionMiddleware.builder(jevCall)
                        .maxTools(1)
                        .contextStrategy(
                                JevToolSelectionMiddleware.ContextStrategy.LATEST_USER_MESSAGE)
                        .build();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(
                                List.of(new UserMessage("Use tool 0")),
                                tools,
                                GenerateOptions.builder().build()),
                        next -> reactor.core.publisher.Flux.empty())
                .then()
                .block();

        assertEquals(2, requests.size());
        ChoiceQuestion rerank = (ChoiceQuestion) requests.get(1).questions().get("tools");
        assertTrue(String.valueOf(rerank.instructions()).contains("userRequest"));
        Map<?, ?> rerankState = (Map<?, ?>) requests.get(1).state();
        assertEquals("Use tool 0", rerankState.get("userRequest"));
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
    void passesThroughWhenToolsAreNull() {
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
                        List.of(new UserMessage("Search the web")),
                        null,
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
        assertNull(captured.get().tools());
    }

    @Test
    void passesThroughWhenOptionalToolsFitWithinMaxTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> {
                                    calls.incrementAndGet();
                                    return Mono.error(new IllegalStateException("should not call"));
                                })
                        .maxTools(3)
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
    void failsClosedPropagatesJevError() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .maxTools(1)
                        .failOpen(false)
                        .build();

        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        assertThrows(
                IllegalStateException.class,
                () ->
                        middleware
                                .onReasoning(
                                        null,
                                        ctx,
                                        input,
                                        next -> reactor.core.publisher.Flux.empty())
                                .then()
                                .block());
    }

    @Test
    void keepsAllToolsWhenJevReturnsNoSelection() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request ->
                                        Mono.just(
                                                result(
                                                        Map.of(
                                                                "tools_0",
                                                                choice(
                                                                        JevSelectionSupport
                                                                                .NONE_OPTION,
                                                                        Map.of(
                                                                                "search",
                                                                                0.1,
                                                                                JevSelectionSupport
                                                                                        .NONE_OPTION,
                                                                                0.9),
                                                                        0.9)))))
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
    void keepsAllToolsWhenChunkAnswersAreNotChoiceAnswers() {
        List<ToolSchema> tools = new java.util.ArrayList<>();
        for (int i = 0; i < 255; i++) {
            tools.add(tool("tool_" + i, "Tool " + i));
        }
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools_0",
                                            new NoulAnswer(0.9),
                                            "tools_1",
                                            new NoulAnswer(0.9))));
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

        assertEquals(1, calls.get(), "an unusable shortlist must not trigger a rerank call");
        assertEquals(255, captured.get().tools().size());
    }

    @Test
    void rerankNonChoiceAnswerKeepsAllTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    if (request.questions().containsKey("tools")) {
                        return Mono.just(result(Map.of("tools", new NoulAnswer(0.9))));
                    }
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
                                                    0.9),
                                            "tools_1",
                                            choice(
                                                    "read_file",
                                                    Map.of(
                                                            "read_file",
                                                            0.7,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.3),
                                                    0.9))));
                };
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall).maxTools(2).build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        largeOptionalToolSet(),
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

        assertEquals(255, captured.get().tools().size());
    }

    @Test
    void keepsAllToolsWhenNoChunkProducesAWinner() {
        List<ToolSchema> tools = new java.util.ArrayList<>();
        for (int i = 0; i < 255; i++) {
            tools.add(tool("tool_" + i, "Tool " + i));
        }
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(
                            result(
                                    Map.of(
                                            "tools_0",
                                            choice(
                                                    JevSelectionSupport.NONE_OPTION,
                                                    Map.of(
                                                            "tool_0",
                                                            0.1,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.9),
                                                    0.9),
                                            "tools_1",
                                            choice(
                                                    JevSelectionSupport.NONE_OPTION,
                                                    Map.of(
                                                            "tool_254",
                                                            0.1,
                                                            JevSelectionSupport.NONE_OPTION,
                                                            0.9),
                                                    0.9))));
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

        assertEquals(1, calls.get(), "an empty shortlist must not trigger a rerank call");
        assertEquals(255, captured.get().tools().size());
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

    @Test
    void orderIsZero() {
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("unused")))
                        .build();
        assertEquals(0, middleware.order());
    }

    @Test
    void buildsFromJevClient() {
        JevClient client = JevClient.builder().apiKey("test-key").build();
        JevToolSelectionMiddleware middleware = JevToolSelectionMiddleware.builder(client).build();
        assertNotNull(middleware);
    }

    @Test
    void rejectsNullClient() {
        assertThrows(
                NullPointerException.class,
                () -> JevToolSelectionMiddleware.builder((JevClient) null));
    }

    @Test
    void rejectsNullJevCall() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        (Function<SystemOneRequest, Mono<SystemOneResult>>) null)
                                .build());
    }

    @Test
    void rejectsNonPositiveMaxTools() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .maxTools(0)
                                .build());
    }

    @Test
    void rejectsConfidenceThresholdBelowZero() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .confidenceThreshold(-0.1)
                                .build());
    }

    @Test
    void rejectsConfidenceThresholdAboveOne() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .confidenceThreshold(1.1)
                                .build());
    }

    @Test
    void rejectsNullContextStrategy() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .contextStrategy(null)
                                .build());
    }

    @Test
    void rejectsNonPositiveMaxContextMessages() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .maxContextMessages(0)
                                .build());
    }

    @Test
    void rejectsNonPositiveMaxContextChars() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        JevToolSelectionMiddleware.builder(
                                        request -> Mono.error(new IllegalStateException()))
                                .maxContextChars(0)
                                .build());
    }

    @Test
    void acceptsNullAlwaysIncludeTools() {
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("offline")))
                        .alwaysIncludeTools(null)
                        .maxTools(1)
                        .failOpen(true)
                        .build();
        assertNotNull(middleware);
    }

    @Test
    void singlePartitionSelectedNamesAreCappedByMaxTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request ->
                        Mono.just(
                                result(
                                        Map.of(
                                                "tools_0",
                                                choice(
                                                        "search",
                                                        Map.of(
                                                                "search",
                                                                0.5,
                                                                "read_file",
                                                                0.3,
                                                                "write_file",
                                                                0.15,
                                                                JevSelectionSupport.NONE_OPTION,
                                                                0.05),
                                                        0.9))));

        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall).maxTools(2).build();
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();

        middleware
                .onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(
                                List.of(new UserMessage("Search then read")),
                                List.of(
                                        tool("search", "Search the web"),
                                        tool("read_file", "Read a file"),
                                        tool("write_file", "Write a file")),
                                GenerateOptions.builder().build()),
                        next -> {
                            captured.set(next);
                            return reactor.core.publisher.Flux.empty();
                        })
                .then()
                .block();

        assertEquals(
                List.of("search", "read_file"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void stateBuildErrorFailsOpenWithFullToolList() {
        RuntimeContext ctx = RuntimeContext.empty();
        AtomicInteger calls = new AtomicInteger();
        Function<SystemOneRequest, Mono<SystemOneResult>> jevCall =
                request -> {
                    calls.incrementAndGet();
                    return Mono.just(result(Map.of()));
                };
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(jevCall).maxTools(1).build();
        Msg poison =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return "poison";
                    }

                    @Override
                    public MsgRole getRole() {
                        return null;
                    }
                };
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web"), poison),
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
        assertEquals(
                List.of("search", "read_file"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void stateBuildErrorFailsClosedWhenFailOpenDisabled() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(
                                request -> Mono.error(new IllegalStateException("must not call")))
                        .maxTools(1)
                        .failOpen(false)
                        .build();
        Msg poison =
                new Msg(null, null, MsgRole.USER, List.of(), null, null, null) {
                    @Override
                    public String getTextContent() {
                        return "poison";
                    }

                    @Override
                    public MsgRole getRole() {
                        return null;
                    }
                };
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web"), poison),
                        List.of(tool("search", "Search the web"), tool("read_file", "Read a file")),
                        GenerateOptions.builder().build());

        assertThrows(
                NullPointerException.class,
                () ->
                        middleware
                                .onReasoning(
                                        null,
                                        ctx,
                                        input,
                                        next -> reactor.core.publisher.Flux.empty())
                                .then()
                                .block());
    }

    @Test
    void hallucinatedSelectionFailsOpenWithFullToolList() {
        RuntimeContext ctx = RuntimeContext.empty();
        List<ToolSchema> tools = largeOptionalToolSet();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(rerankHallucinatingJevCall())
                        .maxTools(2)
                        .build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        tools,
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

        assertEquals(tools.size(), captured.get().tools().size());
        assertTrue(
                captured.get().tools().stream()
                        .map(ToolSchema::getName)
                        .toList()
                        .containsAll(List.of("search", "read_file")));
    }

    @Test
    void hallucinatedSelectionFailsClosedWhenFailOpenDisabled() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(rerankHallucinatingJevCall())
                        .maxTools(2)
                        .failOpen(false)
                        .build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        largeOptionalToolSet(),
                        GenerateOptions.builder().build());

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                middleware
                                        .onReasoning(
                                                null,
                                                ctx,
                                                input,
                                                next -> reactor.core.publisher.Flux.empty())
                                        .then()
                                        .block());
        assertTrue(error.getMessage().contains("no usable tools"));
    }

    @Test
    void partialHallucinationKeepsUsableTools() {
        RuntimeContext ctx = RuntimeContext.empty();
        JevToolSelectionMiddleware middleware =
                JevToolSelectionMiddleware.builder(rerankPartiallyHallucinatingJevCall())
                        .maxTools(2)
                        .build();
        ReasoningInput input =
                new ReasoningInput(
                        List.of(new UserMessage("Search the web")),
                        largeOptionalToolSet(),
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

        assertEquals(
                List.of("search"),
                captured.get().tools().stream().map(ToolSchema::getName).toList());
    }

    private static List<ToolSchema> largeOptionalToolSet() {
        List<ToolSchema> tools = new ArrayList<>();
        tools.add(tool("search", "Search the web"));
        for (int i = 0; i < 253; i++) {
            tools.add(tool("tool_" + i, "Filler tool " + i));
        }
        tools.add(tool("read_file", "Read a file"));
        return tools;
    }

    private static Function<SystemOneRequest, Mono<SystemOneResult>> rerankHallucinatingJevCall() {
        return request -> {
            if (request.questions().containsKey("tools")) {
                return Mono.just(
                        result(
                                Map.of(
                                        "tools",
                                        choice(
                                                "phantom_a",
                                                Map.of(
                                                        "phantom_a",
                                                        0.9,
                                                        "phantom_b",
                                                        0.8,
                                                        JevSelectionSupport.NONE_OPTION,
                                                        0.05),
                                                0.95))));
            }
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
                                            0.9),
                                    "tools_1",
                                    choice(
                                            "read_file",
                                            Map.of(
                                                    "read_file",
                                                    0.7,
                                                    JevSelectionSupport.NONE_OPTION,
                                                    0.3),
                                            0.9))));
        };
    }

    private static Function<SystemOneRequest, Mono<SystemOneResult>>
            rerankPartiallyHallucinatingJevCall() {
        return request -> {
            if (request.questions().containsKey("tools")) {
                return Mono.just(
                        result(
                                Map.of(
                                        "tools",
                                        choice(
                                                "phantom_a",
                                                Map.of(
                                                        "phantom_a",
                                                        0.8,
                                                        "search",
                                                        0.6,
                                                        JevSelectionSupport.NONE_OPTION,
                                                        0.05),
                                                0.9))));
            }
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
                                            0.9),
                                    "tools_1",
                                    choice(
                                            "read_file",
                                            Map.of(
                                                    "read_file",
                                                    0.7,
                                                    JevSelectionSupport.NONE_OPTION,
                                                    0.3),
                                            0.9))));
        };
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
