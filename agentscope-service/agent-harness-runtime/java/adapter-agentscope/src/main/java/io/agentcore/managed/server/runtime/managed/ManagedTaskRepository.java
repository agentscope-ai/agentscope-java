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
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.util.Collection;
import java.util.List;

/** Managed children use native ToolTasks/Turns; no workspace task executor or orphan sweeper exists. */
public final class ManagedTaskRepository implements TaskRepository {
    public static final List<String> LOCAL_TOOLS =
            List.of("task_list", "task_output", "task_cancel", "wait_async_results");

    @Override
    public BackgroundTask getTask(RuntimeContext context, String session, String task) {
        return null;
    }

    @Override
    public Collection<BackgroundTask> listTasks(
            RuntimeContext context, String session, TaskStatus filter) {
        return List.of();
    }

    @Override
    public boolean cancelTask(RuntimeContext context, String session, String task) {
        return false;
    }

    @Override
    public BackgroundTask putTask(
            RuntimeContext context, String task, String agent, String session, TaskRunSpec spec) {
        throw new UnsupportedOperationException(
                "Managed child execution uses native Turn admission and Resume");
    }
}
