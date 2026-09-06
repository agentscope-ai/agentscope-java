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
package io.agentscope.harness.agent.gateway.channel.chatui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Verifies ChatUiChannel Msg / List&lt;Msg&gt; send overloads. */
@DisplayName("ChatUiChannel Msg send overloads")
class ChatUiChannelMsgSendTest {

    @Test
    @DisplayName("send(SendOptions, Msg) forwards multimodal message to agent")
    void sendOptionsMsgForwardsContent() {
        AtomicReference<List<Msg>> seen = new AtomicReference<>();
        ChatUiChannel chat = chatCapturingMessages(seen);

        Msg multimodal = multimodalMsg();
        Msg reply = chat.send(SendOptions.userId("user-1"), multimodal).block();

        assertEquals("ok", reply.getTextContent());
        assertEquals(1, seen.get().size());
        assertSame(multimodal, seen.get().get(0));
        assertEquals(2, multimodal.getContent().size());
    }

    @Test
    @DisplayName("send(SendOptions, List) forwards the list as-is")
    void sendOptionsListForwards() {
        AtomicReference<List<Msg>> seen = new AtomicReference<>();
        ChatUiChannel chat = chatCapturingMessages(seen);

        Msg first = Msg.builder().role(MsgRole.USER).textContent("a").build();
        Msg second = multimodalMsg();
        List<Msg> batch = List.of(first, second);

        chat.send(SendOptions.userId("user-1"), batch).block();

        assertEquals(2, seen.get().size());
        assertSame(first, seen.get().get(0));
        assertSame(second, seen.get().get(1));
    }

    @Test
    @DisplayName("send(Msg) and send(List) work in single-session mode")
    void sendMsgAndListSingleSession() {
        AtomicReference<List<Msg>> seen = new AtomicReference<>();
        ChatUiChannel chat = chatCapturingMessages(seen);

        Msg multimodal = multimodalMsg();
        chat.send(multimodal).block();
        assertSame(multimodal, seen.get().get(0));

        seen.set(null);
        List<Msg> batch = List.of(multimodal);
        chat.send(batch).block();
        assertSame(multimodal, seen.get().get(0));
    }

    @Test
    @DisplayName("send(peerId, Msg) and send(peerId, List) route by peer")
    void sendPeerMsgAndList() {
        AtomicReference<List<Msg>> seen = new AtomicReference<>();
        ChatUiChannel chat = chatCapturingMessages(seen);

        Msg multimodal = multimodalMsg();
        chat.send("peer-1", multimodal).block();
        assertSame(multimodal, seen.get().get(0));

        seen.set(null);
        chat.send("peer-2", List.of(multimodal)).block();
        assertSame(multimodal, seen.get().get(0));
    }

    @Test
    @DisplayName("empty List throw IllegalArgumentException")
    void emptyListRejected() {
        ChatUiChannel chat = ChatUiChannel.create(HarnessGateway.create());
        assertThrows(IllegalArgumentException.class, () -> chat.send(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> chat.send(SendOptions.userId("u"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> chat.send("peer", List.of()));
    }

    private static ChatUiChannel chatCapturingMessages(AtomicReference<List<Msg>> seen) {
        HarnessAgent agent =
                spy(
                        HarnessAgent.builder()
                                .name("assistant")
                                .sysPrompt("hi")
                                .model(new MockModel("ok"))
                                .build());
        doAnswer(
                        inv -> {
                            seen.set(inv.getArgument(0));
                            return Mono.just(
                                    Msg.builder()
                                            .role(MsgRole.ASSISTANT)
                                            .textContent("ok")
                                            .build());
                        })
                .when(agent)
                .call(anyList(), any(RuntimeContext.class));

        HarnessGateway gateway = HarnessGateway.create();
        gateway.bindMainAgent(agent);
        return ChatUiChannel.create(gateway);
    }

    private static Msg multimodalMsg() {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(
                        TextBlock.builder().text("What is in this image?").build(),
                        ImageBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url("https://example.com/photo.png")
                                                .build())
                                .build())
                .build();
    }
}
