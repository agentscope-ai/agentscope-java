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

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a task in the MCP (Model Context Protocol) system.
 *
 * <p>
 * A task encapsulates the state and metadata of a long-running operation
 * that may not complete immediately. Tasks allow for asynchronous execution
 * with status tracking and result retrieval.
 *
 * <p>
 * Key properties:
 * <ul>
 * <li>taskId - Unique identifier for the task</li>
 * <li>status - Current state of the task execution</li>
 * <li>statusMessage - Optional human-readable message describing the current
 * state</li>
 * <li>createdAt - ISO 8601 timestamp when the task was created</li>
 * <li>lastUpdatedAt - ISO 8601 timestamp when the task status was last
 * updated</li>
 * <li>ttl - Time in milliseconds from creation before task may be deleted</li>
 * <li>pollInterval - Suggested time in milliseconds between status checks</li>
 * </ul>
 *
 * @see TaskStatus
 * @see <a href=
 *      "https://modelcontextprotocol.io/specification/draft/basic/utilities/tasks">MCP
 *      Tasks Specification</a>
 */
public class Task {

    private final String taskId;
    private final TaskStatus status;
    private final String statusMessage;
    private final Instant createdAt;
    private final Instant lastUpdatedAt;
    private final Long ttl;
    private final Long pollInterval;

    /**
     * Constructs a new Task instance.
     *
     * @param taskId        unique identifier for the task
     * @param status        current state of the task execution
     * @param statusMessage optional human-readable message describing the current
     *                      state
     * @param createdAt     timestamp when the task was created
     * @param lastUpdatedAt timestamp when the task status was last updated
     * @param ttl           time in milliseconds from creation before task may be
     *                      deleted
     * @param pollInterval  suggested time in milliseconds between status checks
     */
    public Task(
            String taskId,
            TaskStatus status,
            String statusMessage,
            Instant createdAt,
            Instant lastUpdatedAt,
            Long ttl,
            Long pollInterval) {
        this.taskId = Objects.requireNonNull(taskId, "taskId cannot be null");
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.statusMessage = statusMessage;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt cannot be null");
        this.ttl = ttl;
        this.pollInterval = pollInterval;
    }

    /**
     * Gets the unique identifier for this task.
     *
     * @return the task ID
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Gets the current status of this task.
     *
     * @return the task status
     */
    public TaskStatus getStatus() {
        return status;
    }

    /**
     * Gets the optional human-readable status message.
     *
     * @return the status message, or null if not provided
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Gets the timestamp when this task was created.
     *
     * @return the creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets the timestamp when this task was last updated.
     *
     * @return the last update timestamp
     */
    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     * Gets the time-to-live in milliseconds from creation.
     *
     * @return the TTL in milliseconds, or null if not specified
     */
    public Long getTtl() {
        return ttl;
    }

    /**
     * Gets the suggested polling interval in milliseconds.
     *
     * @return the poll interval in milliseconds, or null if not specified
     */
    public Long getPollInterval() {
        return pollInterval;
    }

    /**
     * Checks if this task is in a terminal state.
     *
     * @return true if the task has completed, failed, or been cancelled
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Creates a builder for constructing Task instances.
     *
     * @return a new TaskBuilder
     */
    public static TaskBuilder builder() {
        return new TaskBuilder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(taskId, task.taskId)
                && status == task.status
                && Objects.equals(statusMessage, task.statusMessage)
                && Objects.equals(createdAt, task.createdAt)
                && Objects.equals(lastUpdatedAt, task.lastUpdatedAt)
                && Objects.equals(ttl, task.ttl)
                && Objects.equals(pollInterval, task.pollInterval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval);
    }

    @Override
    public String toString() {
        return "Task{"
                + "taskId='"
                + taskId
                + '\''
                + ", status="
                + status
                + ", statusMessage='"
                + statusMessage
                + '\''
                + ", createdAt="
                + createdAt
                + ", lastUpdatedAt="
                + lastUpdatedAt
                + ", ttl="
                + ttl
                + ", pollInterval="
                + pollInterval
                + '}';
    }

    /**
     * Builder for creating Task instances.
     */
    public static class TaskBuilder {
        private String taskId;
        private TaskStatus status;
        private String statusMessage;
        private Instant createdAt;
        private Instant lastUpdatedAt;
        private Long ttl;
        private Long pollInterval;

        public TaskBuilder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public TaskBuilder status(TaskStatus status) {
            this.status = status;
            return this;
        }

        public TaskBuilder statusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
            return this;
        }

        public TaskBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public TaskBuilder lastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }

        public TaskBuilder ttl(Long ttl) {
            this.ttl = ttl;
            return this;
        }

        public TaskBuilder pollInterval(Long pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Task build() {
            return new Task(
                    taskId, status, statusMessage, createdAt, lastUpdatedAt, ttl, pollInterval);
        }
    }
}
