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

package io.agentscope.extensions.judge.openaidecisions;

/** A sanitized transport, HTTP, timeout, or invalid-response failure. Raw provider bodies are omitted. */
public final class OpenAIDecisionsException extends RuntimeException {
    private final Integer statusCode;
    private final boolean retryable;

    public OpenAIDecisionsException(String message, Integer statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    /** HTTP status, or {@code null} when unavailable. */
    public Integer getStatusCode() {
        return statusCode;
    }

    /** A hint for caller-controlled retries; the client does not automatically retry. */
    public boolean isRetryable() {
        return retryable;
    }
}
