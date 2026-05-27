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
package io.agentscope.dataagent.runtime.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.runtime.channel.InboundMessage;
import io.agentscope.dataagent.runtime.channel.Peer;
import io.agentscope.dataagent.runtime.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/**
 * Parses a DingTalk Stream bot-message payload (subscribed topic
 * {@code /v1.0/im/bot/messages/get}) into an {@link InboundMessage}.
 *
 * <p>For MVP only {@code msgtype=text} is mapped. Other inbound types (image, file, picture, ...)
 * are returned as {@link Optional#empty()} so the caller can ack the event without dispatching to
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
     * Builds an {@link InboundMessage} from a DingTalk Stream bot-message JSON payload, or returns
     * empty when the payload is not a text message we should dispatch.
     */
    public Optional<InboundMessage> map(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return Optional.empty();
        }
        String msgType = textValue(payload, "msgtype");
        if (!"text".equalsIgnoreCase(msgType)) {
            return Optional.empty();
        }
        String content = payload.path("text").path("content").asText(null);
        if (content == null) {
            return Optional.empty();
        }
        content = content.strip();
        if (content.isEmpty()) {
            return Optional.empty();
        }

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

        Msg msg = Msg.builder().role(MsgRole.USER).name(senderId).textContent(content).build();
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
