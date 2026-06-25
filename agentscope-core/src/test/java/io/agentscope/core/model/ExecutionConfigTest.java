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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final class TestModelHttpException extends RuntimeException
            implements ModelHttpException {

        private final Integer statusCode;

        private TestModelHttpException(Integer statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        @Override
        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
