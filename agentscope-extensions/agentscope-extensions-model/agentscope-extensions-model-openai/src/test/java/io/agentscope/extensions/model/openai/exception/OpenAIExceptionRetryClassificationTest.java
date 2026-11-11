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
package io.agentscope.extensions.model.openai.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.transport.HttpTransportException;
import java.net.SocketException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link OpenAIException} participates in the {@link ExecutionConfig#RETRYABLE_ERRORS}
 * classification correctly, in particular for streaming transport failures wrapped without an
 * HTTP status code (issue #3057).
 */
@Tag("unit")
@DisplayName("OpenAIException Retry Classification Tests")
class OpenAIExceptionRetryClassificationTest {

    @Test
    @DisplayName("Should retry streaming transport error wrapped without status code")
    void shouldRetryStreamingTransportErrorWrappedWithoutStatusCode() {
        // Reproduces the production exception chain from issue #3057:
        // OpenAIClient.stream wraps HttpTransportException (no status code) into
        // OpenAIException, which implements ModelHttpException with a null status.
        HttpTransportException transportError =
                new HttpTransportException(
                        "SSE/NDJSON stream failed: java.net.SocketException: Connection reset",
                        new SocketException("Connection reset"));
        OpenAIException exception =
                new OpenAIException(
                        "HTTP transport error during streaming: " + transportError.getMessage(),
                        transportError);

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(exception));
    }

    @Test
    @DisplayName("Should retry wrapped IO error without status code")
    void shouldRetryWrappedIoErrorWithoutStatusCode() {
        OpenAIException exception =
                new OpenAIException(
                        "HTTP transport error during streaming",
                        new SocketException("Connection reset"));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(exception));
    }

    @Test
    @DisplayName("Should keep HTTP status based classification for real responses")
    void shouldKeepHttpStatusBasedClassificationForRealResponses() {
        // 429/5xx remain retryable
        assertTrue(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        OpenAIException.create(429, "Rate limited", null, null)));
        assertTrue(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        OpenAIException.create(503, "Service unavailable", null, null)));
        // Other 4xx remain non-retryable
        assertFalse(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        OpenAIException.create(400, "Bad request", null, null)));
        assertFalse(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        OpenAIException.create(401, "Unauthorized", null, null)));
    }

    @Test
    @DisplayName("Should not retry unknown error without status code or cause")
    void shouldNotRetryUnknownErrorWithoutStatusCodeOrCause() {
        OpenAIException exception = new OpenAIException("Something went wrong");

        assertFalse(ExecutionConfig.RETRYABLE_ERRORS.test(exception));
    }
}
