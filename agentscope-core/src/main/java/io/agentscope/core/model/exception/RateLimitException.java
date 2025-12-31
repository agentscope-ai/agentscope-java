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
package io.agentscope.core.model.exception;

/**
 * Exception thrown when rate limits are exceeded (HTTP 429).
 *
 * <p>This exception is thrown when you've exceeded your assigned rate limits.
 * Consider implementing exponential backoff retry logic.
 *
 * <p>Common causes:
 * <ul>
 *   <li>Too many requests per minute</li>
 *   <li>Token quota exceeded</li>
 *   <li>Concurrent request limit reached</li>
 * </ul>
 */
public class RateLimitException extends OpenAIException {

    public RateLimitException(String message, String errorCode, String responseBody) {
        super(message, 429, errorCode, responseBody);
    }
}
