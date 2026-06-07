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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * DingTalk outbound HTTP client. Wraps:
 *
 * <ul>
 *   <li>{@code POST /v1.0/robot/oToMessages/batchSend} for DMs (one-to-one with the bot)
 *   <li>{@code POST /v1.0/robot/groupMessages/send} for group chats
 * </ul>
 *
 * Both endpoints accept text or markdown via {@code msgKey} = {@code sampleText} /
 * {@code sampleMarkdown}.
 */
public final class DingTalkOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(DingTalkOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final DingTalkAccessTokenProvider tokenProvider;
    private final String robotCode;

    public DingTalkOutboundClient(
            String apiBase, DingTalkAccessTokenProvider tokenProvider, String robotCode) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.tokenProvider = tokenProvider;
        this.robotCode = robotCode;
    }

    /** Sends each {@code msg} to the resolved peer (address format {@code "channelId:kind:peerId"}). */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        PeerTarget target = parseAddress(address);
        return Mono.from(
                Mono.defer(tokenProvider::token)
                        .flatMapMany(
                                token ->
                                        reactor.core.publisher.Flux.fromIterable(messages)
                                                .concatMap(msg -> sendOne(token, target, msg)))
                        .then());
    }

    private Mono<Void> sendOne(String token, PeerTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        boolean useMarkdown = looksLikeMarkdown(text);
        String msgKey = useMarkdown ? "sampleMarkdown" : "sampleText";
        Map<String, Object> param = new LinkedHashMap<>();
        if (useMarkdown) {
            param.put("title", "Agent");
            param.put("text", text);
        } else {
            param.put("content", text);
        }
        String msgParam;
        try {
            msgParam = MAPPER.writeValueAsString(param);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Failed to encode DingTalk msgParam", e));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("robotCode", robotCode);
        body.put("msgKey", msgKey);
        body.put("msgParam", msgParam);
        switch (target.kind()) {
            case GROUP -> {
                body.put("openConversationId", target.id());
                return post("/v1.0/robot/groupMessages/send", token, body);
            }
            case DIRECT -> {
                body.put("userIds", Collections.singletonList(target.id()));
                return post("/v1.0/robot/oToMessages/batchSend", token, body);
            }
            default -> {
                return Mono.error(
                        new IllegalArgumentException(
                                "DingTalk outbound only supports DIRECT and GROUP peer kinds,"
                                        + " got "
                                        + target.kind()));
            }
        }
    }

    private Mono<Void> post(String path, String token, Map<String, Object> body) {
        return client.post()
                .uri(path)
                .header("x-acs-dingtalk-access-token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .doOnNext(resp -> log.debug("DingTalk send response: {}", resp))
                .doOnError(
                        err -> {
                            String msg = err.getMessage();
                            if (msg != null
                                    && (msg.contains("AccessToken") || msg.contains("token"))) {
                                tokenProvider.invalidate();
                            }
                            log.warn("DingTalk send failed: {}", msg);
                        })
                .then();
    }

    private static PeerTarget parseAddress(OutboundAddress address) {
        String to = address.to();
        int sep = to.indexOf(':');
        if (sep < 0) {
            return new PeerTarget(PeerKind.DIRECT, to);
        }
        String rest = to.substring(sep + 1);
        int sep2 = rest.indexOf(':');
        String kindRaw;
        String id;
        if (sep2 < 0) {
            kindRaw = "DIRECT";
            id = rest;
        } else {
            kindRaw = rest.substring(0, sep2);
            id = rest.substring(sep2 + 1);
        }
        PeerKind kind;
        try {
            kind = PeerKind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            kind = PeerKind.DIRECT;
        }
        return new PeerTarget(kind, id);
    }

    private static boolean looksLikeMarkdown(String text) {
        return text.contains("\n")
                || text.contains("**")
                || text.contains("```")
                || text.contains("[");
    }

    private record PeerTarget(PeerKind kind, String id) {}
}
