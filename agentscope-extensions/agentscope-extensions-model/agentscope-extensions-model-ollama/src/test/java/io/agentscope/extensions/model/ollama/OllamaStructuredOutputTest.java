/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.extensions.model.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OllamaStructuredOutputTest {
    private static final String JSON = "{\"description\":[\"a red apple\"],\"name\":\"apple\"}";
    private static final String IMAGE =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aG1kAAAAASUVORK5CYII=";

    public static class ImageDescription {
        public List<String> description;
        public String name;
    }

    public static class Tools {
        @Tool(name = "describe", description = "Describe an image")
        public String describe() {
            return "a red apple";
        }
    }

    @Test
    void builderExposesNativeStructuredOutputConfiguration() {
        HttpTransport transport = mock(HttpTransport.class);
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3.5:9b").httpTransport(transport).build();
        assertTrue(model.supportsNativeStructuredOutput());
        assertTrue(model.supportsNativeStructuredOutputWithTools());

        OllamaChatModel configured =
                OllamaChatModel.builder()
                        .modelName("qwen3.5:9b")
                        .httpTransport(transport)
                        .nativeStructuredOutput(false)
                        .nativeStructuredOutputWithTools(true)
                        .build();
        assertFalse(configured.supportsNativeStructuredOutput());
        assertFalse(configured.supportsNativeStructuredOutputWithTools());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void structuredImageResponsePreservesJsonAndFinalUsage(boolean streaming) {
        HttpTransport transport = mock(HttpTransport.class);
        if (streaming) {
            when(transport.stream(any(HttpRequest.class)))
                    .thenReturn(
                            Flux.just(
                                    frame(
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "thinking",
                                                    "Inspecting the image."),
                                            false),
                                    frame(
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "content",
                                                    JSON.substring(0, 23)),
                                            false),
                                    frame(
                                            Map.of(
                                                    "role",
                                                    "assistant",
                                                    "content",
                                                    JSON.substring(23)),
                                            false),
                                    frame(Map.of("role", "assistant", "content", ""), true)));
        } else {
            when(transport.execute(any(HttpRequest.class)))
                    .thenReturn(
                            HttpResponse.builder()
                                    .statusCode(200)
                                    .body(frame(Map.of("role", "assistant", "content", JSON), true))
                                    .build());
        }

        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3.5:9b").stream(streaming)
                        .httpTransport(transport)
                        .defaultOptions(OllamaOptions.builder().format("json").build())
                        .build();
        Msg input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Describe this image").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .mediaType("image/png")
                                                                .data(IMAGE)
                                                                .build())
                                                .build()))
                        .build();
        try (ReActAgent agent = ReActAgent.builder().name("vision").model(model).build()) {
            Msg result =
                    agent.call(List.of(input), ImageDescription.class)
                            .block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertEquals("apple", result.getStructuredData(ImageDescription.class).name);
            assertEquals(JSON, result.getTextContent());
            assertNotNull(result.getUsage());
            assertEquals(23, result.getUsage().getInputTokens());
            assertEquals(12, result.getUsage().getOutputTokens());
            if (streaming) {
                assertEquals(
                        "Inspecting the image.",
                        result.getContentBlocks(ThinkingBlock.class).get(0).getThinking());
            }
        }

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        if (streaming) {
            verify(transport).stream(captor.capture());
        } else {
            verify(transport).execute(captor.capture());
        }
        JsonNode request =
                JsonUtils.getJsonCodec().fromJson(captor.getValue().getBody(), JsonNode.class);
        assertEquals("object", request.path("format").path("type").asText());
        assertTrue(request.path("format").path("properties").has("name"));
        assertTrue(request.path("format").path("properties").has("description"));
        assertFalse(request.path("format").has("json_schema"));
        assertFalse(request.has("tools"));
        assertTrue(
                request.path("messages").findValues("images").stream()
                        .anyMatch(images -> images.get(0).asText().equals(IMAGE)));
    }

    @Test
    void businessToolsCanOptOutOfNativeStructuredOutput() {
        HttpTransport transport = mock(HttpTransport.class);
        Map<String, Object> call =
                Map.of(
                        "function",
                        Map.of(
                                "name",
                                "generate_response",
                                "arguments",
                                Map.of(
                                        "response",
                                        Map.of(
                                                "name",
                                                "apple",
                                                "description",
                                                List.of("a red apple")))));
        when(transport.execute(any(HttpRequest.class)))
                .thenReturn(
                        HttpResponse.builder()
                                .statusCode(200)
                                .body(
                                        frame(
                                                Map.of(
                                                        "role",
                                                        "assistant",
                                                        "content",
                                                        "",
                                                        "tool_calls",
                                                        List.of(call)),
                                                true))
                                .build());
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3.5:9b").stream(false)
                        .httpTransport(transport)
                        .nativeStructuredOutputWithTools(false)
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder().name("tools").model(model).toolkit(toolkit).build()) {
            Msg result =
                    agent.call("Describe an image", ImageDescription.class)
                            .block(Duration.ofSeconds(10));
            assertNotNull(result);
            assertEquals("apple", result.getStructuredData(ImageDescription.class).name);
            assertEquals(12, result.getUsage().getOutputTokens());
        }

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).execute(captor.capture());
        JsonNode request =
                JsonUtils.getJsonCodec().fromJson(captor.getValue().getBody(), JsonNode.class);
        assertFalse(request.has("format"));
        List<JsonNode> names = request.path("tools").findValues("name");
        assertTrue(names.stream().anyMatch(name -> "generate_response".equals(name.asText())));
        assertTrue(names.stream().anyMatch(name -> "describe".equals(name.asText())));
    }

    public static class Weather {
        public String city;
        public double temperature;
    }

    public static class WeatherTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "get_weather", description = "Get a city's current temperature")
        public String weather(@ToolParam(name = "city", description = "City name") String city) {
            calls.incrementAndGet();
            return JsonUtils.getJsonCodec()
                    .toJson(
                            Map.of(
                                    "city",
                                    city,
                                    "temperature",
                                    "Paris".equals(city) ? 17.43 : 18.43));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,1", "false,1", "true,2", "false,2"})
    void toolsRunBeforeStructuredFormatting(boolean streaming, int toolRounds) {
        HttpTransport transport = mock(HttpTransport.class);
        WeatherTools tools = new WeatherTools();
        List<JsonNode> requests = new ArrayList<>();
        respond(
                transport,
                streaming,
                request -> {
                    requests.add(request);
                    if (!request.has("format")) {
                        assertTrue(request.has("tools"));
                        assertEquals("auto", request.path("tool_choice").asText());
                        if (tools.calls.get() < toolRounds) {
                            String city = tools.calls.get() == 0 ? "Paris" : "Berlin";
                            return Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    "Looking it up.",
                                    "tool_calls",
                                    List.of(
                                            Map.of(
                                                    "function",
                                                    Map.of(
                                                            "name",
                                                            "get_weather",
                                                            "arguments",
                                                            Map.of("city", city)))));
                        }
                        return Map.of(
                                "role",
                                "assistant",
                                "content",
                                "Internal weather draft.",
                                "thinking",
                                "Internal reasoning.");
                    }
                    assertEquals(toolRounds, tools.calls.get());
                    assertFalse(request.has("tools"));
                    assertFalse(request.has("tool_choice"));
                    assertEquals("object", request.path("format").path("type").asText());
                    assertTrue(
                            request.path("messages")
                                    .toString()
                                    .contains("Internal weather draft."));
                    // Derive the final JSON from the actual Java tool result in the request
                    // history.
                    String reading = null;
                    for (JsonNode message : request.path("messages")) {
                        if ("tool".equals(message.path("role").asText())) {
                            reading = message.path("content").asText();
                        }
                    }
                    assertNotNull(reading);
                    JsonNode toolResult =
                            JsonUtils.getJsonCodec().fromJson(reading, JsonNode.class);
                    return Map.of(
                            "role",
                            "assistant",
                            "content",
                            toolResult.isTextual() ? toolResult.asText() : reading);
                });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").stream(streaming)
                        .httpTransport(transport)
                        .defaultOptions(OllamaOptions.builder().format("json").build())
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("weather")
                        .model(model)
                        .toolkit(toolkit)
                        .generateOptions(
                                GenerateOptions.builder().toolChoice(new ToolChoice.Auto()).build())
                        .build()) {
            Msg result =
                    agent.call("Look up the current temperatures", Weather.class)
                            .block(Duration.ofSeconds(10));
            assertNotNull(result);
            Weather weather = result.getStructuredData(Weather.class);
            assertEquals(toolRounds == 1 ? "Paris" : "Berlin", weather.city);
            assertEquals(toolRounds == 1 ? 17.43 : 18.43, weather.temperature);
            assertFalse(result.getTextContent().contains("draft"));
            assertTrue(result.getContentBlocks(ThinkingBlock.class).isEmpty());
            assertEquals(46, result.getUsage().getInputTokens());
            assertEquals(24, result.getUsage().getOutputTokens());
        }
        assertEquals(toolRounds, tools.calls.get());
        assertEquals(toolRounds + 2, requests.size());
        assertTrue(
                requests.subList(0, requests.size() - 1).stream()
                        .allMatch(r -> !r.has("format") && r.has("tools")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unusedToolsStillProduceOnlyFinalJson(boolean streaming) {
        HttpTransport transport = mock(HttpTransport.class);
        List<JsonNode> requests = new ArrayList<>();
        respond(
                transport,
                streaming,
                request -> {
                    requests.add(request);
                    return Map.of(
                            "role",
                            "assistant",
                            "content",
                            request.has("format") ? JSON : "Draft.");
                });
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").stream(streaming)
                        .httpTransport(transport)
                        .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
        try (ReActAgent agent =
                ReActAgent.builder().name("unused").model(model).toolkit(toolkit).build()) {
            Msg result =
                    agent.call("Describe from memory", ImageDescription.class)
                            .block(Duration.ofSeconds(10));
            assertEquals(JSON, result.getTextContent());
            assertEquals("apple", result.getStructuredData(ImageDescription.class).name);
            assertEquals(46, result.getUsage().getInputTokens());
        }
        assertEquals(2, requests.size());
        assertFalse(requests.get(0).has("format"));
        assertFalse(requests.get(1).has("tools"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void formattingFailureDoesNotReplayExecutedTools(boolean streaming) {
        HttpTransport transport = mock(HttpTransport.class);
        WeatherTools tools = new WeatherTools();
        AtomicInteger requests = new AtomicInteger();
        respond(
                transport,
                streaming,
                request -> {
                    requests.incrementAndGet();
                    if (request.has("format")) {
                        throw new IllegalStateException("Final formatting failed");
                    }
                    if (tools.calls.get() == 0) {
                        return Map.of(
                                "role",
                                "assistant",
                                "tool_calls",
                                List.of(
                                        Map.of(
                                                "function",
                                                Map.of(
                                                        "name",
                                                        "get_weather",
                                                        "arguments",
                                                        Map.of("city", "Paris")))));
                    }
                    return Map.of("role", "assistant", "content", "The reading is 17.43.");
                });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").stream(streaming)
                        .httpTransport(transport)
                        .defaultOptions(
                                OllamaOptions.builder()
                                        .executionConfig(
                                                io.agentscope.core.model.ExecutionConfig.builder()
                                                        .maxAttempts(1)
                                                        .build())
                                        .build())
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder().name("failed").model(model).toolkit(toolkit).build()) {
            assertThrows(
                    RuntimeException.class,
                    () -> agent.call("Look up Paris", Weather.class).block(Duration.ofSeconds(10)));
        }
        assertEquals(1, tools.calls.get());
        assertEquals(3, requests.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retryableFormattingFailureRetriesOnlyFinalRequest(boolean streaming) {
        HttpTransport transport = mock(HttpTransport.class);
        WeatherTools tools = new WeatherTools();
        AtomicInteger toolRequests = new AtomicInteger();
        AtomicInteger formatRequests = new AtomicInteger();
        respond(
                transport,
                streaming,
                request -> {
                    if (!request.has("format")) {
                        toolRequests.incrementAndGet();
                        assertTrue(request.has("tools"));
                        if (tools.calls.get() == 0) {
                            return Map.of(
                                    "role",
                                    "assistant",
                                    "tool_calls",
                                    List.of(
                                            Map.of(
                                                    "function",
                                                    Map.of(
                                                            "name",
                                                            "get_weather",
                                                            "arguments",
                                                            Map.of("city", "Paris")))));
                        }
                        return Map.of("role", "assistant", "content", "Internal weather draft.");
                    }

                    assertFalse(request.has("tools"));
                    assertFalse(request.has("tool_choice"));
                    if (formatRequests.incrementAndGet() == 1) {
                        throw new HttpTransportException("Temporary formatting failure", 503, "");
                    }
                    String reading = null;
                    for (JsonNode message : request.path("messages")) {
                        if ("tool".equals(message.path("role").asText())) {
                            reading = message.path("content").asText();
                        }
                    }
                    assertNotNull(reading);
                    JsonNode toolResult =
                            JsonUtils.getJsonCodec().fromJson(reading, JsonNode.class);
                    return Map.of(
                            "role",
                            "assistant",
                            "content",
                            toolResult.isTextual() ? toolResult.asText() : reading);
                });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").stream(streaming)
                        .httpTransport(transport)
                        .defaultOptions(
                                OllamaOptions.builder()
                                        .executionConfig(
                                                ExecutionConfig.builder()
                                                        .maxAttempts(2)
                                                        .initialBackoff(Duration.ofMillis(1))
                                                        .maxBackoff(Duration.ofMillis(1))
                                                        .build())
                                        .build())
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder().name("retry").model(model).toolkit(toolkit).build()) {
            Msg result = agent.call("Look up Paris", Weather.class).block(Duration.ofSeconds(10));
            assertNotNull(result);
            Weather weather = result.getStructuredData(Weather.class);
            assertEquals("Paris", weather.city);
            assertEquals(17.43, weather.temperature);
            assertEquals(46, result.getUsage().getInputTokens());
            assertEquals(24, result.getUsage().getOutputTokens());
        }
        assertEquals(1, tools.calls.get());
        assertEquals(2, toolRequests.get());
        assertEquals(2, formatRequests.get());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void emptyResponseFailsWithoutSyntheticReplay(boolean streaming, boolean finalPhase) {
        HttpTransport transport = mock(HttpTransport.class);
        AtomicInteger requests = new AtomicInteger();
        respond(
                transport,
                streaming,
                request -> {
                    requests.incrementAndGet();
                    return Map.of(
                            "role",
                            "assistant",
                            "content",
                            finalPhase && !request.has("format") ? "Draft." : "");
                });
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").stream(streaming)
                        .httpTransport(transport)
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder().name("empty").model(model).toolkit(toolkit).build()) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            agent.call("Describe", ImageDescription.class)
                                    .block(Duration.ofSeconds(10)));
        }
        assertEquals(finalPhase ? 2 : 1, requests.get());
    }

    @Test
    void cancellationDuringToolDecisionDoesNotStartFormatting() {
        HttpTransport transport = mock(HttpTransport.class);
        AtomicInteger requests = new AtomicInteger();
        when(transport.stream(any(HttpRequest.class)))
                .thenAnswer(
                        invocation -> {
                            requests.incrementAndGet();
                            return Flux.never();
                        });
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3:0.6b").httpTransport(transport).build();
        StepVerifier.create(
                        model.stream(
                                List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build()),
                                List.of(
                                        ToolSchema.builder()
                                                .name("describe")
                                                .description("Describe")
                                                .parameters(Map.of())
                                                .build()),
                                GenerateOptions.builder()
                                        .responseFormat(
                                                ResponseFormat.jsonSchema(
                                                        JsonSchema.builder()
                                                                .name("answer")
                                                                .schema(Map.of("type", "object"))
                                                                .build()))
                                        .build()))
                .verifyTimeout(Duration.ofMillis(100));
        assertEquals(1, requests.get());
    }

    private static void respond(
            HttpTransport transport,
            boolean streaming,
            Function<JsonNode, Map<String, Object>> responder) {
        if (streaming) {
            when(transport.stream(any(HttpRequest.class)))
                    .thenAnswer(
                            invocation ->
                                    Flux.defer(
                                            () -> {
                                                HttpRequest request = invocation.getArgument(0);
                                                Map<String, Object> message =
                                                        responder.apply(
                                                                JsonUtils.getJsonCodec()
                                                                        .fromJson(
                                                                                request.getBody(),
                                                                                JsonNode.class));
                                                return Flux.just(
                                                        frame(message, false),
                                                        frame(
                                                                Map.of(
                                                                        "role",
                                                                        "assistant",
                                                                        "content",
                                                                        ""),
                                                                true));
                                            }));
        } else {
            when(transport.execute(any(HttpRequest.class)))
                    .thenAnswer(
                            invocation -> {
                                HttpRequest request = invocation.getArgument(0);
                                Map<String, Object> message =
                                        responder.apply(
                                                JsonUtils.getJsonCodec()
                                                        .fromJson(
                                                                request.getBody(), JsonNode.class));
                                return HttpResponse.builder()
                                        .statusCode(200)
                                        .body(frame(message, true))
                                        .build();
                            });
        }
    }

    private static String frame(Map<String, Object> message, boolean done) {
        return JsonUtils.getJsonCodec()
                .toJson(
                        Map.of(
                                "model",
                                "qwen3.5:9b",
                                "message",
                                message,
                                "done",
                                done,
                                "prompt_eval_count",
                                done ? 23 : 0,
                                "eval_count",
                                done ? 12 : 0));
    }
}
