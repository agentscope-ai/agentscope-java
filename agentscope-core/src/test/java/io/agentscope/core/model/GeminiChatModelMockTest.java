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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("GeminiChatModel Mock Tests")
class GeminiChatModelMockTest {

    private static final String MOCK_API_KEY = "mock_api_key";
    private static final String MOCK_MODEL_NAME = "gemini-2.0-flash";

    private OkHttpClient createMockClient(Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private String getText(ChatResponse response) {
        if (response.getContent() == null) {
            return "";
        }
        return response.getContent().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    @Test
    @DisplayName("Should handle successful unary response")
    void testUnaryResponse() {
        String jsonResponse =
                "{\n"
                        + "  \"candidates\": [\n"
                        + "    {\n"
                        + "      \"content\": {\n"
                        + "        \"parts\": [\n"
                        + "          {\n"
                        + "            \"text\": \"Hello, world!\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"role\": \"model\"\n"
                        + "      },\n"
                        + "      \"finishReason\": \"STOP\",\n"
                        + "      \"index\": 0\n"
                        + "    }\n"
                        + "  ],\n"
                        + "  \"usageMetadata\": {\n"
                        + "    \"promptTokenCount\": 10,\n"
                        + "    \"candidatesTokenCount\": 5,\n"
                        + "    \"totalTokenCount\": 15\n"
                        + "  }\n"
                        + "}";

        Interceptor interceptor =
                chain ->
                        new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                        ResponseBody.create(
                                                jsonResponse, MediaType.get("application/json")))
                                .build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(MOCK_API_KEY)
                        .modelName(MOCK_MODEL_NAME)
                        .streamEnabled(false)
                        .httpClient(createMockClient(interceptor))
                        .build();

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("Hello").build());
        Flux<ChatResponse> responseFlux = model.stream(messages, null, null);

        StepVerifier.create(responseFlux)
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertEquals("Hello, world!", getText(response));
                            assertEquals(10, response.getUsage().getInputTokens());
                            assertEquals(5, response.getUsage().getOutputTokens());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle successful streaming response")
    void testStreamResponse() {
        String chunk1 =
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hello\"}]},"
                        + " \"finishReason\": null}]}\n\n";
        String chunk2 =
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \", world\"}]},"
                        + " \"finishReason\": null}]}\n\n";
        String chunk3 =
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"!\"}]},"
                        + " \"finishReason\": \"STOP\"}]}\n\n";

        Interceptor interceptor =
                chain ->
                        new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                        ResponseBody.create(
                                                chunk1 + chunk2 + chunk3,
                                                MediaType.get("text/event-stream")))
                                .build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(MOCK_API_KEY)
                        .modelName(MOCK_MODEL_NAME)
                        .streamEnabled(true)
                        .httpClient(createMockClient(interceptor))
                        .build();

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("Hello").build());
        Flux<ChatResponse> responseFlux = model.stream(messages, null, null);

        StepVerifier.create(responseFlux)
                .assertNext(r -> assertEquals("Hello", getText(r)))
                .assertNext(r -> assertEquals(", world", getText(r)))
                .assertNext(r -> assertEquals("!", getText(r)))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle API error response")
    void testErrorResponse() {
        Interceptor interceptor =
                chain ->
                        new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(400)
                                .message("Bad Request")
                                .body(
                                        ResponseBody.create(
                                                "{\"error\": \"Invalid argument\"}",
                                                MediaType.get("application/json")))
                                .build();

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(MOCK_API_KEY)
                        .modelName(MOCK_MODEL_NAME)
                        .streamEnabled(false) // Test unary error
                        .httpClient(createMockClient(interceptor))
                        .build();

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("Hello").build());
        Flux<ChatResponse> responseFlux = model.stream(messages, null, null);

        StepVerifier.create(responseFlux)
                .expectErrorMatches(
                        throwable ->
                                throwable instanceof ModelException
                                        && throwable.getMessage().contains("Gemini API Error: 400"))
                .verify();
    }

    @Test
    @DisplayName("Should handle IOException during request")
    void testNetworkError() {
        Interceptor interceptor =
                chain -> {
                    throw new IOException("Network failure");
                };

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(MOCK_API_KEY)
                        .modelName(MOCK_MODEL_NAME)
                        .streamEnabled(false)
                        .httpClient(createMockClient(interceptor))
                        .build();

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("Hello").build());
        Flux<ChatResponse> responseFlux = model.stream(messages, null, null);

        StepVerifier.create(responseFlux)
                .expectErrorMatches(
                        t ->
                                t instanceof ModelException
                                        && t.getMessage().contains("Gemini network error"))
                .verify();
    }
}
