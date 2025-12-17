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

import java.util.Objects;

/**
 * Parameters for creating a task in the MCP protocol.
 *
 * <p>
 * These parameters can be included in requests (such as tool calls or sampling
 * requests)
 * to indicate that the operation should be executed as a task rather than
 * returning
 * results immediately.
 *
 * <p>
 * When task parameters are included in a request, the server will:
 * <ul>
 * <li>Accept the request and immediately return a CreateTaskResult</li>
 * <li>Execute the operation asynchronously</li>
 * <li>Make results available later through tasks/result</li>
 * </ul>
 *
 * @see <a href=
 *      "https://modelcontextprotocol.io/specification/draft/basic/utilities/tasks">MCP
 *      Tasks Specification</a>
 */
public class TaskParameters {

    private final Long ttl;

    /**
     * Constructs task parameters with the specified TTL.
     *
     * @param ttl requested duration in milliseconds to retain task from creation
     */
    public TaskParameters(Long ttl) {
        this.ttl = ttl;
    }

    /**
     * Gets the requested time-to-live for the task.
     *
     * @return the TTL in milliseconds, or null if not specified
     */
    public Long getTtl() {
        return ttl;
    }

    /**
     * Creates task parameters with the specified TTL.
     *
     * @param ttl requested duration in milliseconds to retain task from creation
     * @return new TaskParameters instance
     */
    public static TaskParameters withTtl(Long ttl) {
        return new TaskParameters(ttl);
    }

    /**
     * Creates task parameters with default settings (no TTL specified).
     *
     * @return new TaskParameters instance with defaults
     */
    public static TaskParameters defaults() {
        return new TaskParameters(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskParameters that = (TaskParameters) o;
        return Objects.equals(ttl, that.ttl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ttl);
    }

    @Override
    public String toString() {
        return "TaskParameters{" + "ttl=" + ttl + '}';
    }
}
