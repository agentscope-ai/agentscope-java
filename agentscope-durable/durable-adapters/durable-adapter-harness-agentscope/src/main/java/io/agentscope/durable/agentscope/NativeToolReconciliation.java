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

package io.agentscope.durable.agentscope;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.Signal;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.WaitCondition;
import io.agentscope.durable.SessionBackend;
import java.util.HashSet;
import java.util.List;

/** Host-authorized results independently verified for a Turn awaiting native reconciliation. */
public record NativeToolReconciliation(
        String requestId, String evidenceRef, List<ToolResultBlock> outcomes) {
    public NativeToolReconciliation {
        DurableJson.text(requestId, "requestId");
        if (evidenceRef == null || evidenceRef.isBlank() || evidenceRef.length() > 2048)
            throw new IllegalArgumentException("A verified evidence reference is required");
        outcomes = List.copyOf(outcomes);
        if (outcomes.isEmpty())
            throw new IllegalArgumentException("Verified tool results are required");
        var ids = new HashSet<String>();
        for (var result : outcomes)
            if (!ids.add(DurableJson.text(result.getId(), "callId"))
                    || result.isSuspended()
                    || result.hasUnknownOutcome()
                    || result.getState() == ToolResultState.RUNNING)
                throw new IllegalArgumentException(
                        "Each original call requires one verified terminal result");
    }

    /** Queues evidence through the privileged backend channel. Retry with the same command. */
    public void submit(SessionBackend backend, ThreadKey thread, String turnId) {
        backend.signal(
                thread,
                turnId,
                new Signal(
                        signalId(),
                        WaitCondition.NATIVE_TOOL_RECONCILIATION,
                        DurableJson.value(this)));
    }

    String signalId() {
        return "native-tool-reconciliation:" + DurableJson.hash(requestId);
    }
}
