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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.journal.JournalEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages;
import io.agentscope.durable.DurableTypes.EventInput;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native tool observations do not create or impersonate Hands execution records. */
final class AgentScopeToolEvents {
    private final Set<String> returned = new HashSet<>();
    private final Set<String> uncertain = new HashSet<>();

    AgentScopeToolEvents() {}

    AgentScopeToolEvents(AgentScopeToolEvents source) {
        returned.addAll(source.returned);
        uncertain.addAll(source.uncertain);
    }

    List<EventInput> accept(JournalEvent event, AgentScopeMessageEvents messages) {
        var data = event.data();
        var result = new ArrayList<EventInput>();
        switch (event.type()) {
            case "tool/requested" -> {
                var call =
                        JsonUtils.getJsonCodec().convertValue(data.get("call"), ToolUseBlock.class);
                var value = update(call.getId(), "REQUESTED");
                value.put("name", call.getName());
                value.set("arguments", DurableJson.value(call.getInput()));
                value.put(
                        "messageId",
                        messages.messageId(event.turnId(), (String) data.get("messageId")));
                result.add(event(event, "tool.call.updated", value));
            }
            case "tool/dispatch" ->
                    result.add(
                            event(
                                    event,
                                    "tool.call.updated",
                                    update((String) data.get("toolCallId"), "RUNNING")));
            case "presentation/tool_result" -> {
                String callId = (String) data.get("toolCallId");
                if (!uncertain.contains(key(event, callId)))
                    result.add(
                            event(
                                    event,
                                    "tool.call.updated",
                                    update(
                                            callId,
                                            status(
                                                    ToolResultState.valueOf(
                                                            (String) data.get("status"))))));
            }
            case "tool/chunk" -> {
                if (uncertain.contains(key(event, (String) data.get("toolCallId")))) break;
                var chunk =
                        JsonUtils.getJsonCodec()
                                .convertValue(data.get("chunk"), ToolResultBlock.class);
                var value =
                        DurableJson.value(
                                Map.of(
                                        "callId",
                                        data.get("toolCallId"),
                                        "content",
                                        content(chunk)));
                result.add(event(event, "tool.call.progress", (ObjectNode) value));
            }
            case "interaction/requested" -> {
                var call =
                        JsonUtils.getJsonCodec().convertValue(data.get("call"), ToolUseBlock.class);
                var value = update(call.getId(), "WAITING");
                value.put("reason", (String) data.get("kind"));
                result.add(event(event, "tool.call.updated", value));
            }
            case "interaction/resolved" -> {
                if ("external_execution".equals(data.get("kind")) && data.get("result") != null) {
                    var block =
                            JsonUtils.getJsonCodec()
                                    .convertValue(data.get("result"), ToolResultBlock.class);
                    var value = outcome(block);
                    result.add(event(event, "tool.call.updated", value));
                    if ("UNKNOWN".equals(value.path("status").asText()))
                        uncertain.add(key(event, block.getId()));
                    if (!block.isSuspended()) returned.add(key(event, block.getId()));
                }
            }
            case "action/end" -> {
                String callId = (String) data.get("toolCallId");
                if (data.get("result") != null) {
                    var block =
                            JsonUtils.getJsonCodec()
                                    .convertValue(data.get("result"), ToolResultBlock.class);
                    var value = outcome(block);
                    result.add(event(event, "tool.call.updated", value));
                    if ("UNKNOWN".equals(value.path("status").asText()))
                        uncertain.add(key(event, callId));
                    if (!block.isSuspended()) returned.add(key(event, callId));
                } else {
                    // An exception or interrupted observer does not prove that a side effect
                    // failed.
                    var value = update(callId, "UNKNOWN");
                    value.put("reason", "invocation_ended_without_result");
                    uncertain.add(key(event, callId));
                    result.add(event(event, "tool.call.updated", value));
                }
            }
            case "tool/result" -> {
                var message = JsonUtils.getJsonCodec().convertValue(data.get("message"), Msg.class);
                for (var block : message.getContentBlocks(ToolResultBlock.class)) {
                    // Native state may contain a returnDirect placeholder instead of the observed
                    // output. Keep the actual action result; explicit reconciliation can replace
                    // it.
                    boolean reconciled = Boolean.TRUE.equals(data.get("reconciled"));
                    String key = key(event, block.getId());
                    if (reconciled || !returned.contains(key) && !uncertain.contains(key)) {
                        var value = outcome(block);
                        if ("UNKNOWN".equals(value.path("status").asText())) uncertain.add(key);
                        else if (reconciled) uncertain.remove(key);
                        result.add(event(event, "tool.call.updated", value));
                    }
                }
            }
            default -> {}
        }
        return result;
    }

    private static ObjectNode outcome(ToolResultBlock result) {
        String status = status(result.getState());
        if (result.getExecutionDetails() != null
                && "UNKNOWN".equals(result.getExecutionDetails().outcome().name()))
            status = "UNKNOWN";
        var value = update(result.getId(), status);
        value.set("content", content(result));
        return value;
    }

    private static String status(ToolResultState state) {
        return switch (state) {
            case SUCCESS -> "SUCCEEDED";
            case ERROR -> "FAILED";
            case DENIED -> "DENIED";
            case RUNNING -> "RUNNING";
            case SUSPENDED -> "WAITING";
            case INTERRUPTED -> "UNKNOWN";
        };
    }

    private static JsonNode content(ToolResultBlock result) {
        return DurableJson.value(
                        new DurableMessages.Input(
                                "1", AgentScopeMessages.content(result.getOutput())))
                .get("content");
    }

    private static ObjectNode update(String callId, String status) {
        return (ObjectNode) DurableJson.value(Map.of("callId", callId, "status", status));
    }

    private static String key(JournalEvent event, String callId) {
        return DurableJson.hash(List.of(event.turnId(), callId));
    }

    private static EventInput event(JournalEvent source, String type, ObjectNode data) {
        return new EventInput(
                "tool:"
                        + DurableJson.hash(
                                List.of(source.eventId(), type, data.path("callId").asText())),
                type,
                data);
    }
}
