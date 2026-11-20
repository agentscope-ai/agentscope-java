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
import io.agentscope.core.agui.queue.ThreadMessageQueue;
import io.agentscope.core.agui.runtime.AguiRequestBodyParser;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
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
 *
 * <p>Claim responses are encoded with AgentScope's own JSON codec instead of Spring's HTTP
 * converters. AG-UI models are annotated for Jackson 2 while Spring Boot 4 registers a Jackson 3
 * converter first, and only AgentScope's codec produces a body the run endpoint can parse again.
 */
@RestController
public class AguiQueueController {

    private static final MediaType JSON_UTF_8 =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final MessageQueueRegistry registry;
    private final AguiRequestProcessor requestProcessor;
    private final AguiRequestBodyParser requestBodyParser;

    /**
     * Creates the controller.
     *
     * @param registry the queue registry
     * @param requestProcessor the request processor used by the run endpoints, so that "idle"
     *     reflects the runs that are actually executing
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
     * Accepts a message for later delivery.
     *
     * <p>The thread does not have to be busy: whatever is accepted stays queued until a client
     * claims it while the thread is idle.
     *
     * <p>The message identifier is the id of the trailing message of the request body, so a retry
     * with the same body is idempotent and a body that appends a tool result identifies itself.
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
        if (threadId == null || threadId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        RunAgentInput input;
        try {
            input = requestBodyParser.parse(body);
        } catch (JsonException error) {
            // Malformed AG-UI JSON is the client's mistake, mapped like the run endpoint does.
            // The catch is local to acceptance: an encoding failure of a claim response is a
            // server fault and must not look like a rejected body.
            return ResponseEntity.badRequest().build();
        }
        if (input == null || !threadId.equals(input.getThreadId())) {
            return ResponseEntity.badRequest().build();
        }
        if (input.hasResume()) {
            // A thread that waits for an interrupt is never idle, so a queued resume could never be
            // claimed. Answers belong on the run endpoint, which accepts them right away.
            return ResponseEntity.badRequest().build();
        }
        List<AguiMessage> messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            // Nothing to run later, so there is nothing to queue.
            return ResponseEntity.badRequest().build();
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            // A null element replays as a broken body, so it is rejected up front.
            return ResponseEntity.badRequest().build();
        }
        AguiMessage trailingMessage = messages.get(messages.size() - 1);
        if (trailingMessage.getId() == null || trailingMessage.getId().isBlank()) {
            // Without an identifier the queue cannot deduplicate retries.
            return ResponseEntity.badRequest().build();
        }
        String messageId = trailingMessage.getId();

        QueueAcceptance acceptance = registry.forThread(threadId).offer(messageId, input);
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
     * @return the queue snapshot, empty for a thread that never queued a message
     */
    @GetMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}")
    public QueueSnapshot status(@PathVariable String threadId) {
        boolean active = requestProcessor.isRunActive(threadId);
        boolean awaitingConfirmation = requestProcessor.isAwaitingConfirmation(threadId);
        return registry.find(threadId)
                .map(queue -> queue.snapshot(active, awaitingConfirmation))
                .orElseGet(() -> new QueueSnapshot(List.of(), active, awaitingConfirmation));
    }

    /**
     * Withdraws a queued message.
     *
     * @param threadId the AG-UI thread id
     * @param messageId the message identifier
     * @return 204 when withdrawn, 404 when the id is unknown, was already withdrawn, or was
     *     forgotten by the ledger, 409 when already claimed
     */
    @DeleteMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}/{messageId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable String threadId, @PathVariable String messageId) {
        return registry.find(threadId)
                .map(queue -> withdraw(queue, messageId))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Void> withdraw(ThreadMessageQueue queue, String messageId) {
        return switch (queue.withdraw(messageId)) {
            case WITHDRAWN -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_CLAIMED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    /**
     * Claims the oldest queued message when the thread is idle.
     *
     * <p>The returned body is the message identifier plus the run input to replay verbatim on the
     * run endpoint.
     *
     * @param threadId the AG-UI thread id
     * @return the claimed message, or 204 when nothing can be claimed
     */
    @PostMapping("${agentscope.agui.path-prefix:/agui}/queue/{threadId}/claim")
    public ResponseEntity<String> claim(@PathVariable String threadId) {
        long observedAtMillis = System.currentTimeMillis();
        // Read the run evidence before the idle state: a replay that starts between the two
        // reads then shows up as idle == false, instead of pairing stale idleness with new
        // evidence and delivering into an occupied thread.
        String lastStartedRunId = requestProcessor.lastStartedRunId(threadId);
        boolean idle = requestProcessor.isThreadIdle(threadId);
        return registry.find(threadId)
                .flatMap(queue -> queue.claim(idle, observedAtMillis, lastStartedRunId))
                .map(message -> new ClaimResponse(message.getMessageId(), message.getInput()))
                .map(AguiQueueController::claimResponse)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ResponseEntity<String> claimResponse(ClaimResponse response) {
        return ResponseEntity.ok()
                .contentType(JSON_UTF_8)
                .body(JsonUtils.getJsonCodec().toJson(response));
    }

    /**
     * Returns the request processor used for idle checks.
     *
     * @return the request processor
     */
    AguiRequestProcessor getRequestProcessor() {
        return requestProcessor;
    }
}
