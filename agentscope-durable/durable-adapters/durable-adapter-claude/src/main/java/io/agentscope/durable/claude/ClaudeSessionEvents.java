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
package io.agentscope.durable.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableMessages.Base64;
import io.agentscope.durable.DurableMessages.Content;
import io.agentscope.durable.DurableMessages.Image;
import io.agentscope.durable.DurableMessages.Input;
import io.agentscope.durable.DurableMessages.Text;
import io.agentscope.durable.DurableMessages.ToolResponse;
import io.agentscope.durable.DurableMessages.Url;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableSession;
import io.agentscope.durable.DurableTurn;
import io.agentscope.durable.store.RecordStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Claude Managed Agents session event operations on an application-authorized Durable Session. */
public final class ClaudeSessionEvents {
    private static final int MAX_BATCH = 100;
    private final DurableSession session;

    public ClaudeSessionEvents(DurableSession session) {
        this.session = Objects.requireNonNull(session);
    }

    /** Lists committed Claude events using the returned next_page token for the same selection. */
    public JsonNode listEvents(String page, int limit, Set<String> types) {
        return ClaudeEventHistory.list(session, page, limit, types);
    }

    JsonNode sendBatch(RecordStore batches, String requestId, JsonNode events) {
        if (!events.isArray() || events.isEmpty() || events.size() > MAX_BATCH)
            throw invalid("Expected an events array of 1..100 items");
        var operations = new ArrayList<Function<String, JsonNode>>(events.size());
        events.forEach(event -> operations.add(prepare(event)));
        String digest = DurableJson.hash(events);
        String batchId = DurableJson.hash(List.of(session.key(), requestId));
        batches.transaction(
                "claude-event-batch:" + batchId,
                tx -> {
                    var prior = tx.get("");
                    if (prior == null || prior.isNull()) tx.put("", DurableJson.value(digest));
                    else if (!digest.equals(prior.asText()))
                        throw new DurableException(
                                DurableException.Code.CONFLICT,
                                "Idempotency-Key reused with a different batch");
                    return null;
                });
        var receipts = new ArrayList<JsonNode>(operations.size());
        for (int i = 0; i < operations.size(); i++)
            receipts.add(operations.get(i).apply("batch:" + batchId + ":" + i));
        return DurableJson.value(Map.of("data", receipts));
    }

    /**
     * Queues one Claude user.message and returns its admission event. A host preserves the
     * operation identity on retries and provides a fresh one for each independent submission.
     */
    public JsonNode sendMessage(String requestId, JsonNode event) {
        return prepareMessage(event).apply(requestId);
    }

    private Function<String, JsonNode> prepareMessage(JsonNode event) {
        String type = requiredText(event.path("type"), "type");
        if (!"user.message".equals(type)) throw unsupported("event type", type);
        fields(event, Set.of("type", "content"));
        var blocks = event.path("content");
        var content = blocks(blocks);
        if (content.isEmpty()) throw invalid("User messages require content");
        var input = new Input("1", content);
        DurableProtocol.validate("input", input);
        return requestId -> {
            var turn = session.submit(operation("message", requestId), input);
            var receipt =
                    (ObjectNode)
                            DurableJson.value(
                                    Map.of(
                                            "type",
                                            type,
                                            "id",
                                            turn.status().inputMessageId(),
                                            "content",
                                            blocks));
            receipt.putNull("processed_at");
            return receipt;
        };
    }

    /** Maps Claude allow/deny to an authorized native confirmation and returns its admission. */
    public JsonNode sendToolConfirmation(String requestId, JsonNode event) {
        return prepareToolConfirmation(event).apply(requestId);
    }

    private Function<String, JsonNode> prepareToolConfirmation(JsonNode event) {
        String type = requiredText(event.path("type"), "type");
        if (!"user.tool_confirmation".equals(type)) throw unsupported("event type", type);
        fields(event, Set.of("type", "result", "tool_use_id", "deny_message"));
        String id =
                DurableJson.text(
                        requiredText(event.path("tool_use_id"), "tool_use_id"), "tool_use_id");
        String result = requiredText(event.path("result"), "result");
        if (!Set.of("allow", "deny").contains(result)) throw invalid("Expected allow or deny");
        if (event.hasNonNull("deny_message")) throw unsupported("field", "deny_message");
        return requestId -> {
            var turn = manualInteraction(id, "confirmation");
            String operation = operation("confirmation", requestId);
            turn.respond(operation, id, "allow".equals(result));
            return routed(
                    ClaudeEventAdapter.toolConfirmation(session.key(), operation, id, result, null),
                    turn);
        };
    }

    /** Admits structured output for a manual external tool, including its error status. */
    public JsonNode sendToolResult(String requestId, JsonNode event) {
        return prepareToolResult(event).apply(requestId);
    }

    private Function<String, JsonNode> prepareToolResult(JsonNode event) {
        String type = requiredText(event.path("type"), "type");
        if (!"user.custom_tool_result".equals(type)) throw unsupported("event type", type);
        fields(event, Set.of("type", "custom_tool_use_id", "content", "is_error"));
        String id =
                DurableJson.text(
                        requiredText(event.path("custom_tool_use_id"), "custom_tool_use_id"),
                        "custom_tool_use_id");
        var blocks = event.has("content") ? event.get("content") : DurableJson.value(List.of());
        var error = event.get("is_error");
        if (error != null && !error.isNull() && !error.isBoolean())
            throw invalid("Expected boolean for is_error");
        var result = new ToolResponse(error != null && error.asBoolean(), blocks(blocks));
        DurableProtocol.validate("toolResponse", result);
        return requestId -> {
            var turn = manualInteraction(id, "external_execution");
            String operation = operation("tool-result", requestId);
            var accepted = turn.respondTool(operation, id, result);
            var receipt =
                    ClaudeEventAdapter.customToolResult(
                            session.key(),
                            operation,
                            id,
                            blocks,
                            result.error(),
                            accepted.acceptedAt());
            return routed(receipt, turn);
        };
    }

    private ObjectNode routed(ObjectNode receipt, DurableTurn turn) {
        String threadId = turn.threadKey().threadId();
        if (!threadId.equals(session.view().primaryThreadId()))
            receipt.put("session_thread_id", threadId);
        return receipt;
    }

    private DurableTurn manualInteraction(String id, String kind) {
        var interaction = session.snapshot().projection().path("interactions").path(id);
        if (!kind.equals(interaction.path("kind").asText())
                || interaction.path("automatic").asBoolean())
            throw new DurableException(
                    DurableException.Code.NOT_FOUND, "Manual interaction not found");
        return session.getThread(interaction.required("threadId").asText())
                .getTurn(interaction.required("turnId").asText());
    }

    /** Requests interruption of the selected Thread, or every open Thread when none is supplied. */
    public JsonNode sendInterrupt(String requestId, JsonNode event) {
        return prepareInterrupt(event).apply(requestId);
    }

    private Function<String, JsonNode> prepareInterrupt(JsonNode event) {
        String type = requiredText(event.path("type"), "type");
        if (!"user.interrupt".equals(type)) throw unsupported("event type", type);
        fields(event, Set.of("type", "session_thread_id"));
        String threadId =
                event.hasNonNull("session_thread_id")
                        ? requiredText(event.get("session_thread_id"), "session_thread_id")
                        : null;
        if (threadId != null) DurableJson.text(threadId, "session_thread_id");
        return requestId -> {
            var receipt = session.cancelActiveTurns(operation("interrupt", requestId), threadId);
            return ClaudeEventAdapter.interrupt(
                    session.key(),
                    receipt.requestId(),
                    receipt.threadId(),
                    receipt.targets().isEmpty() ? receipt.acceptedAt() : null);
        };
    }

    private Function<String, JsonNode> prepare(JsonNode event) {
        String type = requiredText(event.path("type"), "type");
        return switch (type) {
            case "user.message" -> prepareMessage(event);
            case "user.tool_confirmation" -> prepareToolConfirmation(event);
            case "user.custom_tool_result" -> prepareToolResult(event);
            case "user.interrupt" -> prepareInterrupt(event);
            default -> throw unsupported("event type", type);
        };
    }

    private static String operation(String kind, String requestId) {
        return "claude:" + kind + ":" + DurableJson.hash(DurableJson.text(requestId, "requestId"));
    }

    private static List<Content> blocks(JsonNode blocks) {
        if (!blocks.isArray()) throw invalid("Expected a content array");
        var content = new ArrayList<Content>(blocks.size());
        for (var block : blocks) content.add(content(block));
        return content;
    }

    private static Content content(JsonNode block) {
        String type = requiredText(block.path("type"), "content type");
        return switch (type) {
            case "text" -> {
                fields(block, Set.of("type", "text"));
                yield new Text(requiredText(block.path("text"), "text"));
            }
            case "image" -> {
                fields(block, Set.of("type", "source"));
                yield image(block.path("source"));
            }
            default -> throw unsupported("content type", type);
        };
    }

    private static Image image(JsonNode source) {
        String type = requiredText(source.path("type"), "image source type");
        return switch (type) {
            case "url" -> {
                fields(source, Set.of("type", "url"));
                yield new Image(new Url(requiredText(source.path("url"), "url"), null));
            }
            case "base64" -> {
                fields(source, Set.of("type", "media_type", "data"));
                yield new Image(
                        new Base64(
                                requiredText(source.path("media_type"), "media_type"),
                                requiredText(source.path("data"), "data")));
            }
            default -> throw unsupported("image source", type);
        };
    }

    private static String requiredText(JsonNode field, String name) {
        if (!field.isTextual()) throw invalid("Expected string for " + name);
        return field.asText();
    }

    private static void fields(JsonNode object, Set<String> allowed) {
        object.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!allowed.contains(field)) throw unsupported("field", field);
                        });
    }

    private static DurableException invalid(String message) {
        return new DurableException(DurableException.Code.INVALID, message);
    }

    private static DurableException unsupported(String field, String type) {
        return new DurableException(
                DurableException.Code.INCOMPATIBLE, "Unsupported Claude " + field + ": " + type);
    }
}
