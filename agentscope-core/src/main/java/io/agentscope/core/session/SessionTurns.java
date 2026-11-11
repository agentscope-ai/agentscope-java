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
package io.agentscope.core.session;

import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only task and input projection over one committed execution prefix. */
public record SessionTurns(
        List<Turn> turns, Map<String, List<Msg>> inputs, Set<String> started, Set<String> applied) {
    public record Turn(String turnId, String runId, String status) {
        public boolean ended() {
            return status.equals("completed") || status.equals("cancelled");
        }
    }

    public Turn latest() {
        return turns.isEmpty() ? null : turns.get(turns.size() - 1);
    }

    public static SessionTurns read(SessionLog log) {
        var turns = new LinkedHashMap<String, Turn>();
        var inputs = new LinkedHashMap<String, List<Msg>>();
        var started = new LinkedHashSet<String>();
        var applied = new LinkedHashSet<String>();
        for (var event : log.scan(0, log.head().seq())) {
            var data = event.data();
            if (event.type().equals("state/restored")) {
                turns.clear();
                inputs.clear();
            }

            if (event.type().equals("turn/cancelled"))
                turns.put(
                        event.turnId(),
                        new Turn(event.turnId(), event.executionRunId(), "cancelled"));
            if (event.type().equals("run/start"))
                turns.put(
                        event.turnId(),
                        new Turn(event.turnId(), event.executionRunId(), "running"));
            if (event.type().equals("run/end"))
                turns.put(
                        event.turnId(),
                        new Turn(
                                event.turnId(),
                                event.executionRunId(),
                                String.valueOf(data.get("status"))));
            if (event.type().equals("input/received") && event.turnId() != null) {
                var messages = inputs.computeIfAbsent(event.turnId(), ignored -> new ArrayList<>());
                for (var value : (List<?>) data.get("messages")) {
                    Msg msg = JsonUtils.getJsonCodec().convertValue(value, Msg.class);
                    if (messages.stream().noneMatch(m -> m.getId().equals(msg.getId())))
                        messages.add(msg);
                }
            }
            if (event.type().equals("inbox/started")) started.add((String) data.get("commandId"));
            if (event.type().equals("inbox/applied"))
                for (var id : (List<?>) data.get("commandIds")) applied.add((String) id);
        }
        inputs.replaceAll((id, messages) -> List.copyOf(messages));
        return new SessionTurns(
                List.copyOf(turns.values()),
                Map.copyOf(inputs),
                Set.copyOf(started),
                Set.copyOf(applied));
    }
}
