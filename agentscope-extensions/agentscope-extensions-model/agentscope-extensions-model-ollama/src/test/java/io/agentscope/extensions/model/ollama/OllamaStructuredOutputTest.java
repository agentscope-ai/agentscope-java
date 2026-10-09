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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

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
    void constructorsDeclareNativeOutputWithoutTools() {
        HttpTransport transport = mock(HttpTransport.class);
        OllamaChatModel model =
                new OllamaChatModel("qwen3.5:9b", "http://localhost:11434", null, null, transport);
        assertTrue(model.supportsNativeStructuredOutput());
        assertFalse(model.supportsNativeStructuredOutputWithTools());
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
        // Per-call schema must replace a default JSON-only format.
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
            ImageDescription data = result.getStructuredData(ImageDescription.class);
            assertEquals("apple", data.name);
            assertEquals(List.of("a red apple"), data.description);
            assertEquals(JSON, result.getTextContent());
            assertNotNull(result.getUsage());
            assertEquals(23, result.getUsage().getInputTokens());
            assertEquals(12, result.getUsage().getOutputTokens());
            assertEquals(true, result.getMetadata().get("done"));
            assertEquals("qwen3.5:9b", result.getMetadata().get("model"));
            if (streaming) {
                assertEquals(
                        "Inspecting the image.",
                        result.getContentBlocks(ThinkingBlock.class).get(0).getThinking());
            }
        }
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        if (streaming) verify(transport).stream(captor.capture());
        else verify(transport).execute(captor.capture());
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
    void structuredOutputWithBusinessToolsRetainsSyntheticToolPath() {
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
        when(transport.stream(any(HttpRequest.class)))
                .thenReturn(
                        Flux.just(
                                frame(
                                        Map.of(
                                                "role",
                                                "assistant",
                                                "content",
                                                "",
                                                "tool_calls",
                                                List.of(call)),
                                        false),
                                frame(Map.of("role", "assistant", "content", ""), true)));
        OllamaChatModel model =
                OllamaChatModel.builder().modelName("qwen3.5:9b").httpTransport(transport).build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Tools());
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
        verify(transport).stream(captor.capture());
        JsonNode request =
                JsonUtils.getJsonCodec().fromJson(captor.getValue().getBody(), JsonNode.class);
        assertFalse(request.has("format"));
        List<JsonNode> names = request.path("tools").findValues("name");
        assertTrue(names.stream().anyMatch(n -> "generate_response".equals(n.asText())));
        assertTrue(names.stream().anyMatch(n -> "describe".equals(n.asText())));
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
