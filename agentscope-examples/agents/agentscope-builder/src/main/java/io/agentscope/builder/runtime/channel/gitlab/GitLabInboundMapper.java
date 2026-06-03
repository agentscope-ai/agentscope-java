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
import io.agentscope.builder.runtime.channel.InboundMessage;
import io.agentscope.builder.runtime.channel.Peer;
import io.agentscope.builder.runtime.channel.PeerKind;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Optional;

/**
 * Maps a GitLab {@code Note Hook} payload into an {@link InboundMessage}.
 *
 * <p>Only Issue and MergeRequest notes are mapped in MVP; Commit and Snippet notes are dropped.
 * System notes (label changes, status changes) are skipped via {@code object_attributes.system}.
 *
 * <p>Peer model:
 *
 * <ul>
 *   <li>{@code peerKind} = {@link PeerKind#THREAD}
 *   <li>{@code peerId} = {@code "<project.path_with_namespace>#<iid>:<noteable_type>"} — the
 *       trailing {@code :Issue|MergeRequest} is essential for the outbound dispatcher to pick the
 *       correct endpoint.
 * </ul>
 */
public final class GitLabInboundMapper {

    private final String channelId;

    public GitLabInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /** Returns the {@code object_attributes.id} used as the idempotency key. */
    public static Optional<Long> extractNoteId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("object_attributes").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /** Returns the {@code user.id} used for bot-loop self-detection. */
    public static Optional<Long> extractAuthorId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        long id = payload.path("user").path("id").asLong(-1);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }

    /**
     * Maps a Note Hook payload into an {@link InboundMessage}. Returns empty for unsupported
     * noteable types (Commit / Snippet), system notes, or malformed payloads.
     */
    public Optional<InboundMessage> map(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        JsonNode attrs = payload.path("object_attributes");
        if (attrs.isMissingNode() || !attrs.isObject()) {
            return Optional.empty();
        }
        if (attrs.path("system").asBoolean(false)) {
            return Optional.empty();
        }
        String noteableType = attrs.path("noteable_type").asText(null);
        if (!"Issue".equals(noteableType) && !"MergeRequest".equals(noteableType)) {
            return Optional.empty();
        }

        long iid;
        if ("Issue".equals(noteableType)) {
            iid = payload.path("issue").path("iid").asLong(-1);
        } else {
            iid = payload.path("merge_request").path("iid").asLong(-1);
        }
        if (iid <= 0) {
            return Optional.empty();
        }

        String pathWithNamespace = payload.path("project").path("path_with_namespace").asText(null);
        String namespace = payload.path("project").path("namespace").asText(null);
        if (pathWithNamespace == null || pathWithNamespace.isBlank()) {
            return Optional.empty();
        }

        String note = attrs.path("note").asText(null);
        String username = payload.path("user").path("username").asText(null);
        if (note == null || username == null) {
            return Optional.empty();
        }

        String peerId = pathWithNamespace + "#" + iid + ":" + noteableType;
        Peer peer = new Peer(PeerKind.THREAD, peerId);
        Msg msg = Msg.builder().role(MsgRole.USER).name(username).textContent(note).build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(namespace)
                        .senderId(username)
                        .build());
    }
}
