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
package io.agentscope.spring.boot.responses.web;

import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.converter.ResponsesValidationException;
import io.agentscope.core.responses.model.ResponsesConversationItemsRequest;
import io.agentscope.core.responses.model.ResponsesConversationRequest;
import io.agentscope.spring.boot.responses.service.ResponsesStateService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP controller exposing the OpenAI Conversations-compatible API.
 *
 * <p>This controller manages conversation metadata and conversation item resources used by the
 * Responses API. The default backing store is the in-memory {@link ResponsesStateService}; replace
 * that bean in application code when conversations need durable storage or cross-instance sharing.
 *
 * <p><b>How It Works:</b>
 *
 * <ol>
 *   <li>Clients create or retrieve a conversation resource
 *   <li>Clients add, list, retrieve, or delete conversation items
 *   <li>{@code POST /v1/responses} can reference the conversation ID to include prior items as
 *       input context
 *   <li>Completed response input/output items are appended back to the conversation
 * </ol>
 */
@RestController
@RequestMapping
public class ConversationsController {

    private final ResponsesStateService stateService;
    private final ResponsesResponseBuilder responseBuilder;

    /**
     * Constructs a new {@code ConversationsController}.
     *
     * @param stateService Service for conversation state
     * @param responseBuilder Builder for structured error responses
     */
    public ConversationsController(
            ResponsesStateService stateService, ResponsesResponseBuilder responseBuilder) {
        this.stateService = stateService;
        this.responseBuilder = responseBuilder;
    }

    /**
     * Create a conversation resource.
     *
     * @param request Optional metadata and initial items
     * @return The created conversation object
     */
    @PostMapping(
            value = "${agentscope.conversations.base-path:/v1/conversations}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createConversation(
            @Valid @RequestBody(required = false) ResponsesConversationRequest request) {
        return ResponseEntity.ok(stateService.createConversation(request));
    }

    /**
     * Retrieve an existing conversation.
     *
     * @param conversationId Conversation ID
     * @return The conversation object, or a structured not-found error
     */
    @GetMapping(value = "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}")
    public Object retrieveConversation(@PathVariable String conversationId) {
        try {
            return ResponseEntity.ok(stateService.retrieveConversation(conversationId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Update conversation metadata.
     *
     * @param conversationId Conversation ID
     * @param request Replacement metadata payload
     * @return The updated conversation object
     */
    @PostMapping(
            value = "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object updateConversation(
            @PathVariable String conversationId,
            @Valid @RequestBody(required = false) ResponsesConversationRequest request) {
        try {
            return ResponseEntity.ok(stateService.updateConversation(conversationId, request));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Delete a conversation resource.
     *
     * @param conversationId Conversation ID
     * @return Deletion status
     */
    @DeleteMapping(
            value = "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}")
    public Object deleteConversation(@PathVariable String conversationId) {
        try {
            return ResponseEntity.ok(stateService.deleteConversation(conversationId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * List conversation items with optional cursor pagination.
     *
     * @param conversationId Conversation ID
     * @param after Cursor item ID
     * @param limit Maximum item count
     * @param order Sort order, either {@code asc} or {@code desc}
     * @return An OpenAI-style list object
     */
    @GetMapping(
            value =
                    "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}/items")
    public Object listConversationItems(
            @PathVariable String conversationId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String order) {
        try {
            return ResponseEntity.ok(
                    stateService.listConversationItems(conversationId, after, limit, order));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Append items to a conversation.
     *
     * @param conversationId Conversation ID
     * @param request Items to append
     * @return An OpenAI-style list containing the created items
     */
    @PostMapping(
            value =
                    "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}/items",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createConversationItems(
            @PathVariable String conversationId,
            @Valid @RequestBody(required = false) ResponsesConversationItemsRequest request) {
        try {
            return ResponseEntity.ok(
                    stateService.createConversationItems(
                            conversationId, request != null ? request.getItems() : null));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Retrieve a single conversation item.
     *
     * @param conversationId Conversation ID
     * @param itemId Conversation item ID
     * @return The requested item, or a structured not-found error
     */
    @GetMapping(
            value =
                    "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}/items/{itemId}")
    public Object retrieveConversationItem(
            @PathVariable String conversationId, @PathVariable String itemId) {
        try {
            return ResponseEntity.ok(stateService.retrieveConversationItem(conversationId, itemId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    /**
     * Delete a single conversation item.
     *
     * @param conversationId Conversation ID
     * @param itemId Conversation item ID
     * @return Deletion status
     */
    @DeleteMapping(
            value =
                    "${agentscope.conversations.base-path:/v1/conversations}/{conversationId}/items/{itemId}")
    public Object deleteConversationItem(
            @PathVariable String conversationId, @PathVariable String itemId) {
        try {
            return ResponseEntity.ok(stateService.deleteConversationItem(conversationId, itemId));
        } catch (ResponsesValidationException e) {
            return ResponseEntity.status(responseStatus(e))
                    .body(responseBuilder.buildErrorResponse(e));
        }
    }

    private org.springframework.http.HttpStatus responseStatus(ResponsesValidationException e) {
        return "not_found".equals(e.getCode())
                ? org.springframework.http.HttpStatus.NOT_FOUND
                : org.springframework.http.HttpStatus.BAD_REQUEST;
    }
}
