/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.builder.web.managed;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Authenticated, durable work intake. The control plane resolves the human identity and target. */
@Component
@EnableScheduling
public class ChannelWorkBridge {
    private static final Logger log = LoggerFactory.getLogger(ChannelWorkBridge.class);
    private final WebClient control;
    private final ManagedSessionChannelBridge chat;
    private final ChannelManager channels;

    public ChannelWorkBridge(
            @Qualifier("controlPlaneWebClient") WebClient control,
            ManagedSessionChannelBridge chat,
            ChannelManager channels) {
        this.control = control;
        this.chat = chat;
        this.channels = channels;
    }

    public Mono<Msg> receive(InboundMessage in) {
        if (in == null)
            return Mono.error(
                    new IllegalArgumentException("Normalized channel identity is required"));
        Msg message =
                in.messages().stream()
                        .filter(m -> m.getRole() == MsgRole.USER)
                        .reduce((first, last) -> last)
                        .orElse(null);
        if (message == null || message.getTextContent() == null) return Mono.empty();
        Map<String, Object> metadata =
                message.getMetadata() == null ? Map.of() : message.getMetadata();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", in.channelId());
        body.put("accountId", empty(in.accountId()));
        body.put("senderId", empty(in.senderId()));
        body.put("peerKind", in.peer().kind().name());
        body.put("peerId", in.peer().id());
        body.put("messageId", metadata.getOrDefault("channelMessageId", ""));
        body.put("replyToId", metadata.getOrDefault("channelReplyToId", ""));
        body.put("threadId", metadata.getOrDefault("channelThreadId", ""));
        body.put("text", message.getTextContent());
        return control.post()
                .uri("/api/internal/channels/inbound")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Intake.class)
                .timeout(Duration.ofSeconds(10))
                .flatMap(
                        result -> {
                            if (Boolean.TRUE.equals(result.chat())) {
                                return chat.dispatchAndAwaitReply(
                                                result.ownerId(),
                                                result.agentId(),
                                                result.externalKey(),
                                                message.getTextContent())
                                        .map(ChannelWorkBridge::reply);
                            }
                            return result.reply() == null || result.reply().isBlank()
                                    ? Mono.empty()
                                    : Mono.just(reply(result.reply()));
                        });
    }

    /** A claimed delivery is never reported as accepted until the provider returns a message ID. */
    @Scheduled(fixedDelayString = "${builder.scheduler.channel-delivery-poll-ms:1000}")
    public void deliverPending() {
        try {
            Delivery d =
                    control.post()
                            .uri("/api/internal/channels/deliveries/claim")
                            .retrieve()
                            .bodyToMono(Delivery.class)
                            .block(Duration.ofSeconds(15));
            if (d == null) return;
            String messageId;
            try {
                var channel =
                        channels.getChannel(d.channelId())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Channel transport unavailable"));
                OutboundAddress address =
                        new OutboundAddress(
                                d.channelId(),
                                d.accountId(),
                                d.channelId() + ":" + d.peerKind() + ":" + d.peerId(),
                                d.threadId() == null || d.threadId().isBlank()
                                        ? null
                                        : d.threadId());
                messageId =
                        channel.deliverWithReceipt(address, reply(d.text()), d.id())
                                .block(Duration.ofSeconds(30));
                if (messageId == null || messageId.isBlank())
                    throw new IllegalStateException("Provider receipt missing");
            } catch (Exception failure) {
                receipt(
                        d,
                        Map.of(
                                "leaseToken",
                                d.leaseToken(),
                                "error",
                                failure.getClass().getSimpleName()));
                return;
            }
            // If persistence fails the lease expires. The next worker reuses the delivery ID
            // as provider idempotency key; it never invents a second logical notification.
            receipt(d, Map.of("leaseToken", d.leaseToken(), "providerMessageId", messageId));
        } catch (Exception failure) {
            log.debug("Channel delivery worker will retry: {}", failure.getClass().getSimpleName());
        }
    }

    private void receipt(Delivery d, Map<String, String> body) {
        control.post()
                .uri("/api/internal/channels/deliveries/{id}/receipt", d.id())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
    }

    private static Msg reply(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    record Intake(
            Boolean accepted,
            Boolean chat,
            String reply,
            String ownerId,
            String agentId,
            String externalKey) {}

    record Delivery(
            String id,
            String leaseToken,
            String channelId,
            String accountId,
            String peerKind,
            String peerId,
            String threadId,
            String text) {}
}
