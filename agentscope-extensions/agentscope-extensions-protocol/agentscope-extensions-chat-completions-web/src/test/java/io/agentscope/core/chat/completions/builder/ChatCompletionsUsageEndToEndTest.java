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
package io.agentscope.core.chat.completions.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsResponse;
import io.agentscope.core.chat.completions.model.ChatMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
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
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Runs a real {@link ReActAgent} that calls a tool before answering, then builds the
 * non-streaming response: {@code usage} must cover both model calls, not only the last one.
 */
class ChatCompletionsUsageEndToEndTest {

    static class WeatherTools {
        @Tool(description = "Get current weather")
        public String getWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 25°C";
        }
    }

    /** First call: a tool call (10 in / 2 out). Second call: the answer (15 in / 3 out). */
    static final class ToolThenTextModel implements Model {

        private final Deque<ChatResponse> responses =
                new ArrayDeque<>(
                        List.of(
                                ChatResponse.builder()
                                        .id("r1")
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call_1")
                                                                .name("getWeather")
                                                                .input(Map.of("city", "Hangzhou"))
                                                                .content("{\"city\":\"Hangzhou\"}")
                                                                .build()))
                                        .usage(new ChatUsage(10, 2, 0.1))
                                        .build(),
                                ChatResponse.builder()
                                        .id("r2")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("It is sunny.")
                                                                .build()))
                                        .usage(new ChatUsage(15, 3, 0.1))
                                        .build()));

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ChatResponse next = responses.poll();
            return next != null
                    ? Flux.just(next)
                    : Flux.error(new AssertionError("Unexpected extra model call"));
        }

        @Override
        public String getModelName() {
            return "tool-then-text";
        }
    }

    @Test
    void usageCoversEveryModelCallOfTheRequest() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather")
                        .sysPrompt("test")
                        .model(new ToolThenTextModel())
                        .toolkit(toolkit)
                        .build();
        ChatCompletionsRequest request = new ChatCompletionsRequest();
        request.setModel("weather");
        request.setMessages(List.of(new ChatMessage("user", "Weather in Hangzhou?")));

        Msg reply =
                agent.call(
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .content(
                                                TextBlock.builder()
                                                        .text("Weather in Hangzhou?")
                                                        .build())
                                        .build())
                        .block(Duration.ofSeconds(10));
        ChatCompletionsResponse response =
                new ChatCompletionsResponseBuilder().buildResponse(request, reply, "req-1");

        ChatCompletionsResponse.Usage usage = response.getUsage();
        assertNotNull(usage);
        assertEquals(25, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(30, usage.getTotalTokens());
    }
}
