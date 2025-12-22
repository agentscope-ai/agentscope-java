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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of listing tasks from an MCP server.
 *
 * <p>
 * This class encapsulates the response from a tasks/list request,
 * which includes a list of tasks and an optional cursor for pagination.
 *
 * @see Task
 * @see <a href=
 *      "https://modelcontextprotocol.io/specification/draft/basic/utilities/tasks">MCP
 *      Tasks Specification</a>
 */
public class ListTasksResult {

    private final List<Task> tasks;
    private final String nextCursor;

    /**
     * Constructs a new ListTasksResult.
     *
     * @param tasks      the list of tasks
     * @param nextCursor optional cursor for retrieving the next page of results
     */
    public ListTasksResult(List<Task> tasks, String nextCursor) {
        this.tasks =
                Collections.unmodifiableList(Objects.requireNonNull(tasks, "tasks cannot be null"));
        this.nextCursor = nextCursor;
    }

    /**
     * Gets an unmodifiable view of the tasks list.
     *
     * <p>
     * The returned list cannot be modified. Any attempt to modify it will throw
     * {@link UnsupportedOperationException}.
     *
     * @return an unmodifiable list of tasks
     */
    public List<Task> getTasks() {
        return tasks;
    }

    /**
     * Gets the cursor for the next page of results.
     *
     * @return the next cursor, or null if there are no more results
     */
    public String getNextCursor() {
        return nextCursor;
    }

    /**
     * Checks if there are more results available.
     *
     * @return true if a next cursor is present, false otherwise
     */
    public boolean hasMore() {
        return nextCursor != null && !nextCursor.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ListTasksResult that = (ListTasksResult) o;
        return Objects.equals(tasks, that.tasks) && Objects.equals(nextCursor, that.nextCursor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tasks, nextCursor);
    }

    @Override
    public String toString() {
        return "ListTasksResult{" + "tasks=" + tasks + ", nextCursor='" + nextCursor + '\'' + '}';
    }
}
