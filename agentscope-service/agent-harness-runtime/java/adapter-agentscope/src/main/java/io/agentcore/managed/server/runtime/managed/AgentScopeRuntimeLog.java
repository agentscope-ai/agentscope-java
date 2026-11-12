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

import java.util.Map;
import org.slf4j.Logger;

final class AgentScopeRuntimeLog {
    private AgentScopeRuntimeLog() {}

    public static void checkpoints(
            Logger logger, String event, ManagedRuntimeBatch batch, Object... fields) {
        if (!logger.isInfoEnabled()) return;
        for (var nativeEvent : batch.events()) {
            if (nativeEvent.data().get(ManagedCheckpointObjects.REFERENCE)
                    instanceof Map<?, ?> reference) {
                var identity = (Map<?, ?>) reference.get("identity");
                ManagedRuntimeLog.info(
                        logger,
                        event,
                        batch.scope(),
                        batch.turnId(),
                        ManagedRuntimeLog.append(
                                fields,
                                "checkpoint_id",
                                identity.get("checkpointId"),
                                "checkpoint_sequence",
                                nativeEvent.seq(),
                                "run_id",
                                nativeEvent.executionRunId(),
                                "epoch",
                                batch.epoch(),
                                "batch_id",
                                batch.batchId(),
                                "resume_phase",
                                nativeEvent.data().get("resumePhase"),
                                "suspension_id",
                                batch.suspension() == null ? null : batch.suspension().id(),
                                "pending_task_ids",
                                batch.suspension() == null ? null : batch.suspension().taskIds(),
                                "terminal_status",
                                batch.terminalStatus()));
            }
        }
    }
}
