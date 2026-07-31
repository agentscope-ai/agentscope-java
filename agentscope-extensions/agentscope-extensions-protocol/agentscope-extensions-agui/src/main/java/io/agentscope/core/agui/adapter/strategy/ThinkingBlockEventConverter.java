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

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import java.util.Set;

final class ThinkingBlockEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(
                ThinkingBlockStartEvent.class,
                ThinkingBlockDeltaEvent.class,
                ThinkingBlockEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        // ignore if reasoning output is disabled
        if (!context.getConfig().isEnableReasoning()) {
            return;
        }

        if (event instanceof ThinkingBlockDeltaEvent delta) {
            // AguiEvent.ReasoningMessageStart delays sending when content arrives
            context.appendReasoningDelta(delta.getReplyId(), delta.getDelta());
        } else if (event instanceof ThinkingBlockEndEvent end) {
            context.closeReasoningMessage(end.getReplyId());
        }
    }
}
