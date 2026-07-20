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
package io.agentscope.spring.boot.responses.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.model.ResponsesConversation;
import io.agentscope.core.responses.model.ResponsesConversationRequest;
import io.agentscope.core.responses.model.ResponsesDeletionStatus;
import io.agentscope.core.responses.model.ResponsesList;
import io.agentscope.core.responses.model.ResponsesOutputItem;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesTokenCount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;

/**
 * In-memory state service for Responses and Conversations resources.
 *
 * <p>This service provides OpenAI-compatible stateful API behavior for the starter without forcing
 * an external database. Applications can replace this bean with a durable implementation when they
 * need persistence across process restarts.
 */
public class ResponsesStateService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConcurrentMap<String, StoredResponse> responses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoredConversation> conversations =
            new ConcurrentHashMap<>();

    /**
     * Prepare a request by expanding previous response and conversation context into input.
     *
     * <p>The public Responses request remains stateless over HTTP, but {@code
     * previous_response_id} and {@code conversation} require the model call to see earlier input and
     * output items. This method builds that effective input while also returning the current input
     * items so they can be stored separately.
     *
     * @param request Original Responses request
     * @return Prepared request and state metadata
     */
    public PreparedRequest prepare(ResponsesRequest request) {
        List<Object> currentInputItems = inputItems(request.getInput());
        List<Object> effectiveInput = new ArrayList<>();

        if (request.getPreviousResponseId() != null && !request.getPreviousResponseId().isBlank()) {
            StoredResponse previous = responseRecord(request.getPreviousResponseId());
            // OpenAI's previous_response_id semantics replay prior input and output as context for
            // the next call.
            effectiveInput.addAll(previous.inputItems());
            effectiveInput.addAll(outputItems(previous.response()));
        }

        ConversationResolution conversation = resolveConversation(request.getConversation());
        String conversationId = conversation.id();
        if (conversationId != null && !conversation.pending()) {
            // Conversation items are appended before current input so the latest user message stays
            // last in the model context.
            effectiveInput.addAll(conversationItemsSnapshot(conversationId));
        }

        effectiveInput.addAll(currentInputItems);

        ResponsesRequest effectiveRequest = copyRequest(request);
        effectiveRequest.setInput(effectiveInput);
        if (conversationId != null) {
            effectiveRequest.setConversation(conversationId);
        }
        return new PreparedRequest(
                effectiveRequest, currentInputItems, conversationId, conversation.pending());
    }

    /** Persist an auto-allocated conversation after the prepared input has passed validation. */
    public void commitConversation(PreparedRequest prepared) {
        if (prepared == null
                || !prepared.pendingConversation()
                || prepared.conversationId() == null) {
            return;
        }
        ResponsesConversation conversation = new ResponsesConversation();
        conversation.setId(prepared.conversationId());
        conversation.setCreatedAt(Instant.now().getEpochSecond());
        conversations.putIfAbsent(
                conversation.getId(),
                new StoredConversation(conversation, synchronizedItems(List.of())));
    }

    /**
     * Store or update a completed, failed, queued, or canceled response.
     *
     * <p>Responses are stored when {@code store=true} or when the response belongs to a background
     * request. Conversation input/output items are appended at the same time.
     *
     * @param response Response to store
     * @param prepared Prepared request metadata
     * @return The same response object
     */
    public ResponsesResponse save(ResponsesResponse response, PreparedRequest prepared) {
        if (response == null) {
            return null;
        }
        if (prepared != null && prepared.conversationId() != null) {
            response.setConversation(prepared.conversationId());
            appendConversationItems(
                    prepared.conversationId(), prepared.currentInputItems(), outputItems(response));
        }
        if (Boolean.TRUE.equals(response.getStore())
                || Boolean.TRUE.equals(response.getBackground())) {
            responses.put(
                    response.getId(),
                    new StoredResponse(
                            response,
                            prepared != null
                                    ? withItemIds(prepared.currentInputItems())
                                    : List.of(),
                            prepared != null ? prepared.conversationId() : null,
                            null,
                            true));
        }
        return response;
    }

    /**
     * Store a queued background response and remember its running task for cancellation.
     *
     * @param response Queued response placeholder
     * @param prepared Prepared request metadata
     * @param task Stable holder for the running background subscription
     */
    public void saveBackground(
            ResponsesResponse response, PreparedRequest prepared, Disposable task) {
        responses.put(
                response.getId(),
                new StoredResponse(
                        response,
                        prepared != null ? withItemIds(prepared.currentInputItems()) : List.of(),
                        prepared != null ? prepared.conversationId() : null,
                        task,
                        false));
    }

    /**
     * Store a terminal background result unless the response was already canceled or deleted.
     *
     * @param response Completed or failed background response
     * @param prepared Prepared request metadata
     * @return The same response object
     */
    public ResponsesResponse completeBackground(
            ResponsesResponse response, PreparedRequest prepared) {
        if (response == null) {
            return null;
        }
        if (prepared != null && prepared.conversationId() != null) {
            response.setConversation(prepared.conversationId());
        }

        AtomicReference<StoredResponse> completed = new AtomicReference<>();
        responses.compute(
                response.getId(),
                (id, stored) -> {
                    if (stored == null || stored.terminal()) {
                        return stored;
                    }
                    StoredResponse terminal =
                            new StoredResponse(
                                    response,
                                    stored.inputItems(),
                                    stored.conversationId(),
                                    stored.backgroundTask(),
                                    true);
                    completed.set(terminal);
                    return terminal;
                });

        if (completed.get() != null && prepared != null && prepared.conversationId() != null) {
            appendConversationItems(
                    prepared.conversationId(), prepared.currentInputItems(), outputItems(response));
        }
        return response;
    }

    /**
     * Retrieve a stored response.
     *
     * @param responseId Response ID
     * @return Stored response
     */
    public ResponsesResponse retrieveResponse(String responseId) {
        return responseRecord(responseId).response();
    }

    /**
     * Delete a stored response and cancel its background task if it is still running.
     *
     * @param responseId Response ID
     * @return Deletion status
     */
    public ResponsesDeletionStatus deleteResponse(String responseId) {
        AtomicReference<StoredResponse> removed = new AtomicReference<>();
        responses.compute(
                responseId,
                (id, stored) -> {
                    removed.set(stored);
                    return null;
                });
        if (removed.get() == null) {
            throw notFound("Response not found: " + responseId, "response_id");
        }
        if (removed.get().backgroundTask() != null) {
            removed.get().backgroundTask().dispose();
        }
        return new ResponsesDeletionStatus(responseId, "response.deleted", true);
    }

    /**
     * Cancel a stored response.
     *
     * <p>For queued/running background responses this disposes the subscription. For already
     * completed in-memory responses it simply marks the resource as cancelled, matching the
     * resource-oriented API shape.
     *
     * @param responseId Response ID
     * @return Canceled response
     */
    public ResponsesResponse cancelResponse(String responseId) {
        AtomicReference<StoredResponse> cancelled = new AtomicReference<>();
        responses.compute(
                responseId,
                (id, stored) -> {
                    if (stored == null) {
                        return null;
                    }
                    stored.response().setStatus("cancelled");
                    StoredResponse terminal =
                            new StoredResponse(
                                    stored.response(),
                                    stored.inputItems(),
                                    stored.conversationId(),
                                    stored.backgroundTask(),
                                    true);
                    cancelled.set(terminal);
                    return terminal;
                });
        if (cancelled.get() == null) {
            throw notFound("Response not found: " + responseId, "response_id");
        }
        if (cancelled.get().backgroundTask() != null) {
            cancelled.get().backgroundTask().dispose();
        }
        return cancelled.get().response();
    }

    /**
     * List response input items with cursor pagination.
     *
     * @param responseId Response ID
     * @param after Cursor item ID
     * @param limit Maximum item count
     * @param order Sort order, either {@code asc} or {@code desc}
     * @return List wrapper containing input items
     */
    public ResponsesList<Object> responseInputItems(
            String responseId, String after, Integer limit, String order) {
        return page(responseRecord(responseId).inputItems(), after, limit, order);
    }

    /**
     * Count input tokens approximately.
     *
     * <p>The generic starter does not have provider-specific tokenizers, so this endpoint returns a
     * stable approximation based on serialized input length.
     *
     * @param request Responses request
     * @return Approximate token count
     */
    public ResponsesTokenCount countInputTokens(ResponsesRequest request) {
        try {
            String json =
                    OBJECT_MAPPER.writeValueAsString(request != null ? request.getInput() : null);
            return new ResponsesTokenCount(Math.max(1, (int) Math.ceil(json.length() / 4.0)));
        } catch (JsonProcessingException e) {
            return new ResponsesTokenCount(0);
        }
    }

    /**
     * Create a conversation with optional metadata and initial items.
     *
     * @param request Conversation creation request
     * @return Created conversation
     */
    public ResponsesConversation createConversation(ResponsesConversationRequest request) {
        ResponsesConversation conversation = new ResponsesConversation();
        conversation.setId("conv_" + UUID.randomUUID());
        conversation.setCreatedAt(Instant.now().getEpochSecond());
        if (request != null) {
            conversation.setMetadata(request.getMetadata());
        }
        List<Object> items =
                request != null && request.getItems() != null
                        ? synchronizedItems(withItemIds(request.getItems()))
                        : synchronizedItems(List.of());
        conversations.put(conversation.getId(), new StoredConversation(conversation, items));
        return conversation;
    }

    /**
     * Retrieve a conversation.
     *
     * @param conversationId Conversation ID
     * @return Stored conversation
     */
    public ResponsesConversation retrieveConversation(String conversationId) {
        return conversationRecord(conversationId).conversation();
    }

    /**
     * Update conversation metadata.
     *
     * @param conversationId Conversation ID
     * @param request Metadata update request
     * @return Updated conversation
     */
    public ResponsesConversation updateConversation(
            String conversationId, ResponsesConversationRequest request) {
        StoredConversation stored = conversationRecord(conversationId);
        if (request != null && request.getMetadata() != null) {
            stored.conversation().setMetadata(request.getMetadata());
        }
        return stored.conversation();
    }

    /**
     * Delete a conversation.
     *
     * @param conversationId Conversation ID
     * @return Deletion status
     */
    public ResponsesDeletionStatus deleteConversation(String conversationId) {
        StoredConversation removed = conversations.remove(conversationId);
        if (removed == null) {
            throw notFound("Conversation not found: " + conversationId, "conversation_id");
        }
        return new ResponsesDeletionStatus(conversationId, "conversation.deleted", true);
    }

    /**
     * List conversation items with cursor pagination.
     *
     * @param conversationId Conversation ID
     * @param after Cursor item ID
     * @param limit Maximum item count
     * @param order Sort order, either {@code asc} or {@code desc}
     * @return List wrapper containing conversation items
     */
    public ResponsesList<Object> listConversationItems(
            String conversationId, String after, Integer limit, String order) {
        List<Object> items = conversationRecord(conversationId).items();
        synchronized (items) {
            return page(items, after, limit, order);
        }
    }

    /**
     * Append items to a conversation.
     *
     * @param conversationId Conversation ID
     * @param items Items to append
     * @return List wrapper containing the created items
     */
    public ResponsesList<Object> createConversationItems(
            String conversationId, List<Object> items) {
        StoredConversation stored = conversationRecord(conversationId);
        List<Object> created = withItemIds(items != null ? items : List.of());
        synchronized (stored.items()) {
            stored.items().addAll(created);
        }
        return new ResponsesList<>(created);
    }

    /**
     * Retrieve one conversation item.
     *
     * @param conversationId Conversation ID
     * @param itemId Item ID
     * @return Stored item
     */
    public Object retrieveConversationItem(String conversationId, String itemId) {
        List<Object> items = conversationRecord(conversationId).items();
        synchronized (items) {
            return items.stream()
                    .filter(item -> itemId.equals(itemId(item)))
                    .findFirst()
                    .orElseThrow(
                            () -> notFound("Conversation item not found: " + itemId, "item_id"));
        }
    }

    /**
     * Delete one conversation item.
     *
     * @param conversationId Conversation ID
     * @param itemId Item ID
     * @return Deletion status
     */
    public ResponsesDeletionStatus deleteConversationItem(String conversationId, String itemId) {
        StoredConversation stored = conversationRecord(conversationId);
        boolean removed;
        synchronized (stored.items()) {
            removed = stored.items().removeIf(item -> itemId.equals(itemId(item)));
        }
        if (!removed) {
            throw notFound("Conversation item not found: " + itemId, "item_id");
        }
        return new ResponsesDeletionStatus(itemId, "conversation.item.deleted", true);
    }

    private void appendConversationItems(
            String conversationId, List<Object> inputItems, List<Object> outputItems) {
        StoredConversation stored = conversationRecord(conversationId);
        List<Object> input = withItemIds(inputItems);
        List<Object> output = withItemIds(outputItems);
        synchronized (stored.items()) {
            stored.items().addAll(input);
            stored.items().addAll(output);
        }
    }

    private List<Object> conversationItemsSnapshot(String conversationId) {
        List<Object> items = conversationRecord(conversationId).items();
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    private List<Object> synchronizedItems(List<Object> items) {
        return Collections.synchronizedList(new ArrayList<>(items));
    }

    private ResponsesList<Object> page(
            List<Object> source, String after, Integer limit, String order) {
        if (limit != null && limit < 0) {
            throw ResponsesValidationException.invalid("limit must be non-negative", "limit");
        }
        if (order != null && !order.isBlank() && !"asc".equals(order) && !"desc".equals(order)) {
            throw ResponsesValidationException.invalid("order must be asc or desc", "order");
        }

        List<Object> ordered = new ArrayList<>(source != null ? source : List.of());
        if ("desc".equals(order)) {
            Collections.reverse(ordered);
        }

        // The API uses item IDs as cursors. If the cursor is absent, start from the beginning;
        // if it is not found, the behavior is intentionally the same as an empty cursor.
        int start = 0;
        if (after != null && !after.isBlank()) {
            for (int i = 0; i < ordered.size(); i++) {
                if (after.equals(itemId(ordered.get(i)))) {
                    start = i + 1;
                    break;
                }
            }
        }

        int max = limit != null ? limit : ordered.size();
        int end = Math.min(ordered.size(), start + max);
        return new ResponsesList<>(
                new ArrayList<>(ordered.subList(start, end)), end < ordered.size());
    }

    private ConversationResolution resolveConversation(Object conversation) {
        if (conversation == null || OBJECT_MAPPER.valueToTree(conversation).isNull()) {
            return new ConversationResolution(null, false);
        }
        JsonNode node = OBJECT_MAPPER.valueToTree(conversation);
        if (node.isTextual()) {
            String value = node.asText();
            if ("none".equals(value)) {
                return new ConversationResolution(null, false);
            }
            if ("auto".equals(value)) {
                // Allocate the ID now, but persist only after the effective input is validated.
                return new ConversationResolution("conv_" + UUID.randomUUID(), true);
            }
            conversationRecord(value);
            return new ConversationResolution(value, false);
        }
        if (node.isObject()) {
            String id = node.hasNonNull("id") ? node.get("id").asText() : null;
            if (id == null || id.isBlank() || "auto".equals(id)) {
                // Object-shaped conversation payloads may omit an ID. Treat that as auto-create,
                // but defer persistence until validation succeeds.
                return new ConversationResolution("conv_" + UUID.randomUUID(), true);
            }
            conversationRecord(id);
            return new ConversationResolution(id, false);
        }
        throw ResponsesValidationException.invalid(
                "conversation must be a string or object", "conversation");
    }

    private StoredResponse responseRecord(String responseId) {
        StoredResponse stored = responses.get(responseId);
        if (stored == null) {
            throw notFound("Response not found: " + responseId, "response_id");
        }
        return stored;
    }

    private StoredConversation conversationRecord(String conversationId) {
        StoredConversation stored = conversations.get(conversationId);
        if (stored == null) {
            throw notFound("Conversation not found: " + conversationId, "conversation_id");
        }
        return stored;
    }

    private ResponsesValidationException notFound(String message, String param) {
        return new ResponsesValidationException(message, param, "not_found");
    }

    private ResponsesRequest copyRequest(ResponsesRequest request) {
        ResponsesRequest copy = new ResponsesRequest();
        copy.setModel(request.getModel());
        copy.setInput(request.getInput());
        copy.setInstructions(request.getInstructions());
        copy.setStream(request.getStream());
        copy.setTools(request.getTools());
        copy.setToolChoice(request.getToolChoice());
        copy.setTemperature(request.getTemperature());
        copy.setTopP(request.getTopP());
        copy.setMaxOutputTokens(request.getMaxOutputTokens());
        copy.setReasoning(request.getReasoning());
        copy.setText(request.getText());
        copy.setPreviousResponseId(request.getPreviousResponseId());
        copy.setConversation(request.getConversation());
        copy.setBackground(request.getBackground());
        copy.setStore(request.getStore());
        copy.setMetadata(request.getMetadata());
        request.getAdditionalFields().forEach(copy::putAdditionalField);
        return copy;
    }

    private List<Object> inputItems(Object input) {
        JsonNode node = OBJECT_MAPPER.valueToTree(input);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            // String input is the common shorthand; store it back as an official message item so
            // input_items and previous_response_id use the same shape as object input.
            return List.of(userMessageItem(node.asText()));
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonNode child : node) {
                result.add(toObject(child));
            }
            return result;
        }
        return List.of(toObject(node));
    }

    private Map<String, Object> userMessageItem(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", "input_text");
        part.put("text", text);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", List.of(part));
        return item;
    }

    private List<Object> outputItems(ResponsesResponse response) {
        if (response == null || response.getOutput() == null) {
            return List.of();
        }
        List<Object> result = new ArrayList<>();
        for (ResponsesOutputItem item : response.getOutput()) {
            result.add(toObject(item));
        }
        return result;
    }

    private List<Object> withItemIds(List<Object> items) {
        List<Object> result = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = toMap(item);
            // Client-provided IDs are preserved, otherwise allocate an OpenAI-style prefix based on
            // the item type.
            map.putIfAbsent("id", itemIdPrefix(map) + UUID.randomUUID());
            result.add(map);
        }
        return result;
    }

    private String itemIdPrefix(Map<String, Object> item) {
        Object type = item.get("type");
        if ("function_call".equals(type)) {
            return "fc_";
        }
        if ("function_call_output".equals(type)) {
            return "fco_";
        }
        return "msg_";
    }

    private String itemId(Object item) {
        Map<String, Object> map = toMap(item);
        Object id = map.get("id");
        return id instanceof String text ? text : null;
    }

    private Object toObject(Object value) {
        return OBJECT_MAPPER.convertValue(value, Object.class);
    }

    private Map<String, Object> toMap(Object value) {
        return OBJECT_MAPPER.convertValue(value, new TypeReference<>() {});
    }

    public record PreparedRequest(
            ResponsesRequest request,
            List<Object> currentInputItems,
            String conversationId,
            boolean pendingConversation) {

        public PreparedRequest(
                ResponsesRequest request, List<Object> currentInputItems, String conversationId) {
            this(request, currentInputItems, conversationId, false);
        }
    }

    private record StoredResponse(
            ResponsesResponse response,
            List<Object> inputItems,
            String conversationId,
            Disposable backgroundTask,
            boolean terminal) {}

    private record StoredConversation(ResponsesConversation conversation, List<Object> items) {}

    private record ConversationResolution(String id, boolean pending) {}
}
