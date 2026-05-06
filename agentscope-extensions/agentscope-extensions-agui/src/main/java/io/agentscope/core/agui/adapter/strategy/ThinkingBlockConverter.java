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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agui.adapter.StreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ThinkingBlock;

/**
 * Converter for handling ThinkingBlock events, transforming them into AG-UI ReasoningMessage events.
 */
public class ThinkingBlockConverter implements BlockEventConverter<ThinkingBlock> {

    @Override
    public boolean isApplicable(Event event) {
        return event.getType() == EventType.REASONING || event.getType() == EventType.SUMMARY;
    }

    @Override
    public void convert(ThinkingBlock block, Event event, StreamContext ctx) {
        // ignore if reasoning output is disabled
        if (!ctx.getConfig().isEnableReasoning()) {
            return;
        }

        String thinking = block.getThinking();
        String msgId = event.getMessage().getId();

        if (thinking != null && !thinking.isBlank()) {
            if (!ctx.isReasoningActive(msgId)) {
                ctx.flushAllActiveReasonings();

                ctx.emit(
                        new AguiEvent.ReasoningMessageStart(
                                ctx.getThreadId(), ctx.getRunId(), msgId, "reasoning"));
                ctx.deferEndEvent(
                        StreamContext.PREFIX_REASONING + msgId,
                        new AguiEvent.ReasoningMessageEnd(
                                ctx.getThreadId(), ctx.getRunId(), msgId));
                ctx.addActiveReasoning(msgId);
            }

            ctx.emit(
                    new AguiEvent.ReasoningMessageContent(
                            ctx.getThreadId(), ctx.getRunId(), msgId, thinking));
        }

        if (event.isLast() && ctx.isReasoningActive(msgId)) {
            ctx.flushEndEvent(StreamContext.PREFIX_REASONING + msgId);
            ctx.removeActiveReasoning(msgId);
        }
    }
}
