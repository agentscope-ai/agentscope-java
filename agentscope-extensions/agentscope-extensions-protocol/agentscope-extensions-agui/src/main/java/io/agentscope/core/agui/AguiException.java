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
package io.agentscope.core.agui;

/**
 * Base exception for AG-UI related errors.
 */
public class AguiException extends RuntimeException {

    /**
     * Creates a new AguiException with the specified message.
     *
     * @param message The error message
     */
    public AguiException(String message) {
        super(message);
    }

    /**
     * Creates a new AguiException with the specified message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public AguiException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Exception thrown when an agent is not found in the registry.
     */
    public static class AgentNotFoundException extends AguiException {

        /**
         * Creates a new AgentNotFoundException.
         *
         * @param agentId The agent ID that was not found
         */
        public AgentNotFoundException(String agentId) {
            super("Agent not found: " + agentId);
        }
    }

    /**
     * Exception thrown when event encoding fails.
     */
    public static class EncodingException extends AguiException {

        /**
         * Creates a new EncodingException.
         *
         * @param message The error message
         * @param cause The underlying cause
         */
        public EncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
