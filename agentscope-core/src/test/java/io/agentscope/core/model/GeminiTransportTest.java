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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonUtils;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Tag("unit")
@DisplayName("GeminiTransport Unit Tests")
class GeminiTransportTest {

    @Test
    @DisplayName("Should parse unary response")
    void testHandleUnaryResponse() {
        String jsonResponse =
                "{\n"
                        + "  \"candidates\": [\n"
                        + "    {\n"
                        + "      \"content\": {\n"
                        + "        \"parts\": [\n"
                        + "          {\n"
                        + "            \"text\": \"Hello from unary\"\n"
                        + "          }\n"
                        + "        ],\n"
                        + "        \"role\": \"model\"\n"
                        + "      },\n"
                        + "      \"finishReason\": \"STOP\",\n"
                        + "      \"index\": 0\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";

        GeminiTransport transport = newTransport(okClientWithBody(200, "OK", jsonResponse));
        Request request =
                new Request.Builder()
                        .url("https://example.com")
                        .post(RequestBody.create("{}", MediaType.get("application/json")))
                        .build();

        StepVerifier.create(transport.handleUnaryResponse(request, Instant.now()))
                .assertNext(response -> assertEquals("Hello from unary", textOf(response)))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw GeminiApiException for unary non-success status")
    void testUnaryErrorStatus() {
        GeminiTransport transport =
                newTransport(okClientWithBody(429, "Too Many Requests", "{\"error\":\"rate\"}"));
        Request request =
                new Request.Builder()
                        .url("https://example.com")
                        .post(RequestBody.create("{}", MediaType.get("application/json")))
                        .build();

        GeminiApiException exception =
                assertThrows(
                        GeminiApiException.class,
                        () -> transport.handleUnaryResponse(request, Instant.now()));
        assertEquals(429, exception.getStatusCode());
    }

    @Test
    @DisplayName("Should close unary response body on error")
    void testUnaryErrorStatusClosesResponseBody() {
        AtomicBoolean closed = new AtomicBoolean(false);
        GeminiTransport transport =
                newTransport(
                        okClientWithTrackingBody(
                                429, "Too Many Requests", "{\"error\":\"rate\"}", closed));
        Request request =
                new Request.Builder()
                        .url("https://example.com")
                        .post(RequestBody.create("{}", MediaType.get("application/json")))
                        .build();

        assertThrows(
                GeminiApiException.class,
                () -> transport.handleUnaryResponse(request, Instant.now()));
        assertTrue(closed.get());
    }

    @Test
    @DisplayName("Should emit GeminiApiException for streaming non-success status")
    void testStreamingErrorStatus() {
        GeminiTransport transport =
                newTransport(okClientWithBody(500, "Internal Error", "{\"error\":\"boom\"}"));
        Request request =
                new Request.Builder()
                        .url("https://example.com")
                        .post(RequestBody.create("{}", MediaType.get("application/json")))
                        .build();

        Flux<ChatResponse> responseFlux = transport.handleStreamResponse(request, Instant.now());
        StepVerifier.create(responseFlux)
                .expectErrorMatches(
                        throwable ->
                                throwable instanceof GeminiApiException
                                        && ((GeminiApiException) throwable).getStatusCode() == 500)
                .verify();
    }

    private static GeminiTransport newTransport(OkHttpClient httpClient) {
        return new GeminiTransport(httpClient, JsonUtils.getJsonCodec(), new GeminiChatFormatter());
    }

    private static OkHttpClient okClientWithBody(int code, String message, String body) {
        Interceptor interceptor =
                chain ->
                        new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(code)
                                .message(message)
                                .body(ResponseBody.create(body, MediaType.get("application/json")))
                                .build();
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static OkHttpClient okClientWithTrackingBody(
            int code, String message, String body, AtomicBoolean closed) {
        Interceptor interceptor =
                chain ->
                        new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(code)
                                .message(message)
                                .body(new TrackingResponseBody(body, closed))
                                .build();
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static final class TrackingResponseBody extends ResponseBody {

        private final ResponseBody delegate;
        private final AtomicBoolean closed;

        private TrackingResponseBody(String body, AtomicBoolean closed) {
            this.delegate = ResponseBody.create(body, MediaType.get("application/json"));
            this.closed = closed;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public okio.BufferedSource source() {
            return delegate.source();
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                delegate.close();
            } catch (RuntimeException e) {
                throw e;
            }
        }
    }

    private static String textOf(ChatResponse response) {
        if (response.getContent() == null) {
            return "";
        }
        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }
}
