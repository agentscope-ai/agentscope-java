/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.exception;

/**
 * Base exception for agent-oriented errors in AgentScope framework.
 * Aligns with Python's AgentOrientedExceptionBase.
 *
 * <p>This exception serves as the parent class for all custom exceptions
 * in the AgentScope framework, providing a consistent exception hierarchy.
 */
public class AgentScopeException extends RuntimeException {

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message
     */
    public AgentScopeException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public AgentScopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
