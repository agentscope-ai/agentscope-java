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
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAIResponse;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tests that the OpenAI streaming response is terminated deterministically by the SSE {@code
 * [DONE]} sentinel, and that a stream that ends without ever proving it finished fails instead of
 * being accepted as a complete answer.
 *
 * <p>The first case is the regression test for {@code [DONE]} being merely filtered out instead of
 * completing the {@link Flux}: against an OpenAI-compatible gateway that keeps the connection open
 * (HTTP keep-alive) after emitting {@code [DONE]}, the stream would only complete when the socket
 * was eventually closed by an idle timeout, stalling the ReAct turn for up to a minute.
 *
 * <p>The other cases are the regression test for a connection closed mid-generation: that produces
 * {@code onComplete}, not {@code onError}, so the truncated text used to be persisted and shown as
 * if the model had finished normally.
 */
@Tag("unit")
@DisplayName("OpenAI Stream Termination Tests")
class OpenAIStreamTerminationTest {

    private static final String CHUNK_CONTENT =
            "{\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"hello\"},\"index\":0}]}";
    private static final String CHUNK_CONTENT_MORE =
            "{\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\" world\"},\"index\":0}]}";
    private static final String CHUNK_FINISH =
            "{\"id\":\"1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}";

    /**
     * A transport that emits two content chunks and the {@code [DONE]} sentinel, then never
     * completes ({@link Flux#never()}) — simulating a gateway that holds the connection open
     * after {@code [DONE]}.
     */
    private static HttpTransport keepAliveTransportAfterDone() {
        return new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.just(CHUNK_CONTENT, CHUNK_FINISH, "[DONE]").concatWith(Flux.never());
            }

            @Override
            public void close() {}
        };
    }

    /**
     * A transport that emits the given SSE lines and then completes cleanly — simulating a
     * connection closed by the provider or by a gateway in front of it.
     */
    private static HttpTransport transportEmitting(List<String> sseLines) {
        return new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public Flux<String> stream(HttpRequest request) {
                return Flux.fromIterable(sseLines);
            }

            @Override
            public void close() {}
        };
    }

    private static OpenAIRequest sampleRequest() {
        return OpenAIRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(OpenAIMessage.builder().role("user").content("hi").build()))
                .build();
    }

    private static Flux<OpenAIResponse> streamFrom(OpenAIClient client) {
        return client.stream(
                "test-key", "http://localhost", sampleRequest(), GenerateOptions.builder().build());
    }

    @Test
    @DisplayName("Stream should complete on [DONE] even if the connection stays open")
    void streamCompletesOnDoneSentinelWithoutWaitingForConnectionClose() {
        OpenAIClient client = new OpenAIClient(keepAliveTransportAfterDone());

        StepVerifier.create(streamFrom(client))
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Stream should fail when it ends without [DONE] and without a finish_reason")
    void streamFailsWhenConnectionClosesBeforeTheResponseCompletes() {
        OpenAIClient client =
                new OpenAIClient(transportEmitting(List.of(CHUNK_CONTENT, CHUNK_CONTENT_MORE)));

        StepVerifier.create(streamFrom(client))
                .expectNextCount(2)
                .expectErrorSatisfies(
                        error -> {
                            OpenAIException typed = assertInstanceOf(OpenAIException.class, error);
                            assertTrue(
                                    typed.getMessage().contains("ended before completion"),
                                    "unexpected message: " + typed.getMessage());
                            assertTrue(
                                    ExecutionConfig.RETRYABLE_ERRORS.test(typed),
                                    "a truncated stream must be retryable, otherwise the retry"
                                            + " machinery never sees it");
                        })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Stream should complete when finish_reason arrives without [DONE]")
    void streamCompletesWhenFinishReasonArrivesWithoutDoneSentinel() {
        OpenAIClient client =
                new OpenAIClient(transportEmitting(List.of(CHUNK_CONTENT, CHUNK_FINISH)));

        StepVerifier.create(streamFrom(client))
                .expectNextCount(2)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Stream should still complete on [DONE] when no chunk carries a finish_reason")
    void streamCompletesOnDoneSentinelWithoutFinishReason() {
        OpenAIClient client = new OpenAIClient(transportEmitting(List.of(CHUNK_CONTENT, "[DONE]")));

        StepVerifier.create(streamFrom(client))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Stream over the JDK transport should fail when the server closes mid-stream")
    void streamOverRealTransportFailsWhenTheServerClosesMidStream() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            // The body simply stops: no terminating chunk, no [DONE], and the connection is
            // closed cleanly, which is what a gateway cutting the response short looks like.
            server.enqueue(
                    new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(sseLine(CHUNK_CONTENT) + sseLine(CHUNK_CONTENT_MORE)));

            OpenAIClient client = new OpenAIClient(HttpTransportFactory.getDefault());

            StepVerifier.create(
                            client.stream(
                                    "test-key",
                                    server.url("/v1").toString(),
                                    sampleRequest(),
                                    GenerateOptions.builder().build()))
                    .expectNextCount(2)
                    .expectErrorSatisfies(error -> assertInstanceOf(OpenAIException.class, error))
                    .verify(Duration.ofSeconds(10));
        } finally {
            server.shutdown();
        }
    }

    private static String sseLine(String payload) {
        return "data: " + payload + "\n\n";
    }
}
