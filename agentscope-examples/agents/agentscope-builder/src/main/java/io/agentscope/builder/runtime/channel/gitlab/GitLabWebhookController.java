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
package io.agentscope.builder.runtime.channel.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.channel.InboundMessage;
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
 * Handles GitLab webhook deliveries. Single endpoint at
 * {@code POST /api/channels/gitlab/{channelId}/webhook}.
 *
 * <p>Pipeline: {@code X-Gitlab-Token} verify -> {@code X-Gitlab-Event} type dispatch ->
 * idempotency dedup -> bot-loop guard -> dispatch on the channel.
 */
@RestController
@RequestMapping("/api/channels/gitlab")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitLabChannelRegistry registry;

    public GitLabWebhookController() {
        this(GitLabChannelRegistry.instance());
    }

    /** Visible for tests — allows injecting a fresh registry. */
    GitLabWebhookController(GitLabChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/webhook",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> webhook(
            @PathVariable("channelId") String channelId,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
            @RequestBody String rawBody) {
        GitLabChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("GitLab webhook: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. Token verify (constant-time equality).
        if (!constantTimeEquals(token, channel.properties().webhookToken())) {
            log.warn("GitLab webhook: X-Gitlab-Token mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. Only Note Hook is mapped in MVP. Other event types -> 204.
        if (!"Note Hook".equals(eventType)) {
            return Mono.just(ResponseEntity.noContent().build());
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.warn(
                    "GitLab webhook: JSON parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. Idempotency dedup on the note id.
        Optional<Long> noteId = GitLabInboundMapper.extractNoteId(payload);
        if (noteId.isPresent()
                && !channel.idempotency().firstSeen(channelId + "|" + noteId.get())) {
            log.debug(
                    "GitLab webhook: duplicate note.id={} (channelId='{}')",
                    noteId.get(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 4. Bot-loop self-detection.
        Optional<Long> authorId = GitLabInboundMapper.extractAuthorId(payload);
        long botId = channel.botIdentity().botUserId();
        if (botId > 0 && authorId.isPresent() && authorId.get() == botId) {
            log.debug(
                    "GitLab webhook: skipping self-authored note (channelId='{}', author={})",
                    channelId,
                    botId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 5. Map; non-text / system note / unsupported noteable_type -> ack silently.
        Optional<InboundMessage> inbound = channel.mapper().map(payload);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. Per-peer rate guard.
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "GitLab webhook: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "GitLab webhook: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(ResponseEntity.ok("{}"));
                        });
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
