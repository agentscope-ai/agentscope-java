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
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agent.ExecutionIdentity;
import io.agentscope.core.event.AgentEvent;

/**
 * Resolves the AG-UI message identities of a live {@link AgentEvent}.
 *
 * <p>Replay and live delivery must expose the same {@code messageId} for the same content so a
 * client can append live frames to a replayed message. Both sides therefore use the stable
 * execution turn id; the per-block {@code replyId-blockId} pair remains only as a fallback for
 * streams that carry no execution identity.
 */
final class AgentEventIds {

    private static final String REASONING_SUFFIX = ":reasoning";

    private AgentEventIds() {}

    static String turnId(AgentEvent event) {
        if (event == null) {
            return null;
        }
        ExecutionIdentity execution = event.getExecution();
        return execution == null ? null : execution.turnId();
    }

    static String textMessageId(AgentEvent event, String replyId, String blockId) {
        String turnId = turnId(event);
        return isBlank(turnId) ? replyId + "-" + blockId : turnId;
    }

    static String reasoningMessageId(AgentEvent event, String replyId, String blockId) {
        String turnId = turnId(event);
        return isBlank(turnId) ? replyId + "-" + blockId : turnId + REASONING_SUFFIX;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
