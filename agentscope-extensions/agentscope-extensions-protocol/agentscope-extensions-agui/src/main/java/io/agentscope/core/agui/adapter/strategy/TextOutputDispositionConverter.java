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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextOutputDisposition;
import io.agentscope.core.event.TextOutputDispositionEvent;
import io.agentscope.core.message.GenerateReason;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Converts opt-in text output lifecycle signals to AG-UI custom events and final snapshots. */
public final class TextOutputDispositionConverter implements AgentEventConverter {

    public static final String EVENT_NAME = "agentscope.text_output.disposition";

    private static final Set<GenerateReason> FINAL_REASONS =
            EnumSet.of(
                    GenerateReason.MODEL_STOP,
                    GenerateReason.STRUCTURED_OUTPUT,
                    GenerateReason.MAX_ITERATIONS);

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(TextOutputDispositionEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        TextOutputDispositionEvent dispositionEvent = (TextOutputDispositionEvent) event;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("replyId", dispositionEvent.getReplyId());
        value.put("messageIds", context.getTextMessageIds(dispositionEvent.getReplyId()));
        value.put("disposition", dispositionEvent.getDisposition().name());
        value.put(
                "generateReason",
                dispositionEvent.getGenerateReason() != null
                        ? dispositionEvent.getGenerateReason().name()
                        : null);
        context.emit(
                new AguiEvent.Custom(
                        context.getThreadId(),
                        context.getRunId(),
                        EVENT_NAME,
                        Collections.unmodifiableMap(value)));

        if (dispositionEvent.getDisposition() == TextOutputDisposition.TERMINAL
                && FINAL_REASONS.contains(dispositionEvent.getGenerateReason())) {
            context.emitFinalMessagesSnapshot();
        }
    }
}
