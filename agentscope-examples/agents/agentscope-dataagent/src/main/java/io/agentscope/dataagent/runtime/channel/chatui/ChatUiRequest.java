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
package io.agentscope.dataagent.runtime.channel.chatui;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Objects;

/**
 * Request object for {@link ChatUiChannel}. Carries the peer identity (optional) and the messages
 * to send to the agent.
 *
 * <p>The {@code peerId} is used by {@link io.agentscope.dataagent.runtime.channel.ChannelRouter} to build a
 * stable session key:
 *
 * <ul>
 *   <li>If {@code peerId} is null (or blank) and the channel's {@link
 *       io.agentscope.dataagent.runtime.channel.DmScope} is {@link
 *       io.agentscope.dataagent.runtime.channel.DmScope#MAIN}, all requests without a peer share the same
 *       session — convenient for single-user or testing scenarios.
 *   <li>If {@code peerId} is provided, each distinct peer gets its own session (when using {@link
 *       io.agentscope.dataagent.runtime.channel.DmScope#PER_PEER} or similar scopes).
 * </ul>
 *
 * @param peerId optional user / peer identifier; null means "no peer" (single-session mode)
 * @param messages one or more messages to send
 */
public record ChatUiRequest(String peerId, List<Msg> messages) {

    public ChatUiRequest {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }

    // -----------------------------------------------------------------
    //  Factories
    // -----------------------------------------------------------------

    /** Single user-text message in single-session mode (no peer). */
    public static ChatUiRequest of(String text) {
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(null, List.of(userMsg(text)));
    }

    /** Single user-text message associated with a specific peer id. */
    public static ChatUiRequest withPeer(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return new ChatUiRequest(peerId, List.of(userMsg(text)));
    }

    /** Multi-message request without a peer (single-session mode). */
    public static ChatUiRequest of(List<Msg> messages) {
        return new ChatUiRequest(null, messages);
    }

    /** Multi-message request for a specific peer. */
    public static ChatUiRequest withPeer(String peerId, List<Msg> messages) {
        return new ChatUiRequest(peerId, messages);
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
