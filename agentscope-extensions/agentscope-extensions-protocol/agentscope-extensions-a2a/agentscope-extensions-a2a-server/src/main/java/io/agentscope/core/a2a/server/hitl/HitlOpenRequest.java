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

package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;

/** Request to persist a newly-open durable handoff. */
public record HitlOpenRequest(
        String taskId,
        String contextId,
        HitlExecutionKey executionKey,
        A2aHandoffType type,
        List<ToolUseBlock> pendingTools,
        String nextResumeToken,
        Duration ttl,
        String claimedHandoffId) {

    public HitlOpenRequest {
        pendingTools = pendingTools == null ? List.of() : List.copyOf(pendingTools);
    }

    @Override
    public String toString() {
        return "HitlOpenRequest[taskId="
                + taskId
                + ", contextId="
                + contextId
                + ", executionKey="
                + executionKey
                + ", type="
                + type
                + ", pendingTools="
                + pendingTools.size()
                + ", nextResumeToken=<redacted>, ttl="
                + ttl
                + ", claimedHandoffId="
                + claimedHandoffId
                + ']';
    }
}
