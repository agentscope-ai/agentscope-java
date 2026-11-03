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
package io.agentscope.extensions.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.List;
import java.util.Optional;

/** Maps iLink user text messages. The current official plugin advertises direct chat only. */
public final class WeixinInboundMapper {
    private final String channelId;
    private final String accountId;
    private final String selfId;

    public WeixinInboundMapper(String channelId, String accountId, String selfId) {
        this.channelId = channelId;
        this.accountId = accountId;
        this.selfId = selfId;
    }

    public Optional<InboundMessage> map(JsonNode m) {
        if (m == null || m.path("message_type").asInt(0) != 1) return Optional.empty();
        String from = text(m, "from_user_id"), token = text(m, "context_token");
        if (from == null || from.isBlank() || (selfId != null && selfId.equals(from)))
            return Optional.empty();
        String body = null;
        for (JsonNode item : m.path("item_list"))
            if (item.path("type").asInt(0) == 1) {
                body = item.path("text_item").path("text").asText(null);
                break;
            }
        if (body == null || body.isBlank()) return Optional.empty();
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        put(meta, "channelMessageId", text(m, "message_id"));
        put(meta, "weixinContextToken", token);
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name(from)
                        .textContent(body.strip())
                        .metadata(meta)
                        .build();
        return Optional.of(
                InboundMessage.builder(channelId, new Peer(PeerKind.DIRECT, from), List.of(msg))
                        .accountId(accountId)
                        .senderId(from)
                        .build());
    }

    public static Optional<String> messageId(JsonNode m) {
        String id = text(m, "message_id");
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static void put(java.util.Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }
}
