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
package io.agentscope.core.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable bag of {@link Task}s carried by {@link AgentState}.
 *
 * <p>The inner list is mutable so meta-tools can append/replace tasks without rebuilding the whole
 * context. {@link #getTasks()} returns a defensive copy for read-only consumers.
 */
public final class TaskContext {

    private final List<Task> tasks;

    /** Construct an empty context. */
    public TaskContext() {
        this.tasks = new ArrayList<>();
    }

    @JsonCreator
    public TaskContext(@JsonProperty("tasks") List<Task> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
    }

    @JsonProperty("tasks")
    public List<Task> getTasks() {
        return List.copyOf(tasks);
    }

    /** Live, mutable handle for tool implementations that need to append/remove tasks in place. */
    public List<Task> tasksMutable() {
        return tasks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskContext other)) {
            return false;
        }
        return Objects.equals(tasks, other.tasks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tasks);
    }

    @Override
    public String toString() {
        return "TaskContext{tasks=" + tasks + '}';
    }
}
