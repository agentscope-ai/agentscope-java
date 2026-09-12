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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenAIErrorTranslator}, covering each SDK exception type.
 *
 * <p>Each test verifies: provider id, model name, cause preservation, status code
 * extraction (where applicable), safe_message content, and retryable classification
 * via {@link OpenAIOfficialModelException#isRetryableHttpStatus()}.
 */
class OpenAIErrorTranslatorTest {

    private static final String MODEL = TestSdkFixtures.MODEL_NAME;

    /**
     * Verifies the common invariants for every translated exception.
     */
    private static void assertCommon(OpenAIOfficialModelException ex, Throwable originalCause) {
        assertEquals("openai-official", ex.getProvider(), "provider id");
        assertEquals(MODEL, ex.getModelName(), "model name");
        assertNotNull(ex.getMessage(), "safe_message must be non-null");
        assertFalse(ex.getMessage().isBlank(), "safe_message must be non-blank");
        String originalMessage = originalCause.getMessage();
        if (originalMessage != null && !originalMessage.isBlank()) {
            assertEquals(
                    originalMessage,
                    ex.getMessage(),
                    "safe_message must match the original throwable's message");
        }
        assertSame(
                originalCause, ex.getCause(), "original SDK exception must be preserved as cause");
    }

    // ── HTTP 400 Bad Request ──
    @Test
    void errBadRequest() {
        BadRequestException sdk = TestSdkFixtures.badRequest("bad request");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(400, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 401 Unauthorized ──
    @Test
    void errUnauthorized() {
        UnauthorizedException sdk = TestSdkFixtures.unauthorized("unauthorized");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(401, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 403 Permission Denied ──
    @Test
    void errPermissionDenied() {
        PermissionDeniedException sdk = TestSdkFixtures.permissionDenied("forbidden");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(403, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 404 Not Found ──
    @Test
    void errNotFound() {
        NotFoundException sdk = TestSdkFixtures.notFound("not found");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(404, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 422 Unprocessable Entity ──
    @Test
    void errUnprocessableEntity() {
        UnprocessableEntityException sdk = TestSdkFixtures.unprocessableEntity("unprocessable");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(422, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── HTTP 429 Rate Limit ──
    @Test
    void errRateLimit() {
        RateLimitException sdk = TestSdkFixtures.rateLimit("rate limited");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(429, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── HTTP 408 from UnexpectedStatusCodeException ──
    @Test
    void errTimeoutConflict408() {
        UnexpectedStatusCodeException sdk = TestSdkFixtures.unexpectedStatusCode(408, "timeout");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(408, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── HTTP 409 from SseException ──
    @Test
    void errTimeoutConflict409Sse() {
        SseException sdk = TestSdkFixtures.sseException(409, "conflict");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── HTTP 5xx Internal Server Error ──
    @Test
    void errInternalServer500() {
        InternalServerException sdk = TestSdkFixtures.internalServer(500, "server error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(500, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void errInternalServer503() {
        InternalServerException sdk = TestSdkFixtures.internalServer(503, "service unavailable");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(503, ex.getStatusCode());
        assertTrue(ex.isRetryableHttpStatus());
    }

    // ── non-standard HTTP status, non-retryable ──
    @Test
    void errOtherService418() {
        UnexpectedStatusCodeException sdk = TestSdkFixtures.unexpectedStatusCode(418, "teapot");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertEquals(418, ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIIoException ──
    @Test
    void errIoRetryableOpenAIIo() {
        OpenAIIoException sdk = TestSdkFixtures.ioException("connection error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIRetryableException ──
    @Test
    void errIoRetryableOpenAIRetryable() {
        OpenAIRetryableException sdk = TestSdkFixtures.retryableException("transient error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── direct TimeoutException ──
    @Test
    void errSdkTimeoutDirect() {
        TimeoutException sdk = TestSdkFixtures.timeoutException("timed out after 30s");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── TimeoutException in cause chain ──
    @Test
    void errSdkTimeoutInCauseChain() {
        TimeoutException timeout = TestSdkFixtures.timeoutException("timed out");
        RuntimeException wrapper = new RuntimeException("wrapper", timeout);
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(wrapper, MODEL);
        assertCommon(ex, wrapper);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── OpenAIInvalidDataException ──
    @Test
    void errInvalidData() {
        OpenAIInvalidDataException sdk = TestSdkFixtures.invalidDataException("parse error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── already translated (pass-through) ──
    @Test
    void alreadyTranslatedIsPassedThrough() {
        RuntimeException cause = new RuntimeException("sdk error");
        OpenAIOfficialModelException original =
                new OpenAIOfficialModelException("already wrapped", cause, MODEL, 429);
        OpenAIOfficialModelException result = OpenAIErrorTranslator.translate(original, MODEL);
        assertSame(original, result);
    }

    // ── generic fallback ──
    @Test
    void genericRuntimeExceptionIsWrapped() {
        RuntimeException sdk = new RuntimeException("unknown error");
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertNull(ex.getStatusCode());
        assertFalse(ex.isRetryableHttpStatus());
    }

    // ── safe_message fallback for null message ──
    @Test
    void safeMessageFallbackForServiceExceptionWithNullMessage() {
        // OpenAIIoException can be constructed with null message; safeMessage
        // falls back to "OpenAI API error: <class name>".
        OpenAIIoException sdk = new OpenAIIoException(null);
        OpenAIOfficialModelException ex = OpenAIErrorTranslator.translate(sdk, MODEL);
        assertCommon(ex, sdk);
        assertTrue(ex.getMessage().contains("OpenAI API error"));
    }
}
