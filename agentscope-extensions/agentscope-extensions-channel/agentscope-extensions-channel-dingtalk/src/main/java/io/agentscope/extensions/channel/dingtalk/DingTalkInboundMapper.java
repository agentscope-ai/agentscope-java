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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Parses a DingTalk bot-message payload (Stream topic {@code /v1.0/im/bot/messages/get} or the
 * HTTP callback body of the same protocol) into an {@link InboundMessage}.
 *
 * <p>Text messages ({@code msgtype=text}) map to their content. Media messages ({@code picture} /
 * {@code audio} / {@code video} / {@code file}) map to a neutral marker text plus the {@link
 * ChannelMediaMetadata} contract: the {@code downloadCode} and per-kind metadata are preserved
 * verbatim, and exchanging the code for bytes (a server-side API call yielding a temporary
 * download URL) is left to the application. Note the platform delivers audio/video/file to the
 * robot in single chat only, not on group @-mention. {@code richText} and unknown types are
 * returned as {@link Optional#empty()} so the caller can ack the event without dispatching to
 * the agent.
 *
 * <p>Conversation kinds:
 *
 * <ul>
 *   <li>{@code conversationType="1"} → DM ({@link PeerKind#DIRECT}). Peer id = sender staff id.
 *   <li>{@code conversationType="2"} → group ({@link PeerKind#GROUP}). Peer id = conversation id.
 * </ul>
 *
 * <p>Group messages typically arrive only when the bot is @-mentioned (DingTalk's bot SDK
 * dispatches mention-triggered events only); the leading {@code @bot} text is preserved by the
 * platform in {@code text.content}.
 */
public final class DingTalkInboundMapper {

    private final String channelId;
    private final String accountId;

    public DingTalkInboundMapper(String channelId, String accountId) {
        this.channelId = channelId;
        this.accountId = accountId;
    }

    /**
     * Builds an {@link InboundMessage} from a DingTalk bot-message JSON payload. Text messages
     * map to their content; media messages map to a neutral marker plus {@link
     * ChannelMediaMetadata} entries. Returns empty for other payload kinds or malformed events so
     * the caller can ack without dispatching.
     */
    public Optional<InboundMessage> map(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return Optional.empty();
        }
        String msgType = textValue(payload, "msgtype");
        if ("text".equalsIgnoreCase(msgType)) {
            return mapText(payload);
        }
        return mapMedia(payload, msgType);
    }

    private Optional<InboundMessage> mapText(JsonNode payload) {
        String content = payload.path("text").path("content").asText(null);
        if (content == null) {
            return Optional.empty();
        }
        content = content.strip();
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return assemble(payload, content, new LinkedHashMap<>());
    }

    /**
     * Maps a media message to a neutral marker plus the {@link ChannelMediaMetadata} contract.
     * Media fields are read from the payload top level first and from the nested {@code content}
     * object as a fallback — protocol revisions differ in field placement. {@code picture}
     * carries a {@code downloadCode} (plus {@code pictureDownloadCode}); {@code audio} adds
     * {@code recognition} (platform speech-to-text) and {@code duration}; {@code video} adds
     * {@code videoType} and {@code duration} (the DingTalk protocol documents both durations
     * in milliseconds); {@code file} adds {@code fileName}.
     */
    private Optional<InboundMessage> mapMedia(JsonNode payload, String msgType) {
        String kind = mediaKind(msgType);
        if (kind == null) {
            return Optional.empty();
        }
        String downloadCode = mediaField(payload, "downloadCode");
        if (downloadCode == null || downloadCode.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, msgType);
        metadata.put(ChannelMediaMetadata.ID, downloadCode);
        String fileName = null;
        switch (kind) {
            case ChannelMediaMetadata.KIND_IMAGE ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata,
                            ChannelMediaMetadata.SECONDARY_ID,
                            mediaField(payload, "pictureDownloadCode"));
            case ChannelMediaMetadata.KIND_AUDIO -> {
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.RECOGNITION,
                        mediaField(payload, "recognition"));
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.DURATION_MS,
                        mediaField(payload, "duration"));
            }
            case ChannelMediaMetadata.KIND_VIDEO -> {
                ChannelMediaMetadata.putIfPresent(
                        metadata, ChannelMediaMetadata.FORMAT, mediaField(payload, "videoType"));
                ChannelMediaMetadata.putIfPresent(
                        metadata,
                        ChannelMediaMetadata.DURATION_MS,
                        mediaField(payload, "duration"));
            }
            case ChannelMediaMetadata.KIND_FILE -> {
                fileName = mediaField(payload, "fileName");
                ChannelMediaMetadata.putIfPresent(
                        metadata, ChannelMediaMetadata.FILE_NAME, fileName);
            }
            default -> {
                // Unreachable: kinds are enumerated by mediaKind.
            }
        }
        return assemble(payload, ChannelMediaMetadata.markerText(kind, fileName), metadata);
    }

    /**
     * Returns the normalized media kind for a DingTalk {@code msgtype}, or {@code null} when the
     * type is not a mapped media message.
     */
    private static String mediaKind(String msgType) {
        if (msgType == null) {
            return null;
        }
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "picture" -> ChannelMediaMetadata.KIND_IMAGE;
            case "audio" -> ChannelMediaMetadata.KIND_AUDIO;
            case "video" -> ChannelMediaMetadata.KIND_VIDEO;
            case "file" -> ChannelMediaMetadata.KIND_FILE;
            default -> null;
        };
    }

    /** Reads a media field from the payload top level, falling back to the nested {@code
     * content} object. */
    private static String mediaField(JsonNode payload, String field) {
        String value = textValue(payload, field);
        if (value != null) {
            return value;
        }
        JsonNode content = payload.get("content");
        return content != null ? textValue(content, field) : null;
    }

    private Optional<InboundMessage> assemble(
            JsonNode payload, String textContent, Map<String, Object> metadata) {
        metadata.put("channelMessageId", extractMsgId(payload).orElse(""));
        String conversationType = textValue(payload, "conversationType");
        String senderStaffId = textValue(payload, "senderStaffId");
        String conversationId = textValue(payload, "conversationId");

        Peer peer;
        String senderId;
        if ("2".equals(conversationType) && conversationId != null) {
            peer = new Peer(PeerKind.GROUP, conversationId);
            senderId = senderStaffId != null ? senderStaffId : conversationId;
        } else {
            String peerId = senderStaffId != null ? senderStaffId : conversationId;
            if (peerId == null || peerId.isBlank()) {
                return Optional.empty();
            }
            peer = new Peer(PeerKind.DIRECT, peerId);
            senderId = peerId;
        }

        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(senderId)
                        .textContent(textContent)
                        .metadata(metadata)
                        .build();
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(accountId)
                        .senderId(senderId)
                        .build());
    }

    /** Returns the {@code msgId} field if present, used by the idempotency store. */
    public static Optional<String> extractMsgId(JsonNode payload) {
        if (payload == null) {
            return Optional.empty();
        }
        String id = textValue(payload, "msgId");
        return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
    }

    private static String textValue(JsonNode payload, String field) {
        JsonNode v = payload.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
