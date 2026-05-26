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
package io.agentscope.claw2.runtime.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.channel.OutboundAddress;
import io.agentscope.claw2.runtime.channel.PeerKind;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Feishu outbound HTTP client. Posts a text message via
 * {@code POST /open-apis/im/v1/messages?receive_id_type=(open_id|chat_id)}.
 *
 * <p>The {@code receive_id_type} is derived from the {@link OutboundAddress} peer kind:
 *
 * <ul>
 *   <li>{@link PeerKind#DIRECT} — 1:1 chat addressed by {@code chat_id} (we use the chat_id
 *       captured from inbound mapping, so {@code receive_id_type=chat_id})
 *   <li>{@link PeerKind#GROUP} — group chat addressed by {@code chat_id}
 * </ul>
 */
public final class FeishuOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final FeishuAccessTokenProvider tokenProvider;

    public FeishuOutboundClient(String apiBase, FeishuAccessTokenProvider tokenProvider) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.tokenProvider = tokenProvider;
    }

    /** Sends each message to the resolved peer. */
    public Mono<Void> send(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        PeerTarget target = parseAddress(address);
        return Mono.defer(tokenProvider::token)
                .flatMapMany(
                        token ->
                                reactor.core.publisher.Flux.fromIterable(messages)
                                        .concatMap(msg -> sendOne(token, target, msg)))
                .then();
    }

    private Mono<Void> sendOne(String token, PeerTarget target, Msg msg) {
        String text = msg.getTextContent();
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        String receiveIdType = receiveIdType(target.kind());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", target.id());
        body.put("msg_type", "text");
        String contentJson;
        try {
            contentJson = MAPPER.writeValueAsString(Map.of("text", text));
        } catch (Exception e) {
            return Mono.error(e);
        }
        body.put("content", contentJson);
        return client.post()
                .uri(
                        uri ->
                                uri.path("/open-apis/im/v1/messages")
                                        .queryParam("receive_id_type", receiveIdType)
                                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .doOnNext(this::checkSendResponse)
                .then();
    }

    private void checkSendResponse(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int code = node.path("code").asInt(0);
            // 99991663 = invalid token, 99991664 = token expired
            if (code == 99991663 || code == 99991664) {
                tokenProvider.invalidate();
            }
            if (code != 0) {
                log.warn("Feishu send returned code={}, msg={}", code, node.path("msg").asText());
            }
        } catch (Exception e) {
            log.warn("Failed to parse Feishu send response: {}", e.getMessage());
        }
    }

    private static String receiveIdType(PeerKind kind) {
        // Both DIRECT (1:1 chat we captured via chat_id) and GROUP use chat_id addressing.
        return "chat_id";
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

    private record PeerTarget(PeerKind kind, String id) {}
}
