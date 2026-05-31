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
package io.agentscope.spring.boot.admin.metrics;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.legacy.hook.Hook;
import io.agentscope.core.legacy.hook.HookEvent;
import io.agentscope.core.legacy.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import reactor.core.publisher.Mono;

/**
 * Legacy {@link Hook} that observes {@link PostCallEvent} and forwards the per-call token usage
 * to a {@link MetricsRecorder}.
 *
 * <p>The hook is intentionally a non-modifying observer: it never mutates the agent's final
 * message and always returns the event verbatim, so it is safe to register globally via
 * {@link io.agentscope.core.agent.AgentBase#addSystemHook(Hook)}.
 *
 * <p><b>Timing caveat:</b> {@code addSystemHook} only applies to agents constructed AFTER the
 * hook is registered (see {@code AgentBase} constructor). Agents instantiated before the admin
 * starter wires this hook in will not contribute to usage stats.
 */
@SuppressWarnings("deprecation")
public final class MetricsHook implements Hook {

    private final MetricsRecorder recorder;

    public MetricsHook(MetricsRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public int priority() {
        // Run late — we only read, we shouldn't preempt validation/security hooks.
        return 900;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostCallEvent post) {
            recordSafe(post);
        }
        return Mono.just(event);
    }

    private void recordSafe(PostCallEvent post) {
        try {
            Agent agent = post.getAgent();
            String agentName = agent == null ? null : agent.getName();
            String modelName = null;
            if (agent instanceof ReActAgent react && react.getModel() != null) {
                modelName = react.getModel().getModelName();
            }
            Msg msg = post.getFinalMessage();
            ChatUsage usage = msg == null ? null : msg.getUsage();
            if (usage == null) {
                // Still count the call even when usage info is missing.
                recorder.record(agentName, modelName, 0, 0);
                return;
            }
            recorder.record(agentName, modelName, usage.getInputTokens(), usage.getOutputTokens());
        } catch (RuntimeException ignored) {
            // Never let a metrics hook break agent execution.
        }
    }
}
