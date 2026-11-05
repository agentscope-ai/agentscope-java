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
package io.agentscope.extensions.model.gemini.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GeminiToolCallIdentityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentCallsSurviveAggregationAcrossPartsAndChunks(boolean providerIds) {
        var parser = new GeminiResponseParser();
        var reasoning = new ReasoningContext("assistant");
        // A burst of calls exercises collisions in IDs derived from a millisecond clock.
        var cities = IntStream.range(0, 64).mapToObj(i -> "city-" + i).toList();
        for (int offset = 0; offset < cities.size(); offset += 32) {
            reasoning.processChunk(
                    parser.parseResponse(
                            response(cities.subList(offset, offset + 32), providerIds),
                            Instant.now()));
        }

        var calls = reasoning.buildFinalMessage().getContentBlocks(ToolUseBlock.class);
        assertEquals(cities.size(), calls.size());
        assertEquals(cities.size(), calls.stream().map(ToolUseBlock::getId).distinct().count());
        assertEquals(cities, calls.stream().map(call -> call.getInput().get("city")).toList());
        for (var call : calls) {
            assertEquals("get_weather", call.getName());
            assertFalse(call.hasMalformedArguments());
            if (providerIds) assertEquals("call-" + call.getInput().get("city"), call.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void agentExecutesBothCallsToTheSameTool(boolean providerIds) {
        var cities = List.of("Tokyo", "Paris");
        var executedCities = new CopyOnWriteArrayList<String>();
        var toolkit = new Toolkit();
        toolkit.registerTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("get_weather")
                                .description("Look up a city's weather")
                                .inputSchema(Map.of("type", "object"))
                                .readOnly(true)
                                .concurrencySafe(true)) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executedCities.add((String) param.getInput().get("city"));
                        return Mono.just(ToolResultBlock.text("sunny"));
                    }
                });
        var rounds = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "gemini-tool-calls-test";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (rounds.getAndIncrement() == 0)
                            return Flux.just(
                                    new GeminiResponseParser()
                                            .parseResponse(
                                                    response(cities, providerIds), Instant.now()));
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                };

        try (var agent =
                ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).build()) {
            agent.call(new UserMessage("Compare Tokyo and Paris weather"))
                    .block(Duration.ofSeconds(10));
        }
        assertEquals(cities.stream().sorted().toList(), executedCities.stream().sorted().toList());
        assertEquals(2, rounds.get());
    }

    private static GenerateContentResponse response(List<String> cities, boolean providerIds) {
        var parts =
                cities.stream()
                        .map(
                                city -> {
                                    var call =
                                            FunctionCall.builder()
                                                    .name("get_weather")
                                                    .args(Map.of("city", city));
                                    if (providerIds) call.id("call-" + city);
                                    return Part.builder().functionCall(call.build()).build();
                                })
                        .toList();
        return GenerateContentResponse.builder()
                .candidates(
                        List.of(
                                Candidate.builder()
                                        .content(
                                                Content.builder()
                                                        .role("model")
                                                        .parts(parts)
                                                        .build())
                                        .build()))
                .build();
    }
}
