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
import io.agentscope.core.event.ModelCallEndEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ModelCallUsageEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(ModelCallEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        ModelCallEndEvent end = (ModelCallEndEvent) event;
        if (!context.getConfig().isEmitTokenUsage() || end.getUsage() == null) {
            return;
        }

        AguiStreamContext.TokenUsageSnapshot usage =
                context.getTokenUsageAccumulator().add(end.getUsage());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("delta", tokenUsageMap(usage.delta()));
        value.put("cumulative", tokenUsageMap(usage.cumulative()));
        Map<String, Object> modelCall = new LinkedHashMap<>();
        modelCall.put("replyId", end.getReplyId());
        value.put("modelCall", modelCall);
        context.emit(
                new AguiEvent.Custom(
                        context.getThreadId(), context.getRunId(), "token_usage", value));
    }

    private static Map<String, Object> tokenUsageMap(AguiStreamContext.TokenUsage usage) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("inputTokens", usage.inputTokens());
        value.put("outputTokens", usage.outputTokens());
        value.put("cachedTokens", usage.cachedTokens());
        value.put("totalTokens", usage.totalTokens());
        value.put("time", usage.time());
        return value;
    }
}
