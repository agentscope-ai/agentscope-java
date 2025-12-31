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
 * Exception thrown when the server encounters an internal error (HTTP 5xx).
 *
 * <p>This indicates a problem on OpenAI's side. Consider retrying the request
 * after a brief wait.
 *
 * <p>Common status codes:
 * <ul>
 *   <li>500 - Internal Server Error</li>
 *   <li>502 - Bad Gateway</li>
 *   <li>503 - Service Unavailable</li>
 *   <li>504 - Gateway Timeout</li>
 * </ul>
 */
public class InternalServerException extends OpenAIException {

    public InternalServerException(
            String message, int statusCode, String errorCode, String responseBody) {
        super(message, statusCode, errorCode, responseBody);
    }
}
