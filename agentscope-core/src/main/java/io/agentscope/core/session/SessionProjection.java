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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only recovery view. Inspection never repairs records or invokes models/tools. */
public record SessionProjection(
        long asOfSeq, String stateJson, Set<String> uncertainToolCalls, Set<String> activeRuns) {
    public static SessionProjection read(SessionLog log) {
        long cursor = 0;
        String state = null;
        var uncertain = new LinkedHashSet<String>();
        var runs = new LinkedHashSet<String>();
        var results = new LinkedHashMap<String, ToolResultBlock>();
        var verifications = new ArrayList<io.agentscope.core.state.TaskVerification>();
        var extensions = new ArrayList<SessionEvent>();
        long upper = log.head().seq();
        for (var event : log.scan(0, upper)) {
            SessionEventTypes.validate(event);
            var data = event.data();
            switch (event.type()) {
                case "state/checkpoint", "migration/baseline", "state/restored" -> {
                    state = (String) data.get("stateJson");
                    results.clear();
                    verifications.clear();
                    extensions.clear();
                }
                case "verification/result" -> {
                    if (data.get("verification") != null)
                        verifications.add(
                                JsonUtils.getJsonCodec()
                                        .convertValue(
                                                data.get("verification"),
                                                io.agentscope.core.state.TaskVerification.class));
                }
                case "tool/dispatch" -> uncertain.add((String) data.get("toolCallId"));
                case "action/end" -> {
                    if (data.get("result") != null) {
                        String id = (String) data.get("toolCallId");
                        uncertain.remove(id);
                        // Early native action payloads omitted the polymorphic discriminator.
                        var result =
                                new LinkedHashMap<Object, Object>((Map<?, ?>) data.get("result"));
                        result.putIfAbsent("type", "tool_result");
                        results.put(
                                id,
                                JsonUtils.getJsonCodec()
                                        .convertValue(result, ToolResultBlock.class));
                    }
                }
                case "recovery/applied" -> {
                    Object interrupted = data.get("interruptedRuns");
                    if (interrupted instanceof List<?> ids) ids.forEach(runs::remove);
                    Object resolved = data.get("resolvedToolCallIds");
                    if (resolved instanceof List<?> ids) ids.forEach(uncertain::remove);
                }
                case "run/start" -> runs.add(event.executionRunId());
                case "run/end" -> runs.remove(event.executionRunId());
                default -> {
                    if (!SessionEventTypes.BUILTIN.contains(event.type())) extensions.add(event);
                }
            }
            cursor = event.seq();
        }
        if (state != null
                && (!results.isEmpty() || !verifications.isEmpty() || !extensions.isEmpty())) {
            AgentState restored = AgentState.fromJsonString(state);
            var existing = new HashSet<String>();
            for (var message : restored.getContext())
                for (var result : message.getContentBlocks(ToolResultBlock.class))
                    existing.add(result.getId());
            for (var entry : results.entrySet())
                if (!existing.contains(entry.getKey()) && !entry.getValue().isSuspended())
                    restored.contextMutable()
                            .add(
                                    ToolResultMessage.builder()
                                            .id("recovered_" + entry.getKey())
                                            .result(entry.getValue())
                                            .build());
            for (var report : verifications)
                restored.getTasksContext()
                        .recordVerification(
                                report, restored.getTasksContext().snapshot().getRevision());
            for (var extension : extensions)
                SessionEventCodecRegistry.defaultRegistry().applyExtension(restored, extension);
            state = restored.toJson();
        }
        return new SessionProjection(cursor, state, Set.copyOf(uncertain), Set.copyOf(runs));
    }

    public AgentState restore() {
        return stateJson == null ? null : AgentState.fromJsonString(stateJson);
    }
}
