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
package io.agentscope.extensions.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Optional;

/**
 * Parses a decrypted Feishu Event Subscription v2 envelope into an {@link InboundMessage}.
 *
 * <p>Schema 2.0 envelope shape:
 *
 * <pre>
 * {
 *   "schema": "2.0",
 *   "header": {"event_id":"...","event_type":"im.message.receive_v1","tenant_key":"...", ...},
 *   "event": {
 *     "sender": {"sender_id":{"open_id":"...","user_id":"...","union_id":"..."}, "sender_type":"user"},
 *     "message": {
 *       "message_id":"om_...",
 *       "chat_id":"oc_...",
 *       "chat_type":"p2p|group",
 *       "message_type":"text|...",
 *       "content":"{\"text\":\"@_user_1 hello\"}"
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>For MVP only {@code message_type=text} is mapped. The inner {@code content} field is a
 * JSON-encoded string and is re-parsed to read the {@code text} field.
 */
public final class FeishuInboundMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String channelId;

    public FeishuInboundMapper(String channelId) {
        this.channelId = channelId;
    }

    /** Returns the {@code header.event_id}, used by the idempotency store. */
    public static Optional<String> extractEventId(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String id = envelope.path("header").path("event_id").asText(null);
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    /** Returns the URL verification challenge if the envelope is a verification request. */
    public static Optional<String> extractUrlChallenge(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String type = envelope.path("type").asText(null);
        if (!"url_verification".equals(type)) {
            return Optional.empty();
        }
        String challenge = envelope.path("challenge").asText(null);
        return (challenge == null || challenge.isBlank())
                ? Optional.empty()
                : Optional.of(challenge);
    }

    /** Returns the {@code event.sender.sender_id.open_id} for bot-loop self-detection. */
    public static Optional<String> extractSenderOpenId(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String id =
                envelope.path("event")
                        .path("sender")
                        .path("sender_id")
                        .path("open_id")
                        .asText(null);
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    /** Returns the {@code header.tenant_key} (multi-tenant id). */
    public static Optional<String> extractTenantKey(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        String key = envelope.path("header").path("tenant_key").asText(null);
        return (key == null || key.isBlank()) ? Optional.empty() : Optional.of(key);
    }

    /**
     * Maps a decrypted Schema 2.0 envelope into an {@link InboundMessage}. Returns empty for
     * non-text or malformed events so the caller can ack without dispatching.
     */
    public Optional<InboundMessage> map(JsonNode envelope) {
        if (envelope == null) {
            return Optional.empty();
        }
        JsonNode event = envelope.path("event");
        if (event.isMissingNode() || !event.isObject()) {
            return Optional.empty();
        }
        JsonNode message = event.path("message");
        String messageType = message.path("message_type").asText(null);
        if (!"text".equalsIgnoreCase(messageType)) {
            return Optional.empty();
        }
        String chatType = message.path("chat_type").asText(null);
        String chatId = message.path("chat_id").asText(null);
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        String openId = event.path("sender").path("sender_id").path("open_id").asText(null);
        String contentJson = message.path("content").asText(null);
        if (contentJson == null || contentJson.isBlank()) {
            return Optional.empty();
        }
        String text;
        try {
            JsonNode content = MAPPER.readTree(contentJson);
            text = content.path("text").asText(null);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (text == null) {
            return Optional.empty();
        }

        // Group chats are addressed by chat_id (no per-user routing). For p2p, we still use the
        // chat_id as the conversation key — it's the stable identifier Feishu uses for the
        // 1:1 chat instance, and bot replies must be sent to the chat_id with
        // receive_id_type=chat_id
        // (or open_id; we use chat_id for consistency).
        PeerKind kind = "group".equalsIgnoreCase(chatType) ? PeerKind.GROUP : PeerKind.DIRECT;
        Peer peer = new Peer(kind, chatId);
        String senderName = openId != null ? openId : chatId;
        Msg msg = Msg.builder().role(MsgRole.USER).name(senderName).textContent(text).build();
        String tenant = envelope.path("header").path("tenant_key").asText(null);
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(tenant)
                        .senderId(senderName)
                        .build());
    }
}
