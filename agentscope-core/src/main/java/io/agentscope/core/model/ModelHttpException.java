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

/**
 * Exception contract for model providers that can expose an HTTP status code.
 *
 * <p>This keeps retry classification provider-neutral while allowing extension modules to preserve
 * provider-specific exception types.
 */
public interface ModelHttpException {

    /**
     * Returns the HTTP status code associated with the model failure.
     *
     * @return HTTP status code, or null when no status code is available
     */
    Integer getStatusCode();

    /**
     * Returns whether this HTTP status is retryable by default.
     *
     * <p>Rate limiting (429) and server errors (5xx) are considered retryable.
     *
     * @return true if the status code should be retried
     */
    default boolean isRetryableHttpStatus() {
        Integer statusCode = getStatusCode();
        return statusCode != null && (statusCode == 429 || statusCode >= 500 && statusCode < 600);
    }
}
