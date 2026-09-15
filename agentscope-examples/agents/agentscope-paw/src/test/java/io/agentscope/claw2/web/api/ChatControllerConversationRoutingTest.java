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
package io.agentscope.claw2.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Different Chat conversation ids must produce different gateway routing keys (including the
 * {@code |t:} thread segment), even when chatui {@code dmScope} is MAIN.
 */
class ChatControllerConversationRoutingTest {

    private static final ChannelRouter ROUTER = new ChannelRouter(null);
    private static final ChannelConfig MAIN_CHATUI =
            ChannelConfig.builder(ChatUiChannel.CHANNEL_ID).dmScope(DmScope.MAIN).build();

    @Test
    void differentConversationIds_produceDifferentGateKeys_withThreadSegment() {
        String k1 = gateKey("claw", "conv-aaa");
        String k2 = gateKey("claw", "conv-bbb");

        assertNotEquals(k1, k2);
        assertTrue(k1.contains("|t:conv-aaa"), k1);
        assertTrue(k2.contains("|t:conv-bbb"), k2);
        assertTrue(k1.contains("|x:agentId=claw"), k1);
        assertTrue(k2.contains("|x:agentId=claw"), k2);
    }

    @Test
    void blankConversationId_hasNoThreadSegment() {
        String key = gateKey("claw", null);
        assertFalse(key.contains("|t:"), key);
        assertTrue(key.contains("|x:agentId=claw"), key);
    }

    @Test
    void uuidConversationId_isAccepted() {
        ChatController.requireSafeConversationId("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        String key = gateKey("claw", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertTrue(key.contains("|t:3fa85f64-5717-4562-b3fc-2c963f66afa6"), key);
    }

    @Test
    void delimiterConversationId_isRejected() {
        ResponseStatusException forgedAgent =
                assertThrows(
                        ResponseStatusException.class,
                        () -> ChatController.requireSafeConversationId("foo|x:agentId=other"));
        assertEquals(HttpStatus.BAD_REQUEST, forgedAgent.getStatusCode());

        ResponseStatusException forgedThread =
                assertThrows(
                        ResponseStatusException.class,
                        () -> ChatController.requireSafeConversationId("foo|t:bar"));
        assertEquals(HttpStatus.BAD_REQUEST, forgedThread.getStatusCode());

        assertThrows(ResponseStatusException.class, () -> gateKey("claw", "foo|x:agentId=other"));
    }

    private static String gateKey(String agentId, String conversationId) {
        InboundMessage inbound =
                ChatController.buildConversationInbound(
                        agentId,
                        conversationId,
                        List.of(Msg.builder().role(MsgRole.USER).textContent("hi").build()));
        return ROUTER.resolveRoute(MAIN_CHATUI, inbound).context().canonicalKey();
    }
}
