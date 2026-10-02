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
package io.agentscope.extensions.channel.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Handles GitHub webhook deliveries. Single endpoint at
 * {@code POST /api/channels/github/{channelId}/webhook}. The body is JSON (received as a raw
 * byte[] so the HMAC-SHA256 signature can be computed over the exact bytes GitHub signed).
 *
 * <p>Pipeline: signature verify -> event-type dispatch -> idempotency dedup -> bot-loop guard ->
 * dispatch on the channel.
 */
@RestController
@RequestMapping("/api/channels/github")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitHubChannelRegistry registry;

    public GitHubWebhookController() {
        this(GitHubChannelRegistry.instance());
    }

    /** Visible for tests — allows injecting a fresh registry. */
    GitHubWebhookController(GitHubChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/webhook",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> webhook(
            @PathVariable String channelId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature256,
            @RequestBody byte[] rawBody) {
        GitHubChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("GitHub webhook: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. Signature verification on the raw body — mandatory.
        if (!channel.signatureVerifier().verify(signature256, rawBody)) {
            log.warn(
                    "GitHub webhook: signature mismatch (channelId='{}', delivery='{}')",
                    channelId,
                    deliveryId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. Filter event types we don't handle in MVP. Always 204 so GitHub stops trying.
        if (eventType == null
                || !(eventType.equals("issue_comment")
                        || eventType.equals("pull_request_review_comment"))) {
            return Mono.just(ResponseEntity.noContent().build());
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn(
                    "GitHub webhook: JSON parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. Idempotency dedup by comment.id (immutable across edits).
        Optional<Long> commentId = GitHubInboundMapper.extractCommentId(payload);
        if (commentId.isPresent()
                && !channel.idempotency().firstSeen(channelId + "|" + commentId.get())) {
            log.debug(
                    "GitHub webhook: duplicate comment.id={} (channelId='{}')",
                    commentId.get(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 4. Bot-loop self-detection. If the commenter id matches the bot's resolved id, drop.
        Optional<Long> commenterId = GitHubInboundMapper.extractCommenterId(payload);
        long botId = channel.botIdentity().botUserId();
        if (botId > 0 && commenterId.isPresent() && commenterId.get() == botId) {
            log.debug(
                    "GitHub webhook: skipping self-authored comment (channelId='{}', commenter={})",
                    channelId,
                    botId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 5. Map -> InboundMessage; unsupported actions -> ack silently.
        Optional<InboundMessage> inbound = channel.mapper().map(eventType, payload);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. Per-peer rate guard.
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "GitHub webhook: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "GitHub webhook: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(ResponseEntity.ok("{}"));
                        });
    }
}
