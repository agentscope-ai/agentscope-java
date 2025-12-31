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
package io.agentscope.core.formatter;

/**
 * Exception thrown when formatter encounters an error during message formatting or response parsing.
 *
 * <p>This exception is used to wrap underlying errors (JSON parsing, SDK errors, etc.)
 * and provide consistent error handling across all formatter implementations.
 */
public class FormatterException extends RuntimeException {

    /**
     * Constructs a new FormatterException with the specified detail message.
     *
     * @param message The detail message
     */
    public FormatterException(String message) {
        super(message);
    }

    /**
     * Constructs a new FormatterException with the specified detail message and cause.
     *
     * @param message The detail message
     * @param cause The cause of the exception
     */
    public FormatterException(String message, Throwable cause) {
        super(message, cause);
    }
}
