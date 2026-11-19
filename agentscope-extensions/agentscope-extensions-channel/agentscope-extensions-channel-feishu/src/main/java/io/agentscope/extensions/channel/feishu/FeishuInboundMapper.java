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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>Text messages ({@code message_type=text}) map to their content; the inner {@code content}
 * field is a JSON-encoded string and is re-parsed to read the {@code text} field. Media messages
 * ({@code image} / {@code file} / {@code folder} / {@code audio} / {@code media}) map to a
 * neutral marker text plus the {@link ChannelMediaMetadata} contract: the {@code image_key} /
 * {@code file_key} material identifiers and per-kind metadata are preserved verbatim, and
 * fetching the bytes (a token-authenticated message-resource download) is left to the
 * application. Other message types (post, sticker, interactive, share cards, ...) are returned
 * as {@link Optional#empty()} so the caller can ack without dispatching.
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
     * Maps a decrypted Schema 2.0 envelope into an {@link InboundMessage}. Text messages map to
     * their content; media messages map to a neutral marker plus {@link ChannelMediaMetadata}
     * entries. Returns empty for other message types or malformed events so the caller can ack
     * without dispatching.
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
        String chatType = message.path("chat_type").asText(null);
        String chatId = message.path("chat_id").asText(null);
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        String openId = event.path("sender").path("sender_id").path("open_id").asText(null);
        if (openId == null
                || openId.isBlank()
                || !"user".equals(event.path("sender").path("sender_type").asText())
                || message.path("message_id").asText("").isBlank()) return Optional.empty();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelMessageId", message.path("message_id").asText(""));
        metadata.put("channelReplyToId", message.path("parent_id").asText(""));
        metadata.put("channelThreadId", message.path("root_id").asText(""));
        String text;
        if ("text".equalsIgnoreCase(messageType)) {
            String contentJson = message.path("content").asText(null);
            if (contentJson == null || contentJson.isBlank()) {
                return Optional.empty();
            }
            JsonNode content;
            try {
                content = MAPPER.readTree(contentJson);
            } catch (Exception e) {
                return Optional.empty();
            }
            text = content.path("text").asText(null);
            if (text == null) {
                return Optional.empty();
            }
            // Providers encode mentions as structured keys. Strip leading mention keys
            // so an addressed bot can receive explicit work commands in group chats.
            text = text.strip();
            for (JsonNode mention : message.path("mentions")) {
                String key = mention.path("key").asText("");
                if (!key.isBlank() && text.startsWith(key))
                    text = text.substring(key.length()).stripLeading();
            }
        } else {
            Optional<String> marker = mapMediaContent(message, messageType, metadata);
            if (marker.isEmpty()) {
                return Optional.empty();
            }
            text = marker.get();
        }
        // Group chats are addressed by chat_id (no per-user routing). For p2p, we still use the
        // chat_id as the conversation key — it's the stable identifier Feishu uses for the
        // 1:1 chat instance, and bot replies must be sent to the chat_id with
        // receive_id_type=chat_id
        // (or open_id; we use chat_id for consistency).
        PeerKind kind = "group".equalsIgnoreCase(chatType) ? PeerKind.GROUP : PeerKind.DIRECT;
        Peer peer = new Peer(kind, chatId);
        String senderName = openId != null ? openId : chatId;
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(senderName)
                        .textContent(text)
                        .metadata(metadata)
                        .build();
        String tenant = envelope.path("header").path("tenant_key").asText(null);
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(tenant)
                        .senderId(senderName)
                        .build());
    }

    /**
     * Maps the content of a media message into the {@link ChannelMediaMetadata} contract and
     * returns the neutral marker text, or empty when the type is unmapped or the material
     * identifier is missing. {@code image} keys on {@code image_key}; {@code file}, {@code
     * folder}, {@code audio} and {@code media} key on {@code file_key}; {@code media} is a
     * video-bearing composite whose {@code image_key} is the cover.
     */
    private Optional<String> mapMediaContent(
            JsonNode message, String messageType, Map<String, Object> metadata) {
        String kind = mediaKind(messageType);
        if (kind == null) {
            return Optional.empty();
        }
        JsonNode content = parseMessageContent(message);
        if (content == null) {
            return Optional.empty();
        }
        String id =
                ChannelMediaMetadata.KIND_IMAGE.equals(kind)
                        ? content.path("image_key").asText(null)
                        : content.path("file_key").asText(null);
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, messageType);
        metadata.put(ChannelMediaMetadata.ID, id);
        if (ChannelMediaMetadata.KIND_VIDEO.equals(kind)) {
            ChannelMediaMetadata.putIfPresent(
                    metadata,
                    ChannelMediaMetadata.SECONDARY_ID,
                    content.path("image_key").asText(null));
        }
        if (ChannelMediaMetadata.KIND_AUDIO.equals(kind)
                || ChannelMediaMetadata.KIND_VIDEO.equals(kind)) {
            ChannelMediaMetadata.putIfPresent(
                    metadata,
                    ChannelMediaMetadata.DURATION_MS,
                    content.path("duration").asText(null));
        }
        String fileName = content.path("file_name").asText(null);
        ChannelMediaMetadata.putIfPresent(metadata, ChannelMediaMetadata.FILE_NAME, fileName);
        return Optional.of(ChannelMediaMetadata.markerText(kind, fileName));
    }

    /**
     * Returns the normalized media kind for a Feishu {@code message_type}, or {@code null} when
     * the type is not a mapped media message.
     */
    private static String mediaKind(String messageType) {
        if (messageType == null) {
            return null;
        }
        return switch (messageType.toLowerCase(Locale.ROOT)) {
            case "image" -> ChannelMediaMetadata.KIND_IMAGE;
            // A folder's file_key identifies the folder node itself; the message-resource
            // download API serves image / file / audio / video resources only, so it is a
            // reference id rather than fetchable bytes.
            case "file", "folder" -> ChannelMediaMetadata.KIND_FILE;
            case "audio" -> ChannelMediaMetadata.KIND_AUDIO;
            case "media" -> ChannelMediaMetadata.KIND_VIDEO;
            default -> null;
        };
    }

    private static JsonNode parseMessageContent(JsonNode message) {
        String contentJson = message.path("content").asText(null);
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(contentJson);
        } catch (Exception e) {
            return null;
        }
    }
}
