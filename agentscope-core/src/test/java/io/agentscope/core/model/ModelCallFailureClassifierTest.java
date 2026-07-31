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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.ModelCallAttemptFailureCategory;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ModelCallFailureClassifierTest {

    @Test
    void classifyNullReturnsUnknown() {
        assertEquals(
                ModelCallAttemptFailureCategory.UNKNOWN, ModelCallFailureClassifier.classify(null));
    }

    @Test
    void classifyRateLimit429() {
        HttpTransportException ex = new HttpTransportException("rate limited", 429, "");
        assertEquals(
                ModelCallAttemptFailureCategory.RATE_LIMIT,
                ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyServer5xx() {
        HttpTransportException ex = new HttpTransportException("server error", 503, "");
        assertEquals(
                ModelCallAttemptFailureCategory.PROVIDER_5XX,
                ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyAuthentication401() {
        HttpTransportException ex = new HttpTransportException("unauthorized", 401, "");
        assertEquals(
                ModelCallAttemptFailureCategory.AUTHENTICATION,
                ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyAuthorization403() {
        HttpTransportException ex = new HttpTransportException("forbidden", 403, "");
        assertEquals(
                ModelCallAttemptFailureCategory.AUTHORIZATION,
                ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyBadRequest400() {
        HttpTransportException ex = new HttpTransportException("bad request", 400, "");
        assertEquals(
                ModelCallAttemptFailureCategory.INVALID_REQUEST,
                ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyNetworkErrorNoStatusCode() {
        HttpTransportException ex = new HttpTransportException("connection refused");
        assertEquals(
                ModelCallAttemptFailureCategory.NETWORK, ModelCallFailureClassifier.classify(ex));
    }

    @Test
    void classifyTimeoutException() {
        assertEquals(
                ModelCallAttemptFailureCategory.TIMEOUT,
                ModelCallFailureClassifier.classify(new TimeoutException()));
    }

    @Test
    void classifySocketTimeoutException() {
        assertEquals(
                ModelCallAttemptFailureCategory.TIMEOUT,
                ModelCallFailureClassifier.classify(new SocketTimeoutException()));
    }

    @Test
    void classifyConnectException() {
        assertEquals(
                ModelCallAttemptFailureCategory.NETWORK,
                ModelCallFailureClassifier.classify(new ConnectException()));
    }

    @Test
    void classifyIoException() {
        assertEquals(
                ModelCallAttemptFailureCategory.NETWORK,
                ModelCallFailureClassifier.classify(new IOException("reset")));
    }

    @Test
    void classifyWrappedException() {
        Exception inner = new HttpTransportException("rate limited", 429, "");
        RuntimeException wrapped = new RuntimeException("model call failed", inner);
        assertEquals(
                ModelCallAttemptFailureCategory.RATE_LIMIT,
                ModelCallFailureClassifier.classify(wrapped));
    }

    @Test
    void classifyUnknownException() {
        assertEquals(
                ModelCallAttemptFailureCategory.UNKNOWN,
                ModelCallFailureClassifier.classify(new IllegalStateException("oops")));
    }

    @Test
    void isRetryableDelegatesToRetryableErrors() {
        HttpTransportException retryable = new HttpTransportException("rate limited", 429, "");
        assertTrue(ModelCallFailureClassifier.isRetryable(retryable));

        HttpTransportException nonRetryable = new HttpTransportException("bad request", 400, "");
        assertFalse(ModelCallFailureClassifier.isRetryable(nonRetryable));
    }

    @Test
    void sanitizeErrorCodeFromHttpException() {
        HttpTransportException ex = new HttpTransportException("error", 429, "secret-key-abc");
        assertEquals("HTTP_429", ModelCallFailureClassifier.sanitizeErrorCode(ex));
    }

    @Test
    void sanitizeErrorCodeFromNonHttpException() {
        assertEquals(
                "IllegalStateException",
                ModelCallFailureClassifier.sanitizeErrorCode(new IllegalStateException("oops")));
    }

    @Test
    void sanitizeErrorCodeNull() {
        assertEquals("unknown", ModelCallFailureClassifier.sanitizeErrorCode(null));
    }

    @Test
    void sanitizeMessageTruncatesLongMessage() {
        String longMsg = "x".repeat(300);
        HttpTransportException ex = new HttpTransportException(longMsg, 500, "");
        String sanitized = ModelCallFailureClassifier.sanitizeMessage(ex);
        assertTrue(sanitized.length() <= 200);
    }

    @Test
    void sanitizeMessageTakesFirstLine() {
        HttpTransportException ex =
                new HttpTransportException("line1\nline2\nline3", 500, "some-body");
        assertEquals("line1", ModelCallFailureClassifier.sanitizeMessage(ex));
    }

    @Test
    void sanitizeMessageNull() {
        assertEquals("unknown error", ModelCallFailureClassifier.sanitizeMessage(null));
    }

    @Test
    void sanitizeMessageStripsResponseBody() {
        HttpTransportException ex =
                new HttpTransportException("error", 500, "secret-response-body");
        assertEquals("error", ModelCallFailureClassifier.sanitizeMessage(ex));
    }

    @Test
    void sanitizeMessageEmpty() {
        HttpTransportException ex = new HttpTransportException("", 500, "");
        assertEquals("HttpTransportException", ModelCallFailureClassifier.sanitizeMessage(ex));
    }
}
