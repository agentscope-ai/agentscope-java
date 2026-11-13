/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Hooks;

/** Regression tests for cancellation of blocking OpenAI HTTP requests. */
class OpenAIChatModelCancellationTest {

    private MockWebServer server;
    private JdkHttpTransport transport;
    private final Queue<Throwable> droppedErrors = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        transport =
                JdkHttpTransport.builder()
                        .config(
                                HttpTransportConfig.builder()
                                        .readTimeout(Duration.ofSeconds(10))
                                        .build())
                        .build();
        Hooks.onErrorDropped(droppedErrors::add);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            transport.close();
            server.shutdown();
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    @Test
    void timeoutRetriesSuccessfullyWithoutDroppingCancellationError() {
        server.enqueue(successResponse().setBodyDelay(3, TimeUnit.SECONDS));
        server.enqueue(successResponse());

        List<ChatResponse> responses =
                model().stream(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("hello")
                                                .build()),
                                null,
                                GenerateOptions.builder()
                                        .executionConfig(
                                                ExecutionConfig.builder()
                                                        .timeout(Duration.ofSeconds(1))
                                                        .maxAttempts(2)
                                                        .initialBackoff(Duration.ofMillis(100))
                                                        .maxBackoff(Duration.ofMillis(100))
                                                        .build())
                                        .build())
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertEquals(2, server.getRequestCount(), "The timed-out request should be retried");
        assertEquals(1, responses.size());
        assertEquals("hello", ((TextBlock) responses.get(0).getContent().get(0)).getText());
        assertTrue(droppedErrors.isEmpty(), () -> "Unexpected dropped errors: " + droppedErrors);
    }

    private OpenAIChatModel model() {
        return OpenAIChatModel.builder()
                .apiKey("test-key")
                .modelName("test-model")
                .baseUrl(server.url("/").toString())
                .stream(false)
                .httpTransport(transport)
                .build();
    }

    private static MockResponse successResponse() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                        """
                        {"id":"test-response","object":"chat.completion","model":"test-model",
                        "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},
                        "finish_reason":"stop"}]}
                        """);
    }
}
