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
package io.agentscope.claw2.runtime.channel.github;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.Peer;
import io.agentscope.claw2.runtime.channel.PeerKind;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Optional;

/**
 * Maps a GitHub webhook delivery into an {@link InboundMessage}.
 *
 * <p>MVP handles two event types:
 *
 * <ul>
 *   <li>{@code issue_comment} — comments on issues OR PRs (GitHub fires {@code issue_comment} for
 *       both; the {@code issue} object has a {@code pull_request} subobject when it's a PR)
 *   <li>{@code pull_request_review_comment} — line-anchored review comments on PR diffs
 * </ul>
 *
 * <p>The peer model:
 *
 * <ul>
 *   <li>{@code peerKind} = {@link PeerKind#THREAD}
 *   <li>{@code peerId} = {@code "<owner>/<repo>#<number>"} — stable identifier the outbound
 *       client parses to build the comment URL.
 * </ul>
 */
public final class GitHubInboundMapper {

    private final String channelId;

    public GitHubInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /**
     * Returns the {@code comment.id} used as the idempotency key. Immutable per GitHub: edits
     * arrive as {@code action=edited} on the same id which we ignore in MVP.
     */
    public static Optional<Long> extractCommentId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("comment").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * Returns the numeric id of the comment author. Used by the channel for bot-loop self-detection.
     */
    public static Optional<Long> extractCommenterId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("comment").path("user").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * Maps an {@code issue_comment} or {@code pull_request_review_comment} payload into an
     * {@link InboundMessage}. Returns empty for unsupported actions (only {@code "created"} is
     * mapped) or malformed payloads.
     */
    public Optional<InboundMessage> map(String eventType, JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        // We only act on freshly-created comments. Edits / deletes are dropped in MVP.
        String action = payload.path("action").asText(null);
        if (!"created".equals(action)) {
            return Optional.empty();
        }

        JsonNode repo = payload.path("repository");
        String fullName = repo.path("full_name").asText(null);
        String ownerLogin = repo.path("owner").path("login").asText(null);
        if (fullName == null || fullName.isBlank()) {
            return Optional.empty();
        }

        long number;
        if ("issue_comment".equals(eventType)) {
            number = payload.path("issue").path("number").asLong(-1);
        } else if ("pull_request_review_comment".equals(eventType)) {
            number = payload.path("pull_request").path("number").asLong(-1);
        } else {
            return Optional.empty();
        }
        if (number <= 0) {
            return Optional.empty();
        }

        JsonNode comment = payload.path("comment");
        String body = comment.path("body").asText(null);
        String authorLogin = comment.path("user").path("login").asText(null);
        if (body == null || authorLogin == null) {
            return Optional.empty();
        }

        String peerId = fullName + "#" + number;
        Peer peer = new Peer(PeerKind.THREAD, peerId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(authorLogin).textContent(body).build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(ownerLogin)
                        .senderId(authorLogin)
                        .build());
    }
}
