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
import io.agentscope.core.message.TextBlock;

/**
 * Converter for handling TextBlock events, transforming them into AG-UI TextMessage events.
 */
public class TextBlockConverter implements BlockEventConverter<TextBlock> {

    @Override
    public boolean isApplicable(Event event) {
        return event.getType() == EventType.REASONING || event.getType() == EventType.SUMMARY;
    }

    @Override
    public void convert(TextBlock block, Event event, StreamContext ctx) {
        String msgId = event.getMessage().getId();
        // The tool call event will terminate all open text events (assuming text event msgId=1)
        // After a round of reasoning (including reasonings, texts, and tool calls event),
        // the event.last=true packet will carry the complete accumulation of the text event with
        // msgId=1, which needs to be blocked
        if (ctx.isTextFinished(msgId) && event.isLast()) {
            return;
        }

        String text = block.getText();
        if (text != null && !text.isEmpty()) {
            if (!ctx.isTextActive(msgId)) {
                ctx.flushAllActiveTexts();
                // When the text start, it signifies that the reasoning should end
                ctx.flushAllActiveReasonings();

                ctx.emit(
                        new AguiEvent.TextMessageStart(
                                ctx.getThreadId(), ctx.getRunId(), msgId, "assistant"));
                ctx.deferEndEvent(
                        StreamContext.PREFIX_TEXT + msgId,
                        new AguiEvent.TextMessageEnd(ctx.getThreadId(), ctx.getRunId(), msgId));
                ctx.addActiveText(msgId);
            }

            if (!event.isLast()) {
                ctx.emit(
                        new AguiEvent.TextMessageContent(
                                ctx.getThreadId(), ctx.getRunId(), msgId, text));
            }
        }

        if (event.isLast() && ctx.isTextActive(msgId)) {
            ctx.flushEndEvent(StreamContext.PREFIX_TEXT + msgId);
            ctx.removeActiveText(msgId);
        }
    }
}
