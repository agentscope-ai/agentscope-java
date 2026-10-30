/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Verifies that per-call {@link ExecutionConfig} (timeout and retry) passed through {@link
 * GenerateOptions} actually constrains the Ollama HTTP call, instead of being silently dropped by
 * the generic-to-Ollama options conversion.
 *
 * <p>These tests assert observable execution behavior via a fake transport: real timeout
 * termination and real retry attempt counts. Response arrival is controlled manually or with
 * wide margins so the assertions do not depend on precise wall-clock timing.
 */
@Tag("unit")
@DisplayName("OllamaChatModel per-call execution config tests")
class OllamaChatModelExecutionOptionsTest {

    private static final String TEST_MODEL_NAME = "llama3";

    /** Delay of the fake slow response; far above the per-call timeout, far below test bounds. */
    private static final long RESPONSE_DELAY_MS = 1_000;

    /** Per-call timeout under test; far below {@link #RESPONSE_DELAY_MS}. */
    private static final Duration PER_CALL_TIMEOUT = Duration.ofMillis(200);

    private static final String OK_RESPONSE_BODY =
            "{\"model\":\""
                    + TEST_MODEL_NAME
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},"
                    + "\"done\":true,\"eval_count\":2}";

    @Mock private HttpTransport httpTransport;

    private OllamaChatModel model;

    private final List<Msg> messages =
            List.of(Msg.builder().role(MsgRole.USER).textContent("Hi").build());

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        model =
                OllamaChatModel.builder()
                        .modelName(TEST_MODEL_NAME)
                        .baseUrl("http://localhost:11434")
                        .httpTransport(httpTransport)
                        .build();
    }

    private static GenerateOptions optionsWithConfig(ExecutionConfig executionConfig) {
        return GenerateOptions.builder().executionConfig(executionConfig).build();
    }

    private static GenerateOptions nonStreamingOptions(ExecutionConfig executionConfig) {
        return GenerateOptions.builder().stream(false).executionConfig(executionConfig).build();
    }

    /** Asserts the terminal error carries the transport failure somewhere in its cause chain. */
    private static void assertTransportFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof HttpTransportException)) {
            cause = cause.getCause();
        }
        assertTrue(
                cause instanceof HttpTransportException,
                "expected the transport failure in the error chain, got: " + error);
    }

    @Test
    @DisplayName("Per-call timeout terminates a slow non-streaming response on schedule")
    void perCallTimeoutTerminatesSlowNonStreamingResponse() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(RESPONSE_DELAY_MS);
                            return HttpResponse.builder()
                                    .statusCode(200)
                                    .body(OK_RESPONSE_BODY)
                                    .build();
                        });

        GenerateOptions options =
                nonStreamingOptions(
                        ExecutionConfig.builder().timeout(PER_CALL_TIMEOUT).maxAttempts(1).build());

        // Pre-fix the conversion drops the execution config and the model default timeout
        // (5 minutes) applies, so this verification would time out instead of erroring.
        StepVerifier.create(model.stream(messages, null, options))
                .expectError(ModelException.class)
                .verify(Duration.ofSeconds(10));

        // No retry configured: exactly one transport attempt.
        verify(httpTransport, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Same slow response still succeeds without per-call config (default path)")
    void slowResponseSucceedsOnDefaultPath() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenAnswer(
                        inv -> {
                            Thread.sleep(RESPONSE_DELAY_MS);
                            return HttpResponse.builder()
                                    .statusCode(200)
                                    .body(OK_RESPONSE_BODY)
                                    .build();
                        });

        GenerateOptions options = GenerateOptions.builder().stream(false).build();

        StepVerifier.create(model.stream(messages, null, options))
                .expectNextMatches(
                        response ->
                                response.getContent() != null && !response.getContent().isEmpty())
                .verifyComplete();

        verify(httpTransport, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Per-call timeout terminates a hanging streaming response")
    void perCallTimeoutTerminatesHangingStream() {
        when(httpTransport.stream(any(HttpRequest.class))).thenReturn(Flux.never());

        GenerateOptions options =
                optionsWithConfig(
                        ExecutionConfig.builder().timeout(PER_CALL_TIMEOUT).maxAttempts(1).build());

        StepVerifier.create(model.stream(messages, null, options))
                .expectError(ModelException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("Per-call retry limit bounds the number of transport attempts")
    void perCallRetryLimitBoundsTransportAttempts() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenThrow(
                        new HttpTransportException(
                                "Ollama server error", 500, "internal server error"));

        GenerateOptions options =
                nonStreamingOptions(
                        ExecutionConfig.builder()
                                .maxAttempts(2)
                                .initialBackoff(Duration.ofMillis(10))
                                .build());

        StepVerifier.create(model.stream(messages, null, options))
                .expectErrorSatisfies(OllamaChatModelExecutionOptionsTest::assertTransportFailure)
                .verify(Duration.ofSeconds(10));

        // 1 initial attempt + 1 retry = 2 transport calls, not the model default of 3.
        verify(httpTransport, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Unset per-call maxAttempts falls back to the model default of 3")
    void unsetPerCallMaxAttemptsFallsBackToDefault() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenThrow(
                        new HttpTransportException(
                                "Ollama server error", 500, "internal server error"));

        // Only timeout and backoff are set: maxAttempts stays unset and must fall back
        // to the model default (3) through the per-field config merge, not disable retry.
        GenerateOptions options =
                nonStreamingOptions(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(30))
                                .initialBackoff(Duration.ofMillis(10))
                                .build());

        StepVerifier.create(model.stream(messages, null, options))
                .expectErrorSatisfies(OllamaChatModelExecutionOptionsTest::assertTransportFailure)
                .verify(Duration.ofSeconds(10));

        verify(httpTransport, times(3)).execute(any(HttpRequest.class));
    }

    @Test
    @DisplayName("Per-call execution config is not sent to the Ollama server")
    void perCallConfigStaysOffTheWire() {
        when(httpTransport.execute(any(HttpRequest.class)))
                .thenReturn(HttpResponse.builder().statusCode(200).body(OK_RESPONSE_BODY).build());

        GenerateOptions options =
                nonStreamingOptions(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(30))
                                .maxAttempts(1)
                                .build());

        StepVerifier.create(model.stream(messages, null, options))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpTransport).execute(captor.capture());
        String body = captor.getValue().getBody();
        assertFalse(
                body.contains("executionConfig"), "body must not leak execution config: " + body);
    }
}
