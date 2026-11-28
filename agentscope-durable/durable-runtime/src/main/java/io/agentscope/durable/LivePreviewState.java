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
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import java.util.LinkedHashSet;
import java.util.Set;

/** Suppresses recent previews overtaken by committed messages, tool arguments, or outcomes. */
final class LivePreviewState {
    private static final int MAX_COMPLETIONS = 4096;
    private static final Set<String> TERMINAL_TOOLS =
            Set.of("SUCCEEDED", "FAILED", "CANCELLED", "DENIED");
    private final Set<String> completed = new LinkedHashSet<>();

    void committed(PublicEvent event) {
        switch (event.type()) {
            case "message.completed" ->
                    remember("message:" + event.data().path("messageId").asText());
            case "tool.call.updated" -> {
                String tool =
                        EventProjection.toolId(event.threadId(), event.turnId(), event.data());
                remember("arguments:" + tool);
                if (TERMINAL_TOOLS.contains(event.data().path("status").asText()))
                    remember("output:" + tool);
            }
            case "tool.result.accepted" ->
                    remember(
                            "output:"
                                    + EventProjection.toolId(
                                            event.threadId(), event.turnId(), event.data()));
            default -> {}
        }
    }

    boolean accepts(LiveEvent event) {
        var data = event.data();
        return switch (event.type()) {
            case "message.started", "message.delta" ->
                    !completed.contains("message:" + data.path("messageId").asText());
            case "tool.arguments.delta" ->
                    !completed.contains("message:" + data.path("messageId").asText())
                            && (!data.hasNonNull("toolCallId")
                                    || !completed.contains(
                                            "arguments:"
                                                    + EventProjection.toolId(
                                                            event.threadId(),
                                                            event.turnId(),
                                                            data.path("toolCallId").asText())));
            case "tool.output.delta" ->
                    !completed.contains(
                            "output:"
                                    + EventProjection.toolId(
                                            event.threadId(), event.turnId(), data));
            default -> false;
        };
    }

    private void remember(String completion) {
        if (completed.add(completion) && completed.size() > MAX_COMPLETIONS) {
            var oldest = completed.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
