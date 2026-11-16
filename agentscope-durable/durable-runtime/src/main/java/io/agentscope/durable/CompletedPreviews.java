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
import io.agentscope.durable.DurableTypes.Snapshot;
import java.util.HashSet;
import java.util.Set;

/** Accessed by the serial delivery thread. Durable state supersedes delayed live previews. */
final class CompletedPreviews {
    private final Set<String> messages = new HashSet<>();
    private final Set<String> tools = new HashSet<>();

    void reset(Snapshot snapshot) {
        messages.clear();
        tools.clear();
        for (var message : snapshot.projection().path("messages"))
            if (message.path("complete").asBoolean())
                messages.add(message.required("messageId").asText());
        snapshot.projection()
                .path("tools")
                .fields()
                .forEachRemaining(
                        entry -> {
                            if (!"GENERATING_ARGUMENTS"
                                    .equals(entry.getValue().path("data").path("status").asText()))
                                tools.add(entry.getKey());
                        });
    }

    void accept(PublicEvent event) {
        if ("message.completed".equals(event.type()))
            messages.add(event.data().required("messageId").asText());
        if ("tool.call.updated".equals(event.type()))
            tools.add(EventProjection.toolId(event.threadId(), event.turnId(), event.data()));
    }

    boolean contains(LiveEvent event) {
        String message = event.data().path("messageId").asText(null);
        String call = event.data().path("toolCallId").asText(null);
        return (message != null && messages.contains(message))
                || (call != null
                        && tools.contains(
                                EventProjection.toolId(event.threadId(), event.turnId(), call)));
    }
}
