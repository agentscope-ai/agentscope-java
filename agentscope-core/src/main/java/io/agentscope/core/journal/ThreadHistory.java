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
package io.agentscope.core.journal;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Rebuildable human/history views; context replacement never erases earlier messages. */
public final class ThreadHistory {
    private ThreadHistory() {}

    public record Transcript(long asOfSeq, List<Msg> messages) {}

    public static Transcript transcript(ThreadJournal log) {
        long target = log.head().seq();
        var messages = new LinkedHashMap<String, Msg>();
        for (var event : log.scanMetadata(0, target)) {
            for (var msg : messages(log, event)) {
                if (event.type().equals("run/output")) messages.put(msg.getId(), msg);
                else messages.putIfAbsent(msg.getId(), msg);
            }
        }
        return new Transcript(target, List.copyOf(messages.values()));
    }

    /** Shared transcript extraction; ordinary checkpoints never contribute historical messages. */
    static List<Msg> messages(ThreadJournal log, JournalEvent event) {
        if (event.type().equals("state/initialized")) {
            String state = (String) log.materialize(event).data().get("stateJson");
            return state == null ? List.of() : AgentState.fromJsonString(state).getContext();
        }
        if (Set.of("message/user", "message/assistant", "tool/result", "run/output")
                .contains(event.type())) {
            Object value = event.data().get("message");
            if (value != null)
                return List.of(JsonUtils.getJsonCodec().convertValue(value, Msg.class));
        }
        return List.of();
    }
}
