/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.tool.mcp.task;

/**
 * Enumeration of possible task statuses in the MCP protocol.
 *
 * <p>
 * Task statuses represent the current state of a task's execution lifecycle:
 * <ul>
 * <li>{@link #WORKING} - The task is currently being processed</li>
 * <li>{@link #INPUT_REQUIRED} - The task needs input from the requestor</li>
 * <li>{@link #COMPLETED} - The task completed successfully</li>
 * <li>{@link #FAILED} - The task failed to complete</li>
 * <li>{@link #CANCELLED} - The task was cancelled before completion</li>
 * </ul>
 *
 * @see <a href=
 *      "https://modelcontextprotocol.io/specification/draft/basic/utilities/tasks">MCP
 *      Tasks Specification</a>
 */
public enum TaskStatus {
    /**
     * The request is currently being processed.
     */
    WORKING("working"),

    /**
     * The receiver needs input from the requestor.
     * The requestor should call tasks/result to receive input requests,
     * even though the task has not reached a terminal state.
     */
    INPUT_REQUIRED("input_required"),

    /**
     * The request completed successfully and results are available.
     */
    COMPLETED("completed"),

    /**
     * The associated request did not complete successfully.
     * For tool calls specifically, this includes cases where the tool call
     * result has isError set to true.
     */
    FAILED("failed"),

    /**
     * The request was cancelled before completion.
     */
    CANCELLED("cancelled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /**
     * Gets the string value of this status as used in the MCP protocol.
     *
     * @return the protocol string value
     */
    public String getValue() {
        return value;
    }

    /**
     * Parses a string value into a TaskStatus enum.
     *
     * @param value the string value to parse
     * @return the corresponding TaskStatus
     * @throws IllegalArgumentException if the value doesn't match any status
     */
    public static TaskStatus fromValue(String value) {
        for (TaskStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + value);
    }

    /**
     * Checks if this status represents a terminal state.
     * Terminal states are: COMPLETED, FAILED, and CANCELLED.
     *
     * @return true if this is a terminal status, false otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    @Override
    public String toString() {
        return value;
    }
}
