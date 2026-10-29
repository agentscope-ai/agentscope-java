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
package io.agentscope.dataagent.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies conversation-scoped inbound routing produces distinct gate keys per conversation. */
class ChatControllerConversationRoutingTest {

    private static final ChatUiChannel CHANNEL =
            ChatUiChannel.create(
                    ChannelConfig.builder("chatui")
                            .defaultAgentId("data-agent")
                            .dmScope(DmScope.MAIN)
                            .build());

    private static String gateKey(InboundMessage inbound) {
        return CHANNEL.previewRoute(inbound).context().canonicalKey();
    }

    @Test
    void differentConversationIdsProduceDistinctGateKeysWithThreadSegment() {
        InboundMessage a =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());
        InboundMessage b =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-b", List.of());

        String gateKeyA = gateKey(a);
        String gateKeyB = gateKey(b);

        assertThat(gateKeyA).isNotEqualTo(gateKeyB);
        assertThat(gateKeyA).contains("|t:conv-a");
        assertThat(gateKeyB).contains("|t:conv-b");
        assertThat(SessionController.extractConversationId(gateKeyA)).isEqualTo("conv-a");
        assertThat(SessionController.extractConversationId(gateKeyB)).isEqualTo("conv-b");
    }

    @Test
    void sameConversationIdMapsToSameGateKey() {
        InboundMessage first =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());
        InboundMessage second =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());

        assertThat(gateKey(first)).isEqualTo(gateKey(second));
    }

    @Test
    void probeAndDispatchInboundShareCanonicalKey() {
        InboundMessage probe =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());
        InboundMessage dispatch =
                ChatController.buildConversationInbound(
                        "user-1",
                        "data-agent",
                        "conv-a",
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build()));

        assertThat(gateKey(probe)).isEqualTo(gateKey(dispatch));
    }

    @Test
    void blankConversationIdIsRejectedByBuildConversationInbound() {
        assertThatThrownBy(
                        () ->
                                ChatController.buildConversationInbound(
                                        "user-1", "data-agent", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
        assertThatThrownBy(
                        () ->
                                ChatController.buildConversationInbound(
                                        "user-1", "data-agent", "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");
    }

    @Test
    void threadInboundCarriesSenderIdAndParentPeer() {
        InboundMessage inbound =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());

        assertThat(inbound.senderId()).isEqualTo("user-1");
        assertThat(inbound.parentPeer()).isEqualTo(Peer.direct("user-1"));
        assertThat(inbound.peer()).isEqualTo(Peer.thread("conv-a"));
    }

    @Test
    void sameUserDifferentConversationsShareRoomButNotThread() {
        InboundMessage a =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-a", List.of());
        InboundMessage b =
                ChatController.buildConversationInbound(
                        "user-1", "data-agent", "conv-b", List.of());

        String gateKeyA = gateKey(a);
        String gateKeyB = gateKey(b);

        assertThat(gateKeyA).contains("|r:user-1");
        assertThat(gateKeyB).contains("|r:user-1");
        assertThat(gateKeyA).isNotEqualTo(gateKeyB);
    }
}
