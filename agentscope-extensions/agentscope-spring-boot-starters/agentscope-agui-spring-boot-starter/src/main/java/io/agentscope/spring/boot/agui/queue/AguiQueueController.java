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
package io.agentscope.spring.boot.agui.queue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.queue.MessageQueueRegistry;
import io.agentscope.core.agui.queue.QueueAcceptance;
import io.agentscope.core.agui.queue.QueueSnapshot;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the per-thread message queue.
 *
 * <p>The endpoints are only registered when {@code agentscope.agui.queue.enabled=true}. They never
 * start an agent run: the client claims a message and replays it on the regular run endpoint, so
 * the run lifecycle, streaming and interrupt handling stay unchanged.
 */
@RestController
public class AguiQueueController {

    private final MessageQueueRegistry registry;
    private final AguiRequestProcessor requestProcessor;
    private final AguiRequestBodyParser requestBodyParser;

    /**
     * Creates the controller.
     *
     * @param registry the queue registry
     * @param requestProcessor the shared request processor, used to decide whether the thread can
     *     start a new run
     * @param requestBodyParser the AG-UI request body parser
     */
    public AguiQueueController(
            MessageQueueRegistry registry,
            AguiRequestProcessor requestProcessor,
            AguiRequestBodyParser requestBodyParser) {
        this.registry = registry;
        this.requestProcessor = requestProcessor;
        this.requestBodyParser = requestBodyParser;
    }

    /**
     * Accepts a message for a busy thread.
     *
     * @param threadId the AG-UI thread id
     * @param body the regular AG-UI request body
     * @return the acceptance outcome
     */
    @PostMapping(
            value = "${agentscope.agui.path-prefix:/agui}/queue/{threadId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QueueAcceptance> accept(
            @PathVariable String threadId, @RequestBody String body) {
        RunAgentInput input = requestBodyParser.parse(body);
        if (input.getThreadId() == null || !input.getThreadId().equals(threadId)) {
            return ResponseEntity.badRequest().build();
        }
        AguiMessage lastUserMessage = lastUserMessage(input);
        String messageId =
                lastUserMessage != null
                                && lastUserMessage.getId() != null
                                && !lastUserMessage.getId().isBlank()
                        ? lastUserMessage.getId()
                        : UUID.randomUUID().toString();

        QueueAcceptance acceptance =
                registry.forThread(threadId).offer(messageId, input, lastUserMessage);
        return switch (acceptance.status()) {
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok(acceptance);
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(acceptance);
            case FULL -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(acceptance);
        };
    }

    /**
     * Returns the current queue state of a thread.
     *
     * @param threadId the AG-UI thread id
     * @return the queue snapshot
     */
    @GetMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}")
    public QueueSnapshot status(@PathVariable String threadId) {
        return registry.forThread(threadId)
                .snapshot(
                        !requestProcessor.isThreadIdle(threadId),
                        requestProcessor.isAwaitingConfirmation(threadId));
    }

    /**
     * Withdraws a queued message.
     *
     * @param threadId the AG-UI thread id
     * @param messageId the message identifier
     * @return 204 when withdrawn, 404 when unknown, 409 when already claimed
     */
    @DeleteMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}/{messageId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable String threadId, @PathVariable String messageId) {
        return switch (registry.forThread(threadId).withdraw(messageId)) {
            case WITHDRAWN -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_CLAIMED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    /**
     * Claims the oldest queued message when the thread is idle.
     *
     * @param threadId the AG-UI thread id
     * @return the claimed message, or 204 when nothing can be claimed
     */
    @PostMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}/claim")
    public ResponseEntity<ClaimResponse> claim(@PathVariable String threadId) {
        return registry.forThread(threadId)
                .claim(requestProcessor.isThreadIdle(threadId))
                .map(
                        message ->
                                ResponseEntity.ok(
                                        new ClaimResponse(
                                                message.getMessageId(), message.getInput())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static AguiMessage lastUserMessage(RunAgentInput input) {
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            AguiMessage message = messages.get(i);
            if (message.isUserMessage()) {
                return message;
            }
        }
        return null;
    }
}
