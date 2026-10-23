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
package io.agentscope.extensions.channel.wecom;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parses a decrypted WeCom callback XML body into an {@link InboundMessage}.
 *
 * <p>Text messages ({@code MsgType=text}, single-user app message) map to their content. Media
 * messages ({@code MsgType} {@code image} / {@code voice} / {@code video}) map to a neutral
 * marker text plus the {@link ChannelMediaMetadata} contract: the provider material identifiers
 * are preserved verbatim and decoding them (a token-authenticated {@code media/get} exchange,
 * valid for three days) is left to the application. Other inbound types (event, location, link,
 * ...) are returned as {@link Optional#empty()} so the caller can ack the webhook without
 * dispatching to the agent.
 */
public final class WeComInboundMapper {

    private static final DocumentBuilderFactory DBF = newSafeDocumentBuilderFactory();

    private final String channelId;
    private final String accountId;

    public WeComInboundMapper(String channelId, String accountId) {
        this.channelId = channelId;
        this.accountId = accountId;
    }

    /**
     * Builds an {@link InboundMessage} from a decrypted WeCom message XML. Text messages map to
     * their content; media messages map to a neutral marker plus {@link ChannelMediaMetadata}
     * entries. Returns empty for other payload kinds or malformed events so the caller can ack
     * without dispatching.
     */
    public Optional<InboundMessage> map(String xml) {
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            String msgType = textValue(root, "MsgType");
            if ("text".equalsIgnoreCase(msgType)) {
                return mapText(root);
            }
            return mapMedia(root, msgType);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse WeCom callback XML: " + e.getMessage(), e);
        }
    }

    private Optional<InboundMessage> mapText(Element root) {
        String fromUser = textValue(root, "FromUserName");
        String content = textValue(root, "Content");
        if (fromUser == null || fromUser.isBlank() || content == null) {
            return Optional.empty();
        }
        return assemble(
                fromUser,
                content,
                Map.of("channelMessageId", Objects.toString(textValue(root, "MsgId"), "")));
    }

    /**
     * Maps a media message to a neutral marker plus the {@link ChannelMediaMetadata} contract.
     * {@code image} carries {@code PicUrl} + {@code MediaId}; {@code voice} carries {@code
     * MediaId} + {@code Format}; {@code video} carries {@code MediaId} + {@code ThumbMediaId}.
     */
    private Optional<InboundMessage> mapMedia(Element root, String msgType) {
        String kind = mediaKind(msgType);
        if (kind == null) {
            return Optional.empty();
        }
        String fromUser = textValue(root, "FromUserName");
        String mediaId = textValue(root, "MediaId");
        if (fromUser == null || fromUser.isBlank() || mediaId == null || mediaId.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("channelMessageId", Objects.toString(textValue(root, "MsgId"), ""));
        metadata.put(ChannelMediaMetadata.KIND, kind);
        metadata.put(ChannelMediaMetadata.PROVIDER_TYPE, msgType);
        metadata.put(ChannelMediaMetadata.ID, mediaId);
        switch (kind) {
            case ChannelMediaMetadata.KIND_IMAGE ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata, ChannelMediaMetadata.URL, textValue(root, "PicUrl"));
            case ChannelMediaMetadata.KIND_AUDIO ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata, ChannelMediaMetadata.FORMAT, textValue(root, "Format"));
            case ChannelMediaMetadata.KIND_VIDEO ->
                    ChannelMediaMetadata.putIfPresent(
                            metadata,
                            ChannelMediaMetadata.SECONDARY_ID,
                            textValue(root, "ThumbMediaId"));
            default -> {
                // Unreachable: kinds are enumerated by mediaKind.
            }
        }
        return assemble(fromUser, ChannelMediaMetadata.markerText(kind, null), metadata);
    }

    /** Builds the direct-message {@link InboundMessage} from mapped text content and metadata. */
    private Optional<InboundMessage> assemble(
            String fromUser, String textContent, Map<String, Object> metadata) {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(fromUser)
                        .textContent(textContent)
                        .metadata(metadata)
                        .build();
        Peer peer = new Peer(PeerKind.DIRECT, fromUser);
        return Optional.of(
                InboundMessage.builder(channelId, peer, List.of(msg))
                        .accountId(accountId)
                        .senderId(fromUser)
                        .build());
    }

    /**
     * Returns the normalized media kind for a WeCom {@code MsgType}, or {@code null} when the
     * type is not a mapped media message.
     */
    private static String mediaKind(String msgType) {
        if (msgType == null) {
            return null;
        }
        return switch (msgType.toLowerCase(Locale.ROOT)) {
            case "image" -> ChannelMediaMetadata.KIND_IMAGE;
            case "voice" -> ChannelMediaMetadata.KIND_AUDIO;
            case "video" -> ChannelMediaMetadata.KIND_VIDEO;
            default -> null;
        };
    }

    /** Returns the {@code MsgId} field if present, used by the idempotency store. */
    public static Optional<String> extractMsgId(String xml) {
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            String id = textValue(doc.getDocumentElement(), "MsgId");
            return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String textValue(Element root, String tagName) {
        NodeList list = root.getElementsByTagName(tagName);
        if (list == null || list.getLength() == 0) {
            return null;
        }
        Node node = list.item(0);
        return node != null ? node.getTextContent() : null;
    }

    private static DocumentBuilderFactory newSafeDocumentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
            // Best-effort hardening; if the parser does not support a feature, skip.
        }
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }
}
