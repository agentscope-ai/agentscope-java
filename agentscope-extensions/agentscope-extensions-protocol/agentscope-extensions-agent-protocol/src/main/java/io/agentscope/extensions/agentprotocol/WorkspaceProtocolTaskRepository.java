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
package io.agentscope.extensions.agentprotocol;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ProtocolTaskRepository} backed by a dedicated {@link WorkspaceManager} writing into the
 * synthetic {@link AgentProtocolConstants#PROTOCOL_AGENT_ID} /
 * {@link AgentProtocolConstants#PROTOCOL_SESSION_ID} bucket.
 *
 * <p>Pass a manager rooted at the protocol control-plane path (not an execution agent's
 * workspace) so multi-agent deployments keep task metadata separate from each agent's files.
 */
public final class WorkspaceProtocolTaskRepository implements ProtocolTaskRepository {

    private final WorkspaceManager workspaceManager;

    public WorkspaceProtocolTaskRepository(WorkspaceManager workspaceManager) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
    }

    /** Creates a repository rooted at {@code taskStorePath} with a fresh local workspace manager. */
    public WorkspaceProtocolTaskRepository(Path taskStorePath) {
        this(new WorkspaceManager(Objects.requireNonNull(taskStorePath, "taskStorePath")));
    }

    /** Returns the underlying workspace manager (control-plane root only). */
    public WorkspaceManager workspaceManager() {
        return workspaceManager;
    }

    @Override
    public void save(TaskRecord record) {
        Objects.requireNonNull(record, "record");
        workspaceManager.writeTaskRecord(
                RuntimeContext.empty(),
                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                record);
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return workspaceManager.readTaskRecord(
                RuntimeContext.empty(),
                AgentProtocolConstants.PROTOCOL_AGENT_ID,
                AgentProtocolConstants.PROTOCOL_SESSION_ID,
                taskId);
    }
}
