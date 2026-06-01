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
package io.agentscope.builder.runtime.channel.wecom;

import io.agentscope.builder.runtime.channel.InboundMessage;
import io.agentscope.builder.runtime.channel.Peer;
import io.agentscope.builder.runtime.channel.PeerKind;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.io.StringReader;
import java.util.List;
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
 * <p>For MVP only {@code MsgType=text} (single-user app message) is mapped. Other inbound types
 * (event, image, voice, ...) are returned as {@link Optional#empty()} so the caller can ack the
 * webhook without dispatching to the agent.
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
     * Builds an {@link InboundMessage} from a decrypted WeCom message XML, or returns empty when
     * the payload is not a user-text message we should dispatch.
     */
    public Optional<InboundMessage> map(String xml) {
        try {
            DocumentBuilder builder = DBF.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            String msgType = textValue(root, "MsgType");
            if (!"text".equalsIgnoreCase(msgType)) {
                return Optional.empty();
            }
            String fromUser = textValue(root, "FromUserName");
            String content = textValue(root, "Content");
            if (fromUser == null || fromUser.isBlank() || content == null) {
                return Optional.empty();
            }
            Msg msg = Msg.builder().role(MsgRole.USER).name(fromUser).textContent(content).build();
            Peer peer = new Peer(PeerKind.DIRECT, fromUser);
            return Optional.of(
                    InboundMessage.builder(channelId, peer, List.of(msg))
                            .accountId(accountId)
                            .senderId(fromUser)
                            .build());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse WeCom callback XML: " + e.getMessage(), e);
        }
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
