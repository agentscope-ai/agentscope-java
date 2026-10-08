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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpTransportException;
import java.net.SocketException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link ExecutionConfig}. */
@Tag("unit")
@DisplayName("ExecutionConfig Unit Tests")
class ExecutionConfigTest {

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 503})
    @DisplayName("Should retry retryable model HTTP status codes")
    void shouldRetryRetryableModelHttpStatusCodes(int statusCode) {
        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(statusCode)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    @DisplayName("Should not retry non-retryable model HTTP status codes")
    void shouldNotRetryNonRetryableModelHttpStatusCodes(int statusCode) {
        assertFalse(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(statusCode)));
    }

    @Test
    @DisplayName("Should retry model HTTP exception from cause chain")
    void shouldRetryModelHttpExceptionFromCauseChain() {
        RuntimeException wrapped = new RuntimeException(new TestModelHttpException(429));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    @Test
    @DisplayName("Should not retry model HTTP exception without status code")
    void shouldNotRetryModelHttpExceptionWithoutStatusCode() {
        assertFalse(ExecutionConfig.RETRYABLE_ERRORS.test(new TestModelHttpException(null)));
    }

    @Test
    @DisplayName("Should retry model HTTP exception without status code wrapping transport error")
    void shouldRetryModelHttpExceptionWithoutStatusCodeWrappingTransportError() {
        // Reproduces issue #3057: streaming transport failures are wrapped by model
        // exceptions without an HTTP status code (e.g. OpenAIException), so the cause
        // chain must be consulted instead of treating them as permanent client errors.
        HttpTransportException transportError =
                new HttpTransportException(
                        "SSE/NDJSON stream failed: Connection reset",
                        new SocketException("Connection reset"));
        TestModelHttpException wrapped = new TestModelHttpException(null, transportError);

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    @Test
    @DisplayName("Should retry model HTTP exception without status code wrapping IO error")
    void shouldRetryModelHttpExceptionWithoutStatusCodeWrappingIoError() {
        TestModelHttpException wrapped =
                new TestModelHttpException(null, new SocketException("Connection reset"));

        assertTrue(ExecutionConfig.RETRYABLE_ERRORS.test(wrapped));
    }

    @Test
    @DisplayName("Should reject non-sentinel negative durations in Builder.timeout()")
    void shouldRejectNonSentinelNegativeDurations() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ExecutionConfig.builder().timeout(Duration.ofMillis(-500)));
        assertTrue(
                ex.getMessage().contains("positive"),
                "Message must indicate the value must be positive");
    }

    @Test
    @DisplayName("Should reject Duration.ZERO in Builder.timeout()")
    void shouldRejectZeroDuration() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ExecutionConfig.builder().timeout(Duration.ZERO));
        assertTrue(
                ex.getMessage().contains("positive"),
                "Message must indicate the value must be positive");
    }

    @Test
    @DisplayName(
            "mergeConfigs(noTimeout, withTimeout) must keep the NO_TIMEOUT sentinel in primary"
                    + " position")
    void mergeConfigsNoTimeoutPrimaryKeepsSentinel() {
        ExecutionConfig noTimeout = ExecutionConfig.builder().noTimeout().build();
        ExecutionConfig withTimeout =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(30)).build();

        ExecutionConfig merged = ExecutionConfig.mergeConfigs(noTimeout, withTimeout);

        assertTrue(merged.isTimeoutDisabled(), "NO_TIMEOUT sentinel must survive merge");
    }

    @Test
    @DisplayName(
            "mergeConfigs(withTimeout, noTimeout) must keep the positive timeout in primary"
                    + " position")
    void mergeConfigsPositiveTimeoutPrimaryOverridesNoTimeout() {
        ExecutionConfig withTimeout =
                ExecutionConfig.builder().timeout(Duration.ofSeconds(30)).build();
        ExecutionConfig noTimeout = ExecutionConfig.builder().noTimeout().build();

        ExecutionConfig merged = ExecutionConfig.mergeConfigs(withTimeout, noTimeout);

        assertEquals(
                Duration.ofSeconds(30),
                merged.getTimeout(),
                "Positive timeout in primary position must override fallback NO_TIMEOUT");
    }

    @Test
    @DisplayName("mergeConfigs(null timeout primary, noTimeout fallback) should inherit NO_TIMEOUT")
    void mergeConfigsNullTimeoutInheritsNoTimeoutFromFallback() {
        ExecutionConfig primary = ExecutionConfig.builder().build(); // timeout = null
        ExecutionConfig fallback = ExecutionConfig.builder().noTimeout().build();

        ExecutionConfig merged = ExecutionConfig.mergeConfigs(primary, fallback);

        assertTrue(
                merged.isTimeoutDisabled(),
                "Null (unset) primary should inherit NO_TIMEOUT from fallback");
    }

    private static final class TestModelHttpException extends RuntimeException
            implements ModelHttpException {

        private final Integer statusCode;

        private TestModelHttpException(Integer statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private TestModelHttpException(Integer statusCode, Throwable cause) {
            super("HTTP " + statusCode, cause);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
