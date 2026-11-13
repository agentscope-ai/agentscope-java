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

import io.agentscope.core.agui.AguiInterrupts;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Set;

/**
 * Converts permission-mode HITL {@link RequireUserConfirmEvent}s into AG-UI interrupt outcomes.
 *
 * <p>When an agent runs under {@code PermissionMode.DEFAULT}, the permission engine returns {@code
 * ASK} for non-readonly tools and {@code ReActAgent} emits a {@link RequireUserConfirmEvent} (paired
 * with a {@code RequestStopEvent(PERMISSION_ASKING)}) instead of executing the tool. This is a
 * distinct path from the tool-suspension flow handled by {@code AgentLifecycleEventConverter} (which
 * is gated on {@code GenerateReason.TOOL_SUSPENDED}).
 *
 * <p>Each pending {@link ToolUseBlock} is surfaced as one {@link AguiEvent.Interrupt} added to the
 * stream context; {@code AgentLifecycleEventConverter} drains them into the {@code RUN_FINISHED}
 * interrupt outcome on {@code AgentEndEvent}. The interrupt id reuses the {@code replyId:toolCallId}
 * format so the resume path can recover the reply/tool-call identity.
 *
 * <p>The interrupt metadata carries {@code toolContent} — a valid JSON-object string of the tool
 * arguments — so the resume path can rebuild a {@link ToolUseBlock} whose {@code content} is
 * non-null. This matters because {@code ReActAgent.applyConfirmResults} fully replaces the stored
 * {@code ToolUseBlock}, and tool-input validation reads {@code content} directly with no fallback to
 * {@code input}; a null content would fail the resume with {@code argument "content" is null}.
 *
 * <p>{@link UserConfirmResultEvent} silently adopts the validated tool-call ids so the new run
 * can deliver their results without replaying the previous run's tool-call events. Core emits
 * this event after validating the confirmation against its pending tool calls.
 */
final class PermissionConfirmEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireUserConfirmEvent.class, UserConfirmResultEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof UserConfirmResultEvent resultEvent) {
            for (ConfirmResult result : resultEvent.getConfirmResults()) {
                if (result.getToolCall() != null) {
                    context.adoptToolCall(result.getToolCall().getId());
                }
            }
            return;
        }
        RequireUserConfirmEvent confirmEvent = (RequireUserConfirmEvent) event;
        String replyId = confirmEvent.getReplyId();

        List<ToolUseBlock> toolCalls = confirmEvent.getToolCalls();
        for (ToolUseBlock toolUse : toolCalls) {
            // toolUse will not be null, see RequireUserConfirmEvent constructor
            if (isBlank(toolUse.getId())) {
                throw new IllegalStateException(
                        "RequireUserConfirmEvent contains a tool call without a stable id");
            }
            context.addInterrupt(AguiInterrupts.permissionConfirm(replyId, toolUse));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
