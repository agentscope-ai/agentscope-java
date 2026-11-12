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
package io.agentcore.managed.server.runtime.managed;

import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolClaim;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolReference;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Execution-side persistence contract; runtime implementations are used only for internal child control. */
public interface ManagedToolWorkStore {
    /** Platform-owned tools also observe the Session inbox; Hands uses its durable control ledger. */
    default boolean usesSessionInputCancellation() {
        return true;
    }

    /** Seal the captured result and make its output publication eligible. */
    default void complete(ManagedToolResult result) {
        toolEvents().finish(result);
        toolEvents().retryFirst(result.scope(), result.taskId());
    }

    default Duration toolOwnerLifetime() {
        return null;
    }

    ManagedToolEvents toolEvents();

    ToolClaim claimTool(ManagedScope scope, String task, String token);

    ToolTask recordToolHandle(ManagedScope scope, String task, String token, String handle);

    /** Refresh recovery scheduling only; the Redis lease remains the ownership authority. */
    default void toolHeartbeat(ManagedScope scope, String task, String token, long now) {}

    /** Release a fresh claim only when this owner can prove no external start was attempted. */
    default void unstarted(ManagedScope scope, ToolTask task) {}

    void markToolUnknown(ManagedScope scope, String task, String token);

    boolean cancelRequested(ManagedScope scope, String turn);

    default boolean cancelRequested(ManagedScope scope, ToolTask task) {
        return cancelRequested(scope, task.turnId());
    }

    boolean stageToolResult(ManagedToolResult result);

    Optional<ManagedToolResult> stagedToolResult(ManagedScope scope, String task);

    List<ToolReference> pendingToolResultPublications(int limit, long now);

    void toolResultPublishAttempt(ManagedToolResult result, long next);
}
