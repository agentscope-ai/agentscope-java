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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Integration tests for OpenAIChatModel.
 *
 * <p>These tests use MockWebServer to simulate OpenAI API responses and verify:
 * <ul>
 *   <li>Non-streaming chat completion</li>
 *   <li>Streaming chat completion</li>
 *   <li>Tool calling support</li>
 *   <li>Error handling</li>
 *   <li>Options application</li>
 * </ul>
 */
@Tag("integration")
@DisplayName("OpenAIChatModel Integration Tests")
class OpenAIChatModelTest {

    private MockWebServer mockServer;
    private OpenAIChatModel model;
    private HttpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString();
        // Remove trailing slash
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        transport = HttpTransportFactory.getDefault();
        model =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(false)
                        .baseUrl(baseUrl)
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
        // Stateless model doesn't need close()
    }

    @Test
    @DisplayName("Should make non-streaming call successfully")
    void testNonStreamingCall() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! How can I help you?"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 20,
                        "total_tokens": 30
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        StepVerifier.create(model.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                            assertEquals(
                                    "Hello! How can I help you?",
                                    ((io.agentscope.core.message.TextBlock)
                                                    response.getContent().get(0))
                                            .getText());
                            assertNotNull(response.getUsage());
                            assertEquals(10, response.getUsage().getInputTokens());
                            assertEquals(20, response.getUsage().getOutputTokens());
                        })
                .verifyComplete();

        // Verify request
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/chat/completions", request.getPath());
        assertTrue(request.getHeader("Authorization").contains("Bearer test-api-key"));
    }

    @Test
    @DisplayName("Should handle streaming call")
    void testStreamingCall() {
        // Create streaming model
        OpenAIChatModel streamingModel =
                OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(true)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .formatter(new OpenAIChatFormatter())
                        .httpTransport(transport)
                        .build();

        String sseResponse =
                "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Say hello").build()))
                                .build());

        StepVerifier.create(streamingModel.stream(messages, null, null))
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            // First chunk might be role assignment
                        })
                .assertNext(
                        response -> {
                            assertNotNull(response);
                            assertNotNull(response.getContent());
                        })
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("Should apply generation options")
    void testApplyOptions() throws Exception {
        String responseJson =
                """
                {
                    "id": "chatcmpl-123",
                    "object": "chat.completion",
                    "created": 1677652280,
                    "model": "gpt-4",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Response"
                        },
                        "finish_reason": "stop"
                    }]
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json"));

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        GenerateOptions options =
                GenerateOptions.builder().temperature(0.7).maxTokens(1000).build();

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(response -> assertNotNull(response))
                .verifyComplete();

        // Verify request contains options
        RecordedRequest request = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"temperature\":0.7"));
        assertTrue(body.contains("\"max_tokens\":1000"));
    }

    @Test
    @DisplayName("Should return model name")
    void testGetModelName() {
        assertEquals("gpt-4", model.getModelName());
    }
}
