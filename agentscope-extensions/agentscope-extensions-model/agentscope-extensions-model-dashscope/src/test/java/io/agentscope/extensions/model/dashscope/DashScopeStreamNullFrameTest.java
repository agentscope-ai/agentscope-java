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
package io.agentscope.extensions.model.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.dashscope.DashScopeHttpClient.DashScopeHttpException;
import io.agentscope.extensions.model.dashscope.dto.DashScopeInput;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import io.agentscope.extensions.model.dashscope.dto.DashScopeParameters;
import io.agentscope.extensions.model.dashscope.dto.DashScopeRequest;
import io.agentscope.extensions.model.dashscope.dto.DashScopeResponse;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Regression tests for JSON {@code null} frames in DashScope SSE streams.
 *
 * <p>A syntactically valid {@code null} SSE data frame decodes to no response; it must be skipped
 * like malformed JSON instead of producing a {@link NullPointerException} that terminates the
 * stream.
 */
@Tag("unit")
class DashScopeStreamNullFrameTest {

    private static final String VALID_FRAME =
            "{\"request_id\":\"stream-1\",\"output\":{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}}";

    private static final String PROVIDER_ERROR_FRAME =
            "{\"request_id\":\"req-error\",\"code\":\"InvalidParameter\",\"message\":\"provider"
                    + " rejected request\"}";

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    void skipsNullFrameWithSurroundingWhitespaceBeforeValidFrame() {
        StepVerifier.create(streamWithFrames("   null   ", VALID_FRAME + " \n\t"))
                .expectNextMatches(DashScopeStreamNullFrameTest::hasHelloContent)
                .verifyComplete();
    }

    @Test
    void completesOnNullOnlyStream() {
        StepVerifier.create(streamWithFrames("null")).verifyComplete();
    }

    @Test
    void preservesLaterProviderErrorAfterNullFrame() {
        StepVerifier.create(streamWithFrames("null", PROVIDER_ERROR_FRAME))
                .expectErrorSatisfies(
                        error -> {
                            DashScopeHttpException exception =
                                    assertInstanceOf(DashScopeHttpException.class, error);
                            assertEquals("InvalidParameter", exception.getErrorCode());
                            assertEquals(
                                    "DashScope API error: provider rejected request",
                                    exception.getMessage());
                            assertTrue(exception.getResponseBody().contains("req-error"));
                        })
                .verify();
    }

    @Test
    void preservesLaterTransportFailureAfterNullFrame() {
        HttpTransportException failure =
                new HttpTransportException("connection reset", 502, "upstream body");
        StubTransport transport =
                new StubTransport(Flux.concat(Flux.just("null"), Flux.error(failure)));

        StepVerifier.create(clientWithTransport(transport).stream(testRequest(), null, null, null))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();
    }

    @Test
    void nullFrameDoesNotConsumeDownstreamDemand() {
        StepVerifier.create(streamWithFrames("null", VALID_FRAME, VALID_FRAME), 1)
                .expectNextMatches(DashScopeStreamNullFrameTest::hasHelloContent)
                .thenRequest(1)
                .expectNextMatches(DashScopeStreamNullFrameTest::hasHelloContent)
                .verifyComplete();
    }

    @Test
    void downstreamCanCancelAfterNullFrameAndValidFrame() {
        StubTransport transport =
                new StubTransport(Flux.concat(Flux.just("null", VALID_FRAME), Flux.never()));

        StepVerifier.create(clientWithTransport(transport).stream(testRequest(), null, null, null))
                .expectNextMatches(DashScopeStreamNullFrameTest::hasHelloContent)
                .thenCancel()
                .verify();
    }

    @Test
    void loopbackSseSkipsNullFrameAndKeepsLaterFrames() {
        String sseResponse =
                "data: null\n\n"
                        + "data:   null   \n\n"
                        + "data: "
                        + VALID_FRAME
                        + "\n\n"
                        + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeHttpClient client =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        StepVerifier.create(client.stream(testRequest(), null, null, null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void loopbackSsePreservesProviderErrorAfterNullFrame() {
        String sseResponse =
                "data: null\n\n" + "data: " + PROVIDER_ERROR_FRAME + "\n\n" + "data: [DONE]\n\n";

        mockServer.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setBody(sseResponse)
                        .setHeader("Content-Type", "text/event-stream"));

        DashScopeHttpClient client =
                DashScopeHttpClient.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .build();

        StepVerifier.create(client.stream(testRequest(), null, null, null))
                .expectErrorSatisfies(
                        error -> {
                            DashScopeHttpException exception =
                                    assertInstanceOf(DashScopeHttpException.class, error);
                            assertEquals("InvalidParameter", exception.getErrorCode());
                            assertTrue(exception.getResponseBody().contains("req-error"));
                        })
                .verify();
    }

    private Flux<DashScopeResponse> streamWithFrames(String... frames) {
        return clientWithTransport(new StubTransport(Flux.just(frames))).stream(
                testRequest(), null, null, null);
    }

    private DashScopeHttpClient clientWithTransport(HttpTransport transport) {
        return DashScopeHttpClient.builder()
                .transport(transport)
                .apiKey("test-api-key")
                .baseUrl("http://localhost:1")
                .build();
    }

    private static boolean hasHelloContent(DashScopeResponse response) {
        return "Hello"
                .equals(response.getOutput().getFirstChoice().getMessage().getContentAsString());
    }

    private DashScopeRequest testRequest() {
        DashScopeMessage message = DashScopeMessage.builder().role("user").content("Hi").build();
        DashScopeParameters parameters =
                DashScopeParameters.builder()
                        .resultFormat("message")
                        .incrementalOutput(false)
                        .build();
        return DashScopeRequest.builder()
                .model("qwen-plus")
                .input(DashScopeInput.of(List.of(message)))
                .parameters(parameters)
                .build();
    }

    private static final class StubTransport implements HttpTransport {

        private final Flux<String> frames;

        private StubTransport(Flux<String> frames) {
            this.frames = frames;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("execute is not used by streaming tests");
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            return frames;
        }

        @Override
        public void close() {}
    }
}
