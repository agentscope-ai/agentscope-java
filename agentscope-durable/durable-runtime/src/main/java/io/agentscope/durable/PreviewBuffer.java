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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.LiveEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/** Best-effort previews never block publishers or advance the durable cursor. */
final class PreviewBuffer {
    static final int CAPACITY = 128;
    static final int BATCH = 32;
    private final ArrayBlockingQueue<LiveEvent> queue = new ArrayBlockingQueue<>(CAPACITY);

    boolean offer(LiveEvent event) {
        return queue.offer(event);
    }

    int drain(DurableSession session, Consumer<LiveEvent> sink) {
        var batch = new ArrayList<LiveEvent>(BATCH);
        queue.drainTo(batch, BATCH);
        if (!batch.isEmpty()) session.pendingPreviews(batch).forEach(sink);
        return batch.size();
    }

    static List<LiveEvent> pending(
            List<LiveEvent> events,
            Function<String, JsonNode> messages,
            Function<String, JsonNode> tools) {
        return events.stream()
                .filter(
                        event -> {
                            var message = event.data().path("messageId").asText(null);
                            if (message != null) {
                                var row = messages.apply(message);
                                if (row != null && row.path("complete").asBoolean()) return false;
                            }
                            var call = event.data().path("toolCallId").asText(null);
                            if (call == null) return true;
                            var row =
                                    tools.apply(
                                            EventProjection.toolId(
                                                    event.threadId(), event.turnId(), call));
                            return row == null
                                    || "GENERATING_ARGUMENTS"
                                            .equals(row.path("data").path("status").asText());
                        })
                .toList();
    }
}
