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
package io.agentscope.core.chat.completions.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.chat.completions.model.ChatChoice;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import io.agentscope.core.chat.completions.model.ToolCall;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Runs {@link ChatCompletionsStreamingAdapter#stream} against a real {@link ReActAgent}, i.e. on
 * top of {@code streamEvents}: one tool step, then an answer streamed in two pieces.
 */
class ChatCompletionsStreamingAdapterEventsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class WeatherTools {
        @Tool(description = "Get current weather")
        public String getWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 25°C";
        }

        @Tool(description = "Get current time")
        public String getTime(@ToolParam(name = "city", description = "city") String city) {
            return "10:00";
        }
    }

    /** Each model call replays the next scripted list of streamed responses. */
    static final class ScriptedModel implements Model {

        private final Deque<List<ChatResponse>> calls;

        @SafeVarargs
        ScriptedModel(List<ChatResponse>... calls) {
            this.calls = new ArrayDeque<>(List.of(calls));
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            List<ChatResponse> next = calls.poll();
            return next != null
                    ? Flux.fromIterable(next)
                    : Flux.error(new AssertionError("Unexpected extra model call"));
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    private static ToolUseBlock toolUse(String id, String name, String argsJson) {
        return ToolUseBlock.builder().id(id).name(name).content(argsJson).build();
    }

    private static ChatResponse response(String id, ChatUsage usage, ToolUseBlock... blocks) {
        return ChatResponse.builder().id(id).content(List.of(blocks)).usage(usage).build();
    }

    private static ChatResponse text(String id, String text, ChatUsage usage) {
        return ChatResponse.builder()
                .id(id)
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(usage)
                .build();
    }

    /** Tool step (10 in / 2 out), then "It is sunny." streamed as two pieces (15 in / 3 out). */
    private static ScriptedModel toolThenStreamedAnswer(boolean reportUsage) {
        return new ScriptedModel(
                List.of(
                        response(
                                "r1",
                                reportUsage ? new ChatUsage(10, 2, 0.1) : null,
                                toolUse("call_1", "getWeather", "{\"city\":\"Hangzhou\"}"))),
                List.of(
                        text("r2", "It is ", null),
                        text("r2", "sunny.", reportUsage ? new ChatUsage(15, 3, 0.1) : null)));
    }

    private static ReActAgent agent(Model model, MiddlewareBase... middlewares) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        return ReActAgent.builder()
                .name("weather")
                .sysPrompt("test")
                .model(model)
                .toolkit(toolkit)
                .middlewares(List.of(middlewares))
                .build();
    }

    private static List<ChatCompletionsChunk> run(Model model, boolean includeUsage) {
        return run(agent(model), includeUsage);
    }

    private static List<ChatCompletionsChunk> run(ReActAgent agent, boolean includeUsage) {
        Msg user =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Weather in Hangzhou?").build())
                        .build();
        return new ChatCompletionsStreamingAdapter()
                .stream(agent, List.of(user), "req-1", "weather", includeUsage)
                        .collectList()
                        .block(TIMEOUT);
    }

    @Test
    void eventsForwardedFromSubagentsAreNotSentToTheClient() {
        MiddlewareBase forwardsChildText =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        return next.apply(input)
                                .concatWith(
                                        Flux.just(
                                                new TextBlockDeltaEvent("child", "b", "CHILD")
                                                        .withSource("weather/child")));
                    }
                };
        ScriptedModel model = new ScriptedModel(List.of(text("r1", "Hi.", null)));

        List<ChatCompletionsChunk> chunks = run(agent(model, forwardsChildText), false);

        String text =
                chunks.stream()
                        .map(ChatCompletionsStreamingAdapterEventsTest::choice)
                        .filter(Objects::nonNull)
                        .map(c -> c.getDelta().getContent())
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining());
        assertEquals("Hi.", text);
    }

    private static ChatChoice choice(ChatCompletionsChunk chunk) {
        return chunk.getChoices().isEmpty() ? null : chunk.getChoices().get(0);
    }

    private static List<ChatCompletionsChunk> finishChunks(List<ChatCompletionsChunk> chunks) {
        return chunks.stream()
                .filter(c -> choice(c) != null && choice(c).getFinishReason() != null)
                .toList();
    }

    @Test
    void endsWithOneFinishChunkThenTheUsageOfTheWholeRequest() throws Exception {
        List<ChatCompletionsChunk> chunks = run(toolThenStreamedAnswer(true), true);

        List<ChatCompletionsChunk> finishes = finishChunks(chunks);
        assertEquals(1, finishes.size(), "finish_reason must appear once, not per model call");
        assertEquals("stop", choice(finishes.get(0)).getFinishReason());

        ChatCompletionsChunk last = chunks.get(chunks.size() - 1);
        assertEquals(finishes.get(0), chunks.get(chunks.size() - 2), "finish, then usage");
        assertTrue(last.getChoices().isEmpty());
        assertNotNull(last.getUsage());
        assertEquals(25, last.getUsage().getPromptTokens());
        assertEquals(5, last.getUsage().getCompletionTokens());
        assertEquals(30, last.getUsage().getTotalTokens());
        assertEquals(
                1, chunks.stream().filter(c -> c.getUsage() != null).count(), "usage only once");

        String json = MAPPER.writeValueAsString(last);
        assertTrue(json.contains("\"choices\":[]"), json);
        assertTrue(
                json.contains(
                        "\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":5,"
                                + "\"total_tokens\":30}"),
                json);
    }

    @Test
    void noUsageChunkUnlessRequested() throws Exception {
        List<ChatCompletionsChunk> chunks = run(toolThenStreamedAnswer(true), false);

        assertTrue(chunks.stream().allMatch(c -> c.getUsage() == null));
        assertEquals(1, finishChunks(chunks).size());
        assertEquals(finishChunks(chunks).get(0), chunks.get(chunks.size() - 1));
        for (ChatCompletionsChunk chunk : chunks) {
            assertFalse(MAPPER.writeValueAsString(chunk).contains("usage"));
        }
    }

    @Test
    void noUsageChunkWhenNoModelCallReportedUsage() {
        List<ChatCompletionsChunk> chunks = run(toolThenStreamedAnswer(false), true);

        assertTrue(chunks.stream().allMatch(c -> c.getUsage() == null));
        assertEquals(finishChunks(chunks).get(0), chunks.get(chunks.size() - 1));
    }

    @Test
    void streamsTextOnceAndToolCallsInOpenAiDeltaFormat() throws Exception {
        List<ChatCompletionsChunk> chunks = run(toolThenStreamedAnswer(true), false);

        String text =
                chunks.stream()
                        .map(ChatCompletionsStreamingAdapterEventsTest::choice)
                        .filter(Objects::nonNull)
                        .map(c -> c.getDelta().getContent())
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining());
        // Tool result first (String results arrive JSON-encoded), then the answer exactly once:
        // no accumulated copy of the text is re-sent at the end.
        assertTrue(text.startsWith("[Tool: getWeather] "), text);
        assertTrue(text.contains("Sunny"), text);
        assertTrue(text.endsWith("It is sunny."), text);
        assertEquals(text.indexOf("It is sunny."), text.lastIndexOf("It is sunny."), text);

        List<ToolCall> calls =
                chunks.stream()
                        .map(ChatCompletionsStreamingAdapterEventsTest::choice)
                        .filter(c -> c != null && c.getDelta().getToolCalls() != null)
                        .flatMap(c -> c.getDelta().getToolCalls().stream())
                        .toList();
        assertEquals(2, calls.size(), "a start chunk, then the arguments");
        ToolCall start = calls.get(0);
        assertEquals(0, start.getIndex());
        assertEquals("call_1", start.getId());
        assertEquals("getWeather", start.getFunction().getName());
        assertEquals("", start.getFunction().getArguments());
        ToolCall args = calls.get(1);
        assertEquals(0, args.getIndex());
        assertNull(args.getId());
        assertEquals("{\"city\":\"Hangzhou\"}", args.getFunction().getArguments());
        String argsJson = MAPPER.writeValueAsString(args);
        assertFalse(argsJson.contains("\"name\""), argsJson);
        assertFalse(argsJson.contains("\"type\""), argsJson);
    }

    @Test
    void parallelToolCallsGetDistinctIndexes() {
        ScriptedModel model =
                new ScriptedModel(
                        List.of(
                                response(
                                        "r1",
                                        new ChatUsage(10, 4, 0.1),
                                        toolUse("call_a", "getWeather", "{\"city\":\"Hangzhou\"}"),
                                        toolUse("call_b", "getTime", "{\"city\":\"Hangzhou\"}"))),
                        List.of(text("r2", "Done.", new ChatUsage(20, 1, 0.1))));

        List<ChatCompletionsChunk> chunks = run(model, true);

        List<ToolCall> starts =
                chunks.stream()
                        .map(ChatCompletionsStreamingAdapterEventsTest::choice)
                        .filter(c -> c != null && c.getDelta().getToolCalls() != null)
                        .flatMap(c -> c.getDelta().getToolCalls().stream())
                        .filter(tc -> tc.getId() != null)
                        .toList();
        assertEquals(List.of("call_a", "call_b"), starts.stream().map(ToolCall::getId).toList());
        assertEquals(List.of(0, 1), starts.stream().map(ToolCall::getIndex).toList());
        assertEquals(30, chunks.get(chunks.size() - 1).getUsage().getPromptTokens());
    }
}
