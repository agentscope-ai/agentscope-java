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

package io.agentscope.core.util;

/**
 * Runtime exception for JSON processing errors.
 *
 * <p>This exception wraps underlying JSON processing exceptions (such as
 * Jackson's JsonProcessingException) to provide a unified exception type
 * for JSON operations across the framework.
 *
 * @see JsonCodec
 * @see JsonUtils
 */
public class JsonException extends RuntimeException {

    /**
     * Constructs a new JSON exception with the specified detail message.
     *
     * @param message the detail message
     */
    public JsonException(String message) {
        super(message);
    }

    /**
     * Constructs a new JSON exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new JSON exception with the specified cause.
     *
     * @param cause the cause of the exception
     */
    public JsonException(Throwable cause) {
        super(cause);
    }
}
