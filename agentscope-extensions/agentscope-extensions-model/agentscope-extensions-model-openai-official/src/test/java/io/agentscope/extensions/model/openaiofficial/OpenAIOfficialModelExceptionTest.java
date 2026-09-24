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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ModelHttpException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OpenAIOfficialModelException}.
 *
 * <p>Verifies provider id, HTTP status code handling, and the {@link #isRetryableHttpStatus()}
 * override covering 408/409/429/5xx — aligned with SDK {@code RetryingHttpClient.shouldRetry}.
 */
class OpenAIOfficialModelExceptionTest {

    private static final String MODEL = "gpt-4o";

    @Test
    void extendsModelExceptionAndImplementsModelHttpException() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL);
        assertInstanceOf(ModelException.class, ex);
        assertInstanceOf(ModelHttpException.class, ex);
    }

    @Test
    void providerIdIsAlwaysOpenaiOfficial() {
        OpenAIOfficialModelException withStatus =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL, 429);
        OpenAIOfficialModelException withoutStatus =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL);
        OpenAIOfficialModelException validationOnly =
                new OpenAIOfficialModelException("validation error");

        assertEquals("openai-official", withStatus.getProvider());
        assertEquals("openai-official", withoutStatus.getProvider());
        assertEquals("openai-official", validationOnly.getProvider());

        // The 2-arg constructor should also set provider id and model name
        OpenAIOfficialModelException withModelName =
                new OpenAIOfficialModelException("validation error", MODEL);
        assertEquals("openai-official", withModelName.getProvider());
        assertEquals(MODEL, withModelName.getModelName());
    }

    @Test
    void statusCodeIsNullWhenNotProvided() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL);
        assertNull(ex.getStatusCode());
    }

    @Test
    void statusCodeIsPreservedWhenProvided() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL, 429);
        assertEquals(429, ex.getStatusCode());
    }

    @Test
    void modelNameIsPreserved() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException("cause"), MODEL, 400);
        assertEquals(MODEL, ex.getModelName());
    }

    @Test
    void causeIsPreserved() {
        RuntimeException sdkCause = new RuntimeException("sdk error");
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", sdkCause, MODEL, 400);
        assertSame(sdkCause, ex.getCause());
    }

    // ── isRetryableHttpStatus ──

    @Test
    void retryableHttpStatusTrueFor408() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 408);
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusTrueFor409() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 409);
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusTrueFor429() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 429);
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusTrueFor500() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 500);
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusTrueFor503() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 503);
        assertTrue(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseFor400() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 400);
        assertFalse(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseFor401() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 401);
        assertFalse(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseFor403() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 403);
        assertFalse(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseFor404() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 404);
        assertFalse(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseFor422() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL, 422);
        assertFalse(ex.isRetryableHttpStatus());
    }

    @Test
    void retryableHttpStatusFalseWhenStatusCodeNull() {
        OpenAIOfficialModelException ex =
                new OpenAIOfficialModelException("msg", new RuntimeException(), MODEL);
        assertFalse(ex.isRetryableHttpStatus());
    }
}
