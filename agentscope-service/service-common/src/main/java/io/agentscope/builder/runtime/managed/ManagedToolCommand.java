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
package io.agentscope.builder.runtime.managed;

import io.agentscope.builder.runtime.managed.ManagedJournalStore.ToolTask;
import java.util.Set;

/** Immutable workload command; its stable identity never contains a physical Brain lease. */
public record ManagedToolCommand(
        ManagedScope scope,
        String commandId,
        String kind,
        ToolTask task,
        String configuration,
        Submission submission) {
    public ManagedToolCommand(
            ManagedScope scope,
            String commandId,
            String kind,
            ToolTask task,
            String configuration) {
        this(scope, commandId, kind, task, configuration, null);
    }

    /** A publication attempt's fence, never the logical tool execution identity. */
    public record Submission(long epoch, String executionToken, String batchId) {
        public Submission {
            if (epoch < 1) throw new IllegalArgumentException("Invalid submission epoch");
            ManagedScope.requireText(executionToken, "submission token");
            ManagedScope.requireText(batchId, "submission batch");
        }
    }

    public ManagedToolCommand {
        if (scope == null || task == null || !Set.of("execute", "cancel", "replay").contains(kind))
            throw new IllegalArgumentException("Invalid tool command");
        ManagedScope.requireText(commandId, "command id");
        if (!task.id().equals(task.executionId()))
            throw new IllegalArgumentException("Execution identity differs");
    }

    public String messageGroup() {
        return ManagedScope.fingerprint(scope.messageGroup(), task.id()).substring(0, 48);
    }
}
