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
package io.agentscope.examples.advanced.hitl;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Hook that intercepts tool calls requiring user confirmation.
 *
 * <p>When the agent's reasoning output contains a tool call whose name is in
 * {@link #toolsRequiringConfirmation}, this hook stops the agent so the
 * frontend can render approve/reject buttons before execution proceeds.
 */
public class ToolConfirmationHook implements Hook {

    private final Set<String> toolsRequiringConfirmation;

    public ToolConfirmationHook(Set<String> toolsRequiringConfirmation) {
        this.toolsRequiringConfirmation = toolsRequiringConfirmation;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent post) {
            Msg reasoning = post.getReasoningMessage();
            if (reasoning != null && hasToolRequiringConfirmation(reasoning)) {
                post.stopAgent();
            }
        }
        return Mono.just(event);
    }

    private boolean hasToolRequiringConfirmation(Msg reasoning) {
        return reasoning.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(t -> toolsRequiringConfirmation.contains(t.getName()));
    }
}
