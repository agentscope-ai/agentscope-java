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

import io.agentcore.managed.harness.agentscope.AgentScopeEventSchemas;
import java.util.LinkedHashMap;
import java.util.Map;

/** Captures AgentScope continuation metadata before native state crosses the platform boundary. */
public final class AgentScopeCheckpoints {
    private AgentScopeCheckpoints() {}

    public static Map<Long, Map<String, Object>> verify(
            ManagedCheckpointObjects objects, ManagedRuntimeBatch batch) {
        AgentScopeEventSchemas.ensureRegistered();
        return objects.verify(batch.scope(), batch.epoch(), batch.events());
    }

    public static ManagedRuntimeBatch prepare(
            ManagedCheckpointObjects objects, ManagedRuntimeBatch batch) {
        return prepare(objects, batch, Map.of());
    }

    /** HarnessRuntime calls this before publication, while the checkpoint's native state is still frozen. */
    public static ManagedRuntimeBatch prepare(
            ManagedCheckpointObjects objects,
            ManagedRuntimeBatch batch,
            Map<String, Object> context) {
        var facts = new LinkedHashMap<String, Object>(context);
        facts.put("scope", batch.scope());
        facts.put("turnId", batch.turnId());
        facts.put("epoch", batch.epoch());
        facts.put("batchId", batch.batchId());
        facts.put("acceptedThrough", batch.expectedSequence() + batch.events().size());
        facts.put("inputReceipts", batch.inputReceipts());
        facts.put("toolIntents", batch.toolIntents());
        if (batch.suspension() != null) {
            facts.put("suspensionId", batch.suspension().id());
            facts.put("pendingTaskIds", batch.suspension().taskIds());
            facts.put("pendingActionIds", batch.suspension().actionIds());
            facts.put("resumePhase", batch.suspension().resumePhase());
        }
        if (batch.terminalStatus() != null) facts.put("terminalStatus", batch.terminalStatus());
        return batch.withEvents(
                batch.events().stream()
                        .map(
                                event ->
                                        objects.externalize(
                                                batch.scope(), batch.epoch(), event, facts))
                        .toList());
    }
}
