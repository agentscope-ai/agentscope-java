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

        String msgId = event.getMessage().getId();
        // The tool call event will terminate all open reasoning events (assuming reasoning event
        // msgId=2). After a round of reasoning (including reasonings, texts, and tool calls event),
        // the event.last=true packet will carry the complete accumulation of the reasoning event
        // with msgId=2, which needs to be blocked
        if (ctx.isReasoningFinished(msgId) && event.isLast()) {
            return;
        }

        String thinking = block.getThinking();
        if (thinking != null && !thinking.isEmpty()) {
            if (!ctx.isReasoningActive(msgId)) {
                ctx.flushAllActiveReasonings();
                ctx.flushAllActiveTexts();

                ctx.emit(
                        new AguiEvent.ReasoningMessageStart(
                                ctx.getThreadId(), ctx.getRunId(), msgId, "reasoning"));
                ctx.deferEndEvent(
                        StreamContext.PREFIX_REASONING + msgId,
                        new AguiEvent.ReasoningMessageEnd(
                                ctx.getThreadId(), ctx.getRunId(), msgId));
                ctx.addActiveReasoning(msgId);
            }

            if (!event.isLast()) {
                ctx.emit(
                        new AguiEvent.ReasoningMessageContent(
                                ctx.getThreadId(), ctx.getRunId(), msgId, thinking));
            }
        }

        if (event.isLast() && ctx.isReasoningActive(msgId)) {
            ctx.flushEndEvent(StreamContext.PREFIX_REASONING + msgId);
            ctx.removeActiveReasoning(msgId);
        }
    }
}
