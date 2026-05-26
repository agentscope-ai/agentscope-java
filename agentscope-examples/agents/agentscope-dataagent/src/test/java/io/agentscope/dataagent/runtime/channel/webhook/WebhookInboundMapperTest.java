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
package io.agentscope.dataagent.runtime.channel.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.runtime.channel.InboundMessage;
import io.agentscope.dataagent.runtime.channel.PeerKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Covers the request-to-{@link InboundMessage} mapping. Two invariants matter for downstream
 * routing:
 *
 * <ul>
 *   <li>The {@code senderId} is always the bare {@code externalUserId}, regardless of whether a
 *       sub-session id is supplied — per-user workspace namespacing must stay stable across
 *       sub-threads.
 *   <li>The {@code peer.id} composes {@code externalUserId + "::" + externalSessionId} when a
 *       session id is supplied, so concurrent threads from the same user do not collide on a
 *       single session.
 * </ul>
 */
class WebhookInboundMapperTest {

    private final WebhookInboundMapper mapper = new WebhookInboundMapper("ops-webhook");

    @Test
    void mapsBareUserMessage() {
        WebhookInboundRequest req =
                new WebhookInboundRequest(
                        "alice@corp", null, "how many signups yesterday?", null, null, null, null);

        Optional<InboundMessage> out = mapper.map(req);

        assertThat(out).isPresent();
        InboundMessage msg = out.orElseThrow();
        assertThat(msg.channelId()).isEqualTo("ops-webhook");
        assertThat(msg.senderId()).isEqualTo("alice@corp");
        assertThat(msg.peer().kind()).isEqualTo(PeerKind.DIRECT);
        assertThat(msg.peer().id()).isEqualTo("alice@corp");
        assertThat(msg.messages()).hasSize(1);
        assertThat(msg.messages().get(0).getTextContent()).isEqualTo("how many signups yesterday?");
    }

    @Test
    void sessionIdScopesPeerButNotSender() {
        WebhookInboundRequest req =
                new WebhookInboundRequest(
                        "alice@corp", "thread-42", "ping", null, null, null, null);

        InboundMessage msg = mapper.map(req).orElseThrow();

        assertThat(msg.peer().id())
                .as("peer id must include the session so concurrent threads don't collide")
                .isEqualTo("alice@corp::thread-42");
        assertThat(msg.senderId())
                .as("senderId stays bare so workspace namespacing remains stable")
                .isEqualTo("alice@corp");
    }

    @Test
    void preferredAgentIdIsPropagated() {
        WebhookInboundRequest req =
                new WebhookInboundRequest(
                        "alice@corp", null, "ping", null, null, "data-agent", null);

        InboundMessage msg = mapper.map(req).orElseThrow();

        assertThat(msg.preferredAgentId()).isEqualTo("data-agent");
    }

    @Test
    void blankPreferredAgentIdIsIgnored() {
        WebhookInboundRequest req =
                new WebhookInboundRequest("alice@corp", null, "ping", null, null, "   ", null);

        InboundMessage msg = mapper.map(req).orElseThrow();

        assertThat(msg.preferredAgentId()).isNull();
    }

    @Test
    void missingExternalUserIdIsRejected() {
        assertThat(
                        mapper.map(
                                new WebhookInboundRequest(
                                        null, null, "ping", null, null, null, null)))
                .isEmpty();
        assertThat(
                        mapper.map(
                                new WebhookInboundRequest(
                                        "   ", null, "ping", null, null, null, null)))
                .isEmpty();
    }

    @Test
    void missingMessageIsRejected() {
        assertThat(
                        mapper.map(
                                new WebhookInboundRequest(
                                        "alice@corp", null, null, null, null, null, null)))
                .isEmpty();
        assertThat(
                        mapper.map(
                                new WebhookInboundRequest(
                                        "alice@corp", null, "   ", null, null, null, null)))
                .isEmpty();
    }

    @Test
    void nullRequestIsRejected() {
        assertThat(mapper.map(null)).isEmpty();
    }

    @Test
    void whitespaceIsStripped() {
        WebhookInboundRequest req =
                new WebhookInboundRequest(
                        "  alice@corp  ", "  thread-1  ", "  hello  ", null, null, null, null);

        InboundMessage msg = mapper.map(req).orElseThrow();

        assertThat(msg.senderId()).isEqualTo("alice@corp");
        assertThat(msg.peer().id()).isEqualTo("alice@corp::thread-1");
        assertThat(msg.messages().get(0).getTextContent()).isEqualTo("hello");
    }
}
