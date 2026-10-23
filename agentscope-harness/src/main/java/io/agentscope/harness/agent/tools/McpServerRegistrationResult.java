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
package io.agentscope.harness.agent.tools;

import java.time.Instant;
import java.util.Objects;

/**
 * Terminal result of registering one MCP server entry.
 *
 * @param serverName configured server name; may be {@code null} for a skipped invalid entry
 * @param transport configured transport; may be {@code null} when absent or unavailable
 * @param status terminal registration status
 * @param completedAt time at which registration reached the terminal status
 * @param cause failure or skip cause; {@code null} for successful registration
 */
public record McpServerRegistrationResult(
        String serverName, String transport, Status status, Instant completedAt, Throwable cause) {

    public McpServerRegistrationResult {
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(completedAt, "completedAt cannot be null");
        if (status == Status.SUCCESS && cause != null) {
            throw new IllegalArgumentException("successful registration cannot have a cause");
        }
        if (status != Status.SUCCESS) {
            Objects.requireNonNull(cause, "non-successful registration must have a cause");
        }
    }

    /** Terminal status of an MCP server registration attempt. */
    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    /** Creates a successful registration result. */
    public static McpServerRegistrationResult success(String serverName, String transport) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.SUCCESS, Instant.now(), null);
    }

    /** Creates a failed registration result. */
    public static McpServerRegistrationResult failed(
            String serverName, String transport, Throwable cause) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.FAILED, Instant.now(), cause);
    }

    /** Creates a skipped registration result. */
    public static McpServerRegistrationResult skipped(
            String serverName, String transport, Throwable cause) {
        return new McpServerRegistrationResult(
                serverName, transport, Status.SKIPPED, Instant.now(), cause);
    }
}
