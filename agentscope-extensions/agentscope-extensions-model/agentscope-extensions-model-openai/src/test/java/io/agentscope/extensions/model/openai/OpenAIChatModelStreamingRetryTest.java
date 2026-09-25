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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Regression tests for streaming retry behavior in {@link OpenAIChatModel}.
 *
 * <p>Verifies that when a streaming request receives a retryable HTTP error (e.g. 429),
 * {@code retryWhen} re-subscribes to a fresh publisher that re-issues the HTTP request,
 * rather than replaying the same failed stream. The bug primarily manifests with
 * {@link JdkHttpTransport}, whose stream() is backed by a one-shot
 * {@link java.util.concurrent.CompletableFuture}.
 */
class OpenAIChatModelStreamingRetryTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofMillis(10);

    private MockWebServer mockServer;

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
    @DisplayName("Streaming 429 exhausts retries and re-issues HTTP request each time")
    void streaming429ExhaustsRetriesAndReissuesEachRequest() {
        AtomicInteger requestCount = startAlways429Server();

        OpenAIChatModel model = buildStreamingModel(defaultTransport());

        StepVerifier.create(model.stream(List.of(singleUserMessage()), null, retryOptions()))
                .expectErrorSatisfies(e -> assertInstanceOf(OpenAIException.class, rootCause(e)))
                .verify(Duration.ofSeconds(10));

        assertEquals(
                MAX_ATTEMPTS, requestCount.get(), "Each retry should re-issue the HTTP request");
    }

    @Test
    @DisplayName("Streaming 429 retries then succeeds on subsequent attempt")
    void streaming429RetriesThenSucceeds() {
        AtomicInteger requestCount = new AtomicInteger(0);
        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        int attempt = requestCount.incrementAndGet();
                        return attempt < MAX_ATTEMPTS
                                ? rateLimitResponse()
                                : successStreamResponse("hello");
                    }
                });

        OpenAIChatModel model = buildStreamingModel(defaultTransport());

        List<ChatResponse> responses =
                model.stream(List.of(singleUserMessage()), null, retryOptions())
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertEquals(MAX_ATTEMPTS, requestCount.get());
        assertTrue(responses != null && !responses.isEmpty(), "Expected at least one response");
        assertEquals("hello", firstText(responses.get(0)));
    }

    @Test
    @DisplayName("Streaming 400 does not retry")
    void streaming400DoesNotRetry() {
        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(400)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"error\":{\"message\":\"bad request\"}}"));

        OpenAIChatModel model = buildStreamingModel(defaultTransport());

        StepVerifier.create(model.stream(List.of(singleUserMessage()), null, retryOptions()))
                .expectErrorSatisfies(e -> assertInstanceOf(OpenAIException.class, rootCause(e)))
                .verify(Duration.ofSeconds(5));

        assertEquals(1, mockServer.getRequestCount(), "400 errors must not be retried");
    }

    @Test
    @DisplayName("Streaming 429 with JdkHttpTransport retries and re-issues HTTP requests")
    void streaming429WithJdkHttpTransportRetries() {
        AtomicInteger requestCount = startAlways429Server();

        OpenAIChatModel model =
                buildStreamingModel(
                        JdkHttpTransport.builder()
                                .config(
                                        HttpTransportConfig.builder()
                                                .readTimeout(Duration.ofSeconds(5))
                                                .build())
                                .build());

        StepVerifier.create(model.stream(List.of(singleUserMessage()), null, retryOptions()))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(
                MAX_ATTEMPTS,
                requestCount.get(),
                "JdkHttpTransport streaming retry should re-issue HTTP request on each attempt");
    }

    @Test
    @DisplayName("Streaming 429 with OkHttpTransport retries and re-issues HTTP requests")
    void streaming429WithOkHttpTransportRetries() {
        AtomicInteger requestCount = startAlways429Server();

        OpenAIChatModel model =
                buildStreamingModel(
                        OkHttpTransport.builder()
                                .config(
                                        HttpTransportConfig.builder()
                                                .readTimeout(Duration.ofSeconds(5))
                                                .build())
                                .build());

        StepVerifier.create(model.stream(List.of(singleUserMessage()), null, retryOptions()))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(
                MAX_ATTEMPTS,
                requestCount.get(),
                "OkHttpTransport streaming retry should re-issue HTTP request on each attempt");
    }

    @Test
    @DisplayName("Non-streaming 429 retries and re-issues HTTP requests")
    void nonStreaming429Retries() {
        AtomicInteger requestCount = startAlways429Server();

        OpenAIChatModel model =
                buildModel(
                        false,
                        OkHttpTransport.builder()
                                .config(
                                        HttpTransportConfig.builder()
                                                .readTimeout(Duration.ofSeconds(5))
                                                .build())
                                .build());

        StepVerifier.create(model.stream(List.of(singleUserMessage()), null, retryOptions(false)))
                .expectError()
                .verify(Duration.ofSeconds(10));

        assertEquals(
                MAX_ATTEMPTS,
                requestCount.get(),
                "Non-streaming retry should re-issue HTTP request on each attempt");
    }

    // ==================== helpers ====================

    private HttpTransport defaultTransport() {
        HttpTransportFactory.shutdown();
        return HttpTransportFactory.getDefault();
    }

    private AtomicInteger startAlways429Server() {
        AtomicInteger requestCount = new AtomicInteger(0);
        mockServer.setDispatcher(
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        requestCount.incrementAndGet();
                        return rateLimitResponse();
                    }
                });
        return requestCount;
    }

    private OpenAIChatModel buildStreamingModel(HttpTransport transport) {
        return buildModel(true, transport);
    }

    private OpenAIChatModel buildModel(boolean stream, HttpTransport transport) {
        String baseUrl = mockServer.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        return OpenAIChatModel.builder().apiKey("test-key").modelName("gpt-4").stream(stream)
                .baseUrl(baseUrl)
                .formatter(new OpenAIChatFormatter())
                .httpTransport(transport)
                .build();
    }

    private GenerateOptions retryOptions() {
        return retryOptions(true);
    }

    private GenerateOptions retryOptions(boolean stream) {
        return GenerateOptions.builder()
                .executionConfig(
                        ExecutionConfig.builder()
                                .maxAttempts(MAX_ATTEMPTS)
                                .initialBackoff(BACKOFF)
                                .maxBackoff(Duration.ofMillis(50))
                                .build())
                .stream(stream)
                .build();
    }

    private Msg singleUserMessage() {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("hello").build())
                .build();
    }

    private static MockResponse rateLimitResponse() {
        return new MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(
                        "{\"error\":{\"message\":\"rate"
                                + " limit\",\"type\":\"rate_limit_exceeded\"}}");
    }

    private static MockResponse successStreamResponse(String content) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseChunk(content) + sseDone());
    }

    private static String sseChunk(String content) {
        return "data: {\"object\":\"chat.completion.chunk\",\"choices\":[{\"delta\":{\"content\":\""
                + content
                + "\"},\"finish_reason\":null}]}\n\n";
    }

    private static String sseDone() {
        return "data: [DONE]\n\n";
    }

    private static String firstText(ChatResponse response) {
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .findFirst()
                .orElse("");
    }

    /**
     * Walks the cause chain to the innermost non-null cause. Reactor's retryWhen wraps the
     * final failure in a RetryExhaustedException, so assertions on the root cause are more
     * stable than matching the wrapper.
     */
    private static Throwable rootCause(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }
}
