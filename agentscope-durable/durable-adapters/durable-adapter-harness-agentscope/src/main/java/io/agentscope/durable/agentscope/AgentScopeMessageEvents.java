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
package io.agentscope.durable.agentscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.EventInput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rebuilt from the native journal; commit a staged copy together with its public events. */
final class AgentScopeMessageEvents {
    private record Step(String messageId, String phase) {}

    private record Reference(String messageId, long attempt) {}

    private record Version(long attempt, long revision, String replyId, String completedHash) {}

    private final Map<String, Step> steps = new HashMap<>();
    private final Map<String, Reference> calls = new HashMap<>();
    private final Map<String, Reference> nativeMessages = new HashMap<>();
    private final Map<String, Version> versions = new HashMap<>();

    AgentScopeMessageEvents() {}

    AgentScopeMessageEvents(AgentScopeMessageEvents source) {
        steps.putAll(source.steps);
        calls.putAll(source.calls);
        nativeMessages.putAll(source.nativeMessages);
        versions.putAll(source.versions);
    }

    String messageId(String turnId, Msg message) {
        return messageId(turnId, message.getId());
    }

    String messageId(String turnId, String nativeId) {
        String key = nativeKey(turnId, nativeId);
        var reference = nativeMessages.get(key);
        return reference == null ? "message:" + key : reference.messageId();
    }

    ObjectNode presentationPosition(JournalEvent event) {
        var step = steps.get((String) event.data().get("stepId"));
        if (step == null || !"REASONING".equals(step.phase())) return null;
        var version = versions.get(step.messageId());
        if (version == null || version.completedHash() != null) return null;
        var value = payload(step.messageId(), version, "");
        value.remove("text");
        return value;
    }

    EventInput accept(JournalEvent event) {
        var data = DurableJson.value(event.data());
        String stepId = data.path("stepId").asText();
        if ("step/start".equals(event.type())) {
            steps.put(
                    stepId,
                    new Step(
                            "message:"
                                    + DurableJson.hash(
                                            List.of(
                                                    event.turnId(),
                                                    "reasoning",
                                                    data.path("iteration").asInt())),
                            data.path("phase").asText()));
        } else if ("step/phase".equals(event.type()) && steps.containsKey(stepId)) {
            steps.put(stepId, new Step(steps.get(stepId).messageId(), data.path("phase").asText()));
        } else if ("request/prepared".equals(event.type())
                && "REASONING".equals(data.path("purpose").asText())
                && steps.containsKey(stepId)) {
            String id = steps.get(stepId).messageId();
            var previous = versions.get(id);
            long attempt = previous == null ? 1 : Math.addExact(previous.attempt(), 1);
            versions.put(id, new Version(attempt, 0, null, null));
            calls.put(data.path("modelCallId").asText(), new Reference(id, attempt));
        } else if ("model/chunk".equals(event.type())) {
            var reference = calls.get(data.path("modelCallId").asText());
            if (reference != null && data.path("chunk").hasNonNull("id"))
                nativeMessages.put(
                        nativeKey(event.turnId(), data.path("chunk").path("id").asText()),
                        reference);
        } else if ("presentation/text".equals(event.type())
                || "presentation/tool_arguments".equals(event.type())) {
            var step = steps.get(stepId);
            if (step == null || !"REASONING".equals(step.phase())) return null;
            var version = versions.get(step.messageId());
            if (version == null || version.completedHash() != null) return null;
            String replyId = data.path("replyId").asText();
            long revision = version.revision();
            // Replaying a committed model response can restart its presentation without another
            // model request. Replace the old partial output instead of appending it again.
            if (version.replyId() != null && !version.replyId().equals(replyId))
                revision = Math.addExact(revision, 1);
            version = new Version(version.attempt(), revision, replyId, null);
            versions.put(step.messageId(), version);
            if ("presentation/tool_arguments".equals(event.type())) return null;
            var output = payload(step.messageId(), version, data.path("text").asText());
            if (data.hasNonNull("blockId")) output.set("blockId", data.get("blockId"));
            return event(event, "message.delta", output);
        } else if ("message/assistant".equals(event.type()) || "run/output".equals(event.type())) {
            Msg message =
                    JsonUtils.getJsonCodec().convertValue(event.data().get("message"), Msg.class);
            String id = messageId(event.turnId(), message);
            var publicMessage = AgentScopeMessages.output(message, id);
            if (publicMessage.content().isEmpty()
                    && (!message.hasContentBlocks(ToolUseBlock.class)
                            || "run/output".equals(event.type()))) return null;
            String hash = DurableJson.hash(publicMessage);
            var version = versions.getOrDefault(id, new Version(0, 0, null, null));
            var reference = nativeMessages.get(nativeKey(event.turnId(), message.getId()));
            if (reference != null && reference.attempt() < version.attempt()) return null;
            if (Objects.equals(hash, version.completedHash())) return null;
            long revision =
                    version.completedHash() == null
                            ? version.revision()
                            : Math.addExact(version.revision(), 1);
            version = new Version(version.attempt(), revision, version.replyId(), hash);
            versions.put(id, version);
            var output = payload(id, version, message.getTextContent());
            output.set("message", DurableJson.value(publicMessage));
            return event(event, "message.completed", output);
        }
        return null;
    }

    private static String nativeKey(String turnId, String nativeId) {
        return DurableJson.hash(List.of(turnId, nativeId));
    }

    private static ObjectNode payload(String id, Version version, String text) {
        return (ObjectNode)
                DurableJson.value(
                        Map.of(
                                "messageId",
                                id,
                                "attempt",
                                version.attempt(),
                                "revision",
                                version.revision(),
                                "text",
                                text == null ? "" : text));
    }

    private static EventInput event(JournalEvent source, String type, ObjectNode data) {
        return new EventInput(
                source.eventId(),
                type,
                data,
                source.executionRunId(),
                (String) source.data().get("stepId"));
    }
}
