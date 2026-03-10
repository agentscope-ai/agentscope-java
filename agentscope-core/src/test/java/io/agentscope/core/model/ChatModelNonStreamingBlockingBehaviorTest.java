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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ollama.OllamaOptions;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Test to verify the blocking behavior of ChatModel implementations in non-streaming mode.
 */
@Tag("integration")
@DisplayName("ChatModel Non-Streaming Blocking Behavior Tests")
class ChatModelNonStreamingBlockingBehaviorTest {

    private MockWebServer mockServer;
    private static final int RESPONSE_DELAY_MS = 500;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("DashScopeChatModel - Should be NON-BLOCKING in non-streaming mode")
    void testDashScopeChatModelNonBlocking() throws Exception {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody("{\"request_id\":\"test\",\"output\":{\"choices\":[]}}")
                        .setHeader("Content-Type", "application/json"));

        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey("test-key").modelName("qwen-max").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        CountDownLatch latch = new CountDownLatch(1);
        String currentThreadName = Thread.currentThread().getName();
        AtomicReference<String> streamThreadName = new AtomicReference<>();
        model.stream(messages, null, null)
                .subscribe(
                        response -> {
                            streamThreadName.set(Thread.currentThread().getName());
                            latch.countDown();
                        },
                        error -> latch.countDown());
        latch.await(3, TimeUnit.SECONDS);
        assertNotNull(streamThreadName.get());
        assertNotEquals(
                currentThreadName,
                streamThreadName.get(),
                "DashScopeChatModel should be NON-BLOCKING");
    }

    @Test
    @DisplayName("OpenAIChatModel - Should be NON-BLOCKING in non-streaming mode")
    void testOpenAIChatModelNonBlocking() throws Exception {
        // Setup mock response with delay
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
                            "content": "Hello!"
                        },
                        "finish_reason": "stop"
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                    }
                }
                """;

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json")
                        .setBodyDelay(RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS));

        OpenAIChatModel model =
                OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(false)
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        CountDownLatch latch = new CountDownLatch(1);
        String currentThreadName = Thread.currentThread().getName();
        AtomicReference<String> streamThreadName = new AtomicReference<>();

        model.stream(messages, null, null)
                .subscribe(
                        response -> {
                            streamThreadName.set(Thread.currentThread().getName());
                            latch.countDown();
                        },
                        error -> latch.countDown());

        latch.await(3, TimeUnit.SECONDS);
        assertNotNull(streamThreadName.get());
        assertNotEquals(
                currentThreadName,
                streamThreadName.get(),
                "OpenAIChatModel should be NON-BLOCKING");
    }

    @Test
    @DisplayName("OllamaChatModel - Should be NON-BLOCKING in non-streaming mode")
    void testOllamaChatModelNonBlocking() throws Exception {
        // Setup mock response with delay
        String responseJson =
                "{\"model\":\"qwen2.5:14b-instruct\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"done\":true}";

        mockServer.enqueue(
                new MockResponse()
                        .setBody(responseJson)
                        .setHeader("Content-Type", "application/json")
                        .setBodyDelay(RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS));

        OllamaChatModel model =
                OllamaChatModel.builder()
                        .modelName("qwen2.5:14b-instruct")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build());

        CountDownLatch latch = new CountDownLatch(1);
        String currentThreadName = Thread.currentThread().getName();
        AtomicReference<String> streamThreadName = new AtomicReference<>();

        OllamaOptions options =
                OllamaOptions.builder()
                        .executionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                        .build();

        model.stream(messages, null, options.toGenerateOptions())
                .subscribe(
                        response -> {
                            streamThreadName.set(Thread.currentThread().getName());
                            latch.countDown();
                        },
                        error -> latch.countDown());

        latch.await(3, TimeUnit.SECONDS);
        assertNotNull(streamThreadName.get());
        assertNotEquals(
                currentThreadName,
                streamThreadName.get(),
                "OllamaChatModel should be NON-BLOCKING");
    }
}
